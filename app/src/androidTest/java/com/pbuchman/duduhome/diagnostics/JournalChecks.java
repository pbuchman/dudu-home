package com.pbuchman.duduhome.diagnostics;

import android.content.Context;
import java.nio.file.Files;

/** Synthetic event times show buffering preserves observation time without real sleeps. */
public final class JournalChecks {
    public static void run(Context context) {
        long id = 9876;
        Diagnostics.beginAttempt(id);
        Diagnostics.recordAttemptAt(context,id,"JOURNAL_OBSERVATION_TEST",42,17);
        Diagnostics.commitAttempt(context,id);
        if(!Diagnostics.awaitWrites())throw new AssertionError("journal drain");
        try {
            String journal=new String(Files.readAllBytes(new java.io.File(context.getNoBackupFilesDir(),"diagnostics.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8);
            if(journal.lines().noneMatch(line->line.startsWith("42 17 PROCESS=")
                    && line.endsWith("ID="+id+" JOURNAL_OBSERVATION_TEST")))
                throw new AssertionError("buffer must retain observation time, not later send time");
        }catch(java.io.IOException failed){throw new AssertionError(failed);}
        Diagnostics.discardAttempt(id);
    }
}
