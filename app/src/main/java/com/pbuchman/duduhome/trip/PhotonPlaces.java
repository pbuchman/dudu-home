package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.*;

/** Optional keyless public endpoint. All limits are ours, not a provider entitlement. */
final class PhotonPlaces {
    interface Opener { HttpURLConnection open(URL url) throws IOException; }
    private final SharedPreferences quota;
    private final Opener opener;
    private TripFix requested, cachedFix;
    private PlaceResult cached;
    private long lastRequest, blockedUntil;
    private int failures;
    PhotonPlaces(Context context) { this(context, url -> (HttpURLConnection)url.openConnection()); }
    PhotonPlaces(Context context, Opener opener) { quota = context.getSharedPreferences("trip_photon_quota", 0); this.opener = opener; }
    PlaceResult resolve(TripFix fix) {
        long now = SystemClock.elapsedRealtime();
        if (cached != null && cachedFix.distance(fix) < 40) return cached;
        if (now < blockedUntil || (requested != null && (now-lastRequest < 30000 || requested.distance(fix)<100))) return null;
        long wall=System.currentTimeMillis();
        if(wall < quota.getLong("retry_until",0)) return null;
        long lastWall=quota.getLong("last_attempt",0);
        if(lastWall>0 && (wall<lastWall || wall-lastWall<30000)) return null;
        long day = wall/86400000;
        int used = quota.getLong("day", -1)==day ? quota.getInt("used",0) : 0;
        if (used>=300 || !quota.edit().putLong("day",day).putInt("used",used+1).putLong("last_attempt",wall).commit()) return null;
        requested=fix; lastRequest=now;
        HttpURLConnection connection=null;
        try {
            URL url=new URL(String.format(Locale.ROOT,"https://photon.komoot.io/reverse?lat=%.6f&lon=%.6f&lang=default",fix.latitude(),fix.longitude()));
            connection=opener.open(url);
            connection.setConnectTimeout(3000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","DuduHome/1.2 (https://github.com/pbuchman/dudu-home)");
            int code=connection.getResponseCode();
            if(code!=200) {
                long retry=60000;
                if(code==429) {
                    String header=connection.getHeaderField("Retry-After");
                    try { retry=Math.max(retry,Math.multiplyExact(Long.parseLong(header),1000)); }
                    catch(Exception ignored) {
                        try { retry=Math.max(retry,java.time.ZonedDateTime.parse(header,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-wall); }
                        catch(Exception invalid) { }
                    }
                }
                long delay=Math.max(retry,60000L << Math.min(6,failures++));
                blockedUntil=now+Math.min(Long.MAX_VALUE-now,delay);
                quota.edit().putLong("retry_until",wall+Math.min(Long.MAX_VALUE-wall,delay)).commit();
                return null;
            }
            byte[] bytes;
            try(InputStream in=connection.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096]; int n;
                while((n=in.read(buffer))!=-1) { if(out.size()+n>65536) throw new IOException("Response too large"); out.write(buffer,0,n); }
                bytes=out.toByteArray();
            }
            PlaceResult result=parse(new JSONObject(new String(bytes,StandardCharsets.UTF_8)),fix);
            failures=0;
            if(result!=null) { cached=result; cachedFix=fix; }
            return result;
        } catch(Exception unavailable) { blockedUntil=now+(60000L << Math.min(6,failures++)); return null; }
        finally { if(connection!=null) connection.disconnect(); }
    }
    static PlaceResult parse(JSONObject root, TripFix fix) throws Exception {
        JSONArray features=root.optJSONArray("features");
        if(features==null || features.length()==0) return null;
        JSONObject feature=features.getJSONObject(0), p=feature.getJSONObject("properties");
        JSONArray coords=feature.getJSONObject("geometry").getJSONArray("coordinates");
        TripFix returned=new TripFix(fix.elapsed(),fix.received(),coords.getDouble(1),coords.getDouble(0),0,0,false,0,false,false);
        if(!returned.valid() || fix.distance(returned)>100) return null;
        String city=clean(p.optString("city")), street=clean(p.optString("street"));
        if("highway".equals(p.optString("osm_key")) && street.isEmpty()) street=clean(p.optString("name"));
        boolean pointSettlement="place".equals(p.optString("osm_key")) && java.util.Set.of("city","town","village","hamlet").contains(p.optString("osm_value"));
        if(pointSettlement) { String name=clean(p.optString("name")); city=name.isEmpty() ? "" : "Okolice "+name; }
        if(city.isEmpty() && street.isEmpty()) return null;
        return new PlaceResult(city,street,"photon",!pointSettlement && !city.isEmpty(),true);
    }
    private static String clean(String value) {
        String text=value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return text.substring(0,Math.min(160,text.length()));
    }
}
