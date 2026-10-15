package com.pbuchman.duduhome.ui;

import android.content.Context;
import android.content.SharedPreferences;

/** Presentation preferences only: no trip state, coordinates or automation settings. */
public final class TripOverlayPreferences {
    private final SharedPreferences preferences;
    public TripOverlayPreferences(Context context) {
        preferences = context.getSharedPreferences("trip_overlay_ui", Context.MODE_PRIVATE);
    }
    public boolean visible() { return preferences.getBoolean("visible", true); }
    public boolean setVisible(boolean visible) {
        boolean previous=visible();
        if(commit(preferences.edit().putBoolean("visible", visible)))return true;
        // SharedPreferences can update its memory cache even when a disk commit fails.
        // Restore the prior UI choice and report failure rather than claiming it was saved.
        commit(preferences.edit().putBoolean("visible", previous));
        return false;
    }
    public float horizontal() { return bounded(preferences.getFloat("horizontal", 1f)); }
    public float vertical() { return bounded(preferences.getFloat("vertical", 0f)); }
    public boolean savePosition(float horizontal, float vertical) {
        float previousHorizontal=horizontal(),previousVertical=vertical();
        if(commit(preferences.edit().putFloat("horizontal", bounded(horizontal)).putFloat("vertical", bounded(vertical))))return true;
        commit(preferences.edit().putFloat("horizontal", previousHorizontal).putFloat("vertical", previousVertical));
        return false;
    }
    private static boolean commit(SharedPreferences.Editor editor) {
        // Only explicit UI completion writes: never called for individual drag MOVE events.
        try { return editor.commit(); } catch(RuntimeException failed) { return false; }
    }
    private static float bounded(float value) { return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f; }
}
