package com.pbuchman.duduhome.automation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** In-memory scheduling only. Called on the main thread by AutomationRuntime. */
public final class AutomationCoordinator {
    public enum Type {
        GATE(0, 5000), CLEANING(1, 120000), YANOSIK(2, 120000), SPOTIFY(3, 120000);
        public final int priority;
        public final long lifetime;
        Type(int priority, long lifetime) { this.priority = priority; this.lifetime = lifetime; }
    }
    public record Job(long id, Type type, HomeEvent event, long generation, long expiresAt, long day) { }
    private final LinkedHashMap<Long, Job> queued = new LinkedHashMap<>();
    private volatile long generation;
    private long mediaNotBefore;
    public long generation() { return generation; }

    public void enqueue(long id, Type type, HomeEvent event, long now, long day) {
        queued.putIfAbsent(id, new Job(id, type, event, generation, now + type.lifetime, day));
    }
    public Job cancel(long id) { return queued.remove(id); }
    public boolean contains(Type type) { return queued.values().stream().anyMatch(j -> j.type == type); }
    public List<Job> expire(long now, long day) {
        List<Job> expired = new ArrayList<>();
        queued.values().removeIf(j -> {
            boolean invalid = now >= j.expiresAt || (j.type == Type.CLEANING && j.day != day);
            if (invalid) expired.add(j);
            return invalid;
        });
        return expired;
    }
    public Job next(long now, boolean homeReady, boolean mediaReady) {
        Job j = queued.values().stream().min(Comparator.comparingInt((Job v) -> v.type.priority)
                .thenComparingLong(Job::id)).orElse(null);
        if (j == null || now >= j.expiresAt) return null;
        boolean media = j.type == Type.YANOSIK || j.type == Type.SPOTIFY;
        if (media ? !mediaReady || now < mediaNotBefore : !homeReady) return null;
        queued.remove(j.id);
        return j;
    }
    public void yanosikLaunched(long now) { mediaNotBefore = now + 10000; }
    public long nextWake(long now) {
        long next = Long.MAX_VALUE;
        for (Job j : queued.values()) next = Math.min(next, j.expiresAt);
        if (!queued.isEmpty() && mediaNotBefore > now) next = Math.min(next, mediaNotBefore);
        return next;
    }
    public List<Job> reset() {
        List<Job> removed = List.copyOf(queued.values());
        queued.clear(); mediaNotBefore = 0; generation++;
        return removed;
    }
    public List<Job> discardMedia() {
        List<Job> removed = new ArrayList<>();
        queued.values().removeIf(j -> {
            if (j.type == Type.YANOSIK || j.type == Type.SPOTIFY) { removed.add(j); return true; }
            return false;
        });
        mediaNotBefore = 0;
        return removed;
    }
    public boolean current(Job job) { return job.generation == generation; }
    public boolean hasHomeWaiting() {
        return queued.values().stream().anyMatch(j -> j.type == Type.GATE || j.type == Type.CLEANING);
    }
}
