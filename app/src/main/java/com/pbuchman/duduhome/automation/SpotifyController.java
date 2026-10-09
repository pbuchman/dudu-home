package com.pbuchman.duduhome.automation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Local playback only. No metadata, global media keys, notification actions or second play. */
public final class SpotifyController {
    public static final String PACKAGE = "com.spotify.music";
    public enum Result { PLAYING, NO_ACCESS, MISSING, NO_SESSION, AMBIGUOUS, REMOTE, UNKNOWN, TIMEOUT, FAILED, CANCELLED }
    public interface Session {
        Object key(); boolean local(); Integer state(); long actions(); void play();
    }
    public interface Access {
        boolean permitted(); boolean open(); List<Session> sessions();
        void observe(Runnable changed); void close();
    }
    public interface Timer { void replace(Runnable task, long ms); void cancel(); }
    private final Access access;
    private final Timer timer;
    private final BooleanSupplier allowed;
    private final Consumer<Result> complete;
    private final Runnable playSent;
    private java.util.function.BooleanSupplier admission = () -> true;
    private Object sessionKey;
    private boolean finished, sent, started;
    private final Runnable timeout = () -> finish(sessionKey == null ? Result.NO_SESSION : Result.TIMEOUT);

    public SpotifyController(Context context, BooleanSupplier allowed, Runnable playSent, Consumer<Result> complete) {
        this(new AndroidAccess(context.getApplicationContext()), new Timer() {
            private final Handler handler = new Handler(Looper.getMainLooper());
            public void replace(Runnable task, long ms) { cancel(); handler.postDelayed(task, ms); }
            public void cancel() { handler.removeCallbacksAndMessages(null); }
        }, allowed, playSent, complete);
    }
    public SpotifyController(Context context, BooleanSupplier allowed, BooleanSupplier admission, Runnable playSent, Consumer<Result> complete) {
        this(context, allowed, playSent, complete); this.admission = admission;
    }
    public SpotifyController(Access access, Timer timer, BooleanSupplier allowed, Runnable playSent, Consumer<Result> complete) {
        this.access = access; this.timer = timer; this.allowed = allowed;
        this.playSent = playSent; this.complete = complete;
    }
    public void start() {
        if (finished || started) return;
        started = true;
        try {
            if (!access.permitted()) { finish(Result.NO_ACCESS); return; }
            if (!allowed.getAsBoolean()) { finish(Result.CANCELLED); return; }
            if (!admission.getAsBoolean()) { finish(Result.CANCELLED); return; }
            if (!access.open()) { finish(Result.MISSING); return; }
            access.observe(this::inspect);
            timer.replace(timeout, 15000);
            inspect();
        } catch (SecurityException denied) { finish(Result.NO_ACCESS); }
        catch (RuntimeException unavailable) { finish(Result.FAILED); }
    }
    public void priorityChanged() { if (started) inspect(); }
    private void inspect() {
        if (finished) return;
        try {
            if (!access.permitted()) { finish(Result.NO_ACCESS); return; }
            List<Session> matches = access.sessions();
            if (matches == null) { finish(Result.UNKNOWN); return; }
            if (matches.size() > 1) { finish(Result.AMBIGUOUS); return; }
            if (matches.isEmpty()) {
                if (sessionKey != null) finish(Result.NO_SESSION);
                return;
            }
            Session session = matches.get(0);
            if (session.key() == null) { finish(Result.UNKNOWN); return; }
            if (sessionKey != null && !sessionKey.equals(session.key())) { finish(Result.UNKNOWN); return; }
            if (sessionKey == null) { sessionKey = session.key(); timer.replace(timeout, 10000); }
            if (!session.local()) { finish(Result.REMOTE); return; }
            Integer state = session.state();
            if (state == null) return;
            if (state == PlaybackState.STATE_PLAYING) { finish(Result.PLAYING); return; }
            if (state == PlaybackState.STATE_ERROR) { finish(Result.FAILED); return; }
            if (!sent && (state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED)
                    && (session.actions() & PlaybackState.ACTION_PLAY) != 0 && allowed.getAsBoolean()) {
                sent = true; // Reserve before IPC, including uncertain exceptions.
                timer.replace(timeout, 10000);
                playSent.run(); session.play();
            }
        } catch (SecurityException denied) { finish(Result.NO_ACCESS); }
        catch (RuntimeException unavailable) { finish(Result.FAILED); }
    }
    public void cancel() { finish(Result.CANCELLED); }
    private void finish(Result result) {
        if (finished) return;
        finished = true; timer.cancel();
        try { access.close(); } catch (RuntimeException ignored) { }
        complete.accept(result);
    }

    private static final class AndroidAccess implements Access {
        private final Context context;
        private final MediaSessionManager manager;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ComponentName listener;
        private Runnable changed = () -> { };
        private MediaController observed;
        private boolean listening;
        private final MediaSessionManager.OnActiveSessionsChangedListener sessions = ignored -> changed.run();
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { changed.run(); }
            @Override public void onAudioInfoChanged(MediaController.PlaybackInfo info) { changed.run(); }
            @Override public void onSessionDestroyed() { changed.run(); }
        };
        AndroidAccess(Context context) {
            this.context = context; manager = context.getSystemService(MediaSessionManager.class);
            listener = new ComponentName(context, YanosikPresence.class);
        }
        public boolean permitted() { return YanosikPresence.accessGranted(context); }
        public boolean open() {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
            if (intent == null) return false;
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true;
        }
        public void observe(Runnable changed) {
            this.changed = changed;
            manager.addOnActiveSessionsChangedListener(sessions, listener, main); listening = true;
        }
        public List<Session> sessions() {
            var matches = manager.getActiveSessions(listener).stream()
                    .filter(c -> PACKAGE.equals(c.getPackageName())).collect(java.util.stream.Collectors.toList());
            if (matches.size() != 1) {
                return matches.stream().map(c -> (Session)new AndroidSession(c)).collect(java.util.stream.Collectors.toList());
            }
            MediaController next = matches.get(0);
            if (observed == null || !observed.getSessionToken().equals(next.getSessionToken())) {
                if (observed != null) observed.unregisterCallback(callback);
                observed = next; observed.registerCallback(callback, main);
            }
            return List.of(new AndroidSession(next));
        }
        public void close() {
            changed = () -> { };
            try { if (observed != null) observed.unregisterCallback(callback); }
            finally { if (listening) manager.removeOnActiveSessionsChangedListener(sessions); }
        }
    }
    private record AndroidSession(MediaController controller) implements Session {
        public Object key() { return controller.getSessionToken(); }
        public boolean local() {
            var info = controller.getPlaybackInfo();
            return info != null && info.getPlaybackType() == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL;
        }
        public Integer state() { var s = controller.getPlaybackState(); return s == null ? null : s.getState(); }
        public long actions() { var s = controller.getPlaybackState(); return s == null ? 0 : s.getActions(); }
        public void play() { controller.getTransportControls().play(); }
    }
}
