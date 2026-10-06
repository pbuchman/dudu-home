package com.pbuchman.duduhome.routebook;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Emulator-only integration. CA trust applies solely to this instrumentation process.
 * Calls the unchanged production HTTPS function; hostname validation is never overridden. */
public final class RoutebookHttpsInstrumentation extends Instrumentation {
    private Bundle args;private int assertions;
    @Override public void onCreate(Bundle bundle){super.onCreate(bundle);args=bundle;start();}
    private void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private Point point(int n){return new Point(new UUID(0,n+1).toString(),new UUID(0,999).toString(),Point.TIME.format(Instant.ofEpochMilli(1791187200000L+n*5000L)),0,n*.00005,5,2.0);}
    private PrivateConfig config(String device,String host,boolean badToken)throws Exception{
        var m=(Map<?,?>)Json.parse(PrivateConfig.utf8(PrivateConfig.read(new File(getTargetContext().getNoBackupFilesDir(),"routebook-https-config.json"))));
        String text="{\"version\":1,\"device_id\":"+Json.quote(device)+",\"endpoint\":"+Json.quote("https://"+host+"/v1/ingest")+",\"token\":"+Json.quote(badToken?"synthetic-invalid-token-000000000000000":(String)m.get("token"))+",\"enabled\":true,\"sample_ms\":5000,\"max_age_ms\":5000,\"gap_ms\":30000,\"accuracy_m\":50}";
        PrivateConfig cfg=PrivateConfig.parse(text.getBytes(StandardCharsets.UTF_8),device);
        // The production emulator cannot bind privileged 443. TEST ONLY port override:
        // production endpoint validation remains unchanged and is exercised separately.
        var endpoint=PrivateConfig.class.getDeclaredField("endpoint");endpoint.setAccessible(true);
        endpoint.set(cfg,"https://"+host+":18443/v1/ingest");return cfg;
    }
    private void trust()throws Exception{
        KeyStore ks=KeyStore.getInstance(KeyStore.getDefaultType());ks.load(null,null);
        try(var in=new FileInputStream(new File(getTargetContext().getNoBackupFilesDir(),"routebook-https-ca.pem"))){ks.setCertificateEntry("synthetic-local-ca",CertificateFactory.getInstance("X.509").generateCertificate(in));}
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(ks);
        SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(null,tm.getTrustManagers(),null);
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
    }
    private void waitFor(java.util.function.BooleanSupplier done,int seconds)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!done.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(25);check(done.getAsBoolean(),"bounded wait completed");
    }
    @Override public void onStart(){Bundle result=new Bundle();String device="";SSLSocketFactory original=HttpsURLConnection.getDefaultSSLSocketFactory();
        try{
            check(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"),"emulator only");
            String run=args.getString("run_id");check(Point.uuid(run),"unique synthetic run");
            String phase=args.getString("phase");File file=new File(getTargetContext().getNoBackupFilesDir(),"routebook-https-"+run+".sqlite");
            try(Outbox db=new Outbox(file)){
                device=db.device();
                if(phase.equals("seed")){check(db.pending(true,1).isEmpty(),"fresh dedicated synthetic DB");for(int i=0;i<240;i++)db.insert(point(i));check(db.pending(false,200).size()==200,"durable backlog seeded");}
                else if(phase.equals("tls")){
                    byte[] bytes=Protocol.envelope(device,"latest",db.pending(true,1));
                    try{Uploader.https(config(device,"localhost",false),bytes);throw new AssertionError("untrusted certificate accepted");}catch(IOException expected){assertions++;}
                    trust();
                    try{Uploader.https(config(device,"127.0.0.1",false),bytes);throw new AssertionError("wrong hostname accepted");}catch(IOException expected){assertions++;}
                    check(db.pending(false,200).size()==200,"TLS rejection preserves pending");
                }else{
                    trust();List<String> reports=new CopyOnWriteArrayList<>();
                    if(phase.equals("partial")){db.insert(point(400));Point p=point(399);db.insert(new Point(p.eventId,p.segmentId,p.measuredAt,p.lat,p.lon,51,p.speed));db.insert(point(398));}
                    if((phase.equals("auth")||phase.equals("invalid")||phase.equals("redirect"))&&db.pending(true,1).isEmpty())db.insert(point(500));
                    var n=new java.util.concurrent.atomic.AtomicInteger();
                    Uploader.Transport real=(cfg,body)->{var response=Uploader.https(cfg,body);if(phase.equals("resume")&&n.incrementAndGet()==2)db.insert(point(300));return response;};
                    try(Uploader uploader=new Uploader(db,config(device,"localhost",phase.equals("auth")),real,reports::add)){
                        uploader.kick();
                        if(phase.equals("lost")){waitFor(()->reports.contains("UPLOAD_NETWORK_RETRY"),5);check(db.pending(true,1).get(0).json().equals(point(239).json()),"lost ACK keeps identical point");}
                        else if(phase.equals("auth")||phase.equals("redirect")){waitFor(()->reports.contains("UPLOAD_BLOCKED_HTTP"),5);check(db.pending(true,1).size()==1,"blocked HTTP keeps pending");}
                        else if(phase.equals("invalid")){waitFor(()->reports.contains("UPLOAD_INVALID_ACK"),5);check(db.pending(true,1).size()==1,"invalid ACK keeps pending");}
                        else {waitFor(()->db.pending(true,1).isEmpty(),20);if(phase.equals("partial"))check(db.counts().contains("REJECTED_INVALID=1"),"actual server rejection retained terminally");}
                    }
                }
            }
            result.putString("result","PASS: HTTPS "+phase+" "+assertions+" assertions; device_id="+device);finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("result","FAIL: HTTPS "+e.getClass().getSimpleName()+" "+e.getMessage());finish(Activity.RESULT_CANCELED,result);}
        finally{HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
}
