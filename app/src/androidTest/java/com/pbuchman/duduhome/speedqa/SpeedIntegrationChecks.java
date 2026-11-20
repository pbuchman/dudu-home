package com.pbuchman.duduhome.speedqa;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.AutomationRuntime;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.automation.JourneySession;
import com.pbuchman.duduhome.automation.YanosikLauncher;
import com.pbuchman.duduhome.automation.YanosikPresence;
import com.pbuchman.duduhome.gate.GateNumberStore;
import com.pbuchman.duduhome.speed.SpeedConnection;
import com.pbuchman.duduhome.ui.MainActivity;
import com.pbuchman.duduhome.ui.SpeedTestActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.net.ssl.HttpsURLConnection;

/** Exercises real Android Views, WebView, lifecycle and default-network callbacks. */
public final class SpeedIntegrationChecks extends Instrumentation {
    private static final int LIMIT_BYTES = 16 * 1024 * 1024;
    private static final String PREFIX = "speed-qa-";
    private MainActivity menu;
    private int port;
    private String scenario;
    private int assertions;
    private boolean galleryLabel;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        port = Integer.parseInt(args.getString("port", "0"));
        scenario = args.getString("scenario", "all");
        galleryLabel = "true".equals(args.getString("gallery_label"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(Build.VERSION.SDK_INT == 36 && (Build.HARDWARE.contains("ranchu")
                    || Build.HARDWARE.contains("goldfish")), "API 36 emulator required");
            check(port > 0 || scenario.equals("cloudflare"), "fixture port required");
            check(!galleryLabel || scenario.equals("success"), "gallery label applies only to loopback success");
            check(getTargetContext().getPackageManager().resolveService(new Intent().setClassName(
                    "com.syu.ms", "app.ToolkitService"), 0) == null, "no vendor service on emulator");
            check(new GateNumberStore(getTargetContext()).save("000000000"), "synthetic number saved");
            check(getTargetContext().getSharedPreferences("gate_settings", 0).edit()
                    .remove("configured_at").remove("last_dial_started_at").commit(), "fixture cooldown cleared");
            SpeedTestActivity.setEmulatorEndpointForTest(port);
            openMenu();
            await(() -> network(SpeedConnection.Transport.WIFI), 20_000, "validated Wi-Fi");
            awaitMenu();

            if (scenario.equals("cloudflare")) runCloudflare();
            else if (scenario.equals("success")) runSuccess();
            else if (scenario.equals("failures")) runFailures();
            else if (scenario.equals("bounds")) runBounds();
            else if (scenario.equals("lifecycle")) runLifecycle();
            else if (scenario.equals("network")) runNetwork();
            else if (scenario.equals("all")) {
                runSuccess();
                runFailures();
                runBounds();
                runLifecycle();
                runNetwork();
            } else throw new AssertionError("unknown scenario");

            check(!getTargetContext().getSharedPreferences("gate_settings", 0).contains("last_dial_started_at"),
                    "no dial reservation or command");
            result.putString("result", "PASS: " + scenario + " " + assertions + " assertions");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL: " + scenario + " " + failure);
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            result.putString("stack", trace.toString());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            SpeedTestActivity.setEmulatorEndpointForTest(0);
        }
    }

    private void runCloudflare() throws Exception {
        Activity speed = startSpeed();
        awaitResult(speed, 49_000);
        capture("cloudflare-result");
        click(speed, "speed_back");
        awaitMenu();
    }

    private void runSuccess() throws Exception {
        awaitMenu();
        capture("menu");
        mode("ok");
        Activity speed = startSpeed();
        await(() -> count("down") > 0, 14_000, "first HTTPS download reached fixture");
        if (galleryLabel) addGalleryLabel(speed);
        check(!HomeActions.busy(), "manual speed test holds no action lease");
        check(shown(speed, "speed_cancel"), "running UI exposes cancellation");
        capture("running");
        awaitResult(speed, 40_000);
        JSONObject totals = fixture("status");
        check(totals.getInt("ping") == 5, "exactly five zero-byte HTTPS samples");
        check(totals.getInt("down") == 5 && totals.getInt("up") == 5, "five download and five upload requests");
        for (String side : List.of("down", "up")) {
            int bytes = totals.getInt(side + "_bytes");
            check(bytes == (13 * 1024 + 256) * 1024 && bytes <= LIMIT_BYTES,
                    side + " requested payload is exactly 13.25 MiB and within 16 MiB");
        }
        check(totals.getInt("rejected") == 0, "successful run needs no fixture enforcement");
        capture("result");
        click(speed, "speed_back");
        awaitMenu();
    }

