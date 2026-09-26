package com.pbuchman.duduhome.automation;

import com.pbuchman.duduhome.location.HomeDetector.GateArea;

/** Process-local media barrier. Area evidence is not permission to make a call. */
public final class GatePrecondition {
    private GateArea area = GateArea.UNKNOWN, knownArea = GateArea.UNKNOWN;
    private long generation;
    private boolean callSucceeded;

    public void observe(GateArea next) {
        area = next;
        // A GPS gap blocks media, but does not forget a call actually completed here.
        if (next != GateArea.UNKNOWN && next != knownArea) {
            knownArea = next;
            callSucceeded = false;
            generation++;
        }
    }

    public boolean allowsMedia() {
        return area == GateArea.NONE || (area != GateArea.UNKNOWN && callSucceeded);
    }

    /** Capture when the executor accepts a call, not when a result screen is observed. */
    public Runnable successCallback() {
        long captured = generation;
        boolean relevant = knownArea == GateArea.DEPARTURE || knownArea == GateArea.RETURN;
        return () -> {
            if (relevant && captured == generation) callSucceeded = true;
        };
    }

    public void reset() {
        area = knownArea = GateArea.UNKNOWN;
        callSucceeded = false;
        generation++;
    }
}
