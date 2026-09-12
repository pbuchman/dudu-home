package com.pbuchman.duduhome.automation;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;

/** Observation only: contains no position, action command or Android dependency. */
public record DetectionProgress(Kind kind, long id, Phase phase, double value,
                                long validUntil, Reason reason) {
    public enum Kind { DEPARTURE, RETURN, CLEANING, YANOSIK, MOP }
    public enum Phase { CANDIDATE, CONFIRMED, CANCELLED, REQUESTED, ACCEPTED, STARTED,
        SKIPPED, SUCCEEDED, ERROR, UNKNOWN }
    public enum Reason { NONE, CONDITIONS_CHANGED, STOPPED, GPS_UNRELIABLE, RESET, WAKE,
        CONFIGURATION, SERVICE_STOPPED, STALE, NO_NUMBER, COOLDOWN, NO_CONFIG,
        BUSY_OR_MAINTENANCE, UI_BUSY, DAILY_LIMIT_OR_STORAGE, UI_UNAVAILABLE,
        TARGET_UNAVAILABLE, REQUEST_FAILED }

    /** A side channel updated inside the same detector evaluation, never an input to it. */
    public static final class Tracker {
        private final EnumMap<Kind, DetectionProgress> current = new EnumMap<>(Kind.class);
        private long sequence;
        public List<DetectionProgress> snapshot() { return List.copyOf(current.values()); }
        public void update(Kind kind, boolean candidate, boolean confirmed, double value,
                           long validUntil, Reason reason) {
            DetectionProgress old = current.get(kind);
            if (!candidate && !confirmed) {
                if (old != null && old.phase == Phase.CANDIDATE)
                    current.put(kind, new DetectionProgress(kind, old.id, Phase.CANCELLED, old.value, validUntil, reason));
                else current.remove(kind);
                return;
            }
            long id = old != null && (old.phase == Phase.CANDIDATE || old.phase == Phase.CONFIRMED)
                    ? old.id : ++sequence;
            current.put(kind, new DetectionProgress(kind, id, confirmed ? Phase.CONFIRMED : Phase.CANDIDATE,
                    confirmed ? 1 : Math.max(0, Math.min(0.99, value)), validUntil, Reason.NONE));
        }
        public void clear(Reason reason) {
            for (DetectionProgress old : new ArrayList<>(current.values())) {
                if (old.phase == Phase.CANDIDATE || old.phase == Phase.CONFIRMED || old.phase == Phase.CANCELLED)
                    current.put(old.kind, new DetectionProgress(old.kind, old.id, Phase.CANCELLED,
                            old.value, old.validUntil, reason));
            }
        }
    }
}
