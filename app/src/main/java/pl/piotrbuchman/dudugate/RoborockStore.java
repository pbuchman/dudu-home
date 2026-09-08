package pl.piotrbuchman.dudugate;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.util.Arrays;

/** Keystore encryption, no backup; the rejection flag cannot leak credential values. */
final class RoborockStore {
    private static final String ALIAS = "dudu_home_roborock";
    private final AtomicFile file;
    private final SharedPreferences state;
    RoborockStore(Context c) {
        file = new AtomicFile(new File(c.getNoBackupFilesDir(), "roborock.enc"));
        state = c.getSharedPreferences("roborock_state", 0);
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    synchronized boolean save(String json) {
        FileOutputStream out = null;
        try {
            RoborockCredentials credentials = new RoborockCredentials(json);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(credentials.serialized().getBytes(StandardCharsets.UTF_8));
            out = file.startWrite(); out.write(cipher.getIV()); out.write(encrypted); file.finishWrite(out); out = null;
            return state.edit().putBoolean("rejected", false).commit();
        } catch (Exception ignored) { if (out != null) file.failWrite(out); return false; }
    }
    synchronized RoborockCredentials read() {
        if (state.getBoolean("rejected", false)) return null;
        try {
            byte[] bytes = file.readFully();
            if (bytes.length < 28 || bytes.length > 16384) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 0, 12)));
            return new RoborockCredentials(new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8));
        } catch (Exception ignored) { return null; }
    }
    boolean rejected() { return state.getBoolean("rejected", false); }
    synchronized boolean reject(RoborockCredentials used) {
        RoborockCredentials current = read();
        if (current == null || !current.serialized().equals(used.serialized())) return false;
        return state.edit().putBoolean("rejected", true).commit();
    }
}
