package com.pbuchman.duduhome.gate;



import android.content.Context;
import android.content.SharedPreferences;

public final class GateNumberStore {
    public enum DialReservation {
        RESERVED,
        COOLDOWN,
        STORAGE_ERROR
    }

    private static final String PREFERENCES_NAME = "gate_settings";
    private static final String KEY_GATE_NUMBER = "gate_number";
    private static final String KEY_LAST_DIAL_STARTED_AT = "last_dial_started_at";
    private static final String KEY_CONFIGURED_AT = "configured_at";
    private static final int MIN_DIGITS = 3;
    private static final int MAX_DIGITS = 15;

    private final SharedPreferences preferences;

    public GateNumberStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public String read() {
        String stored = preferences.getString(KEY_GATE_NUMBER, null);
        String normalized = normalize(stored);
        if (stored != null && normalized == null) {
            preferences.edit().remove(KEY_GATE_NUMBER).apply();
        }
        return normalized;
    }

    public synchronized boolean save(String number) {
        String normalized = normalize(number);
        return normalized != null
                && preferences.edit().putString(KEY_GATE_NUMBER, normalized)
                .putLong(KEY_CONFIGURED_AT, System.currentTimeMillis()).commit();
    }

    public synchronized long cooldownRemainingMillis() {
        return cooldownRemainingMillis(System.currentTimeMillis());
    }

    public synchronized DialReservation reserveDial() {
        long now = System.currentTimeMillis();
        if (cooldownRemainingMillis(now) > 0L) {
            return DialReservation.COOLDOWN;
        }
        return preferences.edit().putLong(KEY_LAST_DIAL_STARTED_AT, now).commit()
                ? DialReservation.RESERVED
                : DialReservation.STORAGE_ERROR;
    }

    private long cooldownRemainingMillis(long now) {
        return Math.max(remainingFor(KEY_LAST_DIAL_STARTED_AT, now), remainingFor(KEY_CONFIGURED_AT, now));
    }

    private long remainingFor(String key, long now) {
        long lastDialStartedAt = preferences.getLong(key, 0L);
        if (lastDialStartedAt <= 0L) {
            return 0L;
        }
        if (lastDialStartedAt > now) {
            preferences.edit().putLong(key, now).commit();
            return GateConfig.REATTEMPT_COOLDOWN_MS;
        }
        return Math.max(
                0L,
                GateConfig.REATTEMPT_COOLDOWN_MS - (now - lastDialStartedAt));
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }

        String value = raw.trim();
        StringBuilder normalized = new StringBuilder(value.length());
        int digits = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') {
                normalized.append(character);
                digits++;
            } else if (character == '+' && normalized.length() == 0) {
                normalized.append(character);
            } else if (Character.isWhitespace(character)
                    || character == '-'
                    || character == '('
                    || character == ')') {
                // Common visual separators are removed before storing and dialing.
            } else {
                return null;
            }
        }

        if (digits < MIN_DIGITS || digits > MAX_DIGITS) {
            return null;
        }
        return normalized.toString();
    }
}
