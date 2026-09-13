package com.pbuchman.duduhome.startup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs through emulator safety runner; no shell or device properties are changed. */
public final class DuduCycleChecks {
    private static String frame(String... fields) {
        return "DUDU_CYCLE_BEGIN\n" + String.join("\n", fields) + "\nDUDU_CYCLE_END\n";
    }
    public static void run() {
        String cold = frame("1", "", "", "", "", "", "", "1");
        check(DuduCycle.parse(cold).kind() == DuduCycle.Kind.ABSENT_COLD_BASELINE, "complete cold absence");
        for (String n : new String[]{"0", "1", "2", "9".repeat(9)}) {
            var observation = DuduCycle.parse(frame("1", "0", "0", n, "0", "0", n, "1"));
            check(observation.kind() == DuduCycle.Kind.AWAKE_COUNTER && observation.counter() == Long.parseLong(n), "numeric counter");
        }
        String[] valid = {"1", "0", "0", "2", "0", "0", "2", "1"};
        for (int i = 0; i < valid.length; i++) {
            String[] changed = valid.clone(); changed[i] = ""; unknown(frame(changed));
            changed = valid.clone(); changed[i] = "bad"; unknown(frame(changed));
        }
        for (String n : new String[]{"-1", "+1", "1" + "0".repeat(9), " 1", "1 "}) unknown(frame("1","0","0",n,"0","0",n,"1"));
        unknown(frame("1","0","0","1","0","0","2","1"));
        unknown(frame("1","1","0","2","0","0","2","1"));
        unknown(frame("1","0","0","2","0","1","2","1"));
        unknown(frame("0","","","","","","","1"));
        unknown(frame("1","","","","","","","0"));
        unknown(cold.replace("DUDU_CYCLE_BEGIN", ""));
        unknown(cold.replace("DUDU_CYCLE_END", ""));
        unknown(cold + "extra\n"); unknown(cold.substring(0, cold.length()-1));
        unknown("x".repeat(513)); unknown(null); unknown("\n\n\n\n\n");
        Fake validProcess = new Fake(cold, "", 0, true);
        check(DuduCycle.read(() -> validProcess).kind() == DuduCycle.Kind.ABSENT_COLD_BASELINE && validProcess.destroyed, "valid process cleaned");
        for (Fake failed : new Fake[]{new Fake(cold,"",1,true), new Fake(cold,"warning",0,true),
                new Fake(cold,"",0,false),new Fake("x".repeat(513),"",0,true),new Fake(cold,"x".repeat(513),0,true)}) {
            check(DuduCycle.read(() -> failed).kind() == DuduCycle.Kind.UNAVAILABLE && failed.destroyed, "failed process rejected and cleaned");
        }
        check(DuduCycle.read(() -> {throw new IOException();}).kind() == DuduCycle.Kind.UNAVAILABLE, "start exception");
        check(DuduCycle.read(() -> {throw new SecurityException();}).kind() == DuduCycle.Kind.UNAVAILABLE, "denied start");
        Fake interrupted = new Fake(cold,"",0,true) {
            @Override public boolean waitFor(long time, TimeUnit unit) throws InterruptedException { throw new InterruptedException(); }
        };
        check(DuduCycle.read(() -> interrupted).kind() == DuduCycle.Kind.UNAVAILABLE && Thread.interrupted() && interrupted.destroyed,
                "interrupt restored and process cleaned");
    }
    private static void unknown(String input) { check(DuduCycle.parse(input).kind() == DuduCycle.Kind.UNAVAILABLE, "uncertain parser input"); }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
    private static class Fake extends Process {
        final String out, err; final int exit; final boolean done; boolean destroyed;
        Fake(String out, String err, int exit, boolean done) { this.out=out;this.err=err;this.exit=exit;this.done=done; }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(out.getBytes(StandardCharsets.US_ASCII)); }
        public InputStream getErrorStream() { return new ByteArrayInputStream(err.getBytes(StandardCharsets.US_ASCII)); }
        public int waitFor() { return exit; }
        public boolean waitFor(long time, TimeUnit unit) throws InterruptedException {
            check(time == 500 && unit == TimeUnit.MILLISECONDS, "bounded process wait"); return done;
        }
        public int exitValue() { return exit; }
        public void destroy() { destroyed=true; }
    }
}
