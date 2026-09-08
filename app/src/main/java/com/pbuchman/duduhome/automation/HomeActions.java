package com.pbuchman.duduhome.automation;

import com.pbuchman.duduhome.config.PrivateImport;
import com.pbuchman.duduhome.gate.GateNumberStore;
import com.pbuchman.duduhome.roborock.RoborockStore;
import com.pbuchman.duduhome.ui.MainActivity;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.UUID;

/** Explicit event mapping and one ephemeral, internal UI request. No persisted call queue. */
public final class HomeActions {
    public static final String ACTION = "com.pbuchman.duduhome.HOME_ACTION";
    private static String pendingToken;
    private static long pendingSince;
    private static HomeAction pendingAction;
    private static boolean busy;
    public static synchronized boolean begin() { if (busy) return false; busy = true; return true; }
    public static synchronized void end() { busy = false; }
    public static synchronized boolean busy() { return busy; }
    private static WeakReference<MainActivity> visible = new WeakReference<>(null);
    private static WeakReference<MainActivity> screen = new WeakReference<>(null);

    public static void visible(MainActivity activity) { visible = new WeakReference<>(activity); screen = new WeakReference<>(activity); }
    public static void hidden(MainActivity activity) { if (visible.get() == activity) visible.clear(); }

    public static boolean allowsExternalLaunch() {
        MainActivity activity = screen.get();
        boolean pending = pendingToken != null && SystemClock.elapsedRealtime() - pendingSince < 5000;
        return !busy() && !pending && (activity == null || activity.allowsExternalLaunch());
    }

    public static boolean callsGate(HomeEvent event) {
        return event == HomeEvent.DEPARTURE_STARTED || event == HomeEvent.RETURN_APPROACH;
    }

    public static void dispatch(Context context, HomeEvent event) {
        HomeAction action;
        if (callsGate(event)) action = HomeAction.GATE;
        else if (event == HomeEvent.OUTBOUND_CHECKPOINT && DailyCleaning.reserve(context)) action = HomeAction.CLEANING;
        else return;
        if (busy() || PrivateImport.pending(context)) { android.util.Log.i("DuduHome", "Skipped " + action + ": busy or maintenance"); return; }
        if (action == HomeAction.GATE) {
            GateNumberStore store = new GateNumberStore(context);
            if (store.read() == null || store.cooldownRemainingMillis() > 0) return;
        } else if (new RoborockStore(context).read() == null) { android.util.Log.i("DuduHome", "Skipped CLEANING: configuration unavailable"); return; }
        MainActivity activity = visible.get();
        if (activity != null) {
            activity.automaticAction(action);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (pendingToken != null && now - pendingSince < 5000) return;
        pendingToken = UUID.randomUUID().toString();
        pendingAction = action;
        pendingSince = now;
        try {
            context.startActivity(new Intent(context, MainActivity.class).setAction(ACTION)
                    .putExtra("request_token", pendingToken).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { pendingToken = null; }
    }

    public static boolean consume(Intent intent) {
        boolean valid = ACTION.equals(intent.getAction()) && pendingToken != null
                && pendingToken.equals(intent.getStringExtra("request_token"))
                && SystemClock.elapsedRealtime() - pendingSince < 5000;
        if (valid) pendingToken = null;
        return valid;
    }
    public static HomeAction consumeAction(Intent intent) { return consume(intent) ? pendingAction : null; }
}
