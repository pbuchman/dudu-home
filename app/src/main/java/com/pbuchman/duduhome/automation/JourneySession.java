package com.pbuchman.duduhome.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/** Reserve before launch, never rearm on process restart, update or a traffic stop. */
public final class JourneySession {
    private final SharedPreferences state;
    private final int boot;
    public JourneySession(Context context) {
        this(context.getSharedPreferences("journey_session", 0),
                Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
    }
    public JourneySession(SharedPreferences state, int boot) { this.state = state; this.boot = boot; }
    public boolean ensureBoot() {
        if (boot < 0) return false;
        synchronized (JourneySession.class) {
            int previous = state.getInt("boot", -1);
            if (previous > boot) return false;
            return previous == boot || state.edit().putInt("boot", boot).putBoolean("consumed", false)
                    .remove("wake_id").commit();
        }
    }
    public boolean reserve() {
        synchronized (JourneySession.class) {
            return ensureBoot() && !state.getBoolean("consumed", false)
                    && state.edit().putBoolean("consumed", true).commit();
        }
    }
    /** Integration seam only: caller must supply a verified vendor cycle identifier.
     * No production caller until the DUDU wake contract is observed on the actual radio.
     * Ordinary HomeWakeActivity launches are NOT evidence of a new ignition cycle. */
    public boolean verifiedWake(long cycle) {
        synchronized (JourneySession.class) {
            if (!ensureBoot() || cycle < 0 || cycle <= state.getLong("wake_id", -1)) return false;
            return state.edit().putLong("wake_id", cycle).putBoolean("consumed", false).commit();
        }
    }
}
