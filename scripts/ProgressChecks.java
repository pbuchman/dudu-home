import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.location.*;
import java.util.*;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** Deterministic presentation checks: no sleeps, Android or executors. */
public final class ProgressChecks {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void offer(ProgressModel m, long epoch, Kind kind, long id, Phase phase, double value, long now) {
        m.offer(epoch, Set.of(kind), List.of(new DetectionProgress(kind,id,phase,value,now+3000,Reason.NONE)), now);
    }
    public static void main(String[] args) {
        ProgressModel m = new ProgressModel();
        offer(m,0,Kind.YANOSIK,1,Phase.CANDIDATE,.4,0);
        check(m.visible(2000).value()==.4,"time cannot advance fill");
        offer(m,0,Kind.YANOSIK,1,Phase.CANDIDATE,.1,-1);
        check(m.visible(2000).value()==.4,"older same-run observation ignored");
        check(m.visible(3001).reason()==Reason.STALE,"presentation freshness");
        check(m.visible(5000)==null,"stale message expires");
        offer(m,0,Kind.YANOSIK,1,Phase.CANDIDATE,.5,6000);
        check(m.visible(6000).value()==.5,"fresh snapshot without evidence reset");
        long epoch=m.reset(Reason.WAKE,6000);
        offer(m,0,Kind.YANOSIK,1,Phase.CONFIRMED,1,6001);
        check(m.visible(6001).phase()==Phase.CANCELLED,"old generation discarded");
        check(m.visible(8000)==null,"wake cancellation expires");
        offer(m,epoch,Kind.YANOSIK,2,Phase.CANDIDATE,.1,8000);
        offer(m,epoch,Kind.CLEANING,3,Phase.CANDIDATE,.5,8000);
        check(m.visible(8000).kind()==Kind.CLEANING,"cleaning priority");
        offer(m,epoch,Kind.RETURN,4,Phase.CANDIDATE,.5,8000);
        check(m.visible(8000).kind()==Kind.RETURN,"gate priority");
        offer(m,epoch,Kind.DEPARTURE,5,Phase.CANDIDATE,.2,8000);
        check(m.visible(8000).kind()==Kind.RETURN,"equal priority stable");
        long attempt=m.request(Kind.RETURN,8000);
        offer(m,epoch,Kind.RETURN,4,Phase.CONFIRMED,1,8001);
        check(m.snapshot().stream().noneMatch(s->s.kind()==Kind.RETURN&&s.evidenceId()>=0),"consumed evidence stays consumed");
        m.update(attempt,Phase.ACCEPTED,Reason.NONE,8001);
        m.update(attempt,Phase.STARTED,Reason.NONE,8002);
        var started=m.attempt(attempt);
        m.update(attempt,Phase.STARTED,Reason.NONE,8003);
        m.update(attempt,Phase.ACCEPTED,Reason.NONE,8004);
        check(m.attempt(attempt)==started,"duplicates/backwards updates ignored");
        m.update(attempt,Phase.SKIPPED,Reason.UI_UNAVAILABLE,13000);
        m.update(attempt,Phase.STARTED,Reason.NONE,14000);
        check(m.attempt(attempt).phase()==Phase.SKIPPED,"late screen cannot revive expired request");
        check(new ProgressModel().visible(0)==null,"new process empty");
        ProgressModel nav=new ProgressModel();
        long n=nav.request(Kind.YANOSIK,0);
        nav.update(n,Phase.SUCCEEDED,Reason.NONE,1);
        check(nav.visible(2000)!=null&&nav.visible(2001)==null,"two-second result");
        long mop=nav.request(Kind.MOP,2002);
        nav.update(mop,Phase.SKIPPED,Reason.NO_CONFIG,2002);
        check(nav.visible(2002)==null,"no automatic mop banner");
        for(int i=0;i<100;i++){long a=nav.request(Kind.YANOSIK,3000+i);nav.update(a,Phase.SUCCEEDED,Reason.NONE,3000+i);}
        check(nav.snapshot().size()<=16,"bounded attempt history");
        Tracker tracker=new Tracker();
        tracker.update(Kind.RETURN,true,false,.5,3000,Reason.NONE);
        long old=tracker.snapshot().get(0).id();
        tracker.clear(Reason.GPS_UNRELIABLE);
        check(tracker.snapshot().get(0).reason()==Reason.GPS_UNRELIABLE,"specific cancellation");
        tracker.update(Kind.RETURN,true,false,.5,4000,Reason.NONE);
        check(tracker.snapshot().get(0).id()!=old,"reset starts new evidence id");
        MotionDetector motion=new MotionDetector();
        for(int i=0;i<=10;i++){
            boolean confirmed=motion.accept(new MotionDetector.Fix(i*1000,i*2,0,3,2,true,0,false));
            check(confirmed==(i==10),"motion threshold unchanged");
            check(Math.abs(motion.progress().get(0).value()-i/10.0)<.0001,"real motion evidence");
        }
        motion.accept(new MotionDetector.Fix(11000,20,0,3,0,true,0,false));
        check(motion.progress().get(0).reason()==Reason.STOPPED,"specific stop reason");
        System.out.println("PASS: progress, cancellation, epochs, priorities, immutable outcomes, bounded state");
    }
}
