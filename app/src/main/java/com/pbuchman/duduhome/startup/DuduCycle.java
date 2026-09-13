package com.pbuchman.duduhome.startup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Read-only DUDUOS adapter. Unknown reads never become a zero counter. */
public final class DuduCycle {
    public enum Kind { AWAKE_COUNTER, ABSENT_COLD_BASELINE, UNAVAILABLE }
    public record Observation(Kind kind, long counter) {
        public Observation {
            if (kind == null || (kind == Kind.AWAKE_COUNTER && (counter < 0 || counter > 999999999L))
                    || (kind == Kind.ABSENT_COLD_BASELINE && counter != 0)
                    || (kind == Kind.UNAVAILABLE && counter != -1)) throw new IllegalArgumentException("Invalid cycle observation");
        }
    }
    private static final Observation UNKNOWN = new Observation(Kind.UNAVAILABLE, -1);
    private static final int LIMIT = 512;
    private static final String COMMAND = "printf 'DUDU_CYCLE_BEGIN\\n' && "
            + "getprop sys.boot_completed && getprop sys.sleep && getprop sys.fyt.sleeping && getprop sys.sleeptimes && "
            + "getprop sys.sleep && getprop sys.fyt.sleeping && getprop sys.sleeptimes && getprop sys.boot_completed && "
            + "printf 'DUDU_CYCLE_END\\n'";
    private DuduCycle() { }
    public static Observation unavailable() { return UNKNOWN; }
    public static Observation readObservation() {
        return read(() -> new ProcessBuilder("/system/bin/sh", "-c", COMMAND).start());
    }
    interface Starter { Process start() throws IOException; }
    // Synthetic process seam, not an Android component or a runtime override.
    static Observation read(Starter starter) {
        Process process = null;
        try {
            process = starter.start();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return UNKNOWN;
            String output = bounded(process.getInputStream());
            String error = bounded(process.getErrorStream());
            if (output == null || error == null || !error.isEmpty()) return UNKNOWN;
            return parse(output);
        } catch (IOException | RuntimeException ignored) { return UNKNOWN; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return UNKNOWN; }
        finally { if (process != null) process.destroy(); }
    }
    private static String bounded(InputStream input) throws IOException {
        byte[] bytes = new byte[LIMIT];
        int size = 0;
        while (size < LIMIT) {
            int count = input.read(bytes, size, LIMIT - size);
            if (count == -1) return new String(bytes, 0, size, StandardCharsets.US_ASCII);
            size += count;
        }
        return input.read() == -1 ? new String(bytes, StandardCharsets.US_ASCII) : null;
    }
    public static Observation parse(String output) {
        if (output == null || output.length() > LIMIT) return UNKNOWN;
        String[] lines = output.split("\n", -1);
        if (lines.length != 11 || !lines[0].equals("DUDU_CYCLE_BEGIN") || !lines[9].equals("DUDU_CYCLE_END")
                || !lines[10].isEmpty() || !lines[1].equals("1") || !lines[8].equals("1")) return UNKNOWN;
        boolean absent = true;
        for (int i = 2; i <= 7; i++) absent &= lines[i].isEmpty();
        if (absent) return new Observation(Kind.ABSENT_COLD_BASELINE, 0);
        if (!lines[2].equals("0") || !lines[3].equals("0") || !lines[5].equals("0") || !lines[6].equals("0")
                || !lines[4].equals(lines[7]) || !lines[4].matches("[0-9]{1,9}")) return UNKNOWN;
        return new Observation(Kind.AWAKE_COUNTER, Long.parseLong(lines[4]));
    }
}
