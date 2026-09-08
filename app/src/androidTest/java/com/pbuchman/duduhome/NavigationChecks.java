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
        require(first.verifiedWake(1) && first.reserve());
        require(!first.verifiedWake(1) && !first.reserve());
        JourneySession reboot=new JourneySession(prefs,43);
        require(reboot.reserve());
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
        prefs.edit().clear().commit();
    }
    private static void require(boolean value){if(!value)throw new AssertionError("navigation regression");}
}
