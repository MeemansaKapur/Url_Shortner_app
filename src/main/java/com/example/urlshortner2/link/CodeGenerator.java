package com.example.urlshortner2.link;

import java.security.SecureRandom;

/**
 * Random base62 codes from SecureRandom.
 * <ul>
 *   <li>7 chars = 62^7 ≈ 3.5 trillion codes; with 1M links a new code collides with probability ≈ 3e-7.</li>
 *   <li>Non-enumerable (unlike a counter), and needs no coordination across instances or shards.</li>
 *   <li>{@code nextInt(62)} is uniform, so there is no modulo bias.</li>
 * </ul>
 */
public final class CodeGenerator {

    static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private CodeGenerator() {
    }

    public static String newCode(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    public static boolean isValidCode(String s) {
        if (s == null || s.isEmpty() || s.length() > 16) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
