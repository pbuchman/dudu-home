package com.pbuchman.duduhome.routebook;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** v2 never changes the v1 GPS envelope or its ACK validator. */
public final class PowerProtocol {
    public static byte[] envelope(String device,String lane,List<PowerEvent> events) {
        if(!Point.uuid(device)||events.isEmpty()||events.size()>200||!Set.of("latest","backfill").contains(lane)
                ||lane.equals("latest")&&events.size()!=1)throw new IllegalArgumentException();
        StringJoiner rows=new StringJoiner(","); for(PowerEvent e:events)rows.add(e.json());
        byte[] body=("{\"version\":2,\"device_id\":"+Json.quote(device)+",\"lane\":"+Json.quote(lane)+",\"events\":["+rows+"]}").getBytes(StandardCharsets.UTF_8);
        if(body.length>262144)throw new IllegalArgumentException(); return body;
    }
    public static List<Protocol.Result> ack(String text,String device,List<PowerEvent> sent) {
        Map<String,Object> m=Json.object(Json.parse(text),"version","device_id","received_at","revision","results");
        if(Json.integer(m.get("version"))!=2||!device.equals(m.get("device_id"))||!Point.timestamp(Json.string(m.get("received_at")))
                ||Json.integer(m.get("revision"))<0||!(m.get("results") instanceof List<?> rows)||rows.size()!=sent.size())throw new IllegalArgumentException();
        Protocol.Result[] results=new Protocol.Result[sent.size()];
        for(Object row:(List<?>)m.get("results")) {
            Map<String,Object> r=Json.object(row,"index","event_id","status","code"); long n=Json.integer(r.get("index"));
            if(n<0||n>=sent.size()||results[(int)n]!=null||!sent.get((int)n).eventId().equals(r.get("event_id")))throw new IllegalArgumentException();
            String status=Json.string(r.get("status")); Object code=r.get("code");
            if(status.equals("accepted")||status.equals("duplicate")) {if(code!=null)throw new IllegalArgumentException();}
            else if(status.equals("rejected")) {if(!Set.of("invalid_observation","event_conflict").contains(code))throw new IllegalArgumentException();}
            else throw new IllegalArgumentException();
            results[(int)n]=new Protocol.Result((String)r.get("event_id"),status,(String)code);
        }
        return Arrays.asList(results);
    }
}
