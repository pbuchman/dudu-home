package pl.piotrbuchman.dudugate;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Private runtime configuration. No location defaults and no logging of its contents. */
final class HomeConfiguration {
    final double latitude, longitude;
    final HomeDetector.Geometry geometry;
    final boolean enabled;
    final String verifiedPhone;
    final String revision;

    private HomeConfiguration(JSONObject json, String revision) throws Exception {
        if (json.getInt("schema_version") != 1 && json.getInt("schema_version") != 2) throw new IllegalArgumentException("schema");
        JSONObject points = json.getJSONObject("points");
        JSONObject parking = points.getJSONObject("parking");
        latitude = latitude(parking);
        longitude = longitude(parking);
        geometry = new HomeDetector.Geometry(new HomeDetector.Point(0, 0),
                project(points.getJSONObject("gate")), project(points.getJSONObject("approach")),
                project(points.getJSONObject("junction")));
        for (HomeDetector.Point point : new HomeDetector.Point[]{geometry.gate(), geometry.approach(), geometry.junction()}) {
            double distance = point.distance(geometry.parking());
            if (distance < 15 || distance > 5000) throw new IllegalArgumentException("geometry");
        }
        enabled = json.optBoolean("automation_enabled", false);
        verifiedPhone = json.optBoolean("gate_number_verified_on_current_device", false)
                ? GateNumberStore.normalize(json.optString("gate_number", "")) : null;
        this.revision = revision;
    }

    static HomeConfiguration load(Context context) {
        try {
            File file = new File(context.getNoBackupFilesDir(), "home-config.json");
            if (!file.isFile() || file.length() > 16384) return null;
            byte[] bytes = Files.readAllBytes(file.toPath());
            JSONObject parsed = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            // Credentials and phone edits must not reset consumed location evidence.
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(
                    parsed.getJSONObject("points").toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder revision = new StringBuilder();
            for (byte b : hash) revision.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return new HomeConfiguration(parsed, revision.toString());
        } catch (Exception ignored) { return null; }
    }

    static void validateImport(JSONObject json) throws Exception {
        if (!json.isNull("points")) new HomeConfiguration(json, "validation");
        else if (json.optBoolean("automation_enabled", false)) throw new IllegalArgumentException("missing geometry");
    }

    HomeDetector.Point project(double lat, double lon) {
        return new HomeDetector.Point((lon - longitude) * 111320 * Math.cos(Math.toRadians(latitude)),
                (lat - latitude) * 111320);
    }

    private HomeDetector.Point project(JSONObject point) throws Exception {
        return project(latitude(point), longitude(point));
    }
    private static double latitude(JSONObject p) throws Exception {
        double v = p.getDouble("lat");
        if (!Double.isFinite(v) || Math.abs(v) > 85) throw new IllegalArgumentException("latitude");
        return v;
    }
    private static double longitude(JSONObject p) throws Exception {
        double v = p.getDouble("lon");
        if (!Double.isFinite(v) || Math.abs(v) > 180) throw new IllegalArgumentException("longitude");
        return v;
    }
}
