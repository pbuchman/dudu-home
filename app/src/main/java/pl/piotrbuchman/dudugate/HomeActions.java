package pl.piotrbuchman.dudugate;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.UUID;

/** Explicit event mapping and one ephemeral, internal UI request. No persisted call queue. */
final class HomeActions {
    static final String ACTION = "pl.piotrbuchman.dudugate.HOME_ACTION";
    private static String pendingToken;
    private static long pendingSince;
    private static HomeAction pendingAction;
    private static boolean busy;
    static synchronized boolean begin() { if (busy) return false; busy = true; return true; }
    static synchronized void end() { busy = false; }
    static synchronized boolean busy() { return busy; }
    private static WeakReference<MainActivity> visible = new WeakReference<>(null);

    static void visible(MainActivity activity) { visible = new WeakReference<>(activity); }
    static void hidden(MainActivity activity) { if (visible.get() == activity) visible.clear(); }

    static boolean callsGate(HomeEvent event) {
        return event == HomeEvent.DEPARTURE_STARTED || event == HomeEvent.RETURN_APPROACH;
    }

    static void dispatch(Context context, HomeEvent event) {
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

    static boolean consume(Intent intent) {
        boolean valid = ACTION.equals(intent.getAction()) && pendingToken != null
                && pendingToken.equals(intent.getStringExtra("request_token"))
                && SystemClock.elapsedRealtime() - pendingSince < 5000;
        if (valid) pendingToken = null;
        return valid;
    }
    static HomeAction consumeAction(Intent intent) { return consume(intent) ? pendingAction : null; }
}
