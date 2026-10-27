package com.pbuchman.duduhome.automation;

import java.util.Objects;

/** Process-local provenance only. Successful departure provenance, independent of the cancellable media group. */
public final class DepartureContinuation<C> {
    public record Binding<C>(long attempt, HomeEvent origin, long generation, C cycle) { }
    private Binding<C> pending;
    private boolean mediaCancelled;

    public void offered(long attempt, HomeEvent origin, long generation, C cycle) {
        if (origin == HomeEvent.DEPARTURE_STARTED)
            pending = cycle == null ? null : new Binding<>(attempt, origin, generation, cycle);
    }

    public Binding<C> capture(long attempt) {
        return pending != null && pending.attempt() == attempt ? pending : null;
    }

    /** The caller separately validates a live, sent executor attempt before invoking this. */
    public boolean succeeded(Binding<C> binding, long generation, C cycle) {
        if (binding == null || binding != pending) return false;
        pending = null;
        return binding.origin() == HomeEvent.DEPARTURE_STARTED
                && binding.generation() == generation && Objects.equals(binding.cycle(), cycle);
    }

    public void cancelAttempt(long attempt) {
        if (pending != null && pending.attempt() == attempt) pending = null;
    }
    public void cancelMedia() { mediaCancelled = true; }
    /** Called only by the qualified motion hook, after its cancellation baseline. */
    public void motionConfirmed() { mediaCancelled = false; }
    public boolean mediaCancelled() { return mediaCancelled; }
    public void invalidate() { pending = null; }
}
