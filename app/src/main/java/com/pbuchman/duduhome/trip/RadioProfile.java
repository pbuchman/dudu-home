package com.pbuchman.duduhome.trip;

import com.pbuchman.duduhome.routebook.Json;
import java.util.*;

/** Explicit private, firmware-bound evidence contract. Production ships no trusted mapping. */
public final class RadioProfile {
    public enum State { ON, OFF, UNKNOWN }
    public record Pair(int acc,int module1) { }
    private final String id,firmware;
    private final Map<Pair,State> pairs;
    private RadioProfile(String id,String firmware,Map<Pair,State> pairs){this.id=id;this.firmware=firmware;this.pairs=Map.copyOf(pairs);}
    public static RadioProfile diagnostic(String firmware){return new RadioProfile("diagnostic-v1",firmware,Map.of());}
    public String id(){return id;}
    public String firmware(){return firmware;}
    public boolean validated(){return !pairs.isEmpty();}
    public State interpret(Integer acc,Integer module1){return acc==null||module1==null?State.UNKNOWN:pairs.getOrDefault(new Pair(acc,module1),State.UNKNOWN);}
    public static RadioProfile parse(String text,String firmware){
        Map<String,Object> m=Json.object(Json.parse(text),"version","profile_id","firmware_id","validation_id","physically_verified","mappings");
        String id=Json.string(m.get("profile_id")), validation=Json.string(m.get("validation_id"));
        if(Json.integer(m.get("version"))!=1||!Boolean.TRUE.equals(m.get("physically_verified"))||!firmware.equals(m.get("firmware_id"))
                ||!id.matches("[A-Za-z0-9._:-]{1,80}")||id.equals("diagnostic-v1")||!validation.matches("[A-Za-z0-9._:-]{1,120}")
                ||!(m.get("mappings") instanceof List<?> rows)||rows.isEmpty()||rows.size()>16)throw new IllegalArgumentException();
        Map<Pair,State> pairs=new HashMap<>();
        for(Object row:(List<?>)m.get("mappings")){
            Map<String,Object> r=Json.object(row,"acc_state","module1_state","radio_state");
            long a=Json.integer(r.get("acc_state")),b=Json.integer(r.get("module1_state"));
            State state=State.valueOf(Json.string(r.get("radio_state")));
            if(a<Integer.MIN_VALUE||a>Integer.MAX_VALUE||b<Integer.MIN_VALUE||b>Integer.MAX_VALUE||state==State.UNKNOWN
                    ||pairs.put(new Pair((int)a,(int)b),state)!=null)throw new IllegalArgumentException();
        }
        return new RadioProfile(id,firmware,pairs);
    }
}
