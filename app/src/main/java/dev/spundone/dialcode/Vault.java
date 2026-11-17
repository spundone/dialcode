package dev.spundone.dialcode;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Accounts stored as one AES-256-GCM blob (12-byte IV + ciphertext) in the app's private files dir.
 * The key lives in the Android Keystore, can't be exported and only works while the watch is unlocked.
 */
final class Vault {
    private static final String ALIAS = "dialcode-vault-v1";
    private static final String FILE = "vault.bin";
    private static final byte[] AAD = "dialcode.vault.v1".getBytes(StandardCharsets.UTF_8);

    private Vault() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .build());
        return kg.generateKey();
    }

    static List<Account> load(Context ctx) throws Exception {
        File f = new File(ctx.getFilesDir(), FILE);
        List<Account> out = new ArrayList<>();
        if (!f.exists()) return out;
        byte[] blob = Files.readAllBytes(f.toPath());
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, blob, 0, 12));
        c.updateAAD(AAD);
        byte[] plain = c.doFinal(blob, 12, blob.length - 12);
        try {
            JSONArray arr = new JSONArray(new String(plain, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) out.add(Account.fromJson(arr.getJSONObject(i)));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        return out;
    }

    static void save(Context ctx, List<Account> accounts) throws Exception {
        JSONArray arr = new JSONArray();
        for (Account a : accounts) arr.put(a.toJson());
        byte[] plain = arr.toString().getBytes(StandardCharsets.UTF_8);
        byte[] blob;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            c.updateAAD(AAD);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain);
            blob = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ct, 0, blob, iv.length, ct.length);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        File dir = ctx.getFilesDir();
        File tmp = new File(dir, FILE + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(blob);
            os.getFD().sync();
        }
        if (!tmp.renameTo(new File(dir, FILE))) throw new IllegalStateException("vault write failed");
    }

    /** Pinned accounts first, in list order, at most {@code max}. */
    static List<Account> pinned(List<Account> all, int max) {
        List<Account> out = new ArrayList<>();
        for (Account a : all) {
            if (a.pinned && out.size() < max) out.add(a);
        }
        return out;
    }
}
