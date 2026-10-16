package com.pbuchman.duduhome.diagnostics;

/** Emit changed states immediately; an unchanged ongoing blocker at most once per minute. */
public final class BlockCoalescer {
    private record Seen(String value,long elapsed) { }
    private final java.util.LinkedHashMap<Long,Seen> seen=new java.util.LinkedHashMap<>();
    public synchronized boolean emit(long id,String value,long elapsed) {
        Seen old=seen.get(id);
        if (old!=null && old.value.equals(value) && elapsed>=old.elapsed && elapsed-old.elapsed<60000) return false;
        seen.put(id,new Seen(value,elapsed));
        if (seen.size()>256) seen.remove(seen.keySet().iterator().next());
        return true;
    }
}
