package com.pbuchman.duduhome.location;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Category-only timings. The service owns the current window; queued writes retain theirs. */
final class GpsStartupTrace {
    private final long startup;
    private final Consumer<String> record;
    private Observation current;

    GpsStartupTrace(long startup, Consumer<String> record) {
        this.startup = startup;
        this.record = record;
        current = new Observation(0, startup, -1);
        current.emit("STARTED", startup, " REASON=SERVICE_START");
    }

    Observation current() { return current; }

    void verifiedWake(long now, long counter, boolean registered) {
        if (counter <= current.cycle) return;
        current = new Observation(current.id + 1, now, counter);
        current.emit("STARTED", now, " REASON=VERIFIED_WAKE");
        if (registered) {
            current.registrationActive = true;
            current.emit("SUBSCRIBED", now, " STATE=ALREADY_ACTIVE");
        }
    }

    final class Observation {
        private static final int MAX_REJECTIONS = 8;
        private final long id, started;
        private final LongConsumer archiveObserver = this::archiveCommitted;
        private volatile long cycle;
        private volatile boolean archiveCommitted;
        private String lastCycleState, lastRejection;
        private long lastReadCounter;
        private int rejections;
        private Boolean registrationActive;
        private boolean callback, usable, confirmed;

        private Observation(long id, long started, long cycle) {
            this.id = id;
            this.started = started;
            this.cycle = cycle;
        }

        long id() { return id; }

        void cycleRead(long now, String state, long observedCounter, long trustedCounter) {
            cycle = Math.max(cycle, trustedCounter);
            if (state.equals(lastCycleState) && lastReadCounter == observedCounter) return;
            lastCycleState = state;
            lastReadCounter = observedCounter;
            emit("CYCLE_READ", now, " STATE=" + state + " CYCLE=" + observedCounter, -1);
        }

        void subscription(long now, boolean active) {
            if (registrationActive != null && registrationActive == active) return;
            registrationActive = active;
            if (active) emit("SUBSCRIBED", now, " STATE=ACTIVE");
            else emit("SUBSCRIBE_DENIED", now, "");
        }

        void callback(long now, boolean hasFix) {
            if (!callback) {
                callback = true;
                emit("FIRST_CALLBACK", now, hasFix ? " STATE=RECEIVED" : " STATE=EMPTY");
            }
        }

        void watchdog(long now) {
            if (!callback) emit("NO_CALLBACK", now, "");
            emit("RESUBSCRIBE", now, "");
            registrationActive = null;
        }

        void motionRejected(long now, String reason) {
            if (rejections >= MAX_REJECTIONS || reason.equals(lastRejection)) return;
            lastRejection = reason;
            rejections++;
            emit("MOTION_FIX_REJECTED", now, " REASON=" + reason);
        }

        void motionFix(long now, MotionDetector.Evidence evidence) {
            if (evidence.state() == MotionDetector.State.REJECTED) {
                motionRejected(now, evidence.reason().name());
                return;
            }
            lastRejection = null;
            if (!usable) {
                usable = true;
                emit("FIRST_USABLE_MOTION_FIX", now, " STATE=" + evidence.state());
            }
            if (!confirmed && evidence.state() == MotionDetector.State.CONFIRMED) {
                confirmed = true;
                emit("MOTION_CONFIRMED", now, "");
            }
        }

        LongConsumer archiveObserver() { return archiveCommitted ? null : archiveObserver; }

        private synchronized void archiveCommitted(long now) {
            if (archiveCommitted) return;
            archiveCommitted = true;
            emit("FIRST_ARCHIVE_COMMIT", now, "");
        }

        private void emit(String stage, long now, String detail) {
            emit(stage, now, detail, cycle);
        }

        private void emit(String stage, long now, String detail, long eventCycle) {
            record.accept("GPS_STARTUP STARTUP=" + startup + " OBSERVATION=" + id
                    + " STAGE=" + stage + " ELAPSED_MS=" + (now - started) + detail
                    + (eventCycle < 0 ? "" : " CYCLE=" + eventCycle));
        }
    }
}
