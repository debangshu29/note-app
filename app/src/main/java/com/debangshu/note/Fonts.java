package com.debangshu.note;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * Fonts offered in the formatting bar: Android's own styles plus free Google Fonts
 * bundled in the app (assets/fonts, licences in assets/fonts/LICENSES.txt).
 */
public final class Fonts {

    private Fonts() {}

    /** id: a built-in family name ("serif"), or "a:Name" for a bundled font file assets/fonts/Name.ttf */
    public static final String[] IDS = {
            "sans-serif", "sans-serif-light", "sans-serif-thin", "sans-serif-medium", "sans-serif-black",
            "sans-serif-condensed", "sans-serif-condensed-light", "sans-serif-smallcaps",
            "serif", "monospace", "serif-monospace", "casual", "cursive",
            "a:Poppins", "a:Nunito", "a:Comfortaa", "a:Oswald", "a:BebasNeue",
            "a:Lora", "a:PlayfairDisplay", "a:RobotoSlab", "a:CourierPrime",
            "a:DancingScript", "a:GreatVibes", "a:Pacifico", "a:Lobster",
            "a:Caveat", "a:IndieFlower", "a:PatrickHand", "a:Kalam", "a:ShadowsIntoLight", "a:PermanentMarker"
    };
    public static final String[] LABELS = {
            "Default (Roboto)", "Roboto Light", "Roboto Thin", "Roboto Medium", "Roboto Black",
            "Condensed", "Condensed Light", "Small Caps",
            "Serif (Noto Serif)", "Monospace", "Typewriter (serif mono)", "Casual", "Cursive",
            "Poppins", "Nunito", "Comfortaa", "Oswald", "Bebas Neue",
            "Lora", "Playfair Display", "Roboto Slab", "Courier Prime",
            "Dancing Script", "Great Vibes", "Pacifico", "Lobster",
            "Caveat", "Indie Flower", "Patrick Hand", "Kalam (also Hindi)", "Shadows Into Light", "Permanent Marker"
    };
    /** Section headers in the font list: index -> title */
    public static final int[] SECTION_AT = {0, 13, 18, 22, 26};
    public static final String[] SECTION_NAMES = {"Android fonts", "Sans", "Serif & typewriter", "Script", "Handwriting & marker"};

    private static Context app;
    private static final Map<String, Typeface> cache = new HashMap<>();

    public static void init(Context ctx) {
        if (app == null) app = ctx.getApplicationContext();
    }

    public static synchronized Typeface get(String id) {
        if (id == null || id.isEmpty()) return Typeface.DEFAULT;
        Typeface t = cache.get(id);
        if (t != null) return t;
        try {
            if (id.startsWith("a:")) {
                t = app == null ? Typeface.DEFAULT : Typeface.createFromAsset(app.getAssets(), "fonts/" + id.substring(2) + ".ttf");
            } else {
                t = Typeface.create(id, Typeface.NORMAL);
            }
        } catch (Exception e) {
            t = Typeface.DEFAULT;
        }
        cache.put(id, t);
        return t;
    }

    public static String label(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return LABELS[i];
        if ("sans-serif".equals(id) || id == null) return LABELS[0];
        return id;
    }
}
