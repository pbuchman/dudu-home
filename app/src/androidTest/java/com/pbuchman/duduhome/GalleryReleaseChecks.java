package com.pbuchman.duduhome;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.location.HomeMonitorService;
import com.pbuchman.duduhome.navigation.NavigationConfig;
import com.pbuchman.duduhome.navigation.NavigationStore;
import com.pbuchman.duduhome.trip.PlaceResult;
import com.pbuchman.duduhome.trip.TripController;
import com.pbuchman.duduhome.ui.MainActivity;
import com.pbuchman.duduhome.ui.TripActivity;
import com.pbuchman.duduhome.ui.TripPresentation;

import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static com.pbuchman.duduhome.ProgressChecks.check;
import static com.pbuchman.duduhome.ProgressChecks.field;
import static com.pbuchman.duduhome.ProgressChecks.main;

/** Captures the changed 1.5.1 production Views, driven only by synthetic input on an emulator. */
final class GalleryReleaseChecks {
    private static final String CITY = "Próbogród Chmurowy";
    private static final String STATIC_STREET = "Aleja Sześciu Chmur Próbnych";
    private static final String LONG_STREET =
            "Aleja Sześciu Chmur Próbnych nad Ogrodem Księżycowych Latarni i Srebrnych Obłoków";

    static void run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED,
                "gallery requires location permission disabled");
        context.stopService(new Intent(context, HomeMonitorService.class));
        check(context.getSharedPreferences("gate_settings", 0).edit()
                .putString("gate_number", "000000000").commit(), "synthetic number saved");
        new NavigationStore(context).save(NavigationConfig.parse("""
                {"schema_version":2,"slots":[
                  {"slot":1,"label":"Cel pokazowy","icon":"home","destinations":[
                    {"label":"Cel pokazowy","icon":"pin","latitude":0,"longitude":0}]},
                  {"slot":2,"label":"Sport","icon":"squash","destinations":[
                    {"label":"Hala Alfa","icon":"pin","latitude":0,"longitude":0},
                    {"label":"Hala Beta","icon":"pin","latitude":0.001,"longitude":0},
                    {"label":"Hala Gamma","icon":"pin","latitude":0.002,"longitude":0}]}
                ]}
                """));

        MainActivity menu = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(instrumentation, () -> {
            check(menu.getResources().getConfiguration().getLocales().get(0).toLanguageTag().equals("pl-PL"),
                    "menu uses pl-PL locale");
            check(menu.getResources().getConfiguration().densityDpi == 240, "menu uses 240 dpi");
            check(menu.findViewById(R.id.internet_speed_tile).isShown(), "real menu shows the speed shortcut");
        });
        ProgressChecks.capture(instrumentation, "home-speed.png");
        main(instrumentation, menu::finish);

        try (var backdrop = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand(
                        "am start -W -n " + instrumentation.getContext().getPackageName()
                                + "/" + GalleryBackdropActivity.class.getName()))) {
            backdrop.readAllBytes();
        }
        instrumentation.getUiAutomation().waitForIdle(150, 2500);
        var root = instrumentation.getUiAutomation().getRootInActiveWindow();
        check(root != null && !root.findAccessibilityNodeInfosByText(GalleryBackdropActivity.TITLE).isEmpty(),
                "drawn synthetic map is foreground");

        var panel = new AtomicReference<TripPresentation>();
        try {
            main(instrumentation, () -> {
                panel.set(new TripPresentation(context));
                ((Handler) field(panel.get(), "handler")).removeCallbacksAndMessages(null);
                panel.get().render(snapshot(STATIC_STREET));
            });
            SystemClock.sleep(500);
            Rect[] staticRows = rows(instrumentation, panel.get(), false);
            Bitmap still = screenshot(instrumentation);
            try {
                SystemClock.sleep(700);
                Bitmap later = screenshot(instrumentation);
                try {
                    save(instrumentation, "street-two-line.png", still);
                    boolean firstStill = samePixels(still, later, staticRows[0]);
                    boolean secondStill = samePixels(still, later, staticRows[1]);
                    if (!firstStill || !secondStill) save(instrumentation, "street-two-line-diagnostic.png", later);
                    check(firstStill && secondStill, "both fitting street rows remain still on screen: first "
                            + staticRows[0] + "=" + firstStill + ", second " + staticRows[1] + "=" + secondStill);
                } finally {
                    later.recycle();
                }
            } finally {
                still.recycle();
            }

            main(instrumentation, () -> panel.get().render(snapshot(LONG_STREET)));
            SystemClock.sleep(500);
            Rect[] scrollingRows = rows(instrumentation, panel.get(), true);
            Bitmap first = screenshot(instrumentation);
            try {
                // The opening dwell is native TextView marquee behavior. Poll until its pixels move.
                save(instrumentation, "street-marquee-a.png", first);
                boolean moved = false;
                for (int attempt = 0; attempt < 20 && !moved; attempt++) {
                    SystemClock.sleep(200);
                    main(instrumentation, () -> panel.get().render(snapshot(LONG_STREET)));
                    Bitmap current = screenshot(instrumentation);
                    try {
                        check(samePixels(first, current, scrollingRows[0]), "first street row remains still");
                        if (!samePixels(first, current, scrollingRows[1])) {
                            // Hold a further beat so the alternate frame shows a visibly different span.
                            SystemClock.sleep(1750);
                            Bitmap later = screenshot(instrumentation);
                            try {
                                check(samePixels(first, later, scrollingRows[0]), "first street row remains still");
                                moved = !samePixels(first, later, scrollingRows[1]);
                                if (moved) save(instrumentation, "street-marquee-b.png", later);
                            } finally {
                                later.recycle();
                            }
                        }
                    } finally {
                        current.recycle();
                    }
                }
                check(moved, "same street scrolls its second row while the first is fixed");
            } finally {
                first.recycle();
            }

            var openTrip = instrumentation.addMonitor(TripActivity.class.getName(), null, false);
            var row = scrollingRows[1];
            try (var tap = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.getUiAutomation().executeShellCommand(
                            "input tap " + row.centerX() + " " + row.centerY()))) {
                tap.readAllBytes();
            }
            TripActivity trip = (TripActivity) instrumentation.waitForMonitorWithTimeout(openTrip, 5000);
            check(trip != null, "real tap on the scrolling street opens the passive screen");
            main(instrumentation, () -> {
                ((Handler) field(trip, "handler")).removeCallbacksAndMessages(null);
                trip.render(snapshot(LONG_STREET));
                check(TripActivity.visible && !HomeActions.busy(), "trip screen is passive");
                panel.get().render(snapshot(LONG_STREET));
                check(field(panel.get(), "overlay") == null, "overlay yields to the passive screen");
                TextView street = trip.findViewById(R.id.trip_street);
                check(LONG_STREET.contentEquals(street.getText()), "full screen retains the complete street");
            });
            ProgressChecks.capture(instrumentation, "street-full-after-tap.png");
            main(instrumentation, trip::finish);
        } finally {
            if (panel.get() != null) main(instrumentation, panel.get()::close);
        }
        check(!context.getSharedPreferences("gate_settings", 0).contains("last_dial_started_at"),
                "gallery never reserves a dial");
    }

    private static TripController.Snapshot snapshot(String street) {
        return new TripController.Snapshot("ACTIVE", 1, 12840, SystemClock.elapsedRealtime(),
                new PlaceResult(CITY, street, "offline", true, true), "", true, 1939000, false);
    }

    private static Rect[] rows(Instrumentation instrumentation, TripPresentation panel, boolean overflow) {
        Rect[] rows = new Rect[2];
        main(instrumentation, () -> {
            View overlay = (View) field(panel, "overlay");
            check(overlay != null, "overlay is attached");
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) overlay.getLayoutParams();
            check((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
                    && (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0,
                    "panel receives taps without taking focus");
            TextView first = overlay.findViewById(R.id.trip_street);
            TextView tail = overlay.findViewById(R.id.trip_street_tail);
            check(first != null && first.isShown() && tail != null && tail.isShown(), "two visible street rows");
            check(first.getLineCount() == 1 && tail.getLineCount() == 1 && first.getEllipsize() != TextUtils.TruncateAt.MARQUEE,
                    "only the second row can marquee");
            check((tail.getPaint().measureText(tail.getText().toString()) > tail.getWidth()
                    - tail.getCompoundPaddingLeft() - tail.getCompoundPaddingRight()) == overflow,
                    "fixture produces expected second-row overflow");
            if (overflow) check(tail.getEllipsize() == TextUtils.TruncateAt.MARQUEE, "native marquee on overflow");
            rows[0] = bounds(first);
            rows[1] = bounds(tail);
        });
        return rows;
    }

    private static Rect bounds(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private static Bitmap screenshot(Instrumentation instrumentation) {
        Bitmap shot = instrumentation.getUiAutomation().takeScreenshot();
        check(shot != null && shot.getWidth() == 2000 && shot.getHeight() == 1200, "2000 × 1200 emulator capture");
        return shot;
    }

    private static boolean samePixels(Bitmap before, Bitmap after, Rect rect) {
        Bitmap first = Bitmap.createBitmap(before, rect.left, rect.top, rect.width(), rect.height());
        Bitmap second = Bitmap.createBitmap(after, rect.left, rect.top, rect.width(), rect.height());
        try {
            return first.sameAs(second);
        } finally {
            first.recycle();
            second.recycle();
        }
    }

    private static void save(Instrumentation instrumentation, String filename, Bitmap shot) throws Exception {
        try (var out = new FileOutputStream(new java.io.File(
                instrumentation.getTargetContext().getExternalFilesDir(null), filename))) {
            check(shot.compress(Bitmap.CompressFormat.PNG, 100, out), "saved " + filename);
        }
    }
}
