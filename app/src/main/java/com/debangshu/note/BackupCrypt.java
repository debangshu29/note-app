package com.debangshu.note;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-protected backups (plain Java, tested on a computer): AES-256-GCM with a key made from your
 * password (PBKDF2, 120 000 rounds, a random salt). Without the password the file can't be read, and a
 * wrong password or a changed file is detected.
 *
 * File: "NOTEENC1" · rounds (4 bytes) · salt (16) · iv (12) · encrypted zip.
 */
public final class BackupCrypt {

    private BackupCrypt() {}

    private static final byte[] MAGIC = "NOTEENC1".getBytes(StandardCharsets.US_ASCII);
    public static final int ROUNDS = 120_000;

    public static boolean isEncrypted(byte[] d) {
        if (d == null || d.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (d[i] != MAGIC[i]) return false;
        return true;
    }

    public static byte[] newSalt() {
        byte[] s = new byte[16];
        new SecureRandom().nextBytes(s);
        return s;
    }

    public static byte[] key(char[] password, byte[] salt, int rounds) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");   // available on every Android version
        return f.generateSecret(new PBEKeySpec(password, salt, rounds, 256)).getEncoded();
    }

    /** Encrypts with a key already made from the password (so automatic backups don't need it typed each time). */
    public static byte[] encrypt(byte[] plain, byte[] key, byte[] salt, int rounds) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] body = c.doFinal(plain);
        ByteBuffer out = ByteBuffer.allocate(MAGIC.length + 4 + salt.length + iv.length + body.length);
        out.put(MAGIC).putInt(rounds).put(salt).put(iv).put(body);
        return out.array();
    }

    public static byte[] encrypt(byte[] plain, char[] password) throws Exception {
        byte[] salt = newSalt();
        return encrypt(plain, key(password, salt, ROUNDS), salt, ROUNDS);
    }

    /** Throws when the password is wrong or the file was changed. */
    public static byte[] decrypt(byte[] file, char[] password) throws Exception {
        if (!isEncrypted(file)) throw new Exception("not a protected backup");
        ByteBuffer in = ByteBuffer.wrap(file);
        in.position(MAGIC.length);
        int rounds = in.getInt();
        if (rounds < 1000 || rounds > 10_000_000) throw new Exception("not a protected backup");
        byte[] salt = new byte[16], iv = new byte[12];
        in.get(salt).get(iv);
        byte[] body = new byte[in.remaining()];
        in.get(body);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(password, salt, rounds), "AES"), new GCMParameterSpec(128, iv));
        try {
            return c.doFinal(body);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new WrongPassword();
        }
    }

    public static final class WrongPassword extends Exception {
        public WrongPassword() { super("Wrong password"); }
    }
}
