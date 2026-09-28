package com.pbuchman.duduhome.trip;

/** Immutable GPS input; elapsed time is monotonic, coordinates never enter diagnostics. */
public record TripFix(long elapsed, long received, double latitude, double longitude,
        double accuracy, double speed, boolean hasSpeed, double bearing, boolean hasBearing, boolean mock) {
    public boolean valid() {
        return !mock && elapsed > 0 && received >= elapsed && received - elapsed <= TripDistance.MAX_AGE_MS
                && Double.isFinite(latitude) && Math.abs(latitude) <= 90
                && Double.isFinite(longitude) && Math.abs(longitude) <= 180
                && Double.isFinite(accuracy) && accuracy >= 0 && accuracy <= TripDistance.MAX_ACCURACY_M;
    }
    public double distance(TripFix other) {
        double a = Math.toRadians(latitude), b = Math.toRadians(other.latitude);
        double y = Math.sin((b - a) / 2), x = Math.sin(Math.toRadians(other.longitude - longitude) / 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, y*y + Math.cos(a)*Math.cos(b)*x*x)));
    }
}
