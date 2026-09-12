package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import com.pbuchman.duduhome.automation.JourneySession;
import com.pbuchman.duduhome.automation.YanosikLauncher;

final class NavigationChecks {
    static void run(Instrumentation instrumentation) {
        SharedPreferences prefs=instrumentation.getTargetContext().getSharedPreferences("test_journey",0);
        prefs.edit().clear().commit();
        JourneySession first=new JourneySession(prefs,42);
        require(first.ensureBoot() && first.reserve());
        require(!new JourneySession(prefs,42).reserve());
        require(!new JourneySession(prefs,41).reserve());
        require(!first.observeAwakeCycle(0) && !first.reserve());
        require(first.verifiedWake(1) && first.reserve());
        require(!first.verifiedWake(1) && !first.reserve());
        require(!first.observeAwakeCycle(1) && !first.reserve());
        require(!first.observeAwakeCycle(0) && !first.reserve());
        require(first.observeAwakeCycle(2) && first.reserve());
        require(!new JourneySession(prefs,42).observeAwakeCycle(2));
        require(com.pbuchman.duduhome.startup.DuduCycle.parse("0\n0\n2\n0\n0\n") == 2);
        require(com.pbuchman.duduhome.startup.DuduCycle.parse("1\n0\n3\n0\n0\n") == -1);
        require(com.pbuchman.duduhome.startup.DuduCycle.parse("0\n0\n3\n1\n0\n") == -1);
        require(com.pbuchman.duduhome.startup.DuduCycle.parse("\n\n\n\n\n") == -1);
        require(com.pbuchman.duduhome.startup.DuduCycle.parse("0\n0\n-1\n0\n0\n") == -1);
        require(com.pbuchman.duduhome.startup.DuduCycle.parse("0\n0\n3\n0\n1\n") == -1);
        require(com.pbuchman.duduhome.startup.DuduCycle.parse("0\n0\n3\n0\n0\nextra") == -1);
        JourneySession reboot=new JourneySession(prefs,43);
        require(reboot.reserve());
        require(!reboot.observeAwakeCycle(0) && !reboot.reserve());
        require(!new JourneySession(prefs,-1).reserve());
        int[] opens={0};
        YanosikLauncher missing=new YanosikLauncher(new JourneySession(prefs,44), new YanosikLauncher.Launch(){
            public Intent resolve(){return null;}
            public void open(Intent i){throw new AssertionError("Missing app opened");}
        });
        missing.attempt(); require(!new JourneySession(prefs,44).reserve());
        YanosikLauncher denied=new YanosikLauncher(new JourneySession(prefs,45),new YanosikLauncher.Launch(){
            public Intent resolve(){return new Intent();}
            public void open(Intent i){opens[0]++;throw new SecurityException();}
        });
        denied.attempt(); denied.attempt(); require(opens[0]==1);
        YanosikLauncher accepted=new YanosikLauncher(new JourneySession(prefs,46),new YanosikLauncher.Launch(){
            public Intent resolve(){return new Intent();}
            public void open(Intent i){opens[0]++;}
        });
        accepted.attempt(); accepted.attempt(); require(opens[0]==2);
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
}
