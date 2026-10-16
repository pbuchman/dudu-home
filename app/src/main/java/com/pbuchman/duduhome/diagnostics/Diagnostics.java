package com.pbuchman.duduhome.diagnostics;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;

/** Bounded app-private categories only. Attempt evidence is durable before returning to dispatch. */
public final class Diagnostics {
    private Diagnostics() { }
    private static final long PROCESS = SystemClock.elapsedRealtimeNanos();
    private static final java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong dropped=new java.util.concurrent.atomic.AtomicLong();
    private static final BlockCoalescer blocks=new BlockCoalescer();
    private static final java.util.Map<String,RollingJournal> journals=new java.util.HashMap<>();
    private static volatile Context application;
    private static final java.util.concurrent.ThreadPoolExecutor writer=new java.util.concurrent.ThreadPoolExecutor(
            1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(128),
            runnable->{Thread thread=new Thread(runnable,"DuduDiagnostics");thread.setDaemon(true);return thread;},
            (task,executor)->dropped.incrementAndGet());
    public static long startup() { return SystemClock.elapsedRealtime(); }
    public static long process() { return PROCESS; }
    public static int pendingWrites() { return writer.getQueue().size(); }
    public static long droppedWrites() { return dropped.get(); }
    public static boolean awaitWrites() {
        java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
        try {
            if(!writer.getQueue().offer(latch::countDown,2,java.util.concurrent.TimeUnit.SECONDS))return false;
            writer.prestartCoreThread();return latch.await(2,java.util.concurrent.TimeUnit.SECONDS);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
    }
    /** Compatibility hooks; diagnostic lifetime is independent from quota/reservation lifetime. */
    public static void beginAttempt(long id) { }
    public static void discardAttempt(long id) { }
    public static void commitAttempt(Context context,long id) { recordAttempt(context,id,"ATTEMPT_FINALIZED"); }
    public static void recordAttempt(Context context,long id,String category) {
        recordAttemptAt(context,id,category,System.currentTimeMillis(),SystemClock.elapsedRealtime());
    }
    public static void recordAttempt(long id,String category) {
        Context context=application;if(context!=null)recordAttempt(context,id,category);
    }
    static void recordAttemptAt(Context context,long id,String category,long wall,long elapsed) {
        if(!CategoryFormat.valid(category))return;
        if(!category.contains(" ID=")) category+=" ID="+id;
        if(CategoryFormat.valid(category))write(context,wall,elapsed,category);
    }
    public static void queueState(Context context,long id,String action,String state,String reason,long blockerId,String blockerAction) {
        queueState(context,id,action,state,reason,blockerId,blockerAction,0);
    }
    public static void queueState(Context context,long id,String action,String state,String reason,long blockerId,String blockerAction,long mask) {
        String category="QUEUE_STATE ACTION="+action+" STATE="+state+" REASON="+reason
                +" BLOCKER_ID="+blockerId+" BLOCKER_ACTION="+blockerAction+" BLOCKS="+mask;
        if(CategoryFormat.valid(category) && blocks.emit(id,category,SystemClock.elapsedRealtime()))recordAttempt(context,id,category);
    }
    public static void record(Context context,String category) {
        if(!CategoryFormat.valid(category))return;
        application=context.getApplicationContext();
        long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
        writer.execute(()->write(context,wall,elapsed,category));
    }
    private static synchronized void write(Context context,long wall,long elapsed,String category) {
        try {
            application=context.getApplicationContext();
            File directory=context.getNoBackupFilesDir();
            RollingJournal journal=journals.computeIfAbsent(directory.getAbsolutePath(),ignored ->
                    new RollingJournal(directory,"diagnostics.txt","diagnostics.previous.txt",8,1024*1024,512));
            String body="PROCESS="+PROCESS+" SCHEMA=2 SEQ="+sequence.incrementAndGet()+" "+category;
            if(!journal.append(wall,wall+" "+elapsed+" "+body)){dropped.incrementAndGet();Log.w("DuduHome","DIAGNOSTICS_WRITE_FAILED");}
            // Generic categories are safe; private place trace never calls this API.
            Log.i("DuduHome",body);
        } catch(RuntimeException ignored) { dropped.incrementAndGet();Log.w("DuduHome","DIAGNOSTICS_WRITE_FAILED"); }
    }
}
