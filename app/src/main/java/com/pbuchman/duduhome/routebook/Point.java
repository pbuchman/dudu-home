package com.pbuchman.duduhome.routebook;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

public final class Point {
    public final String eventId, segmentId, measuredAt;
    public final double lat, lon, accuracy;
    public final Double speed;
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    public Point(String eventId,String segmentId,String measuredAt,double lat,double lon,double accuracy,Double speed) {
        this.eventId=eventId; this.segmentId=segmentId; this.measuredAt=measuredAt;
        this.lat=lat; this.lon=lon; this.accuracy=accuracy; this.speed=speed;
    }
    public static boolean uuid(String s) {
        if(s==null || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return false;
        return UUID.fromString(s).toString().equals(s);
    }
    public static boolean timestamp(String s) {
        try { return s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")
                && TIME.format(Instant.parse(s)).equals(s) && Instant.parse(s).toEpochMilli()>=946684800000L; }
        catch(RuntimeException e) { return false; }
    }
    public String json() {
        return "{\"event_id\":"+Json.quote(eventId)+",\"segment_id\":"+Json.quote(segmentId)
            +",\"measured_at\":"+Json.quote(measuredAt)+",\"lat\":"+lat+",\"lon\":"+lon
            +",\"accuracy_m\":"+accuracy+",\"speed_mps\":"+(speed==null?"null":speed)+"}";
    }
}
