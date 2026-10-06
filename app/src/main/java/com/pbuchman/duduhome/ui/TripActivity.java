package com.pbuchman.duduhome.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Gravity;
import android.widget.*;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.trip.TripController;
import com.pbuchman.duduhome.location.HomeMonitorService;
import java.util.Locale;

/** Passive screen: unlike the protected manual menu it never owns the home-action lease. */
public final class TripActivity extends Activity {
    public static boolean visible;
    private TripController controller;
    private TextView locality, street, distance, status;
    private Button primary, end;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() { render(controller.snapshot()); handler.postDelayed(this, 500); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_trip);
        controller=TripController.get(this);
        locality=findViewById(R.id.trip_locality); street=findViewById(R.id.trip_street);
        distance=findViewById(R.id.trip_distance); status=findViewById(R.id.trip_status);
        primary=findViewById(R.id.trip_primary); end=findViewById(R.id.trip_end);
        findViewById(R.id.trip_back).setOnClickListener(v -> finish());
        primary.setOnClickListener(v -> {
            String current=controller.snapshot().state();
            if(!current.equals("ACTIVE") && !HomeMonitorService.ready(this)) { status.setText(R.string.trip_permissions); return; }
            controller.command(current.equals("ACTIVE") ? "PAUSE" : current.equals("PAUSED") ? "RESUME" : "START");
            HomeMonitorService.ensureStarted(this);
        });
        end.setOnClickListener(v -> controller.command("END"));
        HomeMonitorService.ensureStarted(this);
    }
    /** Shared renderer; instrumentation supplies synthetic snapshots without starting executors. */
    public void render(TripController.Snapshot state) {
        locality.setText(state.title()); street.setText(state.subtitle());
        distance.setText(String.format(Locale.forLanguageTag("pl"), "%.1f km", state.meters()/1000));
        primary.setText(state.active() ? "Pauza" : state.state().equals("PAUSED") ? "Wznów" : "Rozpocznij");
        primary.setEnabled(state.loaded() && state.error().isEmpty());
        end.setVisibility(state.active() || state.state().equals("PAUSED") ? View.VISIBLE : View.GONE);
        status.setText(!state.error().isEmpty() ? state.error() : !state.loaded() ? "Odczytywanie sesji…"
                : state.state().equals("PAUSED") ? "Pomiar wstrzymany"
                : state.state().equals("FINISHED") ? "Zakończony przejazd"
                : state.active() ? "Dystans jest liczony również w tle" : "Rozpocznij nowy przejazd");
    }
    @Override public void onResume() { super.onResume(); visible=true; handler.post(tick); }
    @Override public void onPause() { visible=false; handler.removeCallbacks(tick); super.onPause(); }
}
