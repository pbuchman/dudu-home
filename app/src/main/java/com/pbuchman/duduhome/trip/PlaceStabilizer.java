package com.pbuchman.duduhome.trip;

/** Raw resolver evidence and retained display are independent; one fix cannot confirm twice. */
final class PlaceStabilizer {
    private final Field locality=new Field();
    private PlaceResult latest=PlaceResult.unknown(false), shown=latest;
    private String candidate="", confirmed="", candidateSource="offline", confirmedSource="offline";
    private PlaceResult.Quality candidateQuality=PlaceResult.Quality.UNKNOWN, confirmedQuality=PlaceResult.Quality.UNKNOWN;
    private long candidateFix=-1;
    private int confirmations;
    private static final class Field {
        String candidate="", source="offline", displayed="", displayedSource="offline";
        PlaceResult.Quality quality=PlaceResult.Quality.UNKNOWN, displayedQuality=PlaceResult.Quality.UNKNOWN;
        long lastFix=-1;
        int confirmations;
        void offer(String text,PlaceResult.Quality evidence,String provenance,long fix) {
            if (!candidate.equals(text) || quality!=evidence) {
                candidate=text;quality=evidence;source=provenance;confirmations=1;lastFix=fix;
            } else {
                if (fix>lastFix) { confirmations++;lastFix=fix; }
                source=provenance;
            }
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
        boolean road=result.streetQuality()==PlaceResult.Quality.UNNAMED
                || result.streetQuality()==PlaceResult.Quality.MATCHED && !result.street().isEmpty();
        if (road) {
            if (!candidate.equals(result.street()) || candidateQuality!=result.streetQuality()) {
                candidate=result.street();candidateQuality=result.streetQuality();candidateFix=fix;confirmations=1;
            } else if (fix>candidateFix) { confirmations++;candidateFix=fix; }
            candidateSource=result.streetSource();
            if (confirmations>=2) {
                confirmed=candidate;confirmedQuality=candidateQuality;confirmedSource=candidateSource;
            }
        } else {
            candidate="";candidateQuality=PlaceResult.Quality.UNKNOWN;candidateFix=-1;confirmations=0;
        }
        boolean current=road && confirmed.equals(result.street()) && confirmedQuality==result.streetQuality() && confirmations>=2;
        PlaceResult.Quality displayQuality=confirmedQuality==PlaceResult.Quality.MATCHED && !current
                ? PlaceResult.Quality.LAST_KNOWN : confirmedQuality;
        shown=new PlaceResult(locality.displayed,confirmed,locality.displayedQuality,displayQuality,
                locality.displayedSource,confirmedSource,result.covered());
        return shown;
    }
    PlaceResult offer(PlaceResult result,TripFix fix) {
        // Invalid/noisy observations cannot confirm a road or erase the retained display.
        return fix.valid() ? offer(result,fix.elapsed()) : shown;
    }
    PlaceResult current() { return shown; }
    PlaceResult latest() { return latest; }
}
