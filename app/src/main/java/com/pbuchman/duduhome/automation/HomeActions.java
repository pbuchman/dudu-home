package com.pbuchman.duduhome.automation;

import com.pbuchman.duduhome.config.PrivateImport;
import com.pbuchman.duduhome.gate.GateNumberStore;
import com.pbuchman.duduhome.roborock.RoborockStore;
import com.pbuchman.duduhome.ui.MainActivity;
import com.pbuchman.duduhome.diagnostics.Diagnostics;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.UUID;
import android.os.Handler;
import android.os.Looper;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** Explicit event mapping and one ephemeral, internal UI request. No persisted call queue. */
public final class HomeActions {
    public static final String ACTION = "com.pbuchman.duduhome.HOME_ACTION";
    private static String pendingToken;
    private static long pendingSince;
    private static HomeAction pendingAction;
    private static long pendingAttempt;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    public record Request(HomeAction action, long attempt) { }
    private static boolean busy;
    public static synchronized boolean begin() { if (busy) return false; busy = true; return true; }
    public static synchronized void end() { busy = false; }
    public static synchronized boolean busy() { return busy; }
    private static WeakReference<MainActivity> visible = new WeakReference<>(null);
    private static WeakReference<MainActivity> screen = new WeakReference<>(null);

    public static void visible(MainActivity activity) { visible = new WeakReference<>(activity); screen = new WeakReference<>(activity); }
    public static void hidden(MainActivity activity) { if (visible.get() == activity) visible.clear(); }
    public static MainActivity visibleActivity() { return visible.get(); }

    public static boolean allowsExternalLaunch() {
        MainActivity activity = screen.get();
        boolean pending = pendingToken != null && SystemClock.elapsedRealtime() - pendingSince < 5000;
        return !busy() && !pending && (activity == null || activity.allowsExternalLaunch());
    }

    public static boolean callsGate(HomeEvent event) {
        return event == HomeEvent.DEPARTURE_STARTED || event == HomeEvent.RETURN_APPROACH;
    }

    public static void dispatch(Context context, HomeEvent event) {
        if (!callsGate(event) && event != HomeEvent.OUTBOUND_CHECKPOINT) return;
        long attempt = ProgressBus.request(context, ProgressBus.kind(event));
        HomeAction action;
        if (callsGate(event)) action = HomeAction.GATE;
        else if (event == HomeEvent.OUTBOUND_CHECKPOINT) {
            if (!DailyCleaning.reserve(context)) { skip(context, attempt, Reason.DAILY_LIMIT_OR_STORAGE, "SKIP_CLEANING_DAILY_LIMIT_OR_STORAGE"); return; }
            action = HomeAction.CLEANING;
        }
        else return;
        if (busy() || PrivateImport.pending(context)) { skip(context, attempt, Reason.BUSY_OR_MAINTENANCE, "SKIP_" + action + "_BUSY_OR_MAINTENANCE"); return; }
        if (action == HomeAction.GATE) {
            GateNumberStore store = new GateNumberStore(context);
            if (store.read() == null) { skip(context, attempt, Reason.NO_NUMBER, "SKIP_GATE_NO_NUMBER"); return; }
            if (store.cooldownRemainingMillis() > 0) { skip(context, attempt, Reason.COOLDOWN, "SKIP_GATE_COOLDOWN"); return; }
        } else if (new RoborockStore(context).read() == null) { skip(context, attempt, Reason.NO_CONFIG, "SKIP_CLEANING_NO_CONFIG"); return; }
        Diagnostics.record(context, "DISPATCH_" + action);
        MainActivity activity = visible.get();
        if (activity != null) {
            ProgressBus.update(context, attempt, Phase.ACCEPTED, Reason.NONE);
            activity.automaticAction(action, attempt);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (pendingToken != null && now - pendingSince < 5000) { skip(context, attempt, Reason.UI_BUSY, "SKIP_ACTION_PENDING"); return; }
        expirePending(context);
        pendingToken = UUID.randomUUID().toString();
        pendingAction = action;
        pendingSince = now;
        pendingAttempt = attempt;
        String token = pendingToken;
        Context app = context.getApplicationContext();
        handler.postDelayed(() -> { if (token.equals(pendingToken)) expirePending(app); }, 5000);
        try {
            context.startActivity(new Intent(context, MainActivity.class).setAction(ACTION)
                    .putExtra("request_token", pendingToken).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { pendingToken = null; skip(context, attempt, Reason.UI_UNAVAILABLE, "ACTION_UI_START_DENIED"); }
    }

    private static void skip(Context context, long id, Reason reason, String category) {
        Diagnostics.record(context, category); ProgressBus.update(context, id, Phase.SKIPPED, reason);
    }
    private static void expirePending(Context context) {
        if (pendingToken != null && SystemClock.elapsedRealtime() - pendingSince >= 5000) {
            pendingToken = null;
            skip(context, pendingAttempt, Reason.UI_UNAVAILABLE, "ACTION_UI_EXPIRED");
        }
    }

    public static boolean consume(Intent intent) {
        boolean valid = ACTION.equals(intent.getAction()) && pendingToken != null
                && pendingToken.equals(intent.getStringExtra("request_token"))
                && SystemClock.elapsedRealtime() - pendingSince < 5000;
        if (valid) pendingToken = null;
        return valid;
    }
    public static HomeAction consumeAction(Intent intent) { return consume(intent) ? pendingAction : null; }
    public static Request consumeRequest(Context context, Intent intent) {
        expirePending(context);
        if (!consume(intent)) return null;
        ProgressBus.update(context, pendingAttempt, Phase.ACCEPTED, Reason.NONE);
        return new Request(pendingAction, pendingAttempt);
    }
}
