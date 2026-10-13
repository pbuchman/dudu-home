package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.startup.DuduCycle;

/** Independent durable gate opportunity. Media/navigation rebaselining cannot touch it. */
public final class DepartureCycle {
    public enum Baseline { UNKNOWN, PARKING, OUTSIDE }
    public enum Result { BASELINE, UNCHANGED, REARMED, UNAVAILABLE, BLOCKED }
    public record State(int boot, long counter, long generation, Baseline baseline, boolean consumed, boolean away) { }
    public interface Store { State read(); boolean write(State state); default Object identity() { return this; } }
    private static final java.util.Set<Object> FAILED = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Store store;
    private State state;
    private boolean failed;
    public DepartureCycle(Store store) { this.store = store; this.state = store.read(); synchronized (FAILED) { failed = FAILED.contains(store.identity()); } }
    public static DepartureCycle memory(int flags) {
        return new DepartureCycle(new Store() {
            private State value = new State(0, -1, 0, Baseline.UNKNOWN, (flags & 1) != 0, (flags & 16) != 0);
            public State read() { return value; }
            public boolean write(State next) { value = next; return true; }
        });
    }
    /** Migration establishes today's boot conservatively; only a later boot rearms. */
    public synchronized boolean ensureBoot(int boot, int legacyFlags) {
        if (failure()) return false;
        if (boot < 0 || state.boot() > boot) { block(); return false; }
        if (state.boot() == boot) return true;
        boolean migrated = state.boot() < 0;
        return save(new State(boot, -1, state.generation() + 1, Baseline.UNKNOWN,
                migrated && (legacyFlags & 1) != 0, migrated && (legacyFlags & 16) != 0));
    }
    public synchronized Result observe(DuduCycle.Observation observation) {
        if (failure() || state.boot() < 0) return Result.BLOCKED;
        if (observation == null || observation.kind() == DuduCycle.Kind.UNAVAILABLE) return Result.UNAVAILABLE;
        if (state.counter() < 0) return save(new State(state.boot(), observation.counter(), state.generation(),
                state.baseline(), state.consumed(), state.away())) ? Result.BASELINE : Result.BLOCKED;
        if (observation.kind() == DuduCycle.Kind.ABSENT_COLD_BASELINE || observation.counter() <= state.counter())
            return Result.UNCHANGED;
        return save(new State(state.boot(), observation.counter(), state.generation() + 1,
                Baseline.UNKNOWN, false, false)) ? Result.REARMED : Result.BLOCKED;
    }
    /** Only a qualified fix decides whether startup began at home. Persist across restarts. */
    public synchronized boolean firstFix(boolean parking) {
        if (failure() || state.boot() < 0) return false;
        return state.baseline() != Baseline.UNKNOWN || save(new State(state.boot(), state.counter(), state.generation(),
                parking ? Baseline.PARKING : Baseline.OUTSIDE, state.consumed(), state.away()));
    }
    public synchronized boolean markAway() {
        if (failure() || state.boot() < 0) return false;
        return state.away() || save(new State(state.boot(), state.counter(), state.generation(),
                state.baseline(), state.consumed(), true));
    }
    public synchronized boolean rearmAfterReturn() {
        if (failure() || state.boot() < 0) return false;
        return save(new State(state.boot(), state.counter(), state.generation() + 1, Baseline.PARKING, false, false));
    }
    /** Called at send admission (or conservative non-user finalization), never at enqueue. */
    public synchronized boolean consume(long generation) {
        return available() && generation == state.generation()
                && save(new State(state.boot(), state.counter(), state.generation(), state.baseline(), true, false));
    }
    public synchronized boolean available() {
        return !failure() && state.boot() >= 0 && !state.consumed() && state.baseline() == Baseline.PARKING;
    }
    public synchronized State state() { return state; }
    public synchronized boolean blocked() { return failure() || state.boot() < 0; }
    private boolean failure() { synchronized (FAILED) { failed |= FAILED.contains(store.identity()); return failed; } }
    private void block() { failed = true; synchronized (FAILED) { FAILED.add(store.identity()); } }
    private boolean save(State next) {
        try { if (store.write(next)) { state = next; return true; } }
        catch (RuntimeException ignored) { /* Cached preference mutation is not durable proof. */ }
        block(); return false;
    }
}
