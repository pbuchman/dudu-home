package com.pbuchman.duduhome.automation;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Observation only. No notification contents, persistence, actions or monitor startup. */
public final class YanosikPresence extends NotificationListenerService {
    public enum State { WORK_DETECTED, NO_WORK_SIGNAL, UNKNOWN }
    private static volatile YanosikPresence connected;

    @Override public void onListenerConnected() { connected = this; }
    @Override public void onListenerDisconnected() { if (connected == this) connected = null; }
    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }

    public static boolean accessGranted(Context context) {
        ComponentName component = new ComponentName(context, YanosikPresence.class);
        try {
            if (Build.VERSION.SDK_INT >= 27)
                return context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(component);
            String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
            if (enabled != null) for (String name : enabled.split(":"))
                if (component.equals(ComponentName.unflattenFromString(name))) return true;
        } catch (RuntimeException unavailable) { return false; }
        return false;
    }

    /** Read a fresh complete snapshot, including notifications posted before our process started. */
    public static State read(Context context) {
        YanosikPresence listener = connected;
        if (listener == null || !accessGranted(context)) return State.UNKNOWN;
        try {
            State result = classify(listener.getActiveNotifications());
            return connected == listener && accessGranted(context) ? result : State.UNKNOWN;
        } catch (RuntimeException unavailable) { return State.UNKNOWN; }
    }

    public static State classify(StatusBarNotification[] notifications) {
        if (notifications == null) return State.UNKNOWN;
        for (StatusBarNotification posted : notifications) {
            if (posted == null || !YanosikLauncher.PACKAGE.equals(posted.getPackageName())) continue;
            Notification notification = posted.getNotification();
            if (notification != null && (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0)
                return State.WORK_DETECTED;
        }
        return State.NO_WORK_SIGNAL;
    }
}
