package com.pbuchman.duduhome.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import com.pbuchman.duduhome.startup.DuduCycle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Reserve before launch, never rearm on process restart, update or a traffic stop. */
public final class JourneySession {
    public enum Target { YANOSIK, SPOTIFY }
    public enum ObservationResult {
        BASELINE_ZERO, BASELINE_COUNTER, REARMED, UNCHANGED, UNAVAILABLE, INVALID_BOOT, STATE_WRITE_FAILED
    }
    private static final Set<SharedPreferences> FAILED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<SharedPreferences> REPORTED = Collections.newSetFromMap(new IdentityHashMap<>());
    private final SharedPreferences state;
    private final int boot;
    public JourneySession(Context context) {
        this(context.getSharedPreferences("journey_session", 0),
                Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
    }
    public JourneySession(SharedPreferences state, int boot) { this.state = state; this.boot = boot; }
    public boolean ensureBoot() {
        synchronized (JourneySession.class) {
            if (FAILED.contains(state) || boot < 0) return false;
            int previous = state.getInt("boot", -1);
            if (previous > boot) return false;
            return previous == boot || commitOrBlock(state.edit().putInt("boot", boot).putBoolean("consumed", false)
                    .putBoolean("spotify_consumed", false).putBoolean("manual_navigation", false).remove("wake_id"));
        }
    }
    public boolean reserve() {
        return reserve(Target.YANOSIK);
    }
    public boolean reserve(Target target) {
        synchronized (JourneySession.class) {
            String key = target == Target.YANOSIK ? "consumed" : "spotify_consumed";
            return ensureBoot() && !state.getBoolean(key, false)
                    && commitOrBlock(state.edit().putBoolean(key, true));
        }
    }
    /** Manual Maps wins, including after a process restart. Rebaseline the next vendor read
     * so an outstanding observation of the current wake cannot immediately undo the choice. */
    public boolean chooseManualNavigation() {
        synchronized (JourneySession.class) {
            return ensureBoot() && commitOrBlock(state.edit().putBoolean("consumed", true)
                    .putBoolean("spotify_consumed", true).putBoolean("manual_navigation", true).remove("wake_id"));
        }
    }
    public boolean manualNavigationChosen() {
        synchronized (JourneySession.class) {
            return FAILED.contains(state) || boot < 0 || (state.getInt("boot", -1) == boot
                    && state.getBoolean("manual_navigation", false));
        }
    }
    /** Read-only presentation eligibility; never establishes a boot or reserves an attempt. */
    public boolean available() {
        return available(Target.YANOSIK);
    }
    public boolean available(Target target) {
        synchronized (JourneySession.class) {
            return !FAILED.contains(state) && boot >= 0 && state.getInt("boot", -1) == boot
                    && !state.getBoolean(target == Target.YANOSIK ? "consumed" : "spotify_consumed", false);
        }
    }
    /** First observation establishes a baseline, never rearms an already consumed boot. */
    public ObservationResult observeAwakeCycle(DuduCycle.Observation observation) {
        synchronized (JourneySession.class) {
            if (!ensureBoot()) return FAILED.contains(state) ? ObservationResult.STATE_WRITE_FAILED : ObservationResult.INVALID_BOOT;
            if (observation == null || observation.kind() == DuduCycle.Kind.UNAVAILABLE) return ObservationResult.UNAVAILABLE;
            if (!state.contains("wake_id")) {
                if (!commitOrBlock(state.edit().putLong("wake_id", observation.counter()))) return ObservationResult.STATE_WRITE_FAILED;
                return observation.kind() == DuduCycle.Kind.ABSENT_COLD_BASELINE
                        ? ObservationResult.BASELINE_ZERO : ObservationResult.BASELINE_COUNTER;
            }
            if (observation.kind() == DuduCycle.Kind.ABSENT_COLD_BASELINE
                    || observation.counter() <= state.getLong("wake_id", -1)) return ObservationResult.UNCHANGED;
            return commitOrBlock(state.edit().putLong("wake_id", observation.counter()).putBoolean("consumed", false)
                    .putBoolean("spotify_consumed", false).putBoolean("manual_navigation", false))
                    ? ObservationResult.REARMED : ObservationResult.STATE_WRITE_FAILED;
        }
    }
    /** Once per failed store/process, even when a different instance made the failed write. */
    public boolean takeStorageFailureReport() {
        synchronized (JourneySession.class) { return FAILED.contains(state) && REPORTED.add(state); }
    }
    private boolean commitOrBlock(SharedPreferences.Editor editor) {
        // Callers hold the class lock. Memory may change even when the disk commit fails.
        try { if (editor.commit()) return true; }
        catch (RuntimeException ignored) { /* Uncertain persistence also blocks this process. */ }
        FAILED.add(state);
        return false;
    }
}
