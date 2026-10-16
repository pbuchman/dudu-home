package com.pbuchman.duduhome.diagnostics;

/** Bounded private presentation transition memory; this class never writes a generic category. */
public final class PresentationCoalescer {
    private record State(boolean visible,String title,String subtitle,long generation) { }
    private final java.util.LinkedHashMap<String,State> surfaces=new java.util.LinkedHashMap<>();
    public synchronized boolean changed(String surface,boolean visible,String title,String subtitle,long generation) {
        State next=new State(visible,title,subtitle,generation),previous=surfaces.put(surface,next);
        if(surfaces.size()>24)surfaces.remove(surfaces.keySet().iterator().next());
        return !next.equals(previous);
    }
}
