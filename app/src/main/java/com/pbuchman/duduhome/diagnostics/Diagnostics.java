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
    private static final long PROCESS = SystemClock.elapsedRealtime();
    private static final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ThreadPoolExecutor writer = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(128),
            runnable -> { Thread thread = new Thread(runnable, "DuduDiagnostics"); thread.setDaemon(true); return thread; },
            (task, executor) -> dropped.incrementAndGet());
    public static long startup() { return SystemClock.elapsedRealtime(); }
    public static int pendingWrites() { return writer.getQueue().size(); }
    public static long droppedWrites() { return dropped.get(); }
    /** Test-only drain; production GPS callbacks never wait for journal IO. */
    public static boolean awaitWrites() {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        // Bounded tests may fill the queue: let the writer make room for the barrier.
        try {
            if (!writer.getQueue().offer(latch::countDown, 2, java.util.concurrent.TimeUnit.SECONDS)) return false;
            writer.prestartCoreThread();
            return latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }
    private record Entry(long wall, long elapsed, String category) { }
    private static final java.util.Map<Long, java.util.List<Entry>> pending = new java.util.HashMap<>();
    private static final java.util.LinkedHashSet<Long> committed = new java.util.LinkedHashSet<>();
    public static synchronized void beginAttempt(long id) { pending.putIfAbsent(id, new java.util.ArrayList<>()); }
    public static synchronized void recordAttempt(Context context, long id, String category) {
        recordAttemptAt(context, id, category, System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }
    static synchronized void recordAttemptAt(Context context, long id, String category, long wall, long elapsed) {
        if (category == null) return;
        if (!category.startsWith("ID=") && !category.contains(" ID=")) category = "ID=" + id + " " + category;
        if (!valid(category)) return;
        java.util.List<Entry> buffer = pending.get(id);
        if (buffer == null) { if (committed.contains(id)) enqueue(context, new Entry(wall, elapsed, category)); return; }
        if (buffer.size() < 48) buffer.add(new Entry(wall, elapsed, category));
    }
    public static synchronized void commitAttempt(Context context, long id) {
        java.util.List<Entry> buffer = pending.remove(id);
        committed.add(id);
        if (committed.size() > 256) committed.remove(committed.iterator().next());
        if (buffer != null) for (Entry entry : buffer) enqueue(context, entry);
    }
    public static synchronized void discardAttempt(long id) { pending.remove(id); committed.remove(id); }


    public static synchronized void record(Context context, String category) {
        // Call sites supply constant categories, enum names and numeric counters, not user input.
        if (!valid(category)) return;
        enqueue(context, new Entry(System.currentTimeMillis(), SystemClock.elapsedRealtime(), category));
    }
    private static boolean valid(String category) {
        return category != null && category.length() <= 240 && category.matches("[A-Z0-9_ =:.-]+");
    }
    private static void enqueue(Context context, Entry entry) {
        File directory = context.getNoBackupFilesDir();
        writer.execute(() -> write(directory, entry.wall(), entry.elapsed(), "PROCESS=" + PROCESS + " " + entry.category()));
    }
    private static void write(File directory, long wall, long elapsed, String category) {
        Log.i("DuduHome", category);
        File current = new File(directory, "diagnostics.txt");
        File previous = new File(directory, "diagnostics.previous.txt");
        try {
            if (current.length() >= LIMIT) {
                if (previous.exists() && !previous.delete()) return;
                if (!current.renameTo(previous)) return;
            }
            try (FileOutputStream out = new FileOutputStream(current, true)) {
                String line = wall + " " + elapsed + " " + category + "\n";
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { Log.w("DuduHome", "DIAGNOSTICS_WRITE_FAILED"); }
    }
}
