package com.pbuchman.duduhome;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
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

    private record Row(float offset, int width, int height, int[] pixels) {
        static Row read(TextView view) {
            if (view.getVisibility() != View.VISIBLE) return new Row(0, 0, 0, new int[0]);
            int width = view.getWidth(), height = view.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            PaintTrace canvas = new PaintTrace(bitmap);
            view.draw(canvas);
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();
            return new Row(canvas.leftwardTravel, width, height, pixels);
        }
        boolean hasInk(float left, float right) {
            int start = Math.max(0, (int) Math.ceil(left)), end = Math.min(width, (int) Math.floor(right));
            for (int y = 0; y < height; y++) {
                for (int x = start; x < end; x++) {
                    if ((pixels[y * width + x] >>> 24) > 0) return true;
                }
            }
            return false;
        }
    }

    private record Frame(Row fixed, Row tail, int renders, long elapsed, boolean shown, int windowVisibility) {
        String describe(long started) {
            return "offset=" + tail.offset + "px, renders=" + renders + ", t=" + (elapsed - started)
                    + "ms, shown=" + shown + ", windowVisibility=" + windowVisibility;
        }
    }

    private record MarqueeGeometry(float viewportWidth, float lineWidth, float lastWordStart, float density,
            int paddingLeft, int screenWidth, int screenHeight) {
        static MarqueeGeometry read(TextView tail) {
            String value = tail.getText().toString();
            int lastWordIndex = value.length();
            while (lastWordIndex > 0 && !Character.isWhitespace(value.charAt(lastWordIndex - 1))) lastWordIndex--;
            Layout layout = tail.getLayout();
            int paddingLeft = tail.getCompoundPaddingLeft();
            var metrics = tail.getResources().getDisplayMetrics();
            return new MarqueeGeometry(tail.getWidth() - paddingLeft - tail.getCompoundPaddingRight(),
                    layout.getLineWidth(0), layout.getPrimaryHorizontal(lastWordIndex), metrics.density,
                    paddingLeft, metrics.widthPixels, metrics.heightPixels);
        }

        boolean lastWordVisible(Row row) {
            float left = lastWordStart - row.offset;
            float right = lineWidth - row.offset;
            return left >= 0 && right <= viewportWidth && row.hasInk(paddingLeft + left, paddingLeft + right);
        }

        long loopBudgetMs() {
            // Allow time for several cycles if a slow emulator skips the last-word viewport.
            long nominalCycleMs = Math.round((lineWidth + viewportWidth / 3f) * 1000 / (30 * density)) + 1200;
            return Math.min(120_000, Math.max(45_000, nominalCycleMs * 3 + 6000));
        }
    }

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
            TextView tail = card.findViewById(R.id.trip_street_tail);
            return new Frame(Row.read(card.findViewById(R.id.trip_street)), Row.read(tail), renders,
                    SystemClock.elapsedRealtime(), tail.isShown(), tail.getWindowVisibility());
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
        MarqueeGeometry geometry = main(i, () -> MarqueeGeometry.read(card.findViewById(R.id.trip_street_tail)));
        long started = initial.elapsed;
        long budget = geometry.loopBudgetMs();
        float minimumDrop = geometry.viewportWidth / 2f;
        Frame last = initial, highest = initial, cyclePeak = initial, restarted = null;
        int samples = 1;
        long widestSamplingGap = 0;
        boolean advanced = false, endShown = false;
        String failureReason = "native text must keep moving to the end, reset, and start moving again";
        do {
            SystemClock.sleep(45);
            Frame current = main(i, () -> overlay.frame(card));
            widestSamplingGap = Math.max(widestSamplingGap, current.elapsed - last.elapsed);
            samples++;
            last = current;
            float offset = current.tail.offset;
            if (offset > highest.tail.offset) highest = current;
            if (offset > cyclePeak.tail.offset) cyclePeak = current;
            if (current.fixed.offset != 0 || !Arrays.equals(initial.fixed.pixels, current.fixed.pixels)) {
                failureReason = "the first street row changed during marquee playback";
                break;
            }

            if (!advanced && current.renders - initial.renders >= 2 && offset - initial.tail.offset >= 20 * geometry.density
                    && !Arrays.equals(initial.tail.pixels, current.tail.pixels)) {
                advanced = true;
                capture(i, output, "trip-marquee-moving.png");
            }
            if (!endShown && geometry.lastWordVisible(current.tail)) {
                endShown = true;
                capture(i, output, "trip-marquee-end.png");
            }

            // The native scroll travels past the line width before looping. Compare with its
            // measured peak so a delayed sample into the next cycle can still see the drop.
            if (cyclePeak.tail.offset >= geometry.lineWidth && cyclePeak.tail.offset - offset >= minimumDrop) {
                cyclePeak = current;
                if (endShown && advanced) {
                    restarted = current;
                    capture(i, output, "trip-marquee-loop.png");
                }
            }
            if (restarted != null && current.renders > restarted.renders
                    && offset - restarted.tail.offset >= 10 * geometry.density
                    && !Arrays.equals(restarted.tail.pixels, current.tail.pixels)) return;
        } while (SystemClock.elapsedRealtime() - started < budget);

        String message = failureReason + " (API " + Build.VERSION.SDK_INT
                + ", screen=" + geometry.screenWidth + "x" + geometry.screenHeight + ", density=" + geometry.density
                + ", viewport=" + geometry.viewportWidth + "px, textWidth=" + geometry.lineWidth
                + "px, endWordBounds=" + geometry.lastWordStart + ".." + geometry.lineWidth + "px"
                + ", budget=" + budget + "ms, samples=" + samples + ", maxGap=" + widestSamplingGap
                + "ms, advancedAcrossRenders=" + advanced + ", endWordVisible=" + endShown
                + ", baseline=[" + initial.describe(started) + "], last=[" + last.describe(started)
                + "], max=[" + highest.describe(started) + "], restart=["
                + (restarted == null ? "unobserved" : restarted.describe(started)) + "])";
        AssertionError failure = new AssertionError(message);
        if (output != null) {
            try {
                saveFrame(output, "trip-marquee-failure-baseline.png", initial);
                saveFrame(output, "trip-marquee-failure-last.png", last);
                saveFrame(output, "trip-marquee-failure-max.png", highest);
                capture(i, output, "trip-marquee-failure-screen.png");
            } catch (IOException | RuntimeException | AssertionError captureFailure) {
                failure.addSuppressed(captureFailure);
            }
        }
        throw failure;
    }

    private static void saveFrame(File directory, String filename, Frame frame) throws IOException {
        Row fixed = frame.fixed, tail = frame.tail;
        try (var stream = new FileOutputStream(new File(directory, filename))) {
            Bitmap bitmap = Bitmap.createBitmap(Math.max(fixed.width, tail.width), fixed.height + tail.height,
                    Bitmap.Config.ARGB_8888);
            try {
                if (fixed.width > 0) bitmap.setPixels(fixed.pixels, 0, fixed.width, 0, 0, fixed.width, fixed.height);
                if (tail.width > 0) bitmap.setPixels(tail.pixels, 0, tail.width, 0, fixed.height, tail.width, tail.height);
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Could not save sampled rows");
            } finally {
                bitmap.recycle();
            }
        }
    }

    private static void checkRestartOnChangedStreet(Instrumentation i, LiveOverlay overlay, TripLocationCard card,
            Fixture fixture) {
        float density = card.getResources().getDisplayMetrics().density;
        long started = SystemClock.elapsedRealtime();
        Frame before;
        do {
            before = main(i, () -> overlay.frame(card));
            if (before.tail.offset >= 40 * density) break;
            check(before.elapsed - started < 12_000, "original street must move before changing address: "
                    + before.describe(started));
            SystemClock.sleep(40);
        } while (true);

        String suffix = main(i, () -> ((TextView) card.findViewById(R.id.trip_street_tail)).getText().toString());
        String street = fixture.street.replaceFirst("Aleja", "Oleja");
        Frame current = main(i, () -> {
            overlay.changeFixture(new Fixture(fixture.locality, street, null, null));
            return overlay.frame(card);
        });
        float minimumDrop = before.tail.offset / 2f;
        Frame reset = null;
        do {
            if (reset == null && before.tail.offset - current.tail.offset >= minimumDrop) reset = current;
            if (reset != null && current.renders > reset.renders && current.tail.offset - reset.tail.offset >= 10 * density) {
                boolean sameSuffix = main(i, () -> {
                    TextView road = card.findViewById(R.id.trip_street);
                    TextView tail = card.findViewById(R.id.trip_street_tail);
                    return road.getText().toString().startsWith("Oleja") && suffix.contentEquals(tail.getText());
                });
                check(sameSuffix, "the fixed prefix changed while the continuation stayed identical");
                return;
            }
            SystemClock.sleep(40);
            current = main(i, () -> overlay.frame(card));
        } while (current.elapsed - before.elapsed < 12_000);
        throw new AssertionError("a new street with the same continuation must restart marquee (before=["
                + before.describe(started) + "], last=[" + current.describe(started) + "], reset=["
                + (reset == null ? "unobserved" : reset.describe(started)) + "])");
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
