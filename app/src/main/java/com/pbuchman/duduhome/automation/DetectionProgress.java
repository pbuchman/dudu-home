package com.pbuchman.duduhome.automation;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;

/** Observation only: contains no position, action command or Android dependency. */
public record DetectionProgress(Kind kind, long id, Phase phase, double value,
                                long validUntil, Reason reason, Stage stage) {
    public DetectionProgress(Kind kind, long id, Phase phase, double value, long validUntil, Reason reason) {
        this(kind, id, phase, value, validUntil, reason, Stage.NONE);
    }
    public enum Stage { NONE, APPROACHING_JUNCTION, APPROACHING_GATE }
    public enum Kind { DEPARTURE, RETURN, CLEANING, YANOSIK, SPOTIFY, MOP }
    public enum Phase { CANDIDATE, CONFIRMED, CANCELLED, REQUESTED, WAITING, ACCEPTED, STARTED,
        SKIPPED, SUCCEEDED, ERROR, UNKNOWN, CANCELLING }
    public enum Reason { NONE, CONDITIONS_CHANGED, STOPPED, GPS_UNRELIABLE, RESET, WAKE,
        CONFIGURATION, SERVICE_STOPPED, STALE, NO_NUMBER, COOLDOWN, NO_CONFIG,
        BUSY_OR_MAINTENANCE, UI_BUSY, DAILY_LIMIT_OR_STORAGE, UI_UNAVAILABLE,
        TARGET_UNAVAILABLE, REQUEST_FAILED, TARGET_STATUS_UNKNOWN, PRIORITY, EXPIRED, RESUMING,
        MEDIA_REMOTE, MEDIA_NO_SESSION, MEDIA_TIMEOUT, MEDIA_NO_ACCESS, TARGET_ALREADY_RUNNING,
        USER_CANCELLED, COMMAND_SENT }

    /** A side channel updated inside the same detector evaluation, never an input to it. */
    public static final class Tracker {
        private final EnumMap<Kind, DetectionProgress> current = new EnumMap<>(Kind.class);
        private long sequence;
        public List<DetectionProgress> snapshot() { return List.copyOf(current.values()); }
        public void update(Kind kind, boolean candidate, boolean confirmed, double value,
                           long validUntil, Reason reason) {
            update(kind, candidate, confirmed, value, validUntil, reason, Stage.NONE);
        }
        public void update(Kind kind, boolean candidate, boolean confirmed, double value,
                           long validUntil, Reason reason, Stage stage) {
            DetectionProgress old = current.get(kind);
            if (!candidate && !confirmed) {
                if (old != null && old.phase == Phase.CANDIDATE)
                    current.put(kind, new DetectionProgress(kind, old.id, Phase.CANCELLED, old.value, validUntil, reason, old.stage));
                else current.remove(kind);
                return;
            }
            long id = old != null && (old.phase == Phase.CANDIDATE || old.phase == Phase.CONFIRMED)
                    ? old.id : ++sequence;
            current.put(kind, new DetectionProgress(kind, id, confirmed ? Phase.CONFIRMED : Phase.CANDIDATE,
                    confirmed ? 1 : Math.max(0, Math.min(0.99, value)), validUntil, Reason.NONE, stage));
        }
        public void clear(Reason reason) {
            for (DetectionProgress old : new ArrayList<>(current.values())) {
                if (old.phase == Phase.CANDIDATE || old.phase == Phase.CONFIRMED || old.phase == Phase.CANCELLED)
                    current.put(old.kind, new DetectionProgress(old.kind, old.id, Phase.CANCELLED,
                            old.value, old.validUntil, reason, old.stage));
            }
        }
    }
}
