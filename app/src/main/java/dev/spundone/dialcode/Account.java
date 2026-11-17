package dev.spundone.dialcode;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** One OTP account. The secret never leaves this object except into the encrypted vault. */
final class Account {
    static final int TOTP = 0, HOTP = 1, STEAM = 2;

    String issuer;
    String label;
    String secret; // normalised Base32
    int type;
    String algorithm; // SHA1, SHA256, SHA512
    int digits;
    int period;
    long counter;
    boolean pinned;

    private byte[] key;

    byte[] key() {
        if (key == null) key = Otp.base32Decode(secret);
        return key;
    }

    /** Identity used to match accounts across imports: same type and same secret. */
    String identity() {
        return type + ":" + secret;
    }

    String title() {
        if (issuer != null && !issuer.isEmpty()) return issuer;
        return label == null ? "" : label;
    }

    String subtitle() {
        if (issuer != null && !issuer.isEmpty() && label != null && !label.isEmpty() && !label.equals(issuer)) return label;
        return "";
    }

    /** Seconds per code window; HOTP has none. */
    int window() {
        return type == HOTP ? 0 : period;
    }

    String code(long nowMillis) {
        switch (type) {
            case HOTP:
                return Otp.hotp(key(), counter, digits, algorithm);
            case STEAM:
                return Otp.steam(key(), Math.floorDiv(nowMillis / 1000, period));
            default:
                return Otp.hotp(key(), Math.floorDiv(nowMillis / 1000, period), digits, algorithm);
        }
    }

    /** "123456" -> "123 456", Steam codes unchanged. */
    static String group(String code) {
        int n = code.length();
        if (n < 6 || !Character.isDigit(code.charAt(0))) return code;
        int cut = n == 8 ? 4 : n / 2;
        return code.substring(0, cut) + " " + code.substring(cut);
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("issuer", issuer == null ? "" : issuer);
        o.put("label", label == null ? "" : label);
        o.put("secret", secret);
        o.put("type", type);
        o.put("alg", algorithm);
        o.put("digits", digits);
        o.put("period", period);
        o.put("counter", counter);
        o.put("pinned", pinned);
        return o;
    }

    static Account fromJson(JSONObject o) {
        Account a = new Account();
        a.issuer = o.optString("issuer", "");
        a.label = o.optString("label", "");
        a.secret = o.optString("secret", "");
        a.type = o.optInt("type", TOTP);
        a.algorithm = o.optString("alg", "SHA1");
        a.digits = o.optInt("digits", 6);
        a.period = o.optInt("period", 30);
        a.counter = o.optLong("counter", 0);
        a.pinned = o.optBoolean("pinned", false);
        return a;
    }

    /**
     * Parses otpauth://totp|hotp/... and steam://SECRET URIs.
     * @param fallbackName display name used when the URI has no issuer or label (Proton's "name").
     */
    static Account parseUri(String raw, String fallbackName) {
        String s = raw.trim();
        Account a = new Account();
        a.algorithm = "SHA1";
        a.digits = 6;
        a.period = 30;
        if (s.regionMatches(true, 0, "steam://", 0, 8)) {
            String secret = s.substring(8);
            int cut = indexOfAny(secret, "/?#");
            if (cut >= 0) secret = secret.substring(0, cut);
            a.type = STEAM;
            a.digits = 5;
            a.issuer = "Steam";
            a.label = fallbackName == null || fallbackName.equals("Steam") ? "" : fallbackName;
            a.secret = normalise(secret);
            return a;
        }
        Uri u = Uri.parse(s);
        if (!"otpauth".equalsIgnoreCase(u.getScheme())) throw new IllegalArgumentException("not an otpauth URI");
        String kind = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (kind.equals("totp")) a.type = TOTP;
        else if (kind.equals("hotp")) a.type = HOTP;
        else if (kind.equals("steam")) { a.type = STEAM; a.digits = 5; }
        else throw new IllegalArgumentException("unsupported OTP type");

        String path = u.getPath() == null ? "" : u.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        String labelIssuer = "";
        String label = path;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            labelIssuer = path.substring(0, colon).trim();
            label = path.substring(colon + 1).trim();
        }
        String issuer = u.getQueryParameter("issuer");
        a.issuer = issuer != null && !issuer.trim().isEmpty() ? issuer.trim() : labelIssuer;
        a.label = label.trim();
        if (a.issuer.isEmpty() && a.label.isEmpty() && fallbackName != null) a.label = fallbackName;
        if (a.type == STEAM && a.issuer.isEmpty()) a.issuer = "Steam";

        String secret = u.getQueryParameter("secret");
        if (secret == null) throw new IllegalArgumentException("missing secret");
        a.secret = normalise(secret);

        String alg = u.getQueryParameter("algorithm");
        if (alg != null) {
            String up = alg.toUpperCase(Locale.ROOT).replace("-", "");
            if (up.equals("SHA1") || up.equals("SHA256") || up.equals("SHA512")) a.algorithm = up;
            else throw new IllegalArgumentException("unsupported algorithm");
        }
        if (a.type != STEAM) {
            String d = u.getQueryParameter("digits");
            if (d != null) a.digits = Integer.parseInt(d.trim());
            if (a.digits < 6 || a.digits > 8) throw new IllegalArgumentException("unsupported digits");
        }
        String p = u.getQueryParameter("period");
        if (p != null) a.period = Integer.parseInt(p.trim());
        if (a.period < 1 || a.period > 3600) throw new IllegalArgumentException("bad period");
        if (a.type == HOTP) {
            String c = u.getQueryParameter("counter");
            a.counter = c == null ? 0 : Long.parseLong(c.trim());
        }
        return a;
    }

    /** A TOTP account typed in by hand: SHA1, 6 digits, 30 s, like nearly every site's setup key. */
    static Account manual(String name, String secret) {
        Account a = new Account();
        a.type = TOTP;
        a.algorithm = "SHA1";
        a.digits = 6;
        a.period = 30;
        a.issuer = name.trim();
        a.label = "";
        a.secret = normalise(secret);
        return a;
    }

    private static String normalise(String secret) {
        // Decode and re-encode so formatting differences (case, spaces, padding) don't create duplicates.
        return Otp.base32Encode(Otp.base32Decode(secret));
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
        return -1;
    }
}
