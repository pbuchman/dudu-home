package com.pbuchman.duduhome.routebook;

import java.util.*;
import java.nio.charset.StandardCharsets;

public final class PowerProtocolChecks {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void reject(Runnable r){try{r.run();throw new AssertionError("invalid ACK accepted");}catch(IllegalArgumentException expected){}}
    private static String json(Object value){
        if(value==null)return "null";if(value instanceof String s)return Json.quote(s);
        if(value instanceof Map<?,?> map){StringJoiner rows=new StringJoiner(",","{","}");for(var entry:map.entrySet())rows.add(Json.quote((String)entry.getKey())+":"+json(entry.getValue()));return rows.toString();}
        if(value instanceof List<?> list){StringJoiner rows=new StringJoiner(",","[","]");for(Object row:list)rows.add(json(row));return rows.toString();}
        return value.toString();
    }
    public static void main(String[] args)throws Exception{
        String id=new UUID(0,1).toString(),epoch=new UUID(0,2).toString(),boot=new UUID(0,3).toString(),device=new UUID(0,4).toString();
        PowerEvent event=new PowerEvent(id,epoch,0,"2026-10-16T10:00:00.000Z",1000,boot,"snapshot",0,1,"diagnostic-v1","synthetic-build");
        check(PowerEvent.parse(event.json()).equals(event),"immutable event roundtrip");
        String body=new String(PowerProtocol.envelope(device,"latest",List.of(event)),StandardCharsets.UTF_8);
        check(body.contains("\"version\":2")&&body.contains("\"events\"")&&!body.contains("\"points\""),"power envelope isolated from v1");
        String ack="{\"version\":2,\"device_id\":\""+device+"\",\"received_at\":\"2026-10-16T10:00:01.000Z\",\"revision\":1,\"results\":[{\"index\":0,\"event_id\":\""+id+"\",\"status\":\"accepted\",\"code\":null}]}";
        check(PowerProtocol.ack(ack,device,List.of(event)).size()==1,"validated ACK");
        check(PowerProtocol.ack(ack.replace("accepted","rejected").replace("\"code\":null","\"code\":\"invalid_observation\""),device,List.of(event)).get(0).status().equals("rejected"),"per-event rejection");
        reject(()->PowerProtocol.ack(ack.replace("\"version\":2","\"version\":1"),device,List.of(event)));
        reject(()->PowerProtocol.ack(ack.replace(id,epoch),device,List.of(event)));
        reject(()->PowerProtocol.ack(ack.replace("\"index\":0","\"index\":1"),device,List.of(event)));
        reject(()->PowerProtocol.ack(ack.replace("\"code\":null","\"code\":\"invalid_point\""),device,List.of(event)));
        reject(()->PowerProtocol.ack(ack.replace(device,boot),device,List.of(event)));
        reject(()->PowerEvent.parse(event.json().replace("\"sequence\":0","\"sequence\":-1")));
        if(args.length>0&&java.nio.file.Files.exists(java.nio.file.Path.of(args[0]))){
            Map<?,?> fixture=(Map<?,?>)Json.parse(java.nio.file.Files.readString(java.nio.file.Path.of(args[0])));
            Map<?,?> request=(Map<?,?>)fixture.get("request");List<PowerEvent> events=new ArrayList<>();
            for(Object row:(List<?>)request.get("events"))events.add(PowerEvent.parse(json(row)));
            String fixtureDevice=(String)request.get("device_id");
            check(Json.parse(new String(PowerProtocol.envelope(fixtureDevice,(String)request.get("lane"),events),StandardCharsets.UTF_8)).equals(request),"normative backend v2 request fixture");
            check(PowerProtocol.ack(json(fixture.get("ack")),fixtureDevice,events).size()==events.size(),"normative backend v2 ACK fixture");
            System.out.println("PASS: shared backend/Android v2 normative request and ACK fixture");
        }
        System.out.println("PASS: independent v2 envelope, immutable raw events and strict ACK identity/index/version/rejection validation");
    }
}
