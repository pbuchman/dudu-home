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
    private static final String SESSION = java.util.UUID.randomUUID().toString();
    private static final java.util.Map<Long, Control> controls = new java.util.HashMap<>();
    private static java.util.function.Consumer<ProgressModel.State> cancellationListener = ignored -> { };
    private static final class Control {
        boolean cancelled, sent, groupSent, finalized;
        java.util.function.BooleanSupplier reservation = () -> true;
        java.util.function.BooleanSupplier admission = () -> true;
        final java.util.List<java.util.function.BooleanSupplier> cleanup = new java.util.ArrayList<>();
    }
    public static AttemptToken token(ProgressModel.State state) { return new AttemptToken(SESSION, state.id()); }
    public static AttemptToken token(long id) { return new AttemptToken(SESSION, id); }
    public static boolean cancellable(ProgressModel.State state) {
        return state != null && !ProgressModel.terminal(state.phase()) && state.phase() != Phase.CANCELLING;
    }
    public static void setCancellationListener(java.util.function.Consumer<ProgressModel.State> listener) {
        checkThread(); cancellationListener = listener;
    }
    public static void clearCancellationListener(java.util.function.Consumer<ProgressModel.State> listener) {
        checkThread(); if (cancellationListener == listener) cancellationListener = ignored -> { };
    }
    public static void reserveWith(long id, java.util.function.BooleanSupplier reservation) {
        synchronized (controls) { controls.computeIfAbsent(id, ignored -> new Control()).reservation = reservation; }
    }
    public static void allowSendWith(long id, java.util.function.BooleanSupplier admission) {
        synchronized (controls) { controls.computeIfAbsent(id, ignored -> new Control()).admission = admission; }
    }
    public static void onCancel(long id, java.util.function.BooleanSupplier cleanup) {
        checkThread(); synchronized (controls) { controls.computeIfAbsent(id, ignored -> new Control()).cleanup.add(cleanup); }
    }
    public static boolean cancelled(long id) {
        synchronized (controls) { Control c = controls.get(id); return c == null || c.cancelled; }
    }
    public static boolean active(long id) {
        checkThread(); ProgressModel.State state = model.state(id); return state != null && !ProgressModel.terminal(state.phase());
    }
    public static void markGroupSent(long id) {
        synchronized (controls) { Control c = controls.get(id); if (c != null) c.groupSent = true; }
    }
    public static boolean sent(long id) {
        synchronized (controls) { Control c = controls.get(id); return c != null && c.sent; }
    }
    /** Linearization point shared with cancel. Reservation failure never permits external work. */
    public static boolean claimSend(Context context, long id) { return claimSend(context, id, () -> true); }
    public static boolean claimSend(Context context, long id, java.util.function.BooleanSupplier ownReservation) {
        synchronized (controls) {
            Control c = controls.get(id);
            if (c == null || c.cancelled || c.finalized || c.sent || !c.admission.getAsBoolean()) return false;
            if (!c.reservation.getAsBoolean() || !ownReservation.getAsBoolean()) return false;
            c.sent = true;
            Diagnostics.commitAttempt(context, id);
            return true;
        }
    }
    public static boolean cancelAttempt(AttemptToken token) {
        checkThread();
        if (token == null || !SESSION.equals(token.session())) return false;
        ProgressModel.State state = model.state(token.id());
        if (!cancellable(state)) return false;
        Control c;
        synchronized (controls) {
            c = controls.get(token.id());
            if (c == null || c.cancelled || c.finalized) return false;
            c.cancelled = true;
        }
        model.cancelByUser(token.id(), (c.sent || c.groupSent) ? Reason.COMMAND_SENT : Reason.USER_CANCELLED, true, SystemClock.elapsedRealtime());
        cancellationListener.accept(state);
        boolean pending = false;
        for (var cleanup : java.util.List.copyOf(c.cleanup)) pending |= cleanup.getAsBoolean();
        if (!c.sent) Diagnostics.discardAttempt(token.id());
        if (!pending) finishCancellation(token.id());
        else notifyObservers();
        return true;
    }
    public static void finishCancellation(long id) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> finishCancellation(id)); return; }
        Control c;
        synchronized (controls) { c = controls.get(id); if (c == null || !c.cancelled) return; c.finalized = true; }
        model.cancelByUser(id, (c.sent || c.groupSent) ? Reason.COMMAND_SENT : Reason.USER_CANCELLED, false, SystemClock.elapsedRealtime());
        notifyObservers();
    }
    private static void prepare(long id) {
        synchronized (controls) {
            if (!controls.containsKey(id)) { controls.put(id, new Control()); Diagnostics.beginAttempt(id); }
        }
    }

    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }
    public static void subscribe(Runnable listener) { checkThread(); observers.add(listener); deliver(listener); }
    public static void unsubscribe(Runnable listener) { checkThread(); observers.remove(listener); }
    public static ProgressModel.State visible() { checkThread(); return model.visible(SystemClock.elapsedRealtime()); }
    public static List<ProgressModel.State> snapshot() { checkThread(); return model.snapshot(); }
    public static long reset(Context c, Reason reason) {
        checkThread(); long generation = model.reset(reason, SystemClock.elapsedRealtime());
        Diagnostics.record(c, "PROGRESS_RESET_" + reason + " GENERATION=" + generation); pruneCandidates(); notifyObservers(); return generation;
    }
    public static void offer(Context c, long epoch, Set<Kind> scope, List<DetectionProgress> updates) {
        checkThread(); List<ProgressModel.State> before = model.snapshot();
        model.offer(epoch, scope, updates, SystemClock.elapsedRealtime());
        for (ProgressModel.State s : model.snapshot()) {
            if (s.evidenceId() < 0 || model.attempt(s.id()) != null) continue;
            if (!ProgressModel.terminal(s.phase())) prepare(s.id());
            if (before.stream().noneMatch(old -> old.id() == s.id() && old.phase() == s.phase() && old.stage() == s.stage()))
                Diagnostics.recordAttempt(c, s.id(), "PROGRESS_" + s.kind() + "_" + s.phase() + "_" + s.reason()
                        + "_STAGE_" + s.stage() + " ID=" + s.id());
        }
        pruneCandidates();
        notifyObservers();
    }
    private static void pruneCandidates() {
        var snapshot = model.snapshot();
        synchronized (controls) {
            controls.entrySet().removeIf(entry -> {
                ProgressModel.State state = snapshot.stream().filter(s -> s.id() == entry.getKey()).findFirst().orElse(null);
                boolean remove = state == null || (model.attempt(state.id()) == null && ProgressModel.terminal(state.phase()));
                if (remove && !entry.getValue().sent) Diagnostics.discardAttempt(entry.getKey());
                return remove;
            });
        }
    }
    public static long request(Context c, Kind kind) { return request(c, kind, false); }
    public static long requestManual(Context c, Kind kind) { return request(c, kind, true); }
    private static long request(Context c, Kind kind, boolean manual) {
        checkThread(); long id = manual ? model.requestManual(kind, SystemClock.elapsedRealtime()) : model.request(kind, SystemClock.elapsedRealtime());
        prepare(id);
        Diagnostics.recordAttempt(c, id, "ACTION_REQUESTED_" + kind + " ID=" + id); pruneCandidates(); notifyObservers(); return id;
    }
    public static void update(Context c, long id, Phase phase, Reason reason) {
        if (id == 0) return;
        Context app = c.getApplicationContext();
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> update(app, id, phase, reason)); return; }
        synchronized (controls) {
            Control control = controls.get(id);
            if (control != null && control.cancelled) return;
            if (control != null && ProgressModel.terminal(phase) && !control.finalized) {
                if (!control.sent) control.reservation.getAsBoolean();
                control.finalized = true;
                Diagnostics.commitAttempt(app, id);
            }
        }
        ProgressModel.State before = model.attempt(id);
        model.update(id, phase, reason, SystemClock.elapsedRealtime());
        ProgressModel.State after = model.attempt(id);
        if (after != null && after != before) {
            Diagnostics.recordAttempt(app, id, "ACTION_" + after.kind() + "_" + phase + "_" + reason + " ID=" + id);
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
