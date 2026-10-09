package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import com.pbuchman.duduhome.automation.ProgressBus;
import com.pbuchman.duduhome.ui.AutomationBanner;
import com.pbuchman.duduhome.ui.ProgressOverlay;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** Bounded UI and notification checks with synthetic attempts, no external commands. */
final class CancellationUiChecks {
    static void run(Instrumentation i) {
        Context context = i.getTargetContext();
        i.runOnMainSync(() -> ProgressBus.reset(context, Reason.RESET));
        android.os.SystemClock.sleep(2200); // Allow prior fixtures' terminal messages to expire.
        i.runOnMainSync(() -> {
            long first = ProgressBus.requestManual(context, Kind.CLEANING);
            View banner = AutomationBanner.create(context);
            AutomationBanner.render(banner, ProgressBus.visible());
            int width = Math.round(390 * context.getResources().getDisplayMetrics().density);
            banner.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            banner.layout(0, 0, width, banner.getMeasuredHeight());
            Button button = banner.findViewById(R.id.banner_cancel_attempt);
            int minimum = Math.round(76 * context.getResources().getDisplayMetrics().density);
            check(button.getVisibility() == View.VISIBLE && button.getHeight() >= minimum && button.getWidth() >= minimum, "cancel target is readable and at least 76 dp");
            button.performClick();
            check(!button.isEnabled() && ProgressBus.cancelled(first), "click disables and cancels synchronously");
            AutomationBanner.render(banner, ProgressBus.snapshot().stream().filter(s -> s.id() == first).findFirst().orElseThrow());
            check(banner.findViewById(R.id.banner_cancel_attempt).getVisibility() == View.GONE, "terminal cancellation hides action");

            long old = ProgressBus.requestManual(context, Kind.CLEANING);
            AutomationBanner.render(banner, ProgressBus.visible());
            ProgressBus.cancelAttempt(ProgressBus.token(old));
            long current = ProgressBus.requestManual(context, Kind.CLEANING);
            button.performClick();
            check(ProgressBus.active(current), "stale rendered button keeps newer attempt active");

            ProgressOverlay presenter = new ProgressOverlay(context);
            try {
                var notices = context.getSystemService(NotificationManager.class).getActiveNotifications();
                var notice = java.util.Arrays.stream(notices).filter(n -> n.getId() == 19).findFirst().orElseThrow();
                check(notice.getNotification().actions != null && notice.getNotification().actions.length == 1, "current attempt has notification cancellation");
                var pending = notice.getNotification().actions[0].actionIntent;
                check(pending.isService() && context.getPackageName().equals(pending.getCreatorPackage()), "notification explicitly targets own service");
                if (Build.VERSION.SDK_INT >= 31) check(pending.isImmutable(), "notification token cannot be rewritten");
                ProgressBus.cancelAttempt(ProgressBus.token(current));
                check(java.util.Arrays.stream(context.getSystemService(NotificationManager.class).getActiveNotifications()).noneMatch(n -> n.getId() == 19), "terminal attempt removes live notification");
            } finally { presenter.close(); }
        });
    }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}
