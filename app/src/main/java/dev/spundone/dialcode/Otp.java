package dev.spundone.dialcode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 4226 HOTP, RFC 6238 TOTP and Steam Guard codes. Pure functions, no state. */
final class Otp {
    private static final String STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY";
    private static final int[] POW10 = {1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000};

    private Otp() {}

    static byte[] base32Decode(String in) {
        String s = in.trim().toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 5 / 8 + 1);
        int buffer = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= '2' && c <= '7') v = c - '2' + 26;
            else if (c == '=' || c == ' ' || c == '-') continue;
            else throw new IllegalArgumentException("bad base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
            }
        }
        byte[] b = out.toByteArray();
        if (b.length == 0) throw new IllegalArgumentException("empty secret");
        return b;
    }

    static String base32Encode(byte[] data) {
        final String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(a.charAt((buffer >> bits) & 31));
            }
        }
        if (bits > 0) sb.append(a.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    /** Dynamic-truncated 31-bit value. */
    static int truncated(byte[] key, long counter, String algorithm) {
        try {
            String name = "Hmac" + algorithm;
            Mac mac = Mac.getInstance(name);
            mac.init(new SecretKeySpec(key, name));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int o = h[h.length - 1] & 0x0f;
            return ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hotp(byte[] key, long counter, int digits, String algorithm) {
        int code = truncated(key, counter, algorithm) % POW10[digits];
        String s = Integer.toString(code);
        StringBuilder sb = new StringBuilder(digits);
        for (int i = s.length(); i < digits; i++) sb.append('0');
        return sb.append(s).toString();
    }

    static String steam(byte[] key, long counter) {
        int code = truncated(key, counter, "SHA1");
        char[] c = new char[5];
        for (int i = 0; i < 5; i++) {
            c[i] = STEAM_ALPHABET.charAt(code % STEAM_ALPHABET.length());
            code /= STEAM_ALPHABET.length();
        }
        return new String(c);
    }
}
