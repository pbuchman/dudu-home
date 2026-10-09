package com.pbuchman.duduhome.diagnostics;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded app-private journal. Categories and counters only, never payloads or positions. */
public final class Diagnostics {
    private static final long LIMIT = 128 * 1024;
    private Diagnostics() { }
    private static final java.util.Map<Long, java.util.List<String>> pending = new java.util.HashMap<>();
    private static final java.util.LinkedHashSet<Long> committed = new java.util.LinkedHashSet<>();
    public static synchronized void beginAttempt(long id) { pending.putIfAbsent(id, new java.util.ArrayList<>()); }
    public static synchronized void recordAttempt(Context context, long id, String category) {
        java.util.List<String> buffer = pending.get(id);
        if (buffer == null) { if (committed.contains(id)) record(context, category); return; }
        if (buffer.size() < 48) buffer.add(category);
    }
    public static synchronized void commitAttempt(Context context, long id) {
        java.util.List<String> buffer = pending.remove(id);
        committed.add(id);
        if (committed.size() > 256) committed.remove(committed.iterator().next());
        if (buffer != null) for (String category : buffer) record(context, category);
    }
    public static synchronized void discardAttempt(long id) { pending.remove(id); committed.remove(id); }


    public static synchronized void record(Context context, String category) {
        // Call sites supply constant categories, enum names and numeric counters, not user input.
        if (category == null || category.length() > 240 || !category.matches("[A-Z0-9_ =:.-]+")) return;
        Log.i("DuduHome", category);
        File current = new File(context.getNoBackupFilesDir(), "diagnostics.txt");
        File previous = new File(context.getNoBackupFilesDir(), "diagnostics.previous.txt");
        try {
            if (current.length() >= LIMIT) {
                if (previous.exists() && !previous.delete()) return;
                if (!current.renameTo(previous)) return;
            }
            try (FileOutputStream out = new FileOutputStream(current, true)) {
                String line = System.currentTimeMillis() + " " + SystemClock.elapsedRealtime() + " " + category + "\n";
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { Log.w("DuduHome", "DIAGNOSTICS_WRITE_FAILED"); }
    }
}
