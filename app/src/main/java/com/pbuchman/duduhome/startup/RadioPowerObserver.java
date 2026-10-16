package com.pbuchman.duduhome.startup;

import android.content.*;
import android.os.*;
import android.provider.Settings;
import com.pbuchman.duduhome.routebook.*;
import com.pbuchman.duduhome.trip.RadioProfile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Passive Toolkit module 0 observer. Its module wrapper exposes only register/unregister. */
public final class RadioPowerObserver implements AutoCloseable {
    private static final Object BOOT_LOCK=new Object();
    private static boolean bootStorageFailed;
    private final Context context;
    private final HandlerThread thread=new HandlerThread("RadioPowerObserver",android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler worker;
    private final BiConsumer<PowerEvent,RadioProfile.State> sink;
    private RadioProfile profile;
    private String boot,epoch=UUID.randomUUID().toString();
    private long sequence,accAt=-1,moduleAt=-1,lastRequest;
    private Integer acc,module1;
    private IBinder module,toolkit;
    private PassiveCallback callback;
    private boolean snapshotPending,accSeen,moduleSeen;
    private Integer coherentAcc,coherentModule;
    private ServiceConnection connection;
    private volatile boolean closed;
    private volatile String diagnostic="POWER_STARTING";
    private final IBinder.DeathRecipient death;
    private final Runnable rebind=this::bind;
    public RadioPowerObserver(Context context,BiConsumer<PowerEvent,RadioProfile.State> sink){
        this.context=context.getApplicationContext();this.sink=sink;thread.start();worker=new Handler(thread.getLooper());
        death=()->worker.post(()->lost(true));
        worker.post(()->{
            try{
                String fingerprint="unavailable";boolean metadata=false;
                try{fingerprint=firmwareId();metadata=true;}catch(Exception unavailable){diagnostic="POWER_FIRMWARE_UNAVAILABLE";}
                profile=RadioProfile.diagnostic(fingerprint);
                File file=new File(this.context.getNoBackupFilesDir(),"radio-power-profile.json");
                if(file.exists()&&metadata)try{profile=RadioProfile.parse(PrivateConfig.utf8(PrivateConfig.read(file)),fingerprint);}catch(Exception invalid){diagnostic="POWER_PROFILE_BLOCKED";}
                boot=bootId();emit("observer_lost");bind();worker.postDelayed(tick,1000);
            }catch(Exception unavailable){diagnostic="POWER_IDENTITY_UNAVAILABLE";}
        });
    }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    @SuppressWarnings("deprecation") private String firmwareId()throws Exception{
        int flags=Build.VERSION.SDK_INT>=28?android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES:android.content.pm.PackageManager.GET_SIGNATURES;
        var info=context.getPackageManager().getPackageInfo("com.syu.ms",flags);
        long version=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
        android.content.pm.Signature[] signatures=Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
        if(signatures==null||signatures.length==0)throw new IllegalStateException("signer unavailable");
        java.util.List<String> digests=new java.util.ArrayList<>();
        for(var signature:signatures)digests.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        java.util.Collections.sort(digests);
        String binding="dudu-power-firmware-v1\n"+Build.FINGERPRINT+"\n"+version+"\n"+String.join(",",digests)+"\n";
        return "sha256:"+hex(MessageDigest.getInstance("SHA-256").digest(binding.getBytes(StandardCharsets.UTF_8)));
    }
    private String bootId(){
        synchronized(BOOT_LOCK){
        if(bootStorageFailed)throw new IllegalStateException("boot storage blocked");
        int count=Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        if(count<0)throw new IllegalStateException("boot unavailable");
        var prefs=context.getSharedPreferences("radio_power_boot",0);String id=prefs.getString("id","");
        if(prefs.getInt("boot",-1)!=count||!Point.uuid(id)){
            id=UUID.randomUUID().toString();if(!prefs.edit().putInt("boot",count).putString("id",id).commit()){bootStorageFailed=true;throw new IllegalStateException("boot not durable");}
        }return id;
        }
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(closed)return;
        long now=SystemClock.elapsedRealtime();
        if(module!=null&&now-lastRequest>=30000){try{snapshot();}catch(RemoteException failure){lost(true);}}
        if(module!=null&&(accAt>=0&&now-accAt>45000||moduleAt>=0&&now-moduleAt>45000)){
            // Cached values cannot refresh their own lifetime. A response to updateNow is required.
            acc=null;module1=null;accAt=moduleAt=-1;emit("observer_lost");diagnostic="POWER_SNAPSHOT_EXPIRED";
        }
        worker.postDelayed(this,1000);
    }};
    private void bind(){
        if(closed)return;epoch=UUID.randomUUID().toString();sequence=0;acc=null;module1=null;accAt=moduleAt=-1;
        coherentAcc=coherentModule=null;snapshotPending=false;
        emit("observer_lost");diagnostic="POWER_BINDING";
        ServiceConnection attempt=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){worker.post(()->connected(this,binder));}
            public void onServiceDisconnected(ComponentName name){worker.post(()->{if(connection==this)lost(true);});}
            public void onBindingDied(ComponentName name){worker.post(()->{if(connection==this)lost(true);});}
            public void onNullBinding(ComponentName name){worker.post(()->{if(connection==this)lost(true);});}
        };connection=attempt;
        Intent intent=new Intent("com.syu.ms.toolkit").setComponent(new ComponentName("com.syu.ms","app.ToolkitService"));
        try{if(!context.bindService(intent,attempt,Context.BIND_AUTO_CREATE)){lost(true);return;}}
        catch(RuntimeException unavailable){lost(true);return;}
        worker.postDelayed(()->{if(!closed&&connection==attempt&&module==null)lost(true);},5000);
    }
    private void connected(ServiceConnection owner,IBinder binder){
        if(closed||connection!=owner)return;
        try{
            toolkit=binder;toolkit.linkToDeath(death,0);
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try{
                data.writeInterfaceToken("com.syu.ipc.IRemoteToolkit");data.writeInt(0);
                if(!toolkit.transact(1,data,reply,0))throw new RemoteException("Toolkit unavailable");
                reply.readException();module=reply.readStrongBinder();
            }finally{reply.recycle();data.recycle();}
            if(module==null||!"com.syu.ipc.IRemoteModule".equals(module.getInterfaceDescriptor()))throw new RemoteException("module unavailable");
            module.linkToDeath(death,0);
            String ownerEpoch=epoch;
            callback=new PassiveCallback(ownerEpoch);snapshot();
        }catch(RemoteException|RuntimeException unavailable){lost(true);}
    }
    /** Module 0 has intentionally no command method or transaction 1 path. */
    private void registration(int transaction,int code)throws RemoteException{
        if(transaction!=3&&transaction!=4)throw new IllegalArgumentException();
        Parcel data=Parcel.obtain();try{
            data.writeInterfaceToken("com.syu.ipc.IRemoteModule");data.writeStrongBinder(callback);data.writeInt(code);
            if(transaction==3)data.writeInt(1);
            if(!module.transact(transaction,data,null,IBinder.FLAG_ONEWAY))throw new RemoteException("registration unavailable");
        }finally{data.recycle();}
    }
    private void snapshot()throws RemoteException{lastRequest=SystemClock.elapsedRealtime();snapshotPending=true;accSeen=moduleSeen=false;registration(3,50);registration(3,1);}
    private void raw(String ownerEpoch,int code,Integer value,long received){
        if(closed||!epoch.equals(ownerEpoch)||module==null||!(code==1||code==50))return;
        if(!snapshotPending){
            Integer previous=code==50?coherentAcc:coherentModule;
            if(java.util.Objects.equals(previous,value))return;
            // A changed half-pair is not a complete power state. Re-read both callbacks first.
            emit("observer_lost");
            try{snapshot();}catch(RemoteException failure){lost(true);}return;
        }
        if(received<lastRequest)return;
        if(code==50){acc=value;accAt=received;accSeen=true;}else{module1=value;moduleAt=received;moduleSeen=true;}
        if(!accSeen||!moduleSeen)return;
        if(Math.abs(accAt-moduleAt)>1000){emit("observer_lost");try{snapshot();}catch(RemoteException failure){lost(true);}return;}
        snapshotPending=false;
        boolean changed=!java.util.Objects.equals(acc,coherentAcc)||!java.util.Objects.equals(module1,coherentModule);
        coherentAcc=acc;coherentModule=module1;
        emit(changed?"transition":"snapshot");diagnostic=profile.validated()?"POWER_PROFILE_VALIDATED":"POWER_DIAGNOSTIC_UNKNOWN";
    }
    private final class PassiveCallback extends Binder {
        private final String ownerEpoch;private volatile boolean active=true;
        PassiveCallback(String epoch){ownerEpoch=epoch;}
        void deactivate(){active=false;}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString("com.syu.ipc.IModuleCallback");return true;}
            if(code!=1)return super.onTransact(code,data,reply,flags);
            data.enforceInterface("com.syu.ipc.IModuleCallback");int update=data.readInt();int[] values=data.createIntArray();data.createFloatArray();data.createStringArray();
            long received=SystemClock.elapsedRealtime();Integer value=values!=null&&values.length==1?values[0]:null;
            if(active)worker.post(()->{if(active)raw(ownerEpoch,update,value,received);});
            if(reply!=null)reply.writeNoException();return true;
        }
    }
    private void emit(String kind){
        long now=SystemClock.elapsedRealtime();Integer a=accAt>=0&&now-accAt<=45000?acc:null,b=moduleAt>=0&&now-moduleAt<=45000?module1:null;
        if(kind.equals("observer_lost")){a=null;b=null;}
        PowerEvent event=new PowerEvent(UUID.randomUUID().toString(),epoch,sequence++,Point.TIME.format(Instant.ofEpochMilli(System.currentTimeMillis())),now,boot,kind,a,b,profile.id(),profile.firmware());
        sink.accept(event,kind.equals("observer_lost")?RadioProfile.State.UNKNOWN:profile.interpret(a,b));
    }
    private void lost(boolean reconnect){
        if(callback!=null){callback.deactivate();if(module!=null)for(int code:new int[]{50,1})try{registration(4,code);}catch(RemoteException|RuntimeException ignored){}}
        unlink(module);unlink(toolkit);
        module=null;toolkit=null;callback=null;acc=null;module1=null;accAt=moduleAt=-1;
        if(boot!=null)emit("observer_lost");
        ServiceConnection prior=connection;connection=null;if(prior!=null)try{context.unbindService(prior);}catch(RuntimeException ignored){}
        diagnostic="POWER_UNKNOWN";worker.removeCallbacks(rebind);if(reconnect&&!closed)worker.postDelayed(rebind,30000);
    }
    private void unlink(IBinder binder){if(binder!=null)try{binder.unlinkToDeath(death,0);}catch(RuntimeException ignored){}}
    public String diagnostic(){return "radio_power="+diagnostic;}
    public void close(){close(()->{});}
    public void close(Runnable after){closed=true;worker.post(()->{worker.removeCallbacksAndMessages(null);lost(false);after.run();thread.quitSafely();});}
}
