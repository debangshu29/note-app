package com.debangshu.note;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Small calculator used by the editor.
 *
 * Supports: + - * / x × ÷ ^ % ( ) √ sqrt, decimals and 1,000-style numbers.
 * Example: "12 x 3 + 10%" -> 36.1 (10% is read as 0.1)
 *          "200 * 10%"    -> 20
 *
 * No Android imports here, so it can be tested on a normal JVM.
 */
public final class MathEval {

    private MathEval() {}

    /** Characters that may appear inside an expression. */
    private static final String EXPR_CHARS = "0123456789.,+-*/xX×÷^%()√ \t";

    /**
     * Looks at the end of {@code textBeforeCursor} (only its last line) and,
     * if it ends with a math expression, returns the formatted answer.
     * Returns null if there is nothing to calculate.
     *
     * The text must NOT include the '=' sign.
     */
    public static String answerFor(String textBeforeCursor) {
        if (textBeforeCursor == null) return null;
        int nl = textBeforeCursor.lastIndexOf('\n');
        String line = textBeforeCursor.substring(nl + 1);
        // also cut at an earlier '=' on the same line ("2+2=4, 4*3" -> "4*3")
        int eq = line.lastIndexOf('=');
        if (eq >= 0) line = line.substring(eq + 1);
        // also allow "sqrt"
        String work = line.replace("sqrt", "√");

        // walk back over expression characters
        int start = work.length();
        while (start > 0 && EXPR_CHARS.indexOf(work.charAt(start - 1)) >= 0) start--;
        String tail = work.substring(start);
        if (tail.trim().isEmpty()) return null;

        // try the longest candidate first, then shorter ones
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == ' ' || c == '\t') continue;
            String cand = tail.substring(i).trim();
            if (!looksLikeMath(cand)) continue;
            Double v = tryEval(cand);
            if (v != null) return format(v);
        }
        return null;
    }

    /** True if the string has a digit and at least one real operator. */
    static boolean looksLikeMath(String s) {
        boolean digit = false, op = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) digit = true;
            if (i > 0 && "+-*/xX×÷^".indexOf(c) >= 0) op = true;
            if (c == '%' || c == '√') op = true;
        }
        return digit && op;
    }

    /** Evaluates an expression, or returns null if it is not valid. */
    public static Double tryEval(String s) {
        try {
            Parser p = new Parser(s);
            double v = p.parseExpression();
            p.skipSpaces();
            if (p.pos != p.src.length()) return null;
            if (Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 2.0 -> "2", 0.1+0.2 -> "0.3", 1/3 -> "0.3333333333" */
    public static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        BigDecimal bd = new BigDecimal(v).round(new MathContext(12, RoundingMode.HALF_UP));
        bd = bd.setScale(Math.min(bd.scale(), 10), RoundingMode.HALF_UP).stripTrailingZeros();
        String out = bd.toPlainString();
        if (out.equals("-0")) out = "0";
        return out;
    }

    /** Recursive-descent parser. */
    private static final class Parser {
        final String src;
        int pos = 0;

        Parser(String s) { src = s; }

        void skipSpaces() {
            while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) pos++;
        }

        char peek() {
            skipSpaces();
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        double parseExpression() {
            double v = parseTerm();
            while (true) {
                char c = peek();
                if (c == '+') { pos++; v += parseTerm(); }
                else if (c == '-') { pos++; v -= parseTerm(); }
                else return v;
            }
        }

        double parseTerm() {
            double v = parseFactor();
            while (true) {
                char c = peek();
                if (c == '*' || c == 'x' || c == 'X' || c == '×') { pos++; v *= parseFactor(); }
                else if (c == '/' || c == '÷') {
                    pos++;
                    double d = parseFactor();
                    if (d == 0) throw new ArithmeticException("divide by zero");
                    v /= d;
                }
                else if (c == '(' || c == '√') { v *= parseFactor(); } // 2(3+4) or 2√9
                else return v;
            }
        }

        double parseFactor() {
            double base = parseUnary();
            if (peek() == '^') {
                pos++;
                double exp = parseFactor(); // right associative
                return Math.pow(base, exp);
            }
            return base;
        }

        double parseUnary() {
            char c = peek();
            if (c == '-') { pos++; return -parseUnary(); }
            if (c == '+') { pos++; return parseUnary(); }
            return parsePostfix();
        }

        double parsePostfix() {
            double v = parsePrimary();
            while (peek() == '%') { pos++; v = v / 100.0; }
            return v;
        }

        double parsePrimary() {
            char c = peek();
            if (c == '(') {
                pos++;
                double v = parseExpression();
                if (peek() != ')') throw new IllegalArgumentException("missing )");
                pos++;
                return v;
            }
            if (c == '√') {
                pos++;
                double v = parsePostfix();
                if (v < 0) throw new ArithmeticException("sqrt of negative");
                return Math.sqrt(v);
            }
            return parseNumber();
        }

        double parseNumber() {
            skipSpaces();
            int start = pos;
            StringBuilder sb = new StringBuilder();
            boolean dot = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c)) { sb.append(c); pos++; }
                else if (c == '.' && !dot) { dot = true; sb.append(c); pos++; }
                else if (c == ',' && sb.length() > 0 && !dot
                        && pos + 3 < src.length() + 1
                        && isThreeDigitsAt(pos + 1)) { pos++; } // thousands separator
                else break;
            }
            if (pos == start || sb.length() == 0 || sb.toString().equals(".")) {
                throw new IllegalArgumentException("number expected");
            }
            return Double.parseDouble(sb.toString());
        }

        boolean isThreeDigitsAt(int p) {
            if (p + 3 > src.length()) return false;
            for (int i = p; i < p + 3; i++) if (!Character.isDigit(src.charAt(i))) return false;
            return p + 3 == src.length() || !Character.isDigit(src.charAt(p + 3));
        }
    }
}