    private void addGalleryLabel(Activity speed) {
        check(onMain(() -> {
            var labels = new ArrayList<View>();
            CharSequence provider = speed.getText(R.string.speed_provider);
            speed.getWindow().getDecorView().findViewsWithText(labels, provider, View.FIND_VIEWS_WITH_TEXT);
            if (labels.size() != 1 || !(labels.get(0) instanceof TextView label)
                    || !provider.toString().contentEquals(label.getText())) return false;
            label.setText("Pomiar syntetyczny · serwer lokalny");
            return true;
        }), "fixture capture identifies synthetic local measurement");
    }

    private void runFailures() throws Exception {
        mode("slow");
        Activity speed = startSpeed();
        await(() -> count("down") > 0, 14_000, "slow HTTPS download arrived");
        long start = SystemClock.elapsedRealtime();
        awaitReason("TIMEOUT", start, 13_000);
        check(SystemClock.elapsedRealtime() - start <= 12_000, "per-request timeout is bounded");
        awaitError(speed);
        check(count("up") == 0, "timeout never uploads");
        capture("timeout");

        mode("rate");
        long retryStart = SystemClock.elapsedRealtime();
        click(speed, "speed_retry");
        await(() -> count("ping") >= 1, 5_000, "rate-limited HTTPS request arrived");
        awaitReason("SERVER", retryStart, 25_000);
        awaitError(speed);
        check(count("ping") == 4 && count("down") == 0 && count("up") == 0,
                "429 stays bounded and prevents a partial success");
        capture("error");

        mode("retry");
        click(speed, "speed_retry");
        awaitResult(speed, 40_000);
        check(count("ping") == 5, "explicit error retry has independent latency budget");
        click(speed, "speed_back");
        awaitMenu();
    }

