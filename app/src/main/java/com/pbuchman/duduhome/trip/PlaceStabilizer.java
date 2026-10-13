package com.pbuchman.duduhome.trip;

/** Independent field confirmation; two sources for one GPS fix are still one observation. */
final class PlaceStabilizer {
    private final Field locality=new Field(), street=new Field();
    private PlaceResult latest=PlaceResult.unknown(false);
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
        return new PlaceResult(locality.displayed,street.displayed,locality.displayedQuality,street.displayedQuality,
                locality.displayedSource,street.displayedSource,result.covered());
    }
    PlaceResult latest() { return latest; }
}
