package com.pbuchman.duduhome.diagnostics;

/** Stable schema-2 blocker bit positions; combinations preserve simultaneous gating causes. */
public final class QueueBlockers {
    public enum Cause { RESOURCE_CLEANUP, ACTIVE_OPERATION, UI_REQUEST_PENDING, PRESENTATION_BUSY,
        MENU_VISIBLE, GATE_PRECONDITION, PRIORITY, YANOSIK_GRACE }
    private long mask;
    public void add(String reason) {
        try { mask|=1L<<Cause.valueOf(reason).ordinal(); } catch(IllegalArgumentException ignored) { }
    }
    public long mask() { return mask; }
    public String reason() {
        for(Cause cause:Cause.values())if((mask&(1L<<cause.ordinal()))!=0)return cause.name();
        return "NONE";
    }
    public String state() { return mask==0 ? "READY" : "WAITING"; }
}
