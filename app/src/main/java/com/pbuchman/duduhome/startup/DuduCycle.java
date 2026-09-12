package com.pbuchman.duduhome.startup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Read-only DUDUOS adapter. Caller runs off the main thread; no hidden APIs or root. */
public final class DuduCycle {
    private DuduCycle() { }

    public static long readAwakeCycle() {
        Process process = null;
        try {
            // Fixed, non-user-supplied command. Counter is bracketed by sleep-state reads.
            process = new ProcessBuilder("/system/bin/sh", "-c",
                    "getprop sys.sleep; getprop sys.fyt.sleeping; getprop sys.sleeptimes; "
                    + "getprop sys.fyt.sleeping; getprop sys.sleep").start();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return -1;
            byte[] bytes = new byte[256];
            int size = 0, count;
            while (size < bytes.length && (count = process.getInputStream().read(bytes, size, bytes.length - size)) != -1) size += count;
            if (size == bytes.length) return -1;
            return parse(new String(bytes, 0, size, StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException ignored) { return -1; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return -1; }
        finally { if (process != null) process.destroy(); }
    }

    public static long parse(String output) {
        String[] lines = output.split("\n", -1);
        if (lines.length != 6 || !lines[0].equals("0") || !lines[1].equals("0")
                || !lines[3].equals("0") || !lines[4].equals("0") || !lines[5].isEmpty()
                || !lines[2].matches("[0-9]{1,9}")) return -1;
        return Long.parseLong(lines[2]);
    }
}
