package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import com.pbuchman.duduhome.automation.JourneySession;
import com.pbuchman.duduhome.location.DepartureCycle;
import com.pbuchman.duduhome.location.HomeDetector;
import com.pbuchman.duduhome.location.HomeMonitorService;
import com.pbuchman.duduhome.startup.DuduCycle;

/** Isolated emulator callbacks/preferences, no subscribed GPS, Binder or external action. */
public final class DepartureCycleChecks {
    public static void run(Instrumentation instrumentation) {
        instrumentation.runOnMainSync(() -> {
            var context = instrumentation.getTargetContext();
            var fixture = context.getSharedPreferences("departure_cycle_fixture",0);
            var mediaPrefs = context.getSharedPreferences("journey_session",0);
            check(fixture.edit().clear().commit() && mediaPrefs.edit().clear().commit(),"fixtures cleared");
            HomeMonitorService service = new HomeMonitorService();
            try {
                var attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",android.content.Context.class);
                attach.setAccessible(true);attach.invoke(service,context);
                DepartureCycle cycle = new DepartureCycle(store(fixture));
                check(cycle.ensureBoot(42,29),"legacy consumed/away migration");
                check(cycle.observe(new DuduCycle.Observation(DuduCycle.Kind.AWAKE_COUNTER,5))==DepartureCycle.Result.BASELINE,"conservative initial baseline");
                var geometry=new HomeDetector.Geometry(new HomeDetector.Point(0,0),new HomeDetector.Point(20,0),
                        new HomeDetector.Point(100,0),new HomeDetector.Point(300,0));
                HomeDetector detector = new HomeDetector(geometry,29,cycle);
                set(service,"departureCycle",cycle);set(service,"detector",detector);
                JourneySession media=new JourneySession(context);
                check(media.ensureBoot() && media.observeAwakeCycle(FirstWakeChecks.cycle(5))==JourneySession.ObservationResult.BASELINE_COUNTER,"media baseline");
                check(media.chooseManualNavigation(),"manual Maps rebaselines media only");
                // Feed four seconds of good moving fixes before the delayed initial read.
                // The barrier must retain the first outside category without emitting.
                var points = new org.json.JSONObject();
                for (var entry : java.util.Map.of("parking",0,"gate",20,"approach",100,"junction",300).entrySet()) {
                    points.put(entry.getKey(),new org.json.JSONObject().put("lat",0).put("lon",entry.getValue()/111320.0));
                }
                var constructor=com.pbuchman.duduhome.location.HomeConfiguration.class.getDeclaredConstructor(org.json.JSONObject.class,String.class);
                constructor.setAccessible(true);
                set(service,"config",constructor.newInstance(new org.json.JSONObject().put("schema_version",1)
                        .put("points",points).put("automation_enabled",true),"synthetic"));
                var accept=HomeMonitorService.class.getDeclaredMethod("acceptHome",android.location.Location.class,long.class);
                accept.setAccessible(true);
                for(int n=0;n<4;n++) {
                    var location=new android.location.Location("synthetic");
                    location.setLatitude(0);location.setLongitude((120+n)/111320.0);
                    location.setAccuracy(15);location.setSpeed(1);
                    set(service,"lastFix",n*1000L);
                    check((boolean)accept.invoke(service,location,n*1000L),"barrier callback accepted without dispatch");
                }
                check(Boolean.FALSE.equals(get(service,"initialParking")) && detector.flags()==29,
                        "moving before initial cycle cannot emit or consume and remembers outside");
                var callback=HomeMonitorService.class.getDeclaredMethod("onCycleRead",DuduCycle.Observation.class);
                callback.setAccessible(true);callback.invoke(service,FirstWakeChecks.cycle(6));
                check((boolean)get(service,"cycleReady"),"delayed first read finishes barrier");
                check(cycle.state().baseline()==DepartureCycle.Baseline.OUTSIDE && !cycle.available(),"first qualified outside fix retained through delayed wake");
                check(!cycle.state().consumed() && media.manualNavigationChosen(),"gate wake independent of manual media baseline");
                check((detector.committedFlags()&14)==12,"unrelated committed cleaning/exit flags preserved");
                callback.invoke(service,FirstWakeChecks.cycle(6));
                check(!cycle.available(),"same read/onStart cannot rearm outside-start cycle");
                check(cycle.rearmAfterReturn(),"qualified-return seam");
                long generation=cycle.state().generation();
                check(cycle.consume(generation),"durable consumption");
                var recreated=new DepartureCycle(store(fixture));
                check(recreated.ensureBoot(42,0) && recreated.state().consumed() && !recreated.state().away(),"restart retains consumed without historical away");
                set(service,"destroyed",true);
                callback.invoke(service,FirstWakeChecks.cycle(7));
                check(cycle.state().generation()==generation && cycle.state().consumed(),"late destroyed-service read ignored");
                HomeMonitorService unknown=new HomeMonitorService();attach.invoke(unknown,context);
                set(unknown,"departureCycle",recreated);
                callback.invoke(unknown,DuduCycle.unavailable());
                check((boolean)get(unknown,"cycleReady") && recreated.state().consumed(),"unavailable read finishes barrier conservatively");
                ((java.util.concurrent.ExecutorService)get(unknown,"cycleReader")).shutdownNow();
            } catch(ReflectiveOperationException | org.json.JSONException failed) { throw new AssertionError(failed); }
            finally {
                com.pbuchman.duduhome.diagnostics.JournalChecks.run(context);
                try { ((java.util.concurrent.ExecutorService)get(service,"cycleReader")).shutdownNow(); }
                catch(ReflectiveOperationException failed) { throw new AssertionError(failed); }
                fixture.edit().clear().commit();mediaPrefs.edit().clear().commit();
            }
        });
    }
    private static DepartureCycle.Store store(SharedPreferences prefs) {
        return new DepartureCycle.Store() {
            public Object identity() { return prefs; }
            public DepartureCycle.State read() {
                return new DepartureCycle.State(prefs.getInt("boot",-1),prefs.getLong("counter",-1),prefs.getLong("generation",0),
                        DepartureCycle.Baseline.valueOf(prefs.getString("baseline","UNKNOWN")),prefs.getBoolean("consumed",false),prefs.getBoolean("away",false));
            }
            public boolean write(DepartureCycle.State next) {
                return prefs.edit().putInt("boot",next.boot()).putLong("counter",next.counter()).putLong("generation",next.generation())
                        .putString("baseline",next.baseline().name()).putBoolean("consumed",next.consumed()).putBoolean("away",next.away()).commit();
            }
        };
    }
    private static Object get(Object object,String name) throws ReflectiveOperationException {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static void set(Object object,String name,Object value) throws ReflectiveOperationException {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
