package com.pbuchman.duduhome.routebook;

import java.nio.file.*;
import java.util.*;

public final class RoutebookChecks {
    static int assertions;
    static void check(boolean ok) { assertions++; if(!ok) throw new AssertionError("assertion "+assertions); }
    static void invalid(Runnable r) { try {r.run();} catch(RuntimeException expected) {assertions++;return;} throw new AssertionError("accepted invalid input"); }
    static CollectorFilter.Fix fix(long wall,long mono) {return new CollectorFilter.Fix(wall,mono,0,0,5,0.0,false);}
    public static void main(String[] args) throws Exception {
        long wall=1791187200000L;
        CollectorFilter f=new CollectorFilter(0,CollectorFilter.DEFAULTS);
        Point a=f.accept(fix(wall,1000),1000,wall); check(a!=null);
        check(f.accept(fix(wall,1000),1000,wall)==null);
        check(f.accept(fix(wall+1000,2000),2000,wall+1000)==null);
        Point b=f.accept(fix(wall+5000,6000),6000,wall+5000);check(b!=null&&b.segmentId.equals(a.segmentId));
        Point c=f.accept(fix(wall+40000,41000),41000,wall+40000);check(c!=null&&!c.segmentId.equals(b.segmentId));
        f.breakSegment();check(f.accept(fix(wall+40000,41000),41000,wall+40000)==null);
        check(f.accept(fix(wall+41000,42000),42000,wall+41000)==null);
        check(f.accept(fix(wall-1,46000),46000,wall+45000)==null);
        Point d=f.accept(fix(wall+45000,47000),47000,wall+46000);check(d!=null&&!d.segmentId.equals(c.segmentId));
        check(f.accept(new CollectorFilter.Fix(wall+50000,52000,0,0,51,0.0,false),52000,wall+50000)==null);
        check(f.accept(new CollectorFilter.Fix(wall+55000,57000,0,0,5,0.0,true),57000,wall+55000)==null);
        check(f.accept(fix(wall+60000,62000),68000,wall+60000)==null);
        check(f.accept(new CollectorFilter.Fix(wall+65000,67000,Double.NaN,0,5,null,false),67000,wall+65000)==null);
        check(f.accept(fix(wall+400000,72000),72000,wall+70000)==null);
        CollectorFilter restarted=new CollectorFilter(wall+60000,CollectorFilter.DEFAULTS);
        check(restarted.accept(fix(wall+55000,1000),1000,wall+65000)==null);
        check(restarted.accept(fix(wall+65000,6000),6000,wall+65000)!=null);
        discardedFixesDoNotAdvanceTime(wall);
        checkCallbackAgeAndClockRecovery(wall);
        DeliverySelection select=new DeliverySelection();check(select.latest(c));select.attempted(c);check(!select.latest(a));check(!select.latest(c));check(select.latest(d));
        check(Protocol.retryMillis(1,0,0)==2000);check(Protocol.retryMillis(2,0,0)==4000);
        check(Protocol.retryMillis(20,0,.2)==144000);check(Protocol.retryMillis(1,2000,0)==900000);
        for(String bad:List.of("{\"a\":1,\"a\":2}","{a:1}","[1,]","NaN","01","1e999","\"\\ud800\"","{\"a\":1}garbage")) invalid(()->Json.parse(bad));
        check(Point.timestamp("2026-10-05T08:00:00.000Z"));check(!Point.timestamp("2026-02-30T08:00:00.000Z"));
        invalid(()->Protocol.envelope("bad","latest",List.of(a)));invalid(()->Protocol.envelope(UUID.randomUUID().toString(),"latest",List.of(a,b)));
        if(args.length>0) {
            Map<?,?> fixtures=(Map<?,?>)Json.parse(Files.readString(Path.of(args[0])));
            for(Object scenario:(List<?>)fixtures.get("cases")) {
                Map<?,?> sc=(Map<?,?>)scenario;
                for(Object delivery:(List<?>)sc.get("deliveries")) {
                    Map<?,?> de=(Map<?,?>)delivery, request=(Map<?,?>)de.get("request"),expected=(Map<?,?>)de.get("expected");
                    String device=(String)request.get("device_id");List<Point> sent=new ArrayList<>();
                    for(Object point:(List<?>)request.get("points")) {Map<?,?> pt=(Map<?,?>)point;
                        // Missing/malformed points are server-only negatives, never emitted by the collector.
                        if(!(pt.get("event_id") instanceof String)||!Point.uuid((String)pt.get("event_id"))) continue;
                        sent.add(new Point((String)pt.get("event_id"),(String)pt.get("segment_id"),(String)pt.get("measured_at"),0,0,5,null));
                    }
                    List<?> results=(List<?>)expected.get("results"); if(results==null||results.size()!=sent.size()) continue;
                    StringJoiner rows=new StringJoiner(",");
                    for(Object row:results) { Map<?,?> rr=(Map<?,?>)row;
                        rows.add("{\"index\":"+rr.get("index")+",\"event_id\":"+Json.quote((String)rr.get("event_id"))+",\"status\":"+Json.quote((String)rr.get("status"))+",\"code\":"+(rr.get("code")==null?"null":Json.quote((String)rr.get("code")))+"}"); }
                    String ack="{\"version\":1,\"device_id\":"+Json.quote(device)+",\"received_at\":"+Json.quote((String)de.get("received_at"))+",\"revision\":"+expected.get("revision")+",\"results\":["+rows+"]}";
                    check(Protocol.ack(ack,device,sent).size()==sent.size());
                    invalid(()->Protocol.ack(ack.replace("\"version\":1","\"version\":2"),device,sent));
                    invalid(()->Protocol.ack(ack,UUID.randomUUID().toString(),sent));
                    invalid(()->Protocol.ack(ack.replace("\"index\":0","\"index\":999"),device,sent));
                }
            }
        }
        System.out.println("PASS: Routebook pure policy/protocol "+assertions+" assertions");
    }

