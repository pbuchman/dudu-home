package com.pbuchman.duduhome;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import com.pbuchman.duduhome.automation.DetectionProgress;
import com.pbuchman.duduhome.automation.ProgressBus;
import com.pbuchman.duduhome.gate.GateNumberStore;
import com.pbuchman.duduhome.location.HomeMonitorService;
import com.pbuchman.duduhome.trip.PlaceResult;
import com.pbuchman.duduhome.trip.TripController;
import com.pbuchman.duduhome.ui.TripActivity;
import com.pbuchman.duduhome.ui.TripLocationCard;
import com.pbuchman.duduhome.ui.TripOverlayPreferences;
import com.pbuchman.duduhome.ui.TripPresentation;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Samples TextView's real draw calls and pixels while the production overlay is attached. */
final class TripMarqueeChecks {
    private record Fixture(String locality, String street, String first, String second) {
        static Fixture read(Context context, String name) throws Exception {
            if (name == null) return new Fixture("Miejscowość Próbna",
                    "Aleja Sześciu Chmur Próbnych przy Porcelanowych Fikcyjnych Ogrodach", null, null);
            check(name.matches("[A-Za-z0-9_-]+\\.json"), "fixture is an app-private JSON file");
            try {
                JSONObject value = new JSONObject(new String(
                        Files.readAllBytes(new File(context.getFilesDir(), name).toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
                return new Fixture(value.getString("locality"), value.getString("street"),
                        value.getString("first"), value.getString("second"));
            } catch (Exception error) {
                throw new IOException("Cannot read the app-private fixture");
            }
        }

        TripController.Snapshot snapshot() {
            return new TripController.Snapshot("ACTIVE", 73, 12840, SystemClock.elapsedRealtime(),
                    new PlaceResult(locality, street, "offline", true, true), "", true);
        }
    }

    private static final class PaintTrace extends Canvas {
        float leftwardTravel;

        PaintTrace(Bitmap pixels) {
            super(pixels);
        }

        @Override public void translate(float dx, float dy) {
            leftwardTravel = Math.max(leftwardTravel, -dx);
            super.translate(dx, dy);
        }
    }

    private record Row(float offset, int[] pixels) {
        static Row read(TextView view) {
            if (view.getVisibility() != View.VISIBLE) return new Row(0, new int[0]);
            int width = view.getWidth(), height = view.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            PaintTrace canvas = new PaintTrace(bitmap);
            view.draw(canvas);
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();
            return new Row(canvas.leftwardTravel, pixels);
        }
    }

    private record Frame(Row fixed, Row tail, int renders) { }

    private static final class LiveOverlay {
        final TripPresentation presentation;
        final Handler handler;
        int renders;
        Fixture fixture;
        final Runnable render;

        LiveOverlay(Context context, Fixture fixture) {
            this.fixture = fixture;
            presentation = new TripPresentation(context);
            handler = (Handler) ProgressChecks.field(presentation, "handler");
            handler.removeCallbacksAndMessages(null);
            render = new Runnable() {
                @Override public void run() {
                    presentation.render(LiveOverlay.this.fixture.snapshot());
                    renders++;
                    handler.postDelayed(this, 500);
                }
            };
            render.run();
        }

        TripLocationCard card() {
            return (TripLocationCard) ProgressChecks.field(presentation, "overlay");
        }

        Frame frame(TripLocationCard expected) {
            TripLocationCard card = card();
            check(card == expected && card.isAttachedToWindow(), "500ms updates keep the same attached card");
            return new Frame(Row.read(card.findViewById(R.id.trip_street)),
                    Row.read(card.findViewById(R.id.trip_street_tail)), renders);
        }

        void changeFixture(Fixture fixture) {
            this.fixture = fixture;
            handler.removeCallbacks(render);
            render.run();
        }

        void close() {
            presentation.close();
        }
    }

    static boolean run(Instrumentation i, Bundle args) throws Exception {
        Context context = i.getTargetContext();
        check(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED,
                "focused marquee runner requires location permission disabled");
        check(Settings.canDrawOverlays(context), "emulator overlay permission granted");
        Fixture fixture = Fixture.read(context, args.getString("fixtureFile"));
        File output = screenshotDirectory(context, args.getString("screenshotDir"));
        if (fixture.first == null) TripUiChecks.checkLocationCard(i, context);
        context.stopService(new Intent(context, HomeMonitorService.class));
        // The ordinary overlay intent can open Trip only after the first-run form is satisfied.
        check(new GateNumberStore(context).save("000000000"), "synthetic setup for the ordinary Trip entry point");
        TripOverlayPreferences preferences = new TripOverlayPreferences(context);
        check(preferences.setVisible(true) && preferences.savePosition(1f, 0f), "test card starts at the right edge");
        ProgressChecks.main(i, () -> ProgressBus.reset(context, DetectionProgress.Reason.RESET));
        SystemClock.sleep(2200);
        context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(500);

        LiveOverlay overlay = main(i, () -> new LiveOverlay(context, fixture));
        try {
            TripLocationCard card = awaitLayout(i, overlay);
            boolean moving = main(i, () -> checkRows(card, fixture));
            Frame initial = main(i, () -> overlay.frame(card));
            capture(i, output, "trip-marquee-start.png");
            if (moving) {
                checkLoop(i, overlay, card, initial, output);
                if (fixture.first == null) checkRestartOnChangedStreet(i, overlay, card, fixture);
            } else {
                check(fixture.first != null, "the public long fixture must exercise motion");
                SystemClock.sleep(1000);
                Frame settled = main(i, () -> overlay.frame(card));
                check(settled.renders > initial.renders && settled.tail.offset == 0
                        && Arrays.equals(initial.fixed.pixels, settled.fixed.pixels)
                        && Arrays.equals(initial.tail.pixels, settled.tail.pixels), "both fitting rows stay static across renders");
            }
            int tappedRow = main(i, () -> card.findViewById(R.id.trip_street_tail).getVisibility() == View.VISIBLE
                    ? R.id.trip_street_tail : R.id.trip_street);
            openTripWithSingleTap(i, card, tappedRow);
            return moving;
        } finally {
            ProgressChecks.main(i, overlay::close);
        }
    }

    private static TripLocationCard awaitLayout(Instrumentation i, LiveOverlay overlay) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            TripLocationCard card = main(i, () -> {
                TripLocationCard attached = overlay.card();
                return attached != null && attached.isAttachedToWindow()
                        && attached.findViewById(R.id.trip_locality).getWidth() > 0 ? attached : null;
            });
            if (card != null) return card;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("production trip overlay is laid out on the emulator");
    }

    private static boolean checkRows(TripLocationCard card, Fixture fixture) {
        WindowManager.LayoutParams panel = (WindowManager.LayoutParams) card.getLayoutParams();
        check((panel.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0 && !card.hasWindowFocus(),
                "the attached marquee works in a non-focusable overlay");
        check((panel.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0, "overlay accepts touch inside its bounds");
        TextView road = card.findViewById(R.id.trip_street);
        TextView tail = card.findViewById(R.id.trip_street_tail);
        String first = road.getText().toString(), second = tail.getText().toString();
        if (fixture.first != null) {
            check(first.equals(fixture.first), "private fixture matches the expected first row");
            check(second.equals(fixture.second), "private fixture matches the expected continuation");
        }
        check(fixture.street.equals(first.isEmpty() ? second : second.isEmpty() ? first : first + " " + second),
                "rows keep all street words in order");
        check(card.getContentDescription().toString().contains(fixture.street), "accessibility keeps the whole street name");
        check(road.getVisibility() != View.VISIBLE || (road.getLineCount() == 1 && road.getLayout().getEllipsisCount(0) == 0
                && road.getEllipsize() == null), "the visible first row is static and unellipsized");
        int width = (first.isEmpty() ? tail : road).getWidth() - road.getCompoundPaddingLeft() - road.getCompoundPaddingRight();
        if (!second.isEmpty()) {
            String next = second.split(" ", 2)[0];
            check(Layout.getDesiredWidth(first, road.getPaint()) <= width
                    && Layout.getDesiredWidth(first.isEmpty() ? next : first + " " + next, road.getPaint()) > width,
                    "the fixed row takes the maximum number of fitting complete words");
            check(tail.getLineCount() == 1 && tail.getLayout().getEllipsisCount(0) == 0,
                    "the continuation renders without an ellipsis");
        }
        boolean overflow = Layout.getDesiredWidth(second, tail.getPaint()) > width;
        check(overflow == tail.isSelected() && (tail.getEllipsize() == TextUtils.TruncateAt.MARQUEE) == overflow,
                "only an overflowing continuation starts native marquee");
        return overflow;
    }

    private static void checkLoop(Instrumentation i, LiveOverlay overlay, TripLocationCard card, Frame initial, File output)
            throws IOException {
        float[] dimensions = main(i, () -> {
            TextView tail = card.findViewById(R.id.trip_street_tail);
            float width = tail.getWidth() - tail.getCompoundPaddingLeft() - tail.getCompoundPaddingRight();
            return new float[]{width, tail.getLayout().getLineWidth(0), tail.getResources().getDisplayMetrics().density};
        });
        float width = dimensions[0], lineWidth = dimensions[1], density = dimensions[2];
        long deadline = SystemClock.uptimeMillis() + (long) ((lineWidth + width) * 1000 / (30 * density)) + 6500;
        float previous = initial.tail.offset;
        float restartedAt = -1f;
        boolean advanced = false, endShown = false;
        do {
            SystemClock.sleep(55);
            Frame current = main(i, () -> overlay.frame(card));
            check(current.fixed.offset == 0 && Arrays.equals(initial.fixed.pixels, current.fixed.pixels),
                    "the first street row stays pixel-identical throughout marquee playback");
            float offset = current.tail.offset;
            if (!advanced && current.renders - initial.renders >= 2 && offset - initial.tail.offset >= 20 * density) {
                check(!Arrays.equals(initial.tail.pixels, current.tail.pixels), "the moving row's painted glyphs change");
                advanced = true;
                capture(i, output, "trip-marquee-moving.png");
            }
            if (!endShown && offset >= lineWidth - width + 3 * density && offset <= lineWidth - width + 22 * density) {
                endShown = true;
                capture(i, output, "trip-marquee-end.png");
            }
            if (previous > lineWidth && offset < 6 * density) {
                check(endShown, "the final word crossed into view before the loop reset");
                restartedAt = offset;
                capture(i, output, "trip-marquee-loop.png");
            }
            if (restartedAt >= 0 && offset - restartedAt >= 10 * density) {
                check(advanced, "motion continued across repeated 500ms renders");
                return;
            }
            previous = offset;
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("native text must keep moving to the end, reset, and start moving again");
    }

    private static void checkRestartOnChangedStreet(Instrumentation i, LiveOverlay overlay, TripLocationCard card,
            Fixture fixture) {
        float density = card.getResources().getDisplayMetrics().density;
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (main(i, () -> overlay.frame(card)).tail.offset < 25 * density) {
            check(SystemClock.uptimeMillis() < deadline, "original street is moving before the address changes");
            SystemClock.sleep(30);
        }
        String suffix = main(i, () -> ((TextView) card.findViewById(R.id.trip_street_tail)).getText().toString());
        String street = fixture.street.replaceFirst("Aleja", "Oleja");
        ProgressChecks.main(i, () -> overlay.changeFixture(new Fixture(fixture.locality, street, null, null)));

        boolean reset = false;
        deadline = SystemClock.uptimeMillis() + 5000;
        do {
            Frame current = main(i, () -> overlay.frame(card));
            if (current.tail.offset < 9 * density) reset = true;
            if (reset && current.tail.offset > 10 * density) {
                boolean sameSuffix = main(i, () -> {
                    TextView road = card.findViewById(R.id.trip_street);
                    TextView tail = card.findViewById(R.id.trip_street_tail);
                    return road.getText().toString().startsWith("Oleja") && suffix.contentEquals(tail.getText());
                });
                check(sameSuffix, "the fixed prefix changed while the continuation stayed identical");
                return;
            }
            SystemClock.sleep(35);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("a new street with the same continuation restarts that marquee");
    }

    private static void openTripWithSingleTap(Instrumentation i, TripLocationCard card, int rowId) {
        int[] center = main(i, () -> {
            View row = card.findViewById(rowId);
            int[] at = new int[2];
            row.getLocationOnScreen(at);
            at[0] += row.getWidth() / 2;
            at[1] += row.getHeight() / 2;
            return at;
        });
        Instrumentation.ActivityMonitor opened = i.addMonitor(TripActivity.class.getName(), null, false);
        try {
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, center[0], center[1], 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    check(i.getUiAutomation().injectInputEvent(event, true), "native overlay tap injected");
                } finally {
                    event.recycle();
                }
            }
            var trip = i.waitForMonitorWithTimeout(opened, 5000);
            check(trip != null, "one tap on the address opens the full Trip screen");
            ProgressChecks.main(i, trip::finish);
        } finally {
            i.removeMonitor(opened);
        }
    }

    private static File screenshotDirectory(Context context, String path) throws IOException {
        if (path == null) return null;
        File external = context.getExternalFilesDir(null).getCanonicalFile();
        File directory = new File(path).getCanonicalFile();
        check(directory.toPath().startsWith(external.toPath()), "screenshot directory is in app-scoped external files");
        check(directory.isDirectory() || directory.mkdirs(), "screenshot directory is available");
        return directory;
    }

    private static void capture(Instrumentation i, File directory, String filename) throws IOException {
        if (directory == null) return;
        Bitmap bitmap = i.getUiAutomation().takeScreenshot();
        check(bitmap != null, "the emulator screenshot is available");
        try (var stream = new FileOutputStream(new File(directory, filename))) {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "screenshot saved");
        } finally {
            bitmap.recycle();
        }
    }

    private static <T> T main(Instrumentation i, Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        ProgressChecks.main(i, () -> result.set(action.get()));
        return result.get();
    }

    private static void check(boolean condition, String message) {
        ProgressChecks.check(condition, message);
    }
}
