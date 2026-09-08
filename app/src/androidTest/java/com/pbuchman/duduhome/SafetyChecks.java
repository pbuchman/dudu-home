package com.pbuchman.duduhome;
import com.pbuchman.duduhome.roborock.RoborockChecks;

import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.automation.HomeEvent;
import com.pbuchman.duduhome.gate.GateCallCoordinator;
import com.pbuchman.duduhome.gate.GateCallState;
import com.pbuchman.duduhome.gate.GateError;
import com.pbuchman.duduhome.gate.GateNumberStore;
import com.pbuchman.duduhome.ui.MainActivity;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.EditText;

/** Dependency-free checks. Refuses to run on a physical radio. */
public final class SafetyChecks extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"),
                    "Emulator only: refusing to touch physical device settings");
            SharedPreferences prefs = getTargetContext().getSharedPreferences("gate_settings", 0);
            require(prefs.edit().clear().commit(), "clear test settings");
            GateNumberStore store = new GateNumberStore(getTargetContext());
            require(store.read() == null, "missing configuration");
            require(GateNumberStore.normalize("bad") == null, "reject invalid number");
            require(!store.save("bad"), "do not persist invalid number");
            Intent launch = new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity screen = startActivitySync(launch);
            runOnMainSync(() -> {
                ((EditText) screen.findViewById(R.id.gate_number_input)).setText("000000000");
                screen.findViewById(R.id.save_number_button).performClick();
            });
            waitForIdleSync();
            require("000000000".equals(store.read()), "UI saved synthetic number");
            require(!prefs.contains("last_dial_started_at"), "saving must never reserve a dial");
            require(store.reserveDial() == GateNumberStore.DialReservation.COOLDOWN, "configuration cooldown");
            require(screen.findViewById(R.id.menu_content).getVisibility() == android.view.View.VISIBLE,
                    "saving returns to menu");
            require(!HomeActions.consume(new Intent(HomeActions.ACTION).putExtra("request_token", "forged")),
                    "external intent cannot request a call");
            require(!HomeActions.callsGate(HomeEvent.OUTBOUND_CHECKPOINT), "vacuum checkpoint never calls gate");
            runOnMainSync(() -> screen.findViewById(R.id.open_gate_button).performClick());
            require(!prefs.contains("last_dial_started_at"), "manual request blocked during setup cooldown");
            runOnMainSync(screen::finish);
            prefs.edit().remove("configured_at").commit();
            require(store.reserveDial() == GateNumberStore.DialReservation.RESERVED, "first reservation");
            require(store.reserveDial() == GateNumberStore.DialReservation.COOLDOWN, "duplicate blocked");
            require(new GateNumberStore(getTargetContext()).cooldownRemainingMillis() > 0,
                    "reservation survives store recreation");
            prefs.edit().putLong("last_dial_started_at", System.currentTimeMillis() + 10000).commit();
            require(store.reserveDial() == GateNumberStore.DialReservation.COOLDOWN, "clock rollback blocked");
            prefs.edit().putLong("last_dial_started_at", System.currentTimeMillis() - 61000).commit();
            require(store.reserveDial() == GateNumberStore.DialReservation.RESERVED, "expired reservation");
            // No private SYU service exists on this emulator: exercising error UI cannot dial.
            prefs.edit().remove("last_dial_started_at").commit();
            Activity manual = startActivitySync(launch);
            require(manual.findViewById(R.id.menu_content).getVisibility() == android.view.View.VISIBLE,
                    "launcher opens menu, not call");
            runOnMainSync(() -> manual.findViewById(R.id.open_gate_button).performClick());
            android.os.SystemClock.sleep(4000);
            require(manual.findViewById(R.id.error_actions).getVisibility() == android.view.View.VISIBLE, "manual error visible");
            runOnMainSync(() -> manual.findViewById(R.id.close_button).performClick());
            require(manual.findViewById(R.id.menu_content).getVisibility() == android.view.View.VISIBLE, "manual error close returns to menu");
            runOnMainSync(() -> ((MainActivity) manual).automaticAction());
            android.os.SystemClock.sleep(4000);
            require(manual.findViewById(R.id.error_actions).getVisibility() == android.view.View.VISIBLE, "automatic error visible");
            runOnMainSync(() -> manual.findViewById(R.id.close_button).performClick());
            require(manual.findViewById(R.id.menu_content).getVisibility() == android.view.View.VISIBLE, "automatic action preserves open menu");
            runOnMainSync(manual::finish);
            require(HomeActions.begin(), "reserve shared gate lease");
            java.util.concurrent.CountDownLatch cleaned = new java.util.concurrent.CountDownLatch(1);
            GateCallCoordinator closed = new GateCallCoordinator(getTargetContext(), "000000000", store,
                    new GateCallCoordinator.Listener() {
                        @Override public void onStateChanged(GateCallState s, String title, String description) { }
                        @Override public void onSuccess() { }
                        @Override public void onError(GateError error, String detail) { }
                        @Override public void onFinished() { HomeActions.end(); cleaned.countDown(); }
                    });
            closed.start(); closed.close();
            require(cleaned.await(5, java.util.concurrent.TimeUnit.SECONDS) && !HomeActions.busy(),
                    "lifecycle close releases shared lease only after cleanup");
            RoborockChecks.run(this);
            NavigationChecks.run(this);
            store.reserveDial();
            result.putString("result", "PASS: gate safety, Roborock signing/transport/encryption, manual-only Mop, daily quota, three tiles, setup, five-second result UI, private import; no real robot or DUDU IPC");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
