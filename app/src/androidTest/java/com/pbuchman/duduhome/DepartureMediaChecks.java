package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.location.HomeMonitorService;
import com.pbuchman.duduhome.location.MotionDetector;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static com.pbuchman.duduhome.automation.AutomationCoordinator.Type.*;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;
import static com.pbuchman.duduhome.location.HomeDetector.GateArea.*;

/** Production runtime/cancellation adapters with fake sent calls and fake Yanosik only. */
final class DepartureMediaChecks {
    static void run(Instrumentation instrumentation) {
        ProgressChecks.main(instrumentation, () -> {
            Object visible = getStatic(HomeActions.class,"visible"), screen = getStatic(HomeActions.class,"screen");
            setStatic(HomeActions.class,"visible",new WeakReference<>(null));
            setStatic(HomeActions.class,"screen",new WeakReference<>(null));
            check(HomeActions.begin(),"synthetic transport lock");
            try {
                Context context = instrumentation.getTargetContext();
                stationarySuccess(context);
                rejectedOutcomes(context);
                cancellations(context);
                cleaningIsolation(context);
                scheduling(context);
            } finally {
                HomeActions.end();
                setStatic(HomeActions.class,"visible",visible); setStatic(HomeActions.class,"screen",screen);
            }
        });
        schedulerFeedback(instrumentation);
    }
    private static void schedulerFeedback(Instrumentation instrumentation) {
        Context context=instrumentation.getTargetContext();
        check(!new java.io.File(context.getNoBackupFilesDir(),"roborock.enc").exists(),"end-to-end cleaning test requires absent credentials");
        check(!HomeMonitorService.ready(context),"synthetic scheduler fixture requires GPS monitor disabled");
        for(boolean used:new boolean[]{false,true}) {
            Fixture[] fixture={null};
            boolean[] sawFeedback={false};
            try {
                ProgressChecks.main(instrumentation,() -> {
                    check(HomeActions.begin(),"hold synthetic gate cleanup before continuation");
                    fixture[0]=new Fixture(context);
                    Fixture f=fixture[0]; f.fakeYanosik(YanosikPresence.State.NO_WORK_SIGNAL,true);
                    if(used)check(DailyCleaning.reserve(context),"isolated used quota for scheduler integration");
                    Call call=f.gate(HomeEvent.DEPARTURE_STARTED);call.success.run();
                    ProgressBus.update(context,call.id,Phase.SUCCEEDED,Reason.NONE);
                    check(f.types().equals(List.of(CLEANING,YANOSIK,SPOTIFY)),"full departure waits in correct order behind gate cleanup");
                    HomeActions.end();
                });
                long deadline=SystemClock.uptimeMillis()+8000;
                boolean[] launched={false};
                do {
                    ProgressChecks.main(instrumentation,() -> {
                        var activity=HomeActions.visibleActivity();
                        if(activity!=null && !activity.isFinishing() && !activity.isDestroyed()
                                && activity.findViewById(R.id.call_status_content).isShown()) {
                            sawFeedback[0]=true;
                            check(!activity.findViewById(R.id.roborock_setup_content).isShown()
                                    && !activity.findViewById(R.id.error_actions).isShown(),"scheduler automatic feedback never opens configuration or a persistent error");
                            check(fixture[0].opens==0 && !HomeActions.allowsExternalLaunch(),"real information timer and UI ownership block fake Yanosik");
                        }
                        launched[0]=fixture[0].opens==1;
                    });
                    if(!launched[0])SystemClock.sleep(40);
                } while(!launched[0] && SystemClock.uptimeMillis()<deadline);
                ProgressChecks.main(instrumentation,() -> {
                    Fixture f=fixture[0];
                    check(sawFeedback[0] && f.opens==1,"real runtime->MainActivity->2.5-second feedback cleanup automatically continues fake Yanosik");
                    check(f.types().equals(List.of(SPOTIFY)),"Spotify remains behind actual ten-second Yanosik grace");
                    var cleaning=ProgressBus.snapshot().stream().filter(s -> s.kind()==Kind.CLEANING && !f.previousIds.contains(s.id())).findFirst().orElseThrow();
                    check(cleaning.phase()==Phase.SKIPPED && cleaning.reason()==(used?Reason.DAILY_LIMIT:Reason.NO_CONFIG),"scheduler preserves missing/used outcome provenance");
                    check(DailyCleaning.available(context,DailyCleaning.day())!=used,"unsent scheduler outcome preserves the original daily quota");
                });
            } finally {
                ProgressChecks.main(instrumentation,() -> {
                    if(fixture[0]!=null)fixture[0].close();
                    HomeActions.end();
                    var activity=HomeActions.visibleActivity(); if(activity!=null)activity.finish();
                });
                instrumentation.waitForIdleSync();
            }
        }
    }
    private static void stationarySuccess(Context context) {
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED);
            check(f.jobs().isEmpty(),"recognition/admission alone cannot queue media");
            call.success.run();
            check(f.types().equals(List.of(CLEANING,YANOSIK,SPOTIFY)),"successful departure queues independent cleaning and ordered media without any motion baseline");
            check(f.session.available() && f.session.available(JourneySession.Target.SPOTIFY),"pending media has no durable reservation");
            call.success.run(); HomeActions.gateSuccessCallback(call.id).run();
            check(f.jobs().size()==3,"repeated and recaptured success cannot duplicate queued media");
            ProgressBus.update(context,call.id,Phase.SUCCEEDED,Reason.NONE);
            HomeActions.gateSuccessCallback(call.id).run();
            check(f.jobs().size()==3,"terminal gate cannot replay completion");
            invoke(f.runtime,"drain");
            check(f.jobs().size()==3,"actual cleanup exclusion blocks all media");
            f.completeCleaning();
            set(f.runtime,"areaFreshUntil",0L);
            check(!f.mediaReady(),"old GPS proof blocks pending continuation");
            f.runtime.gateArea(UNKNOWN,0);
            check(!f.mediaReady(),"unknown GPS cannot release pending continuation");
            f.runtime.gateArea(DEPARTURE,Long.MAX_VALUE);
            HomeActions.end();
            check(f.mediaReady(),"fresh same-area GPS and cleanup allow stationary media");
            check(HomeActions.begin(),"restore synthetic cleanup lock");
        }
        try (Fixture f = new Fixture(context)) {
            f.runtime.home(HomeEvent.OUTBOUND_CHECKPOINT);
            check(f.jobs().isEmpty(),"checkpoint no longer schedules a command");
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED); call.success.run();
            check(f.queue.next(SystemClock.elapsedRealtime(),true,true).type()==CLEANING,"cleaning remains above continuation media");
            check(f.queue.next(SystemClock.elapsedRealtime(),true,true).type()==YANOSIK,"Yanosik is first media target");
        }
    }
    private static void rejectedOutcomes(Context context) {
        for (Phase terminal : new Phase[]{Phase.SKIPPED,Phase.ERROR,Phase.UNKNOWN,Phase.CANCELLED}) {
            try (Fixture f = new Fixture(context)) {
                Call call=f.gate(HomeEvent.DEPARTURE_STARTED);
                if (terminal==Phase.CANCELLED) ProgressBus.cancelAttempt(ProgressBus.token(call.id));
                else ProgressBus.update(context,call.id,terminal,Reason.EXPIRED);
                call.success.run(); HomeActions.gateSuccessCallback(call.id).run();
                check(f.jobs().isEmpty() && !((GatePrecondition)get(f.runtime,"gate")).allowsMedia(),"failed/expired/cancelled call cannot release or continue media: "+terminal);
            }
        }
        for (HomeEvent origin : new HomeEvent[]{null,HomeEvent.RETURN_APPROACH}) {
            try (Fixture f = new Fixture(context)) {
                Call call=f.gate(origin); call.success.run();
                check(f.jobs().isEmpty(),"manual/return success cannot start departure continuation");
                check(((GatePrecondition)get(f.runtime,"gate")).allowsMedia(),"manual/return success retains existing area release");
                ProgressBus.update(context,call.id,Phase.SUCCEEDED,Reason.NONE);
            }
        }
        for (Reason reset : new Reason[]{Reason.RESET,Reason.WAKE,Reason.CONFIGURATION,Reason.SERVICE_STOPPED}) {
            try (Fixture f = new Fixture(context)) {
                Call call=f.gate(HomeEvent.DEPARTURE_STARTED);
                f.runtime.reset(reset); f.runtime.gateArea(DEPARTURE,Long.MAX_VALUE);
                call.success.run(); HomeActions.gateSuccessCallback(call.id).run();
                check(f.jobs().isEmpty(),"runtime generation invalidates old or recaptured continuation: "+reset);
            }
        }
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED);
            f.session.observeAwakeCycle(FirstWakeChecks.cold());
            check(f.session.observeAwakeCycle(FirstWakeChecks.cycle(1))==JourneySession.ObservationResult.REARMED,"synthetic new journey cycle");
            call.success.run();
            check(f.jobs().isEmpty(),"new cycle independently rejects an old success before runtime reset");
        }
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED); f.prefs.edit().clear().commit();
            check(f.session.cycle()==null,"missing journey cycle"); call.success.run();
            check(f.jobs().isEmpty(),"unavailable journey state cannot continue");
        }
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED);
            long wrong=ProgressBus.requestManual(context,Kind.RETURN);
            ProgressBus.update(context,wrong,Phase.STARTED,Reason.NONE);
            check(ProgressBus.claimSend(context,wrong),"synthetic different call");
            HomeActions.gateSuccessCallback(wrong).run();
            check(f.jobs().isEmpty(),"another gate attempt cannot take departure provenance");
            ProgressBus.update(context,wrong,Phase.SUCCEEDED,Reason.NONE);
            check(f.session.chooseManualNavigation(),"manual Maps choice"); call.success.run();
            check(f.types().equals(List.of(CLEANING)),"Maps suppresses media while keeping independent cleaning queued");
            HomeActions.end(); invoke(f.runtime,"drain");
            check(f.types().equals(List.of(CLEANING)),"manual Maps defers automatic cleaning instead of covering navigation");
            check(HomeActions.begin(),"restore lock after Maps priority check");
        }
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED);
            ProgressBus.update(context,call.id,Phase.STARTED,Reason.NONE);
            // A captured callback for a non-sent attempt is rejected at the production bus boundary.
            long unsent=ProgressBus.requestManual(context,Kind.DEPARTURE);
            ProgressBus.update(context,unsent,Phase.STARTED,Reason.NONE);
            HomeActions.gateSuccessCallback(unsent).run();
            check(f.jobs().isEmpty(),"Binder request/STARTED is not proof of a successful sent call");
            ProgressBus.cancelAttempt(ProgressBus.token(unsent));
        }
    }
    private static void cancellations(Context context) {
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED);
            ProgressBus.offer(context,f.epoch,ProgressBus.MOTION,List.of(new DetectionProgress(Kind.YANOSIK,1,Phase.CANDIDATE,.2,Long.MAX_VALUE,Reason.NONE)));
            var candidate=ProgressBus.snapshot().stream().filter(s -> s.kind()==Kind.YANOSIK && s.evidenceId()>=0 && ProgressBus.active(s.id())).findFirst().orElseThrow();
            check(ProgressBus.cancelAttempt(ProgressBus.token(candidate)),"media detector candidate cancellation accepted");
            check(ProgressBus.acceptsGateSuccess(call.id),"candidate cancellation leaves the actual successful gate attempt valid");
            call.success.run();
            check(f.types().equals(List.of(CLEANING)),"pre-enqueue media cancellation cannot suppress independent departure cleaning");
            check(((GatePrecondition)get(f.runtime,"gate")).allowsMedia(),"suppression is cancellation provenance, not rejected gate success");
            f.runtime.reset(Reason.CONFIGURATION);
            Call newer=f.gate(HomeEvent.DEPARTURE_STARTED); newer.success.run();
            check(f.types().equals(List.of(CLEANING)),"reset and another gate success cannot rearm media cancellation");
            f.completeCleaning();
            ProgressBus.update(context,newer.id,Phase.SUCCEEDED,Reason.NONE);
            MotionHook motion=(MotionHook)get(f.monitor,"motion");
            HomeActions.end();
            for (int n=0;n<16;n++) motion.accept(motion(n*1000L,n*2,2),true);
            check(f.jobs().isEmpty(),"continued motion without baseline remains cancelled");
            for (int n=16;n<=26;n++) motion.observeBaseline(motion(n*1000L,30,0));
            check(f.jobs().isEmpty(),"fresh stationary baseline alone sends nothing");
            for (int n=27;n<=37;n++) motion.accept(motion(n*1000L,30+(n-27)*2,2),true);
            check(f.types().equals(List.of(YANOSIK,SPOTIFY)),"qualified baseline plus new motion is the sole cancellation rearm");
            check(HomeActions.begin(),"restore synthetic lock after motion callback");
        }
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED); call.success.run();
            check(ProgressBus.cancelAttempt(ProgressBus.token(f.id(YANOSIK))),"pending departure media cancellation");
            check(f.types().equals(List.of(CLEANING)) && f.session.available() && f.session.available(JourneySession.Target.SPOTIFY),"unsent media cancellation leaves independent cleaning and consumes neither media target");
            f.completeCleaning();
            call.success.run(); HomeActions.gateSuccessCallback(call.id).run();
            check(f.jobs().isEmpty(),"gate callback cannot restore a cancelled media group");
        }
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED); call.success.run();
            f.completeCleaning();
            f.fakeYanosik(YanosikPresence.State.NO_WORK_SIGNAL,true);
            HomeActions.end(); invoke(f.runtime,"drain");
            check(f.opens==1 && !f.session.available(),"synthetic Yanosik sent once");
            check(ProgressBus.cancelAttempt(ProgressBus.token(f.id(SPOTIFY))),"cancel between Yanosik and Spotify");
            check(f.jobs().isEmpty() && f.session.available(JourneySession.Target.SPOTIFY),"Spotify is removed without reserving or launching");
            check(HomeActions.begin(),"restore lock after fake Yanosik");
        }
    }
    private static void cleaningIsolation(Context context) {
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED); call.success.run();
            long cleaning=f.id(CLEANING);
            check(ProgressBus.cancelAttempt(ProgressBus.token(cleaning)),"cleaning has its own cancellation token");
            check(f.types().equals(List.of(YANOSIK,SPOTIFY)),"cleaning cancellation preserves both media targets");
            check(f.session.available() && DailyCleaning.available(context,DailyCleaning.day()),"unsent cleaning cancellation preserves independent quotas");
        }
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED);call.success.run();
            long cleaning=f.id(CLEANING);f.queue.cancel(cleaning);
            ProgressBus.update(context,cleaning,Phase.STARTED,Reason.NONE);
            check(ProgressBus.claimSend(context,cleaning),"synthetic admitted cleaning send commits only its daily quota");
            HomeActions.end();check(HomeActions.begin(cleaning,HomeAction.CLEANING),"hold cleaning transport cleanup lease");
            ProgressBus.onCancel(cleaning,() -> true);
            check(ProgressBus.cancelAttempt(ProgressBus.token(cleaning)),"sent cleaning cancellation is independent");
            check(f.types().equals(List.of(YANOSIK,SPOTIFY)) && !f.mediaReady(),"media survives cancellation but waits for actual cleanup");
            check(!DailyCleaning.available(context,DailyCleaning.day()) && f.session.available(),"sent cleaning keeps quota without consuming media reservation");
            HomeActions.end();ProgressBus.finishCancellation(cleaning);
            check(!f.mediaReady(),"cleaning cancellation feedback still precedes media after cleanup");
            f.expireFeedback(cleaning);check(f.mediaReady(),"media can continue after independent cleaning cleanup and feedback");
            check(HomeActions.begin(),"restore outer synthetic lock");
        }
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED); call.success.run(); f.expireAll(); invoke(f.runtime,"drain");
            check(f.jobs().isEmpty() && DailyCleaning.available(context,DailyCleaning.day()),"expired cleaning consumes no daily quota");
            call.success.run(); HomeActions.gateSuccessCallback(call.id).run();
            check(f.jobs().isEmpty(),"expired departure occurrence never retries");
        }
    }
    private static void scheduling(Context context) {
        for (YanosikPresence.State presence : new YanosikPresence.State[]{YanosikPresence.State.WORK_DETECTED,YanosikPresence.State.NO_WORK_SIGNAL,YanosikPresence.State.UNKNOWN}) {
            try (Fixture f = new Fixture(context)) {
                Call call=f.gate(HomeEvent.DEPARTURE_STARTED); call.success.run();
                f.completeCleaning();
                f.fakeYanosik(presence,false);
                HomeActions.end(); invoke(f.runtime,"drain");
                check(f.opens==0 && f.types().equals(List.of(SPOTIFY)),"working/missing/unknown Yanosik does not reopen or block Spotify");
                check((long)get(f.queue,"mediaNotBefore")==0,"skipped Yanosik adds no fake launch allowance");
                check(HomeActions.begin(),"restore lock after skipped Yanosik");
            }
        }
        try (Fixture f = new Fixture(context)) {
            Call call=f.gate(HomeEvent.DEPARTURE_STARTED); call.success.run();
            f.completeCleaning();
            f.fakeYanosik(YanosikPresence.State.NO_WORK_SIGNAL,true);
            HomeActions.end(); invoke(f.runtime,"drain");
            check(f.opens==1 && (long)get(f.queue,"mediaNotBefore")>SystemClock.elapsedRealtime(),"actual launch request retains ten-second grace");
            check(HomeActions.begin(),"restore lock before expiry fixture");
            f.expireAll(); invoke(f.runtime,"drain");
            check(f.jobs().isEmpty() && !f.session.available(JourneySession.Target.SPOTIFY),"expired pending media consumes opportunity without retry");
            HomeActions.end();
            call.success.run(); HomeActions.gateSuccessCallback(call.id).run(); f.runtime.motion();
            check(f.jobs().isEmpty(),"expired continuation is never replayed");
            check(HomeActions.begin(),"restore lock after unlocked expiry replay check");
        }
    }
    private record Call(long id,Runnable success) { }
    private static final class Fixture implements AutoCloseable {
        final Context context;
        final SharedPreferences prefs;
        final JourneySession session;
        final SharedPreferences daily;
        final boolean hadDaily;
        final long previousDay;
        final long epoch;
        final java.util.Set<Long> previousIds;
        final AutomationRuntime runtime;
        final AutomationCoordinator queue;
        final HomeMonitorService monitor = new HomeMonitorService();
        final Consumer<ProgressModel.State> cancellation;
        int opens;
        @SuppressWarnings("unchecked") Fixture(Context context) {
            this.context=context; prefs=context.getSharedPreferences("journey_session",0);
            daily=context.getSharedPreferences("daily_cleaning",0); hadDaily=daily.contains("last_attempt_day");
            previousDay=daily.getLong("last_attempt_day",Long.MIN_VALUE);
            check(daily.edit().clear().commit(),"isolated cleaning fixture quota");
            previousIds=ProgressBus.snapshot().stream().map(ProgressModel.State::id).collect(java.util.stream.Collectors.toSet());
            check(prefs.edit().clear().commit(),"isolated journey session");
            epoch=ProgressBus.reset(context,Reason.RESET);
            session=new JourneySession(context); check(session.ensureBoot(),"journey boot");
            runtime=new AutomationRuntime(context); queue=(AutomationCoordinator)get(runtime,"queue");
            set(monitor,"automation",runtime);
            cancellation=(Consumer<ProgressModel.State>)get(monitor,"cancellation");
            ProgressBus.setCancellationListener(cancellation);
        }
        Call gate(HomeEvent origin) {
            runtime.gateArea(origin==HomeEvent.RETURN_APPROACH ? RETURN : DEPARTURE,Long.MAX_VALUE);
            long id;
            if (origin==null) id=ProgressBus.requestManual(context,Kind.DEPARTURE);
            else {
                runtime.home(origin);
                var job=queue.next(SystemClock.elapsedRealtime(),true,false);
                check(job!=null && job.type()==GATE,"synthetic selection of gate executor"); id=job.id();
            }
            ProgressBus.update(context,id,Phase.ACCEPTED,Reason.NONE);
            Runnable success=HomeActions.gateSuccessCallback(id);
            ProgressBus.update(context,id,Phase.STARTED,Reason.NONE);
            check(ProgressBus.claimSend(context,id),"fake sent call with normal admission/reservation");
            return new Call(id,success);
        }
        @SuppressWarnings("unchecked") Map<Long,AutomationCoordinator.Job> jobs() { return (Map<Long,AutomationCoordinator.Job>)get(queue,"queued"); }
        List<AutomationCoordinator.Type> types() { return jobs().values().stream().map(AutomationCoordinator.Job::type).toList(); }
        long id(AutomationCoordinator.Type type) { return jobs().values().stream().filter(j -> j.type()==type).findFirst().orElseThrow().id(); }
        boolean mediaReady() { return (boolean)invoke(runtime,"mediaReady"); }
        void fakeYanosik(YanosikPresence.State presence,boolean installed) {
            set(runtime,"yanosik",new YanosikLauncher(new YanosikLauncher.Launch() {
                public Intent resolve() { return installed ? new Intent("synthetic.media.launch") : null; }
                public void open(Intent intent) { opens++; }
            },() -> presence));
        }
        @SuppressWarnings("unchecked") void completeCleaning() {
            long id=id(CLEANING); queue.cancel(id);
            ProgressBus.update(context,id,Phase.SKIPPED,Reason.NO_CONFIG);
            check(!mediaReady(),"automatic skip feedback precedes media");
            check(DailyCleaning.available(context,DailyCleaning.day()),"unsent cleaning skip never consumes quota");
            expireFeedback(id);
        }
        @SuppressWarnings("unchecked") void expireFeedback(long id) {
            Object model=getStatic(ProgressBus.class,"model");
            Map<Long,ProgressModel.State> attempts=(Map<Long,ProgressModel.State>)get(model,"attempts");
            var state=attempts.get(id);
            check(state.expiresAt()-SystemClock.elapsedRealtime()<=2500 && state.expiresAt()>SystemClock.elapsedRealtime(),"automatic cleaning feedback is bounded to 2.5 seconds");
            attempts.put(id,new ProgressModel.State(state.id(),state.generation(),state.kind(),state.phase(),state.value(),state.reason(),state.validUntil(),0,state.evidenceId(),state.stage()));
        }
        void expireAll() {
            for (var job : List.copyOf(jobs().values())) jobs().put(job.id(),new AutomationCoordinator.Job(job.id(),job.type(),job.event(),job.generation(),0,job.day()));
        }
        @Override public void close() {
            runtime.close(); ProgressBus.clearCancellationListener(cancellation);
            // Selected fake calls/jobs have no executor to publish a terminal result during reset.
            for (var state : ProgressBus.snapshot())
                if (!previousIds.contains(state.id()) && ProgressBus.active(state.id()))
                    ProgressBus.cancelAttempt(ProgressBus.token(state));
            ((java.util.concurrent.ExecutorService)get(monitor,"cycleReader")).shutdownNow();
            check(prefs.edit().clear().commit(),"journey fixture cleanup");
            var restore=daily.edit().clear(); if(hadDaily) restore.putLong("last_attempt_day",previousDay);
            check(restore.commit(),"daily fixture cleanup");
        }
    }
    private static MotionDetector.Fix motion(long t,double x,double speed) { return new MotionDetector.Fix(t,x,0,3,speed,true,0,false); }
    private static Object get(Object object,String field) { return get(object.getClass(),object,field); }
    private static Object getStatic(Class<?> type,String field) { return get(type,null,field); }
    private static Object get(Class<?> type,Object object,String field) {
        try { var f=type.getDeclaredField(field); f.setAccessible(true); return f.get(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void set(Object object,String field,Object value) { set(object.getClass(),object,field,value); }
    private static void setStatic(Class<?> type,String field,Object value) { set(type,null,field,value); }
    private static void set(Class<?> type,Object object,String field,Object value) {
        try { var f=type.getDeclaredField(field); f.setAccessible(true); f.set(object,value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Object invoke(Object object,String method) {
        try { var m=object.getClass().getDeclaredMethod(method); m.setAccessible(true); return m.invoke(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void check(boolean pass,String message) { if (!pass) throw new AssertionError(message); }
}
