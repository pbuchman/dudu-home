package com.pbuchman.duduhome.config;

import com.pbuchman.duduhome.gate.GateNumberStore;
import com.pbuchman.duduhome.location.HomeConfiguration;
import com.pbuchman.duduhome.roborock.RoborockCredentials;
import com.pbuchman.duduhome.roborock.RoborockStore;

import android.content.Context;
import com.pbuchman.duduhome.navigation.NavigationConfig;
import com.pbuchman.duduhome.navigation.NavigationStore;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** An explicit staging file is consumed once; normal launches never reimport settings. */
public final class PrivateImport {
    public static boolean pending(Context c) {
        return new File(c.getNoBackupFilesDir(), "pending-config.json").exists()
                || new File(c.getNoBackupFilesDir(), "maintenance").exists()
                || new File(c.getNoBackupFilesDir(), "install-verification").exists();
    }
    public static boolean apply(Context c) {
        File source = new File(c.getNoBackupFilesDir(), "pending-config.json");
        if (!source.exists()) return !new File(c.getNoBackupFilesDir(), "maintenance").exists();
        try {
            if (source.length() > 16384) return false;
            JSONObject data = new JSONObject(new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
            if (data.getInt("schema_version") != 2) return false;
            HomeConfiguration.validateImport(data);
            NavigationConfig navigation = data.has("navigation")
                    ? NavigationConfig.parse(data.getJSONObject("navigation").toString()) : null;
            String phone = data.isNull("gate_number") ? null : GateNumberStore.normalize(data.getString("gate_number"));
            if (!data.isNull("gate_number") && (phone == null || !data.optBoolean("gate_number_verified_on_current_device"))) return false;
            RoborockCredentials rr = data.isNull("roborock") ? null : new RoborockCredentials(data.getJSONObject("roborock").toString());
            // Import only changes explicitly supplied optional sections.
            GateNumberStore numbers = new GateNumberStore(c);
            if (phone != null && !phone.equals(numbers.read()) && !numbers.save(phone)) return false;
            if (rr != null) {
                RoborockStore robots = new RoborockStore(c);
                RoborockCredentials old = robots.read();
                if ((old == null || !old.serialized().equals(rr.serialized())) && !robots.save(rr.serialized())) return false;
            }
            JSONObject geometry = new JSONObject().put("schema_version", 2)
                    .put("automation_enabled", data.optBoolean("automation_enabled", false));
            if (!data.isNull("points")) geometry.put("points", data.getJSONObject("points"));
            AtomicFile target = new AtomicFile(new File(c.getNoBackupFilesDir(), "home-config.json"));
            FileOutputStream out = null;
            try {
                out = target.startWrite(); out.write(geometry.toString().getBytes(StandardCharsets.UTF_8));
                target.finishWrite(out);
            } catch (Exception failure) { if (out != null) target.failWrite(out); return false; }
            if (navigation != null) new NavigationStore(c).save(navigation);
            if (!source.delete()) return false;
            File maintenance = new File(c.getNoBackupFilesDir(), "maintenance");
            return !maintenance.exists() || maintenance.delete();
        } catch (Exception ignored) { return false; }
    }
    public static void migrateLegacyPhone(Context c) {
        android.content.SharedPreferences state = c.getSharedPreferences("config_import", 0);
        if (state.getBoolean("legacy_done", false)) return;
        HomeConfiguration config = HomeConfiguration.load(c);
        GateNumberStore store = new GateNumberStore(c);
        if (config != null && config.verifiedPhone != null && store.read() == null && !store.save(config.verifiedPhone)) return;
        state.edit().putBoolean("legacy_done", true).commit();
    }
}
