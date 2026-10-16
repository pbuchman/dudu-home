package com.pbuchman.duduhome.routebook;

import java.util.Map;
import java.util.Set;

/** Immutable raw observation. Interpretation is independently audited on each consumer. */
public record PowerEvent(String eventId, String observerEpoch, long sequence, String observedAt,
        long elapsedRealtimeMs, String bootId, String kind, Integer accState, Integer module1State,
        String interpretationProfile, String firmwareId) {
    public PowerEvent {
        if (!Point.uuid(eventId) || !Point.uuid(observerEpoch) || !Point.uuid(bootId)
                || sequence < 0 || sequence > 9007199254740991L || elapsedRealtimeMs < 0
                || elapsedRealtimeMs > 9007199254740991L || !Point.timestamp(observedAt)
                || !Set.of("snapshot", "transition", "observer_lost").contains(kind)
                || !interpretationProfile.matches("[A-Za-z0-9._:-]{1,80}")
                || !firmwareId.matches("[A-Za-z0-9._:-]{1,120}")) throw new IllegalArgumentException();
    }
    public String json() {
        return "{\"event_id\":"+Json.quote(eventId)+",\"observer_epoch\":"+Json.quote(observerEpoch)
                +",\"sequence\":"+sequence+",\"observed_at\":"+Json.quote(observedAt)
                +",\"elapsed_realtime_ms\":"+elapsedRealtimeMs+",\"boot_id\":"+Json.quote(bootId)
                +",\"kind\":"+Json.quote(kind)+",\"acc_state\":"+accState+",\"module1_state\":"+module1State
                +",\"interpretation_profile\":"+Json.quote(interpretationProfile)+",\"firmware_id\":"+Json.quote(firmwareId)+"}";
    }
    public static PowerEvent parse(String text) {
        Map<String,Object> m=Json.object(Json.parse(text),"event_id","observer_epoch","sequence","observed_at",
                "elapsed_realtime_ms","boot_id","kind","acc_state","module1_state","interpretation_profile","firmware_id");
        return new PowerEvent(Json.string(m.get("event_id")),Json.string(m.get("observer_epoch")),Json.integer(m.get("sequence")),
                Json.string(m.get("observed_at")),Json.integer(m.get("elapsed_realtime_ms")),Json.string(m.get("boot_id")),
                Json.string(m.get("kind")),raw(m.get("acc_state")),raw(m.get("module1_state")),
                Json.string(m.get("interpretation_profile")),Json.string(m.get("firmware_id")));
    }
    private static Integer raw(Object value) { if(value==null)return null; long n=Json.integer(value); if(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw new IllegalArgumentException(); return (int)n; }
}
