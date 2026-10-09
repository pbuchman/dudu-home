package com.pbuchman.duduhome.automation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** Pure presentation state. No callback here can dispatch an action or reserve a quota. */
public final class ProgressModel {
    public record State(long id, long generation, Kind kind, Phase phase, double value,
                        Reason reason, long validUntil, long expiresAt, long evidenceId, Stage stage) {
        public State(long id, long generation, Kind kind, Phase phase, double value,
                     Reason reason, long validUntil, long expiresAt, long evidenceId) {
            this(id, generation, kind, phase, value, reason, validUntil, expiresAt, evidenceId, Stage.NONE);
        }
    }
    private final EnumMap<Kind, State> candidates = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Long> consumed = new EnumMap<>(Kind.class);
    private final LinkedHashMap<Long, State> attempts = new LinkedHashMap<>();
    private long generation, sequence, selected;
    public long reset(Reason reason, long now) {
        for (State s : new ArrayList<>(candidates.values()))
            if (!terminal(s.phase)) candidates.put(s.kind, cancel(s, reason, now));
        consumed.clear();
        return ++generation;
    }
    public void offer(long epoch, Set<Kind> scope, List<DetectionProgress> updates, long now) {
        if (epoch != generation) return;
        for (Kind kind : scope) {
            DetectionProgress p = updates.stream().filter(v -> v.kind() == kind).findFirst().orElse(null);
            State old = candidates.get(kind);
            if (p == null) {
                if (old != null && !terminal(old.phase)) candidates.put(kind, cancel(old, Reason.CONDITIONS_CHANGED, now));
                continue;
            }
            if (consumed.getOrDefault(kind, -1L) == p.id()) continue;
            if (old != null && old.generation == epoch
                    && (p.id() < old.evidenceId || (p.id() == old.evidenceId && p.validUntil() < old.validUntil))) continue;
            if (old != null && old.generation == epoch && old.evidenceId == p.id()
                    && old.phase == p.phase() && old.phase == Phase.CANCELLED) continue;
            long id = old != null && old.generation == epoch && old.evidenceId == p.id() ? old.id : ++sequence;
            candidates.put(kind, new State(id, epoch, kind, p.phase(), p.value(), p.reason(),
                    p.validUntil(), p.phase() == Phase.CANCELLED ? now + 2000 : Long.MAX_VALUE, p.id(), p.stage()));
        }
    }
    public long request(Kind kind, long now) { return request(kind, now, true); }
    public long requestManual(Kind kind, long now) { return request(kind, now, false); }
    private long request(Kind kind, long now, boolean useEvidence) {
        State candidate = useEvidence ? candidates.remove(kind) : null;
        if (candidate != null && terminal(candidate.phase)) candidate = null;
        if (candidate != null) consumed.put(kind, candidate.evidenceId);
        long id = candidate == null ? ++sequence : candidate.id;
        attempts.put(id, new State(id, generation, kind, Phase.REQUESTED, 1, Reason.NONE,
                now + 5000, Long.MAX_VALUE, candidate == null ? -1 : candidate.evidenceId));
        trim();
        return id;
    }
    public void update(long id, Phase phase, Reason reason, long now) {
        State s = attempts.get(id);
        if (s == null || terminal(s.phase)) return;
        if (s.phase == phase && s.reason == reason) return;
        if (!terminal(phase) && !canAdvance(s.phase, phase)) return;
        attempts.put(id, new State(id, s.generation, s.kind, phase, 1, reason,
                s.validUntil, terminal(phase) ? now + 2000 : Long.MAX_VALUE, s.evidenceId, s.stage));
    }
    private static boolean canAdvance(Phase before, Phase after) {
        if (after == Phase.CANCELLING) return true;
        if (before == Phase.CANCELLING) return false;
        return switch (after) {
            case WAITING -> before == Phase.REQUESTED;
            case ACCEPTED -> before == Phase.REQUESTED || before == Phase.WAITING;
            case STARTED -> before == Phase.REQUESTED || before == Phase.WAITING || before == Phase.ACCEPTED || before == Phase.STARTED;
            default -> before == after;
        };
    }
    public State state(long id) { return snapshot().stream().filter(s -> s.id == id).findFirst().orElse(null); }
    public void cancelByUser(long id, Reason reason, boolean pending, long now) {
        State s = state(id);
        if (s == null || terminal(s.phase)) return;
        State updated = new State(s.id, s.generation, s.kind, pending ? Phase.CANCELLING : Phase.CANCELLED,
                s.value, reason, s.validUntil, pending ? Long.MAX_VALUE : now + 2000, s.evidenceId, s.stage);
        if (attempts.containsKey(id)) attempts.put(id, updated);
        else { candidates.put(s.kind, updated); consumed.put(s.kind, s.evidenceId); }
    }
    public State attempt(long id) { return attempts.get(id); }
    public List<State> snapshot() {
        List<State> out = new ArrayList<>(candidates.values()); out.addAll(attempts.values());
        return List.copyOf(out);
    }
    public State visible(long now) {
        State best = null;
        for (State s : new ArrayList<>(candidates.values())) {
            if (!terminal(s.phase) && now > s.validUntil) {
                // Presentation expiry is not a detector reset. A fresh snapshot may resume it.
                s = cancel(s, Reason.STALE, s.validUntil);
                candidates.put(s.kind, s);
            }
        }
        for (State s : snapshot()) {
            if (s.expiresAt <= now) continue;
            if (best == null || priority(s.kind) < priority(best.kind)
                    || (priority(s.kind) == priority(best.kind) && terminal(best.phase) && !terminal(s.phase))
                    || (priority(s.kind) == priority(best.kind) && terminal(s.phase) == terminal(best.phase)
                    && s.id == selected)) best = s;
        }
        selected = best == null ? 0 : best.id;
        return best;
    }
    private static State cancel(State s, Reason reason, long now) {
        return new State(s.id, s.generation, s.kind, Phase.CANCELLED, s.value, reason,
                s.validUntil, now + 2000, s.evidenceId, s.stage);
    }
    public static boolean terminal(Phase p) {
        return p == Phase.CANCELLED || p == Phase.SKIPPED || p == Phase.SUCCEEDED
                || p == Phase.ERROR || p == Phase.UNKNOWN;
    }
    private static int priority(Kind kind) {
        return switch (kind) { case DEPARTURE, RETURN -> 0; case CLEANING -> 1; default -> 2; };
    }
    private void trim() {
        if (attempts.size() <= 16) return;
        var iterator = attempts.entrySet().iterator();
        while (attempts.size() > 16 && iterator.hasNext()) {
            if (terminal(iterator.next().getValue().phase)) iterator.remove();
        }
    }
}
