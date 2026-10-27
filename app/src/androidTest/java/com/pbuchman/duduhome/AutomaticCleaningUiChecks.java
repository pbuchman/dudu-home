package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.roborock.RoborockClient;
import com.pbuchman.duduhome.ui.MainActivity;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** Synthetic automatic feedback only. Credentials are absent, no robot transport is created. */
final class AutomaticCleaningUiChecks {
    static void run(Instrumentation i) throws Exception {
        var context=i.getTargetContext();
        check(!new java.io.File(context.getNoBackupFilesDir(),"roborock.enc").exists(),"UI fixture must contain no robot credentials");
        var daily=context.getSharedPreferences("daily_cleaning",0);
        boolean had=daily.contains("last_attempt_day"); long previous=daily.getLong("last_attempt_day",Long.MIN_VALUE);
        var screen=(MainActivity)i.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            check(daily.edit().clear().commit(),"isolated automatic UI quota");
            long[] attempt={0};
            i.runOnMainSync(() -> {
                attempt[0]=ProgressBus.request(context,Kind.CLEANING);
                ProgressBus.reserveWith(attempt[0],() -> DailyCleaning.reserve(context),ProgressBus.ReservationPolicy.SEND_ONLY);
                screen.automaticAction(HomeAction.CLEANING,attempt[0]);
            });
            check(screen.findViewById(R.id.call_status_content).isShown() && !screen.findViewById(R.id.roborock_setup_content).isShown()
                    && screen.findViewById(R.id.error_actions).getVisibility()==View.GONE,"missing auto config shows bounded information, no credential form or persistent error");
            check(DailyCleaning.available(context,DailyCleaning.day()) && !screen.allowsExternalLaunch(),"missing config preserves quota and blocks continuation during feedback");
            SystemClock.sleep(2700); i.waitForIdleSync();
            check(screen.findViewById(R.id.menu_content).isShown(),"missing config feedback ends within 2.5 seconds plus scheduler tolerance");
            check(DailyCleaning.reserve(context),"synthetic used daily quota");
            i.runOnMainSync(() -> {
                attempt[0]=ProgressBus.request(context,Kind.CLEANING);
                ProgressBus.reserveWith(attempt[0],() -> DailyCleaning.reserve(context),ProgressBus.ReservationPolicy.SEND_ONLY);
                screen.automaticAction(HomeAction.CLEANING,attempt[0]);
            });
            check(((TextView)screen.findViewById(R.id.status_description)).getText().toString().equals("Dzisiejsza próba sprzątania została już wykorzystana"),"used quota exact automatic text");
            SystemClock.sleep(2700); i.waitForIdleSync();
            check(screen.findViewById(R.id.menu_content).isShown(),"used quota does not leave persistent automatic UI");
            var render=MainActivity.class.getDeclaredMethod("renderCleaningResult",RoborockClient.Result.class);render.setAccessible(true);
            var selected=MainActivity.class.getDeclaredField("actionAttempt");selected.setAccessible(true);
            for(var result:new RoborockClient.Result[]{RoborockClient.Result.AUTH_REJECTED,RoborockClient.Result.NETWORK_UNKNOWN,RoborockClient.Result.SERVER_ERROR}) {
                i.runOnMainSync(() -> {
                    try {
                        attempt[0]=ProgressBus.request(context,Kind.CLEANING);
                        ProgressBus.reserveWith(attempt[0],() -> true,ProgressBus.ReservationPolicy.SEND_ONLY);
                        check(ProgressBus.claimSend(context,attempt[0]),"fake sent result admission; no transport");
                        selected.setLong(screen,attempt[0]); render.invoke(screen,result);
                    } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
                });
                check(!screen.findViewById(R.id.roborock_setup_content).isShown() && screen.findViewById(R.id.error_actions).getVisibility()==View.GONE
                        && !screen.allowsExternalLaunch(),"automatic auth/error/unknown remains bounded feedback and never opens setup");
                i.runOnMainSync(() -> ProgressBus.update(context,attempt[0],result==RoborockClient.Result.NETWORK_UNKNOWN?Phase.UNKNOWN:Phase.ERROR,Reason.NONE));
                SystemClock.sleep(2700);i.waitForIdleSync();
                check(screen.findViewById(R.id.menu_content).isShown(),"automatic failure feedback releases presentation");
            }
            check(context.getString(R.string.automatic_cleaning_accepted).equals("Zlecono sprzątanie"),"automatic success exact text");
        } finally {
            i.runOnMainSync(screen::finish);
            var restore=daily.edit().clear();if(had)restore.putLong("last_attempt_day",previous);
            check(restore.commit(),"automatic UI daily fixture cleanup");
        }
    }
    private static void check(boolean pass,String message) { if(!pass)throw new AssertionError(message); }
}
