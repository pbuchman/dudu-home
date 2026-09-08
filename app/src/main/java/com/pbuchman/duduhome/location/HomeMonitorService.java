package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.automation.HomeEvent;
import com.pbuchman.duduhome.config.PrivateImport;
import com.pbuchman.duduhome.ui.MainActivity;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

public final class HomeMonitorService extends Service implements LocationListener {
    private static final String CHANNEL = "home_monitor";
    private LocationManager locations;
    private HomeConfiguration config;
    private HomeDetector detector;
    private SharedPreferences state;
    private boolean registered;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastFix;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!ready(HomeMonitorService.this) || PrivateImport.pending(HomeMonitorService.this)) {
                stopSelf();
                return;
            }
            if (SystemClock.elapsedRealtime() - lastFix > 15000) {
                detector.clearEvidence();
                if (registered) locations.removeUpdates(HomeMonitorService.this);
                registered = false;
                subscribe();
            }
            handler.postDelayed(this, 15000);
        }
    };

    public static boolean ready(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED) && Settings.canDrawOverlays(context)
                && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED);
    }

    public static void ensureStarted(Context context) {
        HomeConfiguration c = HomeConfiguration.load(context);
        if (c == null || !c.enabled || !ready(context) || PrivateImport.pending(context)) return;
        try { context.startForegroundService(new Intent(context, HomeMonitorService.class)); }
        catch (RuntimeException ignored) { Log.w("DuduHome", "Monitor start blocked by system; manual calls remain available"); }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Automatyzacja domu", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Dudu Home").setContentText("Automatyzacja aktywna")
                .setContentIntent(open).setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(17, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(17, notification);
        } catch (RuntimeException denied) { stopSelf(); return; }
        state = getSharedPreferences("home_detector", 0);
        locations = getSystemService(LocationManager.class);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        HomeConfiguration next = HomeConfiguration.load(this);
        if (state == null || next == null || !next.enabled || !ready(this) || PrivateImport.pending(this)) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (config == null || !next.revision.equals(config.revision)) {
            config = next;
            int saved = config.revision.equals(state.getString("revision", "")) ? state.getInt("flags", 0) : 0;
            detector = new HomeDetector(config.geometry, saved);
            if (!state.edit().putString("revision", config.revision).putInt("flags", saved).commit()) {
                stopSelf(); return START_NOT_STICKY;
            }
        }
        subscribe();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, 15000);
        return START_STICKY;
    }

    @SuppressWarnings("MissingPermission")
    private void subscribe() {
        if (registered || !ready(this)) return;
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            registered = true;
        } catch (RuntimeException denied) { Log.w("DuduHome", "GPS subscription unavailable"); }
    }

    @Override public void onLocationChanged(Location location) {
        if (detector == null) return;
        lastFix = SystemClock.elapsedRealtime();
        long fixTime = location.getElapsedRealtimeNanos() / 1000000;
        java.util.List<HomeEvent> events = detector.accept(new HomeDetector.Fix(fixTime,
                config.project(location.getLatitude(), location.getLongitude()),
                location.hasAccuracy() ? location.getAccuracy() : Double.POSITIVE_INFINITY,
                location.hasSpeed() ? location.getSpeed() : 0,
                lastFix - fixTime, location.isFromMockProvider()));
        int flags = detector.flags();
        if (flags != state.getInt("flags", 0) && !state.edit().putInt("flags", flags).commit()) {
            stopSelf(); return; // Persist consumed events before any side effect.
        }
        for (HomeEvent event : events) {
            HomeConfiguration current = HomeConfiguration.load(this);
            if (current == null || !current.enabled || !current.revision.equals(config.revision)
                    || PrivateImport.pending(this)) { stopSelf(); return; }
            Log.i("DuduHome", "Event " + event.name());
            HomeActions.dispatch(this, event);
        }
    }
    @Override public void onProviderDisabled(String provider) { if (detector != null) detector.clearEvidence(); }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (registered && locations != null) locations.removeUpdates(this);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
