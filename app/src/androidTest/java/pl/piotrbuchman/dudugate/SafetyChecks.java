package pl.piotrbuchman.dudugate;

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
            runOnMainSync(screen::finish);
            require(store.reserveDial() == GateNumberStore.DialReservation.RESERVED, "first reservation");
            require(store.reserveDial() == GateNumberStore.DialReservation.COOLDOWN, "duplicate blocked");
            require(new GateNumberStore(getTargetContext()).cooldownRemainingMillis() > 0,
                    "reservation survives store recreation");
            prefs.edit().putLong("last_dial_started_at", System.currentTimeMillis() + 10000).commit();
            require(store.reserveDial() == GateNumberStore.DialReservation.COOLDOWN, "clock rollback blocked");
            prefs.edit().putLong("last_dial_started_at", System.currentTimeMillis() - 61000).commit();
            require(store.reserveDial() == GateNumberStore.DialReservation.RESERVED, "expired reservation");
            result.putString("result", "PASS: setup without dial, validation, persistent reservation, rollback, expiry");
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
