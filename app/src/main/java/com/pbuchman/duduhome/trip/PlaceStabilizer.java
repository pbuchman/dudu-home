package com.pbuchman.duduhome.trip;

/** Independent field confirmation; two sources for one GPS fix are still one observation. */
final class PlaceStabilizer {
    private final Field locality=new Field(), street=new Field();
    private PlaceResult latest=PlaceResult.unknown(false);
    private PlaceResult shown=latest, lastStreet;
    private TripFix lastStreetFix;
    static final long STREET_GRACE_MS=3000;
    static final double STREET_GRACE_METERS=40;
    private static final class Field {
        String candidate="", source="offline", displayed="", displayedSource="offline";
        PlaceResult.Quality quality=PlaceResult.Quality.UNKNOWN, displayedQuality=PlaceResult.Quality.UNKNOWN;
        long lastFix=-1;
        int confirmations;
        void offer(String text,PlaceResult.Quality evidence,String provenance,long fix) {
            if (!candidate.equals(text) || quality!=evidence) {
                candidate=text;quality=evidence;source=provenance;confirmations=1;lastFix=fix;
            } else {
                if (lastFix!=fix) { confirmations++;lastFix=fix; }
                source=provenance;
            }
            // During a disagreement, stop displaying the former place as current.
            if (!displayed.equals(text) || displayedQuality!=evidence) {
                displayed="";displayedQuality=PlaceResult.Quality.UNKNOWN;
            }
            if (confirmations>=2 || text.isEmpty()) {
                displayed=text;displayedQuality=evidence;displayedSource=source;
            }
        }
    }
    PlaceResult offer(PlaceResult result,long fix) {
        latest=result;
        locality.offer(result.locality(),result.localityQuality(),result.localitySource(),fix);
        street.offer(result.street(),result.streetQuality(),result.streetSource(),fix);
        shown=new PlaceResult(locality.displayed,street.displayed,locality.displayedQuality,street.displayedQuality,
                locality.displayedSource,street.displayedSource,result.covered());
        return shown;
    }
    PlaceResult offer(PlaceResult result,TripFix fix) {
        PlaceResult previous=lastStreet;
        TripFix previousFix=lastStreetFix;
        PlaceResult current=offer(result,fix.elapsed());
        boolean contradicts=previous!=null && ((!result.street().isEmpty() && !result.street().equals(previous.street()))
                || (result.certain() && !result.locality().equals(previous.locality())));
        if (!result.covered() || contradicts || !fix.valid()) { lastStreet=null;lastStreetFix=null; }
        if (!current.street().isEmpty() && current.streetQuality()==PlaceResult.Quality.MATCHED) {
            lastStreet=current;lastStreetFix=fix;
        } else if (lastStreet!=null && previousFix!=null && recent(fix.elapsed(),fix)) {
            // Preserve the readable name, but never present uncertain evidence as a current match.
            shown=new PlaceResult(current.locality(),previous.street(),current.localityQuality(),PlaceResult.Quality.LAST_KNOWN,
                    current.localitySource(),previous.streetSource(),current.covered());
        }
        return shown;
    }
    private boolean recent(long now,TripFix fix) {
        return lastStreetFix!=null && now>=lastStreetFix.elapsed() && now-lastStreetFix.elapsed()<=STREET_GRACE_MS
                && fix!=null && fix.valid() && lastStreetFix.distance(fix)<=STREET_GRACE_METERS;
    }
    PlaceResult current(long now,TripFix fix) {
        if (shown.streetQuality()==PlaceResult.Quality.LAST_KNOWN && !recent(now,fix))
            shown=shown.atStreetDeadline(0,now);
        shown=shown.atStreetDeadline(streetDeadline(),now);
        return shown;
    }
    long streetDeadline() {
        return shown.streetQuality()==PlaceResult.Quality.LAST_KNOWN && lastStreetFix!=null
                ? lastStreetFix.elapsed()+STREET_GRACE_MS : 0;
    }
    PlaceResult latest() { return latest; }
}
