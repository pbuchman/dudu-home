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
        returnProgress();
        System.out.println("PASS: progress, cancellation, epochs, priorities, immutable outcomes, bounded state");
    }
    static DetectionProgress returning(HomeDetector d) {
        return d.progress().stream().filter(p -> p.kind() == Kind.RETURN).findFirst().orElseThrow();
    }
    static void sample(HomeDetector d, long second, double x, double y, double speed) {
        d.accept(new HomeDetector.Fix(second * 1000, new HomeDetector.Point(x,y), 3,speed,0,false));
    }
    static void returnProgress() {
        var g = new HomeDetector.Geometry(new HomeDetector.Point(-250,-180),new HomeDetector.Point(-220,-150),
                new HomeDetector.Point(-180,0),new HomeDetector.Point(0,0));
        HomeDetector d = new HomeDetector(g,0);
        long t=0;
        sample(d,t++,0,290,5);sample(d,t++,0,280,5);
        var early=returning(d);
        check(early.stage()==Stage.APPROACHING_JUNCTION && early.value()<.05,"early near-empty banner");
        for(int y=260;y>=20;y-=20)sample(d,t++,0,y,5);
        var junction=returning(d);
        check(junction.id()==early.id() && junction.value()==.3,"continuous first stage");
        sample(d,t++,0,20,0);sample(d,t++,0,20,0);
        check(returning(d).value()==junction.value(),"traffic stop cannot fill");
        sample(d,t++,-20,0,5);sample(d,t++,-40,0,5);
        var inward=returning(d);
        check(inward.id()==early.id() && inward.stage()==Stage.APPROACHING_GATE && inward.value()>.3,"turn keeps same run");
        for(int x=-60;x>=-140;x-=20)sample(d,t++,x,0,5);
        check(returning(d).value()>.8 && returning(d).value()<1,"distance-derived inward fill");
        var events=d.accept(new HomeDetector.Fix(t++*1000,new HomeDetector.Point(-150,0),3,5,0,false));
        check(events.contains(HomeEvent.RETURN_APPROACH) && returning(d).value()==1,"unchanged event, complete fill");
        HomeDetector through=new HomeDetector(g,0);t=0;
        for(int y=280;y>=-60;y-=20)sample(through,t++,0,y,5);
        check(through.progress().stream().noneMatch(p->p.kind()==Kind.RETURN&&p.phase()==Phase.CANDIDATE),"through-road cancellation");
        HomeDetector gap=new HomeDetector(g,0);
        sample(gap,0,0,280,5);sample(gap,1,0,260,5);long old=returning(gap).id();
        gap.accept(new HomeDetector.Fix(2000,new HomeDetector.Point(0,240),30,5,0,false));
        check(returning(gap).reason()==Reason.GPS_UNRELIABLE,"bad GPS cancels early return");
        sample(gap,3,0,220,5);sample(gap,4,0,200,5);
        check(returning(gap).id()!=old,"recovered GPS uses new run");
        ProgressModel model=new ProgressModel();
        model.offer(0,Set.of(Kind.RETURN),List.of(returning(gap)),4000);
        check(model.visible(4000).stage()==Stage.APPROACHING_JUNCTION,"stage reaches model");
    }
}
