package com.pbuchman.duduhome.trip;

/** Locality and street are independent evidence: a known city does not imply a known road. */
public record PlaceResult(String locality, String street, Quality localityQuality, Quality streetQuality,
        String localitySource, String streetSource, boolean covered) {
    public enum Quality { UNKNOWN, AMBIGUOUS, NEARBY, ADDRESS, CONTAINMENT, MATCHED, LAST_KNOWN }
    // Retained for renderer fixtures and schema-1 callers.
    public PlaceResult(String locality, String street, String source, boolean certain, boolean covered) {
        this(locality, street, locality.isEmpty() ? Quality.UNKNOWN : certain ? Quality.ADDRESS : Quality.NEARBY,
                street.isEmpty() ? Quality.UNKNOWN : Quality.MATCHED, source, source, covered);
    }
    public static PlaceResult unknown(boolean covered) { return new PlaceResult("", "", "offline", false, covered); }
    public boolean certain() { return localityQuality == Quality.ADDRESS || localityQuality == Quality.CONTAINMENT; }
    public String source() { return localitySource.startsWith("photon") || streetSource.startsWith("photon") ? "photon" : "offline"; }
    public boolean complete() { return certain() && !locality.isEmpty() && streetQuality == Quality.MATCHED && !street.isEmpty(); }
    public String title() { return locality.isEmpty() ? "Nieznana miejscowość" : locality; }
    public String subtitle() { return street.isEmpty() ? "Nieznana ulica" : streetQuality == Quality.LAST_KNOWN ? "Ostatnio: " + street : street; }
    /** Immutable snapshot readers enforce the deadline even if its publisher is delayed. */
    public PlaceResult atStreetDeadline(long deadline,long now) {
        if (streetQuality!=Quality.LAST_KNOWN || (deadline>0 && now<deadline)) return this;
        return new PlaceResult(locality,"",localityQuality,Quality.UNKNOWN,localitySource,"offline",covered);
    }

    /** Only genuine online components may survive as short-lived online cache evidence. */
    public PlaceResult onlineEvidence() {
        boolean city=localitySource.startsWith("photon"), road=streetSource.startsWith("photon");
        return new PlaceResult(city ? locality : "",road ? street : "",
                city ? localityQuality : Quality.UNKNOWN,road ? streetQuality : Quality.UNKNOWN,
                city ? localitySource : "offline",road ? streetSource : "offline",city || road);
    }

    public static PlaceResult enrich(PlaceResult local, PlaceResult online) {
        if (online == null) return local;
        boolean keepLocal = local.certain() || local.localityQuality == Quality.AMBIGUOUS;
        boolean useOnlineCity = !keepLocal && !online.locality.isEmpty()
                && (online.certain() || local.locality.isEmpty());
        // A street belonging to a different certain locality is incoherent with a local boundary.
        boolean conflict = local.certain() && online.certain() && !online.locality.isEmpty()
                && !local.locality.equals(online.locality);
        boolean useOnlineStreet = local.street.isEmpty() && local.streetQuality != Quality.AMBIGUOUS
                && !online.street.isEmpty() && !conflict;
        return new PlaceResult(useOnlineCity ? online.locality : local.locality,
                useOnlineStreet ? online.street : local.street,
                useOnlineCity ? online.localityQuality : local.localityQuality,
                useOnlineStreet ? online.streetQuality : local.streetQuality,
                useOnlineCity ? online.localitySource : local.localitySource,
                useOnlineStreet ? online.streetSource : local.streetSource, local.covered || online.covered);
    }
}
