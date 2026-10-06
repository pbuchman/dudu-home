package com.pbuchman.duduhome.routebook;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Protocol {
    public record Result(String eventId,String status,String code) { }
    public static byte[] envelope(String device,String lane,List<Point> points) {
        if(!Point.uuid(device)||points.isEmpty()||points.size()>200||(!lane.equals("latest")&&!lane.equals("backfill"))
                || (lane.equals("latest")&&points.size()!=1)) throw new IllegalArgumentException();
        StringJoiner j=new StringJoiner(","); for(Point p:points) j.add(p.json());
        byte[] bytes=("{\"version\":1,\"device_id\":"+Json.quote(device)+",\"lane\":"+Json.quote(lane)+",\"points\":["+j+"]}").getBytes(StandardCharsets.UTF_8);
        if(bytes.length>262144) throw new IllegalArgumentException(); return bytes;
    }
    public static List<Result> ack(String text,String device,List<Point> sent) {
        Map<String,Object> m=Json.object(Json.parse(text),"version","device_id","received_at","revision","results");
        if(Json.integer(m.get("version"))!=1 || !device.equals(m.get("device_id"))
            || !Point.timestamp(Json.string(m.get("received_at"))) || Json.integer(m.get("revision"))<0
            || !(m.get("results") instanceof List<?>)) throw new IllegalArgumentException();
        List<?> rows=(List<?>)m.get("results"); if(rows.size()!=sent.size()) throw new IllegalArgumentException();
        Result[] results=new Result[sent.size()];
        for(Object row:rows) {
            Map<String,Object> r=Json.object(row,"index","event_id","status","code");
            long n=Json.integer(r.get("index")); if(n<0||n>=sent.size()||results[(int)n]!=null) throw new IllegalArgumentException();
            int i=(int)n; if(!sent.get(i).eventId.equals(r.get("event_id"))) throw new IllegalArgumentException();
            String status=Json.string(r.get("status")); Object code=r.get("code");
            if(status.equals("accepted")||status.equals("duplicate")) { if(code!=null) throw new IllegalArgumentException(); }
            else if(status.equals("rejected")) { if(!"invalid_point".equals(code)&&!"event_conflict".equals(code)) throw new IllegalArgumentException(); }
            else throw new IllegalArgumentException();
            results[i]=new Result(sent.get(i).eventId,status,(String)code);
        }
        return Arrays.asList(results);
    }
    public static long retryMillis(int failures,long retryAfterSeconds,double jitter) {
        long base=Math.min(120000,2000L << Math.min(6,Math.max(0,failures-1)));
        return Math.max((long)(base*(1+Math.max(0,Math.min(.2,jitter)))), Math.min(900,Math.max(0,retryAfterSeconds))*1000);
    }
}
