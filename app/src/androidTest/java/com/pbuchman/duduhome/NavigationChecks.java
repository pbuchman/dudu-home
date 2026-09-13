package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import com.pbuchman.duduhome.automation.JourneySession;
import com.pbuchman.duduhome.automation.YanosikLauncher;
import com.pbuchman.duduhome.automation.YanosikPresence;

final class NavigationChecks {
    static void run(Instrumentation instrumentation) {
        SharedPreferences prefs=instrumentation.getTargetContext().getSharedPreferences("test_journey",0);
        prefs.edit().clear().commit();
        FirstWakeChecks.run();
        com.pbuchman.duduhome.startup.DuduCycleChecks.run();
        int[] opens={0};
        int[] homes={0};
        java.util.List<Runnable> callbacks = new java.util.ArrayList<>();
        YanosikLauncher.Delay delay = (callback, millis) -> {
            require(millis == 10000); callbacks.add(callback);
        };
        YanosikLauncher missing=new YanosikLauncher(new JourneySession(prefs,44), new YanosikLauncher.Launch(){
            public Intent resolve(){return null;}
            public void open(Intent i){throw new AssertionError("Missing app opened");}
            public void home(){throw new AssertionError("Missing app returned home");}
        }, delay, () -> YanosikPresence.State.NO_WORK_SIGNAL);
        missing.attempt(); require(!new JourneySession(prefs,44).reserve());
        YanosikLauncher denied=new YanosikLauncher(new JourneySession(prefs,45),new YanosikLauncher.Launch(){
            public Intent resolve(){return new Intent();}
            public void open(Intent i){opens[0]++;throw new SecurityException();}
            public void home(){throw new AssertionError("Denied app returned home");}
        }, delay, () -> YanosikPresence.State.NO_WORK_SIGNAL);
        denied.attempt(); denied.attempt(); require(opens[0]==1);
        require(callbacks.isEmpty());
        YanosikLauncher accepted=new YanosikLauncher(new JourneySession(prefs,46),new YanosikLauncher.Launch(){
            public Intent resolve(){return new Intent();}
            public void open(Intent i){opens[0]++;}
            public void home(){homes[0]++;}
        }, delay, () -> YanosikPresence.State.NO_WORK_SIGNAL);
        accepted.attempt(); accepted.attempt(); require(opens[0]==2);
        require(homes[0] == 0 && callbacks.size() == 1);
        callbacks.get(0).run(); callbacks.get(0).run();
        require(homes[0] == 1); // No repeated desktop navigation, even on duplicate delivery.
        YanosikLauncher cancelled = new YanosikLauncher(new JourneySession(prefs,47),
                new YanosikLauncher.Launch() {
                    public Intent resolve(){return new Intent();}
                    public void open(Intent i){opens[0]++;}
                    public void home(){homes[0]++;}
                }, delay, () -> YanosikPresence.State.NO_WORK_SIGNAL);
        cancelled.attempt(); cancelled.cancelPendingHome(); callbacks.get(1).run();
        require(homes[0] == 1 && !new JourneySession(prefs,47).reserve());
        YanosikLauncher homeDenied = new YanosikLauncher(new JourneySession(prefs,48),
                new YanosikLauncher.Launch() {
                    public Intent resolve(){return new Intent();}
                    public void open(Intent i){opens[0]++;}
                    public void home(){homes[0]++; throw new SecurityException();}
                }, delay, () -> YanosikPresence.State.NO_WORK_SIGNAL);
        homeDenied.attempt(); callbacks.get(2).run(); callbacks.get(2).run(); homeDenied.attempt();
        require(homes[0] == 2 && callbacks.size() == 3);
        YanosikLauncher.Launch untouched = new YanosikLauncher.Launch() {
            public Intent resolve(){throw new AssertionError("Existing/unknown target resolved");}
            public void open(Intent i){throw new AssertionError("Existing/unknown target opened");}
            public void home(){throw new AssertionError("Existing/unknown target hidden");}
        };
        YanosikPresence.State[] presence = {YanosikPresence.State.WORK_DETECTED};
        YanosikLauncher running = new YanosikLauncher(new JourneySession(prefs,49), untouched, delay, () -> presence[0]);
        running.attempt();
        presence[0] = YanosikPresence.State.NO_WORK_SIGNAL;
        running.attempt(); // Closing Yanosik cannot grant another attempt in this cycle.
        require(!new JourneySession(prefs,49).reserve() && callbacks.size() == 3);
        YanosikLauncher unknown = new YanosikLauncher(new JourneySession(prefs,50), untouched, delay,
                () -> YanosikPresence.State.UNKNOWN);
        unknown.attempt(); unknown.attempt();
        require(!new JourneySession(prefs,50).reserve() && callbacks.size() == 3);
        YanosikLauncher error = new YanosikLauncher(new JourneySession(prefs,51), untouched, delay,
                () -> {throw new SecurityException();});
        error.attempt(); require(!new JourneySession(prefs,51).reserve());
        // Read at attempt time, not at construction/startup of the monitor.
        presence[0] = YanosikPresence.State.NO_WORK_SIGNAL;
        YanosikLauncher manualDuringMotion = new YanosikLauncher(new JourneySession(prefs,52), untouched, delay, () -> presence[0]);
        presence[0] = YanosikPresence.State.WORK_DETECTED;
        manualDuringMotion.attempt(); require(callbacks.size() == 3);
        require(YanosikPresence.classify(null) == YanosikPresence.State.UNKNOWN);
        require(YanosikPresence.classify(new android.service.notification.StatusBarNotification[0]) == YanosikPresence.State.NO_WORK_SIGNAL);
        var regular = notification(YanosikLauncher.PACKAGE, 0);
        var ongoingAd = notification(YanosikLauncher.PACKAGE, android.app.Notification.FLAG_ONGOING_EVENT);
        var unrelated = notification("example.unrelated", android.app.Notification.FLAG_FOREGROUND_SERVICE);
        var working = notification(YanosikLauncher.PACKAGE, android.app.Notification.FLAG_FOREGROUND_SERVICE);
        require(YanosikPresence.classify(new android.service.notification.StatusBarNotification[]{regular, ongoingAd, unrelated})
                == YanosikPresence.State.NO_WORK_SIGNAL);
        require(YanosikPresence.classify(new android.service.notification.StatusBarNotification[]{regular, working})
                == YanosikPresence.State.WORK_DETECTED);
        android.content.Context context = instrumentation.getTargetContext();
        com.pbuchman.duduhome.diagnostics.Diagnostics.record(context, "TEST_CATEGORY");
        java.io.File journal = new java.io.File(context.getNoBackupFilesDir(), "diagnostics.txt");
        long length = journal.length();
        require(length > 0);
        com.pbuchman.duduhome.diagnostics.Diagnostics.record(context, "invalid payload\nsecond line");
        require(journal.length() == length);
        prefs.edit().clear().commit();
    }
    private static void require(boolean value){if(!value)throw new AssertionError("navigation regression");}
    private static android.service.notification.StatusBarNotification notification(String pkg, int flags) {
        android.app.Notification notification = new android.app.Notification();
        notification.flags = flags;
        return new android.service.notification.StatusBarNotification(pkg, pkg, 1, null, 10000, 0,
                0, notification, android.os.Process.myUserHandle(), 0);
    }
}
