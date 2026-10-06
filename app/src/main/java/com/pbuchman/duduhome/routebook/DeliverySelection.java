package com.pbuchman.duduhome.routebook;

/** Upload-pass high-water tuple. Older pending points use bounded backfill after the newest ACK. */
public final class DeliverySelection {
    private Point attempted;
    public boolean latest(Point newest) {
        return attempted==null || newest.measuredAt.compareTo(attempted.measuredAt)>0
            || (newest.measuredAt.equals(attempted.measuredAt)&&newest.eventId.compareTo(attempted.eventId)>0);
    }
    public void attempted(Point point) { if(latest(point)) attempted=point; }
}
