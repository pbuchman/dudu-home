package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import org.json.JSONObject;

class TripStore {
    record Saved(String state, long generation, double meters, long millis, boolean partialTime) { }
    private final AtomicFile file;
    TripStore(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "trip-state.json")); }
    Saved read() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath()+".bak").exists()) return new Saved("PAUSED", 0, 0, 0, false);
        byte[] bytes = file.readFully();
        if (bytes.length > 4096) throw new IOException("Invalid trip state");
        JSONObject json = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        String state = json.getString("state");
        if (!java.util.Set.of("OFF", "ACTIVE", "PAUSED", "FINISHED").contains(state)) throw new IOException("Invalid state");
        double meters = json.getDouble("meters");
        long generation = json.getLong("generation");
        if (!Double.isFinite(meters) || meters < 0 || generation < 0) throw new IOException("Invalid trip values");
        boolean legacy = !json.has("time_ms");
        long millis = legacy ? 0 : json.getLong("time_ms");
        boolean partial = legacy || json.getBoolean("partial_time");
        if (millis < 0) throw new IOException("Invalid trip time");
        return new Saved(state.equals("ACTIVE") ? "ACTIVE" : "PAUSED", generation, meters, millis, partial);
    }
    void write(String state, long generation, double meters, long millis, boolean partialTime) throws Exception {
        byte[] bytes = new JSONObject().put("state", state).put("generation", generation)
                .put("meters", meters).put("time_ms", millis).put("partial_time", partialTime).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(bytes); stream.flush(); stream.getFD().sync();
            file.finishWrite(stream); stream = null;
            if (!java.util.Arrays.equals(bytes, file.readFully())) throw new IOException("Trip state not committed");
        }
        catch (Exception failure) { if (stream != null) file.failWrite(stream); throw failure; }
    }
}
