package com.pbuchman.duduhome.automation;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Read-only diagnostic samples for the two target apps, in a single bounded worker. */
final class TargetAppsSnapshot {
    enum Stage { FIRST_MOVING, MOTION_CONFIRMED, YANOSIK_DECISION, SPOTIFY_DECISION, POST_YANOSIK, POST_SPOTIFY }
    private static final long MIN_SAMPLE_INTERVAL_MS = 5000;
    private static final long MAX_SAMPLE_AGE_MS = 5000;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), job -> {
                var thread = new Thread(job, "DuduTargetSnapshot");
                thread.setDaemon(true);
                return thread;
            });

    static { WORKER.allowCoreThreadTimeOut(true); }

    private record Previous(long observation, long cycle, long attempt, long time) { }
    private final EnumMap<Stage, Previous> last = new EnumMap<>(Stage.class);
    private final Context context;

    TargetAppsSnapshot(Context context) { this.context = context; }

    void request(Stage stage, long startup, long observation, long cycle, long attempt, String uiBlocker, BooleanSupplier current) {
        long now = SystemClock.elapsedRealtime();
        Previous previous = last.get(stage);
        if (previous != null && previous.observation == observation && previous.cycle == cycle && previous.attempt == attempt
                && now - previous.time < MIN_SAMPLE_INTERVAL_MS) return;
        last.put(stage, new Previous(observation, cycle, attempt, now));
        String tag = " STAGE=" + stage + " STARTUP=" + startup + " CYCLE=" + cycle + " OBSERVATION=" + observation
                + (attempt > 0 ? " ID=" + attempt : "");
        String ui = " REASON=" + uiBlocker;
        try {
            WORKER.execute(() -> {
                if (!current.getAsBoolean() || SystemClock.elapsedRealtime() - now >= MAX_SAMPLE_AGE_MS) {
                    Diagnostics.record(context, "MEDIA_TARGET_SNAPSHOT_STALE" + tag + ui);
                    return;
                }
                capture(tag, ui, now, current);
            });
        } catch (RejectedExecutionException busy) {
            Diagnostics.record(context, "MEDIA_TARGET_SNAPSHOT_DROPPED" + tag + ui);
        }
    }

    private void capture(String tag, String ui, long requestedAt, BooleanSupplier current) {
        var yanosik = YanosikPresence.observe(context);
        var spotify = readSpotify(yanosik.access());
        if (!current.getAsBoolean() || SystemClock.elapsedRealtime() - requestedAt >= MAX_SAMPLE_AGE_MS) {
            Diagnostics.record(context, "MEDIA_TARGET_SNAPSHOT_STALE" + tag + ui);
            return;
        }
        String fgs = switch (yanosik.state()) {
            case WORK_DETECTED -> "PRESENT";
            case NO_WORK_SIGNAL -> "ABSENT";
            case UNKNOWN -> "UNKNOWN";
        };
        Diagnostics.record(context, "MEDIA_TARGET_SNAPSHOT" + tag + ui
                + " Y_ACCESS=" + yanosik.access() + " Y_LISTENER=" + (yanosik.listenerBound() ? "BOUND" : "UNBOUND")
                + " Y_FGS=" + fgs + " S_ACCESS=" + spotify.access + " S_SESSIONS=" + spotify.sessions
                + " S_SCOPE=" + spotify.scope + " S_PLAYBACK=" + spotify.playback);
        var foreground = RecentForegroundApp.observe(context);
        if (!current.getAsBoolean() || SystemClock.elapsedRealtime() - requestedAt >= MAX_SAMPLE_AGE_MS)
            foreground = RecentForegroundApp.Observation.unknown("UNAVAILABLE", "STALE");
        Diagnostics.record(context, "MEDIA_RECENT_FOREGROUND" + tag + foreground.fields());
    }

    private record Spotify(String access, String sessions, String scope, String playback) {
        static Spotify unavailable(YanosikPresence.Access access) {
            return new Spotify(access.name(), "UNKNOWN", "UNKNOWN", "UNKNOWN");
        }
    }

    private Spotify readSpotify(YanosikPresence.Access access) {
        if (access != YanosikPresence.Access.GRANTED) return Spotify.unavailable(access);
        try {
            var manager = context.getSystemService(MediaSessionManager.class);
            if (manager == null) return Spotify.unavailable(access);
            List<MediaController> controllers = manager.getActiveSessions(new ComponentName(context, YanosikPresence.class));
            if (controllers == null) return Spotify.unavailable(access);
            MediaController match = null;
            for (var controller : controllers) {
                if (controller == null || !SpotifyController.PACKAGE.equals(controller.getPackageName())) continue;
                if (match != null) return new Spotify("GRANTED", "MULTIPLE", "AMBIGUOUS", "UNKNOWN");
                match = controller;
            }
            if (match == null) return new Spotify("GRANTED", "ZERO", "NONE", "NONE");
            String scope = SpotifyController.scopeOf(match.getPlaybackInfo()).name();
            return new Spotify("GRANTED", "ONE", scope, playback(match.getPlaybackState()));
        } catch (SecurityException denied) {
            return Spotify.unavailable(YanosikPresence.Access.DENIED);
        } catch (RuntimeException unavailable) {
            return Spotify.unavailable(access);
        }
    }

    private static String playback(PlaybackState state) {
        if (state == null) return "UNKNOWN";
        return switch (state.getState()) {
            case PlaybackState.STATE_NONE -> "NONE";
            case PlaybackState.STATE_PLAYING -> "PLAYING";
            case PlaybackState.STATE_PAUSED -> "PAUSED";
            case PlaybackState.STATE_STOPPED -> "STOPPED";
            case PlaybackState.STATE_BUFFERING -> "BUFFERING";
            case PlaybackState.STATE_CONNECTING -> "CONNECTING";
            case PlaybackState.STATE_ERROR -> "ERROR";
            case PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
                    PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_NEXT,
                    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "OTHER";
            default -> "OTHER";
        };
    }
}
