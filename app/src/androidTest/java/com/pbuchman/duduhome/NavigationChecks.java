package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.content.Intent;
import com.pbuchman.duduhome.automation.*;

final class NavigationChecks {
    static void run(Instrumentation i) {
        FirstWakeChecks.run();
        com.pbuchman.duduhome.startup.DuduCycleChecks.run();
        int[] opens = {0};
        YanosikPresence.State[] state = {YanosikPresence.State.WORK_DETECTED};
        var launch = new YanosikLauncher.Launch() {
            public Intent resolve() { return new Intent(); }
            public void open(Intent intent) { opens[0]++; }
        };
        var target = new YanosikLauncher(launch, () -> state[0]);
        require(target.launchReserved() == YanosikLauncher.Result.ALREADY_RUNNING && opens[0] == 0);
        state[0] = YanosikPresence.State.UNKNOWN;
        require(target.launchReserved() == YanosikLauncher.Result.UNKNOWN && opens[0] == 0);
        state[0] = YanosikPresence.State.NO_WORK_SIGNAL;
        require(target.launchReserved() == YanosikLauncher.Result.REQUESTED && opens[0] == 1);
        var denied = new YanosikLauncher(new YanosikLauncher.Launch() {
            public Intent resolve() { return new Intent(); }
            public void open(Intent intent) { throw new SecurityException(); }
        }, () -> state[0]);
        require(denied.launchReserved() == YanosikLauncher.Result.FAILED);
        var missing = new YanosikLauncher(new YanosikLauncher.Launch() {
            public Intent resolve() { return null; }
            public void open(Intent intent) { throw new AssertionError(); }
        }, () -> state[0]);
        require(missing.launchReserved() == YanosikLauncher.Result.MISSING);
        require(new YanosikLauncher(launch, () -> { throw new SecurityException(); }).launchReserved()
                == YanosikLauncher.Result.FAILED);
        require(YanosikPresence.classify(null) == YanosikPresence.State.UNKNOWN);
        require(YanosikPresence.classify(new android.service.notification.StatusBarNotification[0]) == YanosikPresence.State.NO_WORK_SIGNAL);
        var regular = notification(YanosikLauncher.PACKAGE, 0);
        var ad = notification(YanosikLauncher.PACKAGE, android.app.Notification.FLAG_ONGOING_EVENT);
        var other = notification("example.unrelated", android.app.Notification.FLAG_FOREGROUND_SERVICE);
        var working = notification(YanosikLauncher.PACKAGE, android.app.Notification.FLAG_FOREGROUND_SERVICE);
        require(YanosikPresence.classify(new android.service.notification.StatusBarNotification[]{regular,ad,other})
                == YanosikPresence.State.NO_WORK_SIGNAL);
        require(YanosikPresence.classify(new android.service.notification.StatusBarNotification[]{regular,working})
                == YanosikPresence.State.WORK_DETECTED);
        var c = i.getTargetContext();
        var prefs = c.getSharedPreferences("test_media_journey",0); prefs.edit().clear().commit();
        var session = new JourneySession(prefs,44);
        require(session.ensureBoot() && session.reserve());
        require(session.reserve(JourneySession.Target.SPOTIFY));
        require(!new JourneySession(prefs,44).reserve() && !new JourneySession(prefs,44).reserve(JourneySession.Target.SPOTIFY));
        session.observeAwakeCycle(FirstWakeChecks.cold());
        require(session.observeAwakeCycle(FirstWakeChecks.cycle(1)) == JourneySession.ObservationResult.REARMED);
        require(session.reserve() && session.reserve(JourneySession.Target.SPOTIFY));
        prefs.edit().remove("spotify_consumed").commit();
        require(!session.reserve() && session.reserve(JourneySession.Target.SPOTIFY));
        prefs.edit().clear().commit();
        com.pbuchman.duduhome.diagnostics.Diagnostics.record(c,"TEST_CATEGORY");
        var journal = new java.io.File(c.getNoBackupFilesDir(),"diagnostics.txt"); long size=journal.length();
        com.pbuchman.duduhome.diagnostics.Diagnostics.record(c,"invalid payload\nsecond line");
        require(size>0 && size==journal.length());
    }
    private static void require(boolean ok) { if(!ok)throw new AssertionError("navigation regression"); }
    private static android.service.notification.StatusBarNotification notification(String pkg,int flags) {
        var n=new android.app.Notification(); n.flags=flags;
        return new android.service.notification.StatusBarNotification(pkg,pkg,1,null,10000,0,0,n,android.os.Process.myUserHandle(),0);
    }
}
