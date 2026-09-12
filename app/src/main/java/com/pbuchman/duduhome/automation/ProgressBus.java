package com.pbuchman.duduhome.automation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** Single-process observable state. Reading/subscribing never starts the monitor. */
public final class ProgressBus {
    public static final Set<Kind> HOME = Set.of(Kind.DEPARTURE, Kind.RETURN, Kind.CLEANING);
    public static final Set<Kind> MOTION = Set.of(Kind.YANOSIK);
    private static final ProgressModel model = new ProgressModel();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Runnable> observers = new ArrayList<>();
    private ProgressBus() { }
    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }
    public static void subscribe(Runnable listener) { checkThread(); observers.add(listener); deliver(listener); }
    public static void unsubscribe(Runnable listener) { checkThread(); observers.remove(listener); }
    public static ProgressModel.State visible() { checkThread(); return model.visible(SystemClock.elapsedRealtime()); }
    public static List<ProgressModel.State> snapshot() { checkThread(); return model.snapshot(); }
    public static long reset(Context c, Reason reason) {
        checkThread(); long generation = model.reset(reason, SystemClock.elapsedRealtime());
        Diagnostics.record(c, "PROGRESS_RESET_" + reason + " GENERATION=" + generation); notifyObservers(); return generation;
    }
    public static void offer(Context c, long epoch, Set<Kind> scope, List<DetectionProgress> updates) {
        checkThread(); List<ProgressModel.State> before = model.snapshot();
        model.offer(epoch, scope, updates, SystemClock.elapsedRealtime());
        for (ProgressModel.State s : model.snapshot()) {
            if (s.evidenceId() < 0) continue;
            if (before.stream().noneMatch(old -> old.id() == s.id() && old.phase() == s.phase()))
                Diagnostics.record(c, "PROGRESS_" + s.kind() + "_" + s.phase() + "_" + s.reason() + " ID=" + s.id());
        }
        notifyObservers();
    }
    public static long request(Context c, Kind kind) {
        checkThread(); long id = model.request(kind, SystemClock.elapsedRealtime());
        Diagnostics.record(c, "ACTION_REQUESTED_" + kind + " ID=" + id); notifyObservers(); return id;
    }
    public static void update(Context c, long id, Phase phase, Reason reason) {
        if (id == 0) return;
        Context app = c.getApplicationContext();
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> update(app, id, phase, reason)); return; }
        ProgressModel.State before = model.attempt(id);
        model.update(id, phase, reason, SystemClock.elapsedRealtime());
        ProgressModel.State after = model.attempt(id);
        if (after != null && after != before) {
            Diagnostics.record(app, "ACTION_" + after.kind() + "_" + phase + "_" + reason + " ID=" + id);
            notifyObservers();
        }
    }
    public static Kind kind(HomeEvent event) {
        return switch (event) { case DEPARTURE_STARTED -> Kind.DEPARTURE; case RETURN_APPROACH -> Kind.RETURN;
            case OUTBOUND_CHECKPOINT -> Kind.CLEANING; default -> throw new IllegalArgumentException("No action"); };
    }
    public static Kind kind(HomeAction action) {
        return switch (action) { case GATE -> Kind.DEPARTURE; case CLEANING -> Kind.CLEANING; case MOP -> Kind.MOP; };
    }
    public static void presentationChanged() { checkThread(); notifyObservers(); }
    private static void notifyObservers() {
        for (Runnable observer : List.copyOf(observers)) {
            deliver(observer);
        }
    }
    private static void deliver(Runnable observer) {
        try { observer.run(); } catch (RuntimeException failed) { android.util.Log.w("DuduHome", "PROGRESS_OBSERVER_FAILED"); }
    }
}