    private void runLifecycle() throws Exception {
        mode("cancel");
        Activity first = startSpeed();
        await(() -> count("down") > 0, 14_000, "cancelled run reached a pending HTTPS download");
        long start = SystemClock.elapsedRealtime();
        click(first, "speed_cancel");
        awaitReason("CANCELLED", start, 3_000);
        awaitMenu();
        mode("retry");
        Activity next = startSpeed();
        check(first != next, "manual relaunch starts a fresh activity");
        awaitResult(next, 40_000);
        Thread.sleep(4500); // Allow the cancelled fixture response and any obsolete callbacks to settle.
        check(!shown(next, "speed_error_title") && shown(next, "speed_download_value"), "stale callbacks did not replace the new result");
        click(next, "speed_back");
        awaitMenu();

        mode("takeover");
        Activity covered = startSpeed();
        await(() -> count("down") > 0, 14_000, "takeover starts during real HTTPS");
        var runtime = new AutomationRuntime(getTargetContext());
        check(new JourneySession(getTargetContext()).ensureBoot(), "synthetic driving cycle available");
        // Keep the real queue, reservation and launcher. Substitute only package resolution and
        // the presence signal so an installed test Activity stands in for the driving app.
        var launcher = AutomationRuntime.class.getDeclaredField("yanosik");
        launcher.setAccessible(true);
        String componentPackage = getContext().getPackageName();
        launcher.set(runtime, new YanosikLauncher(new YanosikLauncher.Launch() {
            @Override public Intent resolve() {
                return new Intent().setClassName(componentPackage, SpeedTakeoverActivity.class.getName());
            }
            @Override public void open(Intent intent) {
                getTargetContext().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }, () -> YanosikPresence.State.NO_WORK_SIGNAL));
        long takeoverStart = SystemClock.elapsedRealtime();
        try {
            runOnMainSync(() -> SpeedDriveReplay.moving(getTargetContext(), runtime));
            await(() -> {
                var root = getUiAutomation().getRootInActiveWindow();
                return root != null && !root.findAccessibilityNodeInfosByText(SpeedTakeoverActivity.CONTENT).isEmpty();
            }, 5_000, "real test takeover Activity is foreground");
            await(() -> journalAfter(takeoverStart).stream().anyMatch(line -> line.contains(" YANOSIK_LAUNCH_REQUESTED ")),
                    5_000, "runtime admitted normal media launch step");
            awaitReason("PREEMPTED", takeoverStart, 3_000);
            check(onMain(covered::isFinishing) || onMain(covered::isDestroyed), "covered measurement disposed");
            getUiAutomation().waitForIdle(300, 2_000);
            capture("takeover");
        } finally {
            runOnMainSync(runtime::close);
        }
        shell("input keyevent KEYCODE_BACK");
        awaitMenu();

        mode("automation");
        Activity automated = startSpeed();
        await(() -> count("down") > 0, 14_000, "departure starts during real HTTPS");
        await(() -> onMain(() -> HomeActions.visibleActivity() == null), 2_000,
                "MainActivity is hidden so dispatch requires the internal token");
        long autoStart = SystemClock.elapsedRealtime();
        var departure = new AutomationRuntime(getTargetContext());
        try {
            runOnMainSync(() -> SpeedDriveReplay.parking(getTargetContext(), departure));
            awaitReason("PREEMPTED", autoStart, 5_000);
            await(() -> journalAfter(autoStart).stream().anyMatch(line -> line.contains(" DISPATCH_GATE ID=")),
                    5_000, "actual departure queue dispatched through HomeActions");
            await(() -> shown(menu, "call_status_content") && onMain(menu::hasWindowFocus), 5_000,
                    "real MainActivity consumed internal token and presented gate status");
            check(onMain(automated::isFinishing) || onMain(automated::isDestroyed), "automatic action disposes measurement");
            capture("automation-takeover");
        } finally {
            runOnMainSync(departure::close);
        }
        await(() -> shown(menu, "error_actions"), 9_000, "missing emulator Binder is reported through the real flow");
        click(menu, "close_button");
        await(() -> onMain(menu::isDestroyed), 5_000, "dismissed background automatic action closes its task");
        openMenu();
        awaitMenu();
    }

    private void runBounds() throws Exception {
        for (String side : List.of("down", "up")) {
            mode("budget-" + side);
            long start = SystemClock.elapsedRealtime();
            Activity speed = startSpeed();
            awaitReason("DATA_LIMIT", start, 30_000);
            awaitError(speed);
            check(count(side) == 5 && count(side + "_bytes") == (13 * 1024 + 256) * 1024,
                    "429 retry for " + side + " would exceed 16 MiB and never reaches HTTPS fixture");
            check(count("rejected") == 0, "client enforces " + side + " budget before fixture");
            capture("budget-" + side);
            click(speed, "speed_back");
            awaitMenu();
        }
        mode("slow-total");
        long start = SystemClock.elapsedRealtime();
        Activity speed = startSpeed();
        awaitReason("TIMEOUT", start, 48_000);
        long elapsed = SystemClock.elapsedRealtime() - start;
        check(elapsed >= 44_000 && elapsed <= 47_000, "45-second total deadline stops sub-ten-second requests");
        awaitError(speed);
        check(count("ping") == 5 && count("down") == 1 && count("up") == 0,
                "total timeout stops the first download after five slow successful latency probes");
        capture("total-timeout");
        click(speed, "speed_back");
        awaitMenu();
    }

    private void runNetwork() throws Exception {
        try {
            mode("switch");
            Activity speed = startSpeed();
            await(() -> count("down") > 0, 14_000, "network change happens during an active HTTPS run");
            long switchStart = SystemClock.elapsedRealtime();
            shell("svc wifi disable");
            await(() -> network(SpeedConnection.Transport.CELLULAR), 25_000, "real default route changed from Wi-Fi to cellular");
            awaitReason("NETWORK_CHANGED", switchStart, 4_000);
            awaitError(speed);
            check(textEquals(speed, "speed_error_title", R.string.speed_network_changed), "switch shows its own message");
            check(count("up") == 0, "changed-network run cannot upload or publish mixed results");
            capture("network-changed");
            click(speed, "speed_back");
            awaitMenu();

            mode("offline");
            shell("svc data disable");
            await(() -> SpeedConnection.current(getTargetContext()).network() == null, 20_000, "offline default route");
            long offlineStart = SystemClock.elapsedRealtime();
            speed = startSpeed();
            awaitReason("NO_NETWORK", offlineStart, 4_000);
            awaitError(speed);
            check(textEquals(speed, "speed_error_title", R.string.speed_no_access), "offline start explains loss of access");
            check(count("ping") == 0 && count("down") == 0 && count("up") == 0, "offline start made zero HTTPS requests");
            capture("offline");
            click(speed, "speed_back");
            awaitMenu();
        } finally {
            shell("svc data enable");
            shell("svc wifi enable");
        }
    }

    private Activity startSpeed() {
        ActivityMonitor monitor = addMonitor(SpeedTestActivity.class.getName(), null, false);
        click(menu, "internet_speed_tile");
        Activity speed = waitForMonitorWithTimeout(monitor, 5_000);
        check(speed != null, "tile launched real speed Activity");
        return speed;
    }

    private void openMenu() {
        menu = (MainActivity) startActivitySync(new Intent(Intent.ACTION_MAIN)
                .setClass(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void awaitResult(Activity speed, long timeout) throws Exception {
        await(() -> shown(speed, "speed_error_title") || metrics(speed), timeout, "measurement finished");
        check(metrics(speed), "result has measured ping, download and upload");
    }

    private boolean metrics(Activity speed) {
        return metric(speed, "speed_download_value") && metric(speed, "speed_upload_value")
                && metric(speed, "speed_ping_value") && !shown(speed, "speed_error_title")
                && !shown(speed, "speed_cancel");
    }

    private void awaitError(Activity screen) throws Exception {
        await(() -> shown(screen, "speed_error_title") && shown(screen, "speed_retry"), 3_000, "error offers explicit retry");
        check(!shown(screen, "speed_cancel"), "error is no longer running");
    }

    private void awaitMenu() throws Exception {
        await(() -> shown(menu, "menu_content") && onMain(menu::hasWindowFocus), 8_000, "returned to visible Dudu menu");
    }

    private boolean network(SpeedConnection.Transport transport) {
        SpeedConnection network = SpeedConnection.current(getTargetContext());
        return network.transport() == transport && network.validated();
    }

    private void awaitReason(String reason, long from, long timeout) throws Exception {
        await(() -> journalAfter(from).stream().anyMatch(line -> line.contains(" SPEED_TEST_END ")
                && line.matches(".* REASON=" + reason + "(?: .*|$)")), timeout, "completion reason " + reason);
    }

    private List<String> journalAfter(long from) {
        try {
            File file = new File(getTargetContext().getNoBackupFilesDir(), "diagnostics.txt");
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream().filter(line -> {
                String[] fields = line.split(" ", 3);
                return fields.length == 3 && fields[1].matches("\\d+") && Long.parseLong(fields[1]) >= from;
            }).toList();
        } catch (AccessDeniedException | NoSuchFileException pending) {
            // Creation, rotation and owner-only permission changes can race with this poll.
            return List.of();
        } catch (Exception failure) {
            throw new AssertionError("cannot read synthetic diagnostics", failure);
        }
    }

    private void mode(String name) throws Exception { fixture(name); }

    private int count(String channel) {
        try { return fixture("status").getInt(channel); }
        catch (Exception failure) { throw new AssertionError("cannot query HTTPS fixture stats", failure); }
    }

    private JSONObject fixture(String command) throws Exception {
        URL url = new URL("https://127.0.0.1:" + port + "/control/" + command);
        HttpsURLConnection call = (HttpsURLConnection) url.openConnection();
        call.setConnectTimeout(2_000);
        call.setReadTimeout(2_000);
        try (InputStream data = call.getInputStream()) {
            if (call.getResponseCode() == 204) return new JSONObject();
            return new JSONObject(new String(data.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            call.disconnect();
        }
    }

    private String shell(String command) throws Exception {
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void capture(String name) throws Exception {
        waitForIdleSync();
        Bitmap shot = getUiAutomation().takeScreenshot();
        check(shot != null, "captured " + name);
        try (FileOutputStream output = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), PREFIX + name + ".png"))) {
            check(shot.compress(Bitmap.CompressFormat.PNG, 100, output), "wrote " + name);
        } finally {
            shot.recycle();
        }
    }

    private View view(Activity screen, String resource) {
        return screen.findViewById(screen.getResources().getIdentifier(resource, "id", screen.getPackageName()));
    }

    private boolean shown(Activity screen, String id) {
        return onMain(() -> {
            View candidate = view(screen, id);
            return candidate != null && candidate.isShown();
        });
    }

    private boolean metric(Activity screen, String id) {
        return onMain(() -> view(screen, id) instanceof TextView value && value.getText().toString().matches(".*\\d.*"));
    }

    private boolean textEquals(Activity screen, String id, int text) {
        return onMain(() -> view(screen, id) instanceof TextView value
                && screen.getString(text).contentEquals(value.getText()));
    }

    private void click(Activity screen, String id) {
        check(onMain(() -> {
            View button = view(screen, id);
            return button != null && button.isShown() && button.performClick();
        }), "clicked " + id);
    }

    private boolean onMain(BooleanSupplier assertion) {
        var value = new AtomicReference<Boolean>();
        runOnMainSync(() -> value.set(assertion.getAsBoolean()));
        return Boolean.TRUE.equals(value.get());
    }

    private void await(BooleanSupplier condition, long timeout, String label) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        check(condition.getAsBoolean(), label);
    }

    private void check(boolean pass, String message) {
        assertions++;
        if (!pass) throw new AssertionError(message);
    }

}
