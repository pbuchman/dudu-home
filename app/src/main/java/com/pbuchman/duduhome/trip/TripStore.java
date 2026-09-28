package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import org.json.JSONObject;

final class TripStore {
    record Saved(String state, long generation, double meters) { }
    private final AtomicFile file;
    TripStore(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "trip-state.json")); }
    Saved read() throws Exception {
        if (!file.getBaseFile().exists()) return new Saved("OFF", 0, 0);
        byte[] bytes = file.readFully();
        if (bytes.length > 4096) throw new IOException("Invalid trip state");
        JSONObject json = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        String state = json.getString("state");
        if (!java.util.Set.of("OFF", "ACTIVE", "PAUSED", "FINISHED").contains(state)) throw new IOException("Invalid state");
        double meters = json.getDouble("meters");
        long generation = json.getLong("generation");
        if (!Double.isFinite(meters) || meters < 0 || generation < 0) throw new IOException("Invalid trip values");
        return new Saved(state, generation, meters);
    }
    void write(String state, long generation, double meters) throws Exception {
        byte[] bytes = new JSONObject().put("state", state).put("generation", generation)
                .put("meters", meters).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream); }
        catch (Exception failure) { if (stream != null) file.failWrite(stream); throw failure; }
    }
}
