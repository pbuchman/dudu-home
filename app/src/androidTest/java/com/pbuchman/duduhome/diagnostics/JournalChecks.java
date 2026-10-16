package com.pbuchman.duduhome.diagnostics;

import android.content.Context;
import java.nio.file.Files;

/** Synthetic times prove evidence reaches disk before commit/send, preserving observation time. */
public final class JournalChecks {
    public static void run(Context context) {
        long id = 9876;
        long observedWall = System.currentTimeMillis()-20;
        Diagnostics.beginAttempt(id);
        Diagnostics.recordAttemptAt(context,id,"JOURNAL_OBSERVATION_TEST",observedWall,17);
        if(!Diagnostics.awaitWrites())throw new AssertionError("journal drain");
        try {
            String journal=new String(Files.readAllBytes(new java.io.File(context.getNoBackupFilesDir(),"diagnostics.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8);
            if(journal.lines().noneMatch(line->line.startsWith(observedWall+" 17 PROCESS=")
                    && line.endsWith("JOURNAL_OBSERVATION_TEST ID="+id)))
                throw new AssertionError("observation must be durable before send without commit");
        }catch(java.io.IOException failed){throw new AssertionError(failed);}
        Diagnostics.discardAttempt(id);
        java.io.File failure=new java.io.File(context.getCacheDir(),"diagnostic-failure-fixture");
        java.io.File blocked=new java.io.File(failure,"diagnostics.txt");
        if(!blocked.mkdirs() && !blocked.isDirectory())throw new AssertionError("failure fixture");
        Context broken=new android.content.ContextWrapper(context) {
            @Override public java.io.File getNoBackupFilesDir() { return failure; }
        };
        long dropped=Diagnostics.droppedWrites();
        long attempt=com.pbuchman.duduhome.automation.ProgressBus.requestManual(broken,
                com.pbuchman.duduhome.automation.DetectionProgress.Kind.CLEANING);
        int[] reservations={0};
        com.pbuchman.duduhome.automation.ProgressBus.reserveWith(attempt,()->{reservations[0]++;return true;});
        if(!com.pbuchman.duduhome.automation.ProgressBus.claimSend(broken,attempt)
                || com.pbuchman.duduhome.automation.ProgressBus.claimSend(broken,attempt)
                || reservations[0]!=1 || Diagnostics.droppedWrites()<=dropped)
            throw new AssertionError("IO failure remains controlled, reports drop and preserves one-shot admission/reservation");
        com.pbuchman.duduhome.automation.ProgressBus.cancelAttempt(
                com.pbuchman.duduhome.automation.ProgressBus.token(attempt));
        if(!com.pbuchman.duduhome.automation.ProgressBus.sent(attempt) || reservations[0]!=1)
            throw new AssertionError("failed diagnostic storage cannot rearm sent attempt");
        if(!blocked.delete() || !failure.delete())throw new AssertionError("failure fixture cleanup");
    }
}
