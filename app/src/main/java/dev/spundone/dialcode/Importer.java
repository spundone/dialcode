package dev.spundone.dialcode;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks up export files pushed over adb into the app's external files dir, applies them to the vault
 * and wipes them. Runs only from the activity, while it is on screen.
 *
 * <pre>
 *   files/import/  merge: add accounts that aren't on the watch yet
 *   files/sync/    mirror: the watch list becomes exactly the export (pins and HOTP counters are kept)
 * </pre>
 *
 * Accepted contents: an unencrypted Proton Authenticator export (JSON, version 1), or plain text with one
 * otpauth:// or steam:// URI per line.
 */
final class Importer {
    static final String MERGE_DIR = "import";
    static final String MIRROR_DIR = "sync";

    private Importer() {}

    /** @return a short user-facing message, or null when nothing was waiting. */
    static String run(Context ctx, List<Account> vault) throws Exception {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) return null;
        File merge = new File(base, MERGE_DIR);
        File mirror = new File(base, MIRROR_DIR);
        // Create the drop folders so adb push has somewhere to go.
        merge.mkdirs();
        mirror.mkdirs();

        StringBuilder msg = new StringBuilder();
        boolean changed = false;
        for (File f : list(mirror)) {
            changed |= apply(f, vault, true, msg);
        }
        for (File f : list(merge)) {
            changed |= apply(f, vault, false, msg);
        }
        if (msg.length() == 0) return null;
        if (changed) Vault.save(ctx, vault);
        return msg.toString().trim();
    }

    private static File[] list(File dir) {
        File[] files = dir.listFiles(File::isFile);
        if (files == null) return new File[0];
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        return files;
    }

    private static boolean apply(File f, List<Account> vault, boolean mirror, StringBuilder msg) {
        Parsed p;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String text = new String(bytes, StandardCharsets.UTF_8);
            java.util.Arrays.fill(bytes, (byte) 0);
            p = parse(text);
        } catch (Exception e) {
            p = new Parsed();
            p.error = "Couldn't read " + f.getName();
        } finally {
            wipe(f);
        }
        if (p.error != null) {
            msg.append(p.error).append('\n');
            return false;
        }
        if (p.accounts.isEmpty()) {
            msg.append("No accounts found").append(p.skipped > 0 ? " (" + p.skipped + " unreadable)" : "").append('\n');
            return false;
        }
        if (mirror) {
            Map<String, Account> old = new HashMap<>();
            for (Account a : vault) old.put(a.identity(), a);
            int added = 0, kept = 0;
            List<Account> next = new ArrayList<>();
            for (Account a : p.accounts) {
                Account prev = old.remove(a.identity());
                if (prev != null) {
                    a.pinned = prev.pinned;
                    a.counter = Math.max(a.counter, prev.counter);
                    kept++;
                } else {
                    added++;
                }
                next.add(a);
            }
            int removed = old.size();
            vault.clear();
            vault.addAll(next);
            msg.append("Synced: ").append(added).append(" new, ").append(kept).append(" kept, ")
                    .append(removed).append(" removed");
        } else {
            Map<String, Account> have = new HashMap<>();
            for (Account a : vault) have.put(a.identity(), a);
            int added = 0, dup = 0;
            for (Account a : p.accounts) {
                if (have.containsKey(a.identity())) {
                    dup++;
                } else {
                    vault.add(a);
                    have.put(a.identity(), a);
                    added++;
                }
            }
            msg.append("Imported ").append(added);
            if (dup > 0) msg.append(", ").append(dup).append(" already here");
        }
        if (p.skipped > 0) msg.append(", ").append(p.skipped).append(" skipped");
        msg.append('\n');
        return true;
    }

    static final class Parsed {
        final List<Account> accounts = new ArrayList<>();
        int skipped;
        String error;
    }

    static Parsed parse(String text) throws Exception {
        Parsed p = new Parsed();
        String t = text.trim();
        if (t.startsWith("﻿")) t = t.substring(1);
        if (t.startsWith("{")) {
            JSONObject root = new JSONObject(t);
            if (root.has("salt") && root.has("content") && !root.has("entries")) {
                p.error = "This export is password-protected. Export again without a password.";
                return p;
            }
            JSONArray entries = root.optJSONArray("entries");
            if (entries == null) {
                p.error = "Not a Proton Authenticator export";
                return p;
            }
            int version = root.optInt("version", 1);
            if (version != 1) {
                p.error = "Unsupported export version " + version;
                return p;
            }
            for (int i = 0; i < entries.length(); i++) {
                try {
                    JSONObject content = entries.getJSONObject(i).getJSONObject("content");
                    String name = content.isNull("name") ? null : content.optString("name", null);
                    p.accounts.add(Account.parseUri(content.getString("uri"), name));
                } catch (Exception e) {
                    p.skipped++;
                }
            }
            return p;
        }
        for (String line : t.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            try {
                p.accounts.add(Account.parseUri(l, null));
            } catch (Exception e) {
                p.skipped++;
            }
        }
        return p;
    }

    /** Overwrite then delete. Flash storage may keep old blocks, so the README says to delete the Mac copy too. */
    private static void wipe(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            long len = raf.length();
            byte[] zeros = new byte[4096];
            for (long done = 0; done < len; done += zeros.length) {
                raf.write(zeros, 0, (int) Math.min(zeros.length, len - done));
            }
            raf.getFD().sync();
        } catch (Exception ignored) {
            // fall through to delete
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
