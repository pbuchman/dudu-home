package com.pbuchman.duduhome.automation;

import com.pbuchman.duduhome.location.MotionDetector;

/** A blocked request is reconsidered only on another valid moving fix, never on a timer. */
public final class MotionHook {
    private final MotionDetector detector = new MotionDetector();
    private final Runnable action;
    public MotionHook(Runnable action) { this.action = action; }
    public void accept(MotionDetector.Fix fix, boolean uiAvailable) {
        if (detector.accept(fix) && uiAvailable) action.run();
    }
    public boolean fresh(long now) { return detector.fresh(now); }
    public void clear() { detector.clear(); }
}
