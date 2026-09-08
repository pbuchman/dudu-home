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
    private static WeakReference<MainActivity> visible = new WeakReference<>(null);

    static void visible(MainActivity activity) { visible = new WeakReference<>(activity); }
    static void hidden(MainActivity activity) { if (visible.get() == activity) visible.clear(); }

    static boolean callsGate(HomeEvent event) {
        return event == HomeEvent.DEPARTURE_STARTED || event == HomeEvent.RETURN_APPROACH;
    }

    static void dispatch(Context context, HomeEvent event) {
        if (!callsGate(event)) return; // Reserved checkpoints have no action in this version.
        GateNumberStore store = new GateNumberStore(context);
        if (store.read() == null || store.cooldownRemainingMillis() > 0) return;
        MainActivity activity = visible.get();
        if (activity != null) {
            activity.automaticAction();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (pendingToken != null && now - pendingSince < 5000) return;
        pendingToken = UUID.randomUUID().toString();
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
}
