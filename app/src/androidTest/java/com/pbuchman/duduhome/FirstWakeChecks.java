package com.pbuchman.duduhome;

import android.content.SharedPreferences;
import com.pbuchman.duduhome.automation.JourneySession;
import com.pbuchman.duduhome.startup.DuduCycle;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import static com.pbuchman.duduhome.automation.JourneySession.ObservationResult.*;

/** Synthetic stores only: never writes the radio's production session. */
final class FirstWakeChecks {
    static DuduCycle.Observation cold() {
        return DuduCycle.parse("DUDU_CYCLE_BEGIN\n1\n\n\n\n\n\n\n1\nDUDU_CYCLE_END\n");
    }
    static DuduCycle.Observation cycle(long n) {
        return new DuduCycle.Observation(DuduCycle.Kind.AWAKE_COUNTER, n);
    }
    static void run() {
        Store store = new Store();
        JourneySession first = new JourneySession(store.prefs, 42);
        check(first.ensureBoot(), "new boot");
        check(first.observeAwakeCycle(cold()) == BASELINE_ZERO, "cold baseline recorded");
        check(first.reserve(), "cold attempt");
        JourneySession wake = new JourneySession(store.prefs, 42);
        check(wake.observeAwakeCycle(cycle(1)) == REARMED, "first real wake rearms");
        check(wake.reserve() && !wake.reserve(), "one first-wake attempt");
        check(wake.observeAwakeCycle(cycle(1)) == UNCHANGED && !wake.available(), "duplicate");
        check(wake.observeAwakeCycle(cycle(0)) == UNCHANGED && !wake.available(), "rollback");
        check(wake.observeAwakeCycle(cold()) == UNCHANGED && store.values.get("wake_id").equals(1L), "absent cannot replace positive");
        check(wake.observeAwakeCycle(cycle(2)) == REARMED && wake.reserve(), "second wake");
        check(!new JourneySession(store.prefs, 42).reserve(), "process recreation/update");
        check(!new JourneySession(store.prefs, 41).reserve(), "older boot");
        check(!new JourneySession(store.prefs, -1).reserve(), "unknown boot");
        check(new JourneySession(store.prefs, 43).reserve(), "new boot once");
        check(!new JourneySession(store.prefs, 43).reserve(), "same new boot twice");

        Store legacy = new Store();
        JourneySession old = new JourneySession(legacy.prefs, 7);
        check(old.reserve(), "old consumed state");
        check(old.observeAwakeCycle(DuduCycle.unavailable()) == UNAVAILABLE && !legacy.values.containsKey("wake_id"), "unknown is not zero");
        check(old.observeAwakeCycle(cycle(1)) == BASELINE_COUNTER && !old.available(), "migration does not rearm");
        check(old.observeAwakeCycle(cycle(2)) == REARMED, "migration next increase");
        Store delayed = new Store();
        JourneySession later = new JourneySession(delayed.prefs, 8);
        check(later.reserve(), "attempt before completed boot");
        check(later.observeAwakeCycle(cold()) == BASELINE_ZERO && !later.available(), "late zero preserves consumed");
        check(later.observeAwakeCycle(cycle(1)) == REARMED, "late zero supports wake");
        Store numeric = new Store();
        JourneySession zero = new JourneySession(numeric.prefs, 9);
        check(zero.reserve() && zero.observeAwakeCycle(cycle(0)) == BASELINE_COUNTER, "numeric zero");
        check(zero.observeAwakeCycle(cycle(1)) == REARMED, "numeric zero equivalent");

        for (String phase : new String[]{"boot", "baseline", "reserve", "rearm"}) {
            Store failed = new Store();
            JourneySession session = new JourneySession(failed.prefs, 20);
            if (!phase.equals("boot")) check(session.ensureBoot(), "setup boot");
            if (phase.equals("rearm")) {
                check(session.observeAwakeCycle(cold()) == BASELINE_ZERO && session.reserve(), "setup consumed baseline");
            }
            failed.fail = true;
            switch (phase) {
                case "boot" -> check(!session.ensureBoot(), "boot persistence fails");
                case "baseline" -> check(session.observeAwakeCycle(cold()) == STATE_WRITE_FAILED, "baseline persistence fails");
                case "reserve" -> check(!session.reserve(), "reservation persistence fails");
                case "rearm" -> check(session.observeAwakeCycle(cycle(1)) == STATE_WRITE_FAILED, "rearm persistence fails");
            }
            int writes = failed.writes;
            failed.fail = false;
            JourneySession another = new JourneySession(failed.prefs, 20);
            check(!another.ensureBoot() && !another.available() && !another.reserve(), "same store stays blocked");
            check(another.observeAwakeCycle(cycle(2)) == STATE_WRITE_FAILED, "no recovery from cached memory");
            check(failed.writes == writes, "no implicit retry");
            check(another.takeStorageFailureReport() && !session.takeStorageFailureReport(), "one failure report across instances");
            check(new JourneySession(new Store().prefs, 20).reserve(), "other store unaffected");
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    /** commit applies cached edits BEFORE reporting failure, just as a failed disk write can. */
    private static final class Store {
        final Map<String, Object> values = new HashMap<>();
        boolean fail;
        int writes;
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getInt", "getLong", "getBoolean", "getString" -> values.getOrDefault(args[0], args[1]);
                        case "contains" -> values.containsKey(args[0]);
                        case "getAll" -> new HashMap<>(values);
                        case "edit" -> editor();
                        default -> throw new AssertionError("Unexpected preference operation " + method.getName());
                    };
                });
        private SharedPreferences.Editor editor() {
            Map<String, Object> edits = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "putInt", "putLong", "putBoolean" -> edits.put((String) args[0], args[1]);
                            case "remove" -> edits.put((String) args[0], null);
                            case "commit" -> {
                                writes++;
                                edits.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                                return !fail;
                            }
                            default -> throw new AssertionError("Unexpected editor operation " + method.getName());
                        }
                        return proxy;
                    });
        }
    }
}
