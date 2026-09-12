import com.pbuchman.duduhome.location.*;
import java.nio.file.*;
import java.util.*;

/** Compare decisions and flags to pre-UI source, including optional private replays. */
public final class DetectorEquivalence {
    static HomeDetector current;
    static BaselineHomeDetector baseline;
    static long count;
    static void geometry(double[] v) {
        current=new HomeDetector(new HomeDetector.Geometry(new HomeDetector.Point(v[0],v[1]),
                new HomeDetector.Point(v[2],v[3]),new HomeDetector.Point(v[4],v[5]),new HomeDetector.Point(v[6],v[7])),0);
        baseline=new BaselineHomeDetector(new BaselineHomeDetector.Geometry(new BaselineHomeDetector.Point(v[0],v[1]),
                new BaselineHomeDetector.Point(v[2],v[3]),new BaselineHomeDetector.Point(v[4],v[5]),new BaselineHomeDetector.Point(v[6],v[7])),0);
    }
    static void fix(long t,double x,double y,double accuracy,double speed,long age,boolean mock) {
        var a=current.accept(new HomeDetector.Fix(t,new HomeDetector.Point(x,y),accuracy,speed,age,mock));
        var b=baseline.accept(new BaselineHomeDetector.Fix(t,new BaselineHomeDetector.Point(x,y),accuracy,speed,age,mock));
        current.progress();current.progress();
        if(!a.equals(b)||current.flags()!=baseline.flags())throw new AssertionError("decision/flags divergence at sample "+count);
        count++;
    }
    public static void main(String[] args) throws Exception {
        geometry(new double[]{0,0,0,-70,100,100,400,100});
        Random random=new Random(42);
        for(int i=0;i<20000;i++){
            if(i%500==0){current.clearEvidence();baseline.clearEvidence();}
            fix(i*1000L,random.nextInt(600)-100,random.nextInt(400)-200,i%13==0?30:3,i%7==0?0:2,i%11==0?5000:0,i%17==0);
        }
        MotionDetector motion=new MotionDetector();BaselineMotionDetector old=new BaselineMotionDetector();
        for(int i=0;i<20000;i++){
            if(i%500==0){motion.clear();old.clear();}
            double speed=i%31==0?0:2;
            boolean a=motion.accept(new MotionDetector.Fix(i*1000L,i*2,0,3,speed,true,0,false));
            boolean b=old.accept(new BaselineMotionDetector.Fix(i*1000L,i*2,0,3,speed,true,0,false));
            motion.progress();motion.progress();
            if(a!=b)throw new AssertionError("motion divergence");
        }
        int files=0;
        for(String file:args){
            List<String> lines=Files.readAllLines(Path.of(file));
            geometry(Arrays.stream(lines.get(0).split("\\t")).mapToDouble(Double::parseDouble).toArray());
            for(String line:lines.subList(1,lines.size())){
                String[] f=line.split("\\t");
                fix(Long.parseLong(f[0]),Double.parseDouble(f[1]),Double.parseDouble(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4]),Long.parseLong(f[5]),false);
            }
            files++;
        }
        System.out.println("PASS: identical events, sample times and flags; "+count+" home samples, 20000 motion samples, "+files+" replay files");
    }
}
