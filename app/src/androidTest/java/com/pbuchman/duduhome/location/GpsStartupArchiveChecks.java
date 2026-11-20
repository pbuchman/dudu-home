package com.pbuchman.duduhome.location;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.SystemClock;
import com.pbuchman.duduhome.routebook.Routebook;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Exercises the production collector and its SQLite completion hook without a provider or server. */
public final class GpsStartupArchiveChecks {
    public static void run(Instrumentation instrumentation) throws Exception {
        Context target = instrumentation.getTargetContext();
        File directory = Files.createTempDirectory(target.getCacheDir().toPath(), "gps-startup-check-").toFile();
        Context isolated = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        List<String> events = new CopyOnWriteArrayList<>();
        long started = SystemClock.elapsedRealtime();
        GpsStartupTrace trace = new GpsStartupTrace(started, events::add);
        GpsStartupTrace.Observation beforeWake = trace.current();
        beforeWake.cycleRead(started, "BASELINE_COUNTER", 1, 1);
        try (Routebook collector = new Routebook(isolated)) {
            var worker = Routebook.class.getDeclaredField("disk");
            worker.setAccessible(true);
            ExecutorService disk = (ExecutorService) worker.get(collector);
            drain(disk);
            CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1);
            disk.execute(() -> {
                waiting.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });

            long callbackTime = SystemClock.elapsedRealtime();
            long callbackWall = System.currentTimeMillis();
            long releasedAt;
            try {
                check(waiting.await(3, TimeUnit.SECONDS), "SQLite worker held behind queue barrier");
                collector.offer(fix(callbackTime, callbackWall), callbackTime, callbackWall, beforeWake.archiveObserver());
                SystemClock.sleep(20);
                trace.verifiedWake(SystemClock.elapsedRealtime(), 2, true);
                check(commitCount(events) == 0, "queued fix has no archive milestone before insert");
            } finally {
                releasedAt = SystemClock.elapsedRealtime();
                release.countDown();
            }
            drain(disk);
            GpsStartupTrace.Observation afterWake = trace.current();
            check(commitCount(events) == 1 && beforeWake.archiveObserver() == null && afterWake.archiveObserver() != null,
                    "completed SQLite insert is attributed to its original window");
            String oldCommit = events.stream().filter(event -> event.contains("OBSERVATION=0 STAGE=FIRST_ARCHIVE_COMMIT"))
                    .findFirst().orElseThrow();
            long elapsed = Long.parseLong(oldCommit.split(" ELAPSED_MS=")[1].split(" ")[0]);
            check(started + elapsed >= releasedAt, "archive timestamp is SQLite completion, after the intake and wake");

            File file = new File(directory, "routebook.sqlite");
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.toString(), null, SQLiteDatabase.OPEN_READWRITE)) {
                check(DatabaseUtils.queryNumEntries(db, "points") == 1, "first milestone refers to a durable point");
                db.execSQL("CREATE TRIGGER reject_test_insert BEFORE INSERT ON points "
                        + "BEGIN SELECT RAISE(FAIL, 'synthetic'); END");
                collector.offer(fix(callbackTime + 5_000, callbackWall + 5_000), callbackTime + 5_000,
                        callbackWall + 5_000, afterWake.archiveObserver());
                drain(disk);
                check(commitCount(events) == 1 && DatabaseUtils.queryNumEntries(db, "points") == 1,
                        "failed insert cannot report a successful archive milestone");

                db.execSQL("DROP TRIGGER reject_test_insert");
                collector.offer(fix(callbackTime + 10_000, callbackWall + 10_000), callbackTime + 10_000,
                        callbackWall + 10_000, afterWake.archiveObserver());
                drain(disk);
                check(events.stream().anyMatch(event -> event.contains("OBSERVATION=1 STAGE=FIRST_ARCHIVE_COMMIT"))
                                && DatabaseUtils.queryNumEntries(db, "points") == 2,
                        "next successful SQLite write records the first archive milestone for the wake");
            }
        }
    }

    private static Location fix(long monotonic, long wall) {
        Location fix = new Location("synthetic");
        fix.setElapsedRealtimeNanos(monotonic * 1_000_000);
        fix.setTime(wall);
        fix.setLatitude(0);
        fix.setLongitude(0);
        fix.setAccuracy(5);
        fix.setSpeed(0);
        return fix;
    }

    private static long commitCount(List<String> events) {
        return events.stream().filter(event -> event.contains("STAGE=FIRST_ARCHIVE_COMMIT")).count();
    }

    private static void drain(ExecutorService disk) throws Exception {
        disk.submit(() -> { }).get(3, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
