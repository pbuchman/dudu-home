package com.pbuchman.duduhome.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.automation.ProgressBus;
import com.pbuchman.duduhome.automation.ProgressModel;
import com.pbuchman.duduhome.automation.AttemptToken;
import com.pbuchman.duduhome.location.HomeMonitorService;
import com.pbuchman.duduhome.diagnostics.Diagnostics;

/** Lifecycle owned by the existing monitor. No Activity launch, wake or action permission. */
public final class ProgressOverlay implements AutoCloseable {
    private static final String CHANNEL = "automation_attempt";
    private static final int NOTIFICATION_ID = 19;
    private final Context context;
    private final NotificationManager notifications;
    private ProgressModel.State notified;
    private final WindowManager windows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable observer = this::render;
    private final Runnable tick = new Runnable() {
        public void run() {
            try { render(); } catch (RuntimeException failed) { remove(); }
            if (!closed) handler.postDelayed(this, 250);
        }
    };
    private View overlay, inline;
    private ViewGroup host;
    private boolean closed, denied;
    private long retryAfter;
    public ProgressOverlay(Context service) {
        Context windowContext = Build.VERSION.SDK_INT >= 30
                ? service.createDisplayContext(service.getSystemService(DisplayManager.class)
                        .getDisplay(Display.DEFAULT_DISPLAY))
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) : service;
        context = new ContextThemeWrapper(windowContext, R.style.Theme_DuduGate);
        windows = windowContext.getSystemService(WindowManager.class);
        notifications = service.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Bieżąca automatyzacja", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false);
        notifications.createNotificationChannel(channel);
        ProgressBus.subscribe(observer);
        handler.post(tick);
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void render() {
        if (closed) return;
        ProgressModel.State state = ProgressBus.visible();
        renderNotification(state);
        MainActivity activity = HomeActions.visibleActivity();
        if (state == null || HomeActions.busy() || !HomeActions.allowsExternalLaunch()) { remove(); return; }
        if (activity != null) {
            removeOverlay();
            ViewGroup next = activity.progressHost();
            if (next == null) { removeInline(); return; }
            if (host != next) {
                removeInline(); host = next; inline = AutomationBanner.create(activity);
                int width = Math.min(dp(480), Math.max(1, activity.getResources().getDisplayMetrics().widthPixels - dp(96)));
                host.addView(inline, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            }
            AutomationBanner.render(inline, state); return;
        }
        removeInline();
        if (!Settings.canDrawOverlays(context)) { removeOverlay(); return; }
        if (android.os.SystemClock.elapsedRealtime() < retryAfter) return;
        try {
            if (overlay == null) {
                overlay = AutomationBanner.create(context);
                int available = Build.VERSION.SDK_INT >= 30 ? windows.getCurrentWindowMetrics().getBounds().width()
                        : context.getResources().getDisplayMetrics().widthPixels;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(Math.min(dp(480), Math.max(1, available - dp(32))),
                        ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; params.y = dp(16);
                params.alpha = Build.VERSION.SDK_INT >= 31
                        ? Math.min(0.8f, context.getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch()) : 0.8f;
                params.setTitle("Dudu Home progress");
                AutomationBanner.render(overlay, state);
                windows.addView(overlay, params);
            } else AutomationBanner.render(overlay, state);
        } catch (RuntimeException error) {
            removeOverlay();
            retryAfter = android.os.SystemClock.elapsedRealtime() + 5000;
            if (!denied) { Diagnostics.record(context, "PROGRESS_OVERLAY_UNAVAILABLE"); denied = true; }
        }
    }
    private void renderNotification(ProgressModel.State state) {
        if (state == null || (!ProgressBus.cancellable(state) && state.phase() != com.pbuchman.duduhome.automation.DetectionProgress.Phase.CANCELLING)) {
            notifications.cancel(NOTIFICATION_ID); notified = null; return;
        }
        if (state.equals(notified)) return;
        AttemptToken token = ProgressBus.token(state);
        Intent cancel = new Intent(context, HomeMonitorService.class).setAction(HomeMonitorService.CANCEL_ACTION)
                .setData(new Uri.Builder().scheme("duduhome").authority("cancel").appendPath(token.session()).appendPath(Long.toString(token.id())).build())
                .putExtra(HomeMonitorService.CANCEL_SESSION_EXTRA, token.session())
                .putExtra(HomeMonitorService.CANCEL_ID_EXTRA, token.id());
        PendingIntent pending = PendingIntent.getService(context, NOTIFICATION_ID, cancel, PendingIntent.FLAG_IMMUTABLE);
        String title = context.getString(switch (state.kind()) {
            case DEPARTURE -> R.string.progress_departure; case RETURN -> R.string.progress_return;
            case CLEANING -> R.string.full_cleaning; case MOP -> R.string.full_mop;
            default -> R.string.progress_media_group;
        });
        Notification.Builder notification = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title).setContentText(context.getString(AutomationBanner.detail(state)))
                .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PRIVATE);
        if (ProgressBus.cancellable(state)) notification.addAction(new Notification.Action.Builder(null, context.getString(R.string.cancel_attempt), pending).build());
        try { notifications.notify(NOTIFICATION_ID, notification.build()); notified = state; }
        catch (RuntimeException unavailable) { notified = null; }
    }
    private void removeOverlay() {
        if (overlay != null) { try { windows.removeViewImmediate(overlay); } catch (RuntimeException ignored) { } overlay = null; }
    }
    private void removeInline() { if (host != null && inline != null) host.removeView(inline); host = null; inline = null; }
    private void remove() { removeOverlay(); removeInline(); }
    @Override public void close() { closed = true; ProgressBus.unsubscribe(observer); handler.removeCallbacksAndMessages(null); remove(); notifications.cancel(NOTIFICATION_ID); }
}
