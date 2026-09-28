package com.pbuchman.duduhome.trip;

public record PlaceResult(String locality, String street, String source, boolean certain, boolean covered) {
    public static PlaceResult unknown(boolean covered) { return new PlaceResult("", "", "offline", false, covered); }
    public boolean complete() { return certain && !locality.isEmpty() && !street.isEmpty(); }
    public String title() { return locality.isEmpty() ? "Nieznana miejscowość" : locality; }
    public String subtitle() { return street.isEmpty() ? "Nieznana ulica" : street; }
}
