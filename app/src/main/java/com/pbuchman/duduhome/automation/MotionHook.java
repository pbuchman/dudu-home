package com.pbuchman.duduhome.automation;

import com.pbuchman.duduhome.location.MotionDetector;

/** A blocked request is reconsidered only on another valid moving fix, never on a timer. */
public final class MotionHook {
    private final MotionDetector detector = new MotionDetector();
    private final Runnable action;
    public MotionHook(Runnable action) { this.action = action; }
    public void accept(MotionDetector.Fix fix, boolean uiAvailable) {
        accept(fix, uiAvailable, ignored -> { });
    }
    public void accept(MotionDetector.Fix fix, boolean uiAvailable,
                       java.util.function.Consumer<java.util.List<DetectionProgress>> observer) {
        boolean confirmed = detector.accept(fix);
        observer.accept(detector.progress());
        if (confirmed && uiAvailable) action.run();
    }
    public boolean fresh(long now) { return detector.fresh(now); }
    public java.util.List<DetectionProgress> progress() { return detector.progress(); }
    public MotionDetector.Evidence evidence() { return detector.evidence(); }
    public boolean hasSpatialEvidence() { return detector.hasSpatialEvidence(); }
    public void clear() { detector.clear(); }
    public void observeBaseline(MotionDetector.Fix fix) { detector.observeBaseline(fix); }
    public void cancel() { detector.cancel(); }
    public void requireStationaryBaseline() { detector.requireStationaryBaseline(); }
    public void verifiedCycle(MotionDetector.Source source) { detector.verifiedCycle(source); }
}