    private static void discardedFixesDoNotAdvanceTime(long wall) {
        CollectorFilter filter=new CollectorFilter(0,CollectorFilter.DEFAULTS);
        Point first=filter.accept(fix(wall,1000),1000,wall);
        check(first!=null);
        check(filter.accept(fix(wall+6000,3600000),6000,wall+6000)==null);
        check(filter.outcome()==CollectorFilter.Outcome.FUTURE_MONOTONIC);
        var mock=new CollectorFilter.Fix(wall+300000,7000,0,0,5,null,true);
        check(filter.accept(mock,7000,wall+7000)==null);
        check(filter.outcome()==CollectorFilter.Outcome.MOCK);
        var missingAccuracy=new CollectorFilter.Fix(wall+290000,8000,0,0,Double.NaN,null,false);
        check(filter.accept(missingAccuracy,8000,wall+8000)==null);
        check(filter.outcome()==CollectorFilter.Outcome.MISSING_DATA);
        var poorAccuracy=new CollectorFilter.Fix(wall+290000,9000,0,0,55,null,false);
        check(filter.accept(poorAccuracy,9000,wall+9000)==null);
        check(filter.outcome()==CollectorFilter.Outcome.ACCURACY);
        long validWall=wall+5000;
        Point next=filter.accept(fix(validWall,6000),9000,validWall+3000);
        check(next!=null&&next.segmentId.equals(first.segmentId));
        check(filter.accept(fix(validWall,6000),9000,validWall+3000)==null);
        check(filter.outcome()==CollectorFilter.Outcome.DUPLICATE);
    }

    private static void checkCallbackAgeAndClockRecovery(long wall) {
        CollectorFilter filter=new CollectorFilter(wall,CollectorFilter.DEFAULTS);
        Point first=filter.accept(fix(wall,1000),6000,wall+5000);
        check(first!=null);
        check(filter.accept(fix(wall+5000,6000),11001,wall+10001)==null);
        check(filter.outcome()==CollectorFilter.Outcome.STALE);
        check(filter.accept(fix(wall+1000,7000),7000,wall+4000)==null);
        check(filter.outcome()==CollectorFilter.Outcome.CLOCK_REGRESSION);
        Point recovered=filter.accept(fix(wall+8000,9000),9000,wall+8000);
        check(recovered!=null&&!recovered.segmentId.equals(first.segmentId));
        var restarted=new CollectorFilter(wall+8000,CollectorFilter.DEFAULTS);
        check(restarted.accept(fix(wall+7000,1000),1000,wall+9000)==null);
        check(restarted.outcome()==CollectorFilter.Outcome.CLOCK_REGRESSION);
        check(restarted.accept(fix(wall+8000,2000),2000,wall+9000)!=null);
    }
}
