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
