package com.pbuchman.duduhome;

import android.app.Instrumentation;
import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** Synthetic cancellation boundaries; never invokes a real Binder, app launch or robot. */
public final class CancellationChecks {
    public static void run(Instrumentation i) {
        i.runOnMainSync(() -> {
            var context = i.getTargetContext();
            ProgressBus.reset(context, Reason.RESET);
            var journal = new java.io.File(context.getNoBackupFilesDir(), "diagnostics.txt");
            long before = journal.length();
            long id = ProgressBus.requestManual(context, Kind.CLEANING);
            int[] reservations = {0}, cleanup = {0};
            ProgressBus.reserveWith(id, () -> { reservations[0]++; return true; });
            ProgressBus.onCancel(id, () -> { cleanup[0]++; return false; });
            var token = ProgressBus.token(id);
            check(!ProgressBus.cancelAttempt(new AttemptToken("previous-process",id)), "process nonce rejects old button");
            check(ProgressBus.cancelAttempt(token) && !ProgressBus.cancelAttempt(token), "cancel is idempotent");
            check(reservations[0] == 0 && cleanup[0] == 1, "unsent cancellation releases RAM only");
            check(!ProgressBus.claimSend(context,id), "cancelled admission cannot send");
            Diagnostics.recordAttempt(context,id,"LATE_CANCELLED_CALLBACK");
            ProgressBus.update(context,id,Phase.SUCCEEDED,Reason.NONE);
            check(journal.length() == before, "cancelled pre-send attempt has no durable diagnostics, including late callback");
            var state = ProgressBus.snapshot().stream().filter(s -> s.id()==id).findFirst().orElseThrow();
            check(state.phase()==Phase.CANCELLED && state.reason()==Reason.USER_CANCELLED, "late success does not revive cancellation");
            long other = ProgressBus.requestManual(context,Kind.RETURN);
            check(ProgressBus.active(other), "independent attempt remains active");
            check(!ProgressBus.cancelAttempt(token) && ProgressBus.active(other), "old click cannot cancel new attempt");
            check(ProgressBus.cancelAttempt(ProgressBus.token(other)), "clean up independent fixture");

            long epoch = ProgressBus.reset(context,Reason.RESET);
            ProgressBus.offer(context,epoch,ProgressBus.HOME,java.util.List.of(new DetectionProgress(Kind.DEPARTURE,1,Phase.CANDIDATE,.2,android.os.SystemClock.elapsedRealtime()+3000,Reason.NONE)));
            var candidate = ProgressBus.visible();
            long automatic = ProgressBus.request(context,Kind.DEPARTURE);
            check(automatic==candidate.id(), "request preserves visible candidate token");
            ProgressBus.cancelAttempt(ProgressBus.token(automatic));
            long manual = ProgressBus.requestManual(context,Kind.DEPARTURE);
            check(manual!=automatic && !ProgressBus.cancelled(manual), "manual action receives fresh independent token after auto cancel");
            ProgressBus.cancelAttempt(ProgressBus.token(manual));

            long failed = ProgressBus.requestManual(context,Kind.CLEANING);
            ProgressBus.reserveWith(failed, () -> { reservations[0]++; return true; });
            ProgressBus.update(context,failed,Phase.SKIPPED,Reason.NO_CONFIG);
            check(reservations[0]==1, "non-user missing config still consumes legacy quota");
            ProgressBus.update(context,failed,Phase.ERROR,Reason.NONE);
            check(reservations[0]==1, "terminal finalization is once only");

            long sent = ProgressBus.requestManual(context,Kind.CLEANING);
            ProgressBus.reserveWith(sent, () -> { reservations[0]++; return true; });
            ProgressBus.onCancel(sent, () -> true);
            check(ProgressBus.claimSend(context,sent) && !ProgressBus.claimSend(context,sent), "one serialized send admission");
            check(ProgressBus.cancelAttempt(ProgressBus.token(sent)), "sent work remains cancellable");
            check(ProgressBus.snapshot().stream().anyMatch(s -> s.id()==sent && s.phase()==Phase.CANCELLING), "cleanup holds cancelling state");
            ProgressBus.finishCancellation(sent);
            check(ProgressBus.sent(sent) && reservations[0]==2, "sent cancellation retains committed guard");

            var cyclePrefs = context.getSharedPreferences("cancellation_cycle_fixture",0);
            check(cyclePrefs.edit().clear().commit(), "cycle fixture");
            var cycle = new JourneySession(cyclePrefs,55); check(cycle.ensureBoot(), "cycle boot");
            var initialPending = cycle.reservation(JourneySession.Target.YANOSIK);
            cycle.observeAwakeCycle(FirstWakeChecks.cold());
            check(initialPending.getAsBoolean(), "first baseline preserves current pending cycle");
            var oldPending = cycle.reservation(JourneySession.Target.SPOTIFY);
            check(cycle.observeAwakeCycle(FirstWakeChecks.cycle(1))==JourneySession.ObservationResult.REARMED, "new wake observed");
            check(!oldPending.getAsBoolean() && cycle.available(JourneySession.Target.SPOTIFY), "old pending finalization cannot consume new wake");
            cyclePrefs.edit().clear().commit();

            var prefs = context.getSharedPreferences("journey_session",0);
            check(prefs.edit().clear().commit(), "isolated media fixture");
            var session = new JourneySession(context); check(session.ensureBoot(), "media boot");
            var runtime = new AutomationRuntime(context);
            runtime.noHomeConfiguration();
            try {
                runtime.motion();
                var media = ProgressBus.snapshot().stream().filter(s -> ProgressBus.active(s.id()) && (s.kind()==Kind.YANOSIK || s.kind()==Kind.SPOTIFY)).toList();
                var y = media.stream().filter(s -> s.kind()==Kind.YANOSIK).findFirst().orElseThrow();
                var spotify = media.stream().filter(s -> s.kind()==Kind.SPOTIFY).findFirst().orElseThrow();
                check(session.available() && session.available(JourneySession.Target.SPOTIFY), "media queue reserves only RAM");
                check(ProgressBus.claimSend(context,y.id()), "synthetic Yanosik commit");
                ProgressBus.update(context,y.id(),Phase.SUCCEEDED,Reason.NONE);
                check(ProgressBus.cancelAttempt(ProgressBus.token(spotify)), "pending sibling cancelled after Yanosik completed");
                check(!session.available() && session.available(JourneySession.Target.SPOTIFY), "only actually sent sibling stays consumed");
                check(!ProgressBus.sent(spotify.id()), "group wording does not falsely mark Spotify sent");
                check(ProgressBus.snapshot().stream().anyMatch(s -> s.id()==spotify.id() && s.reason()==Reason.COMMAND_SENT), "partial group explains already sent step");
                check(!((AutomationCoordinator)get(runtime,"queue")).contains(AutomationCoordinator.Type.SPOTIFY), "pending media removed");
            } finally { runtime.close(); prefs.edit().clear().commit(); }
        });
    }
    private static Object get(Object o,String name) {
        try { var field=o.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(o); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void check(boolean pass,String message) { if(!pass) throw new AssertionError(message); }
}
