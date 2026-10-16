package com.pbuchman.duduhome.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Gravity;
import android.widget.*;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.trip.TripController;
import com.pbuchman.duduhome.trip.TripTime;
import com.pbuchman.duduhome.location.HomeMonitorService;
import java.util.Locale;

/** Passive screen: unlike the protected manual menu it never owns the home-action lease. */
public final class TripActivity extends Activity {
    public static boolean visible;
    private TripController controller;
    private TextView locality, street, distance, status, duration, durationLabel;
    private Button primary, reset, overlayToggle;
    private TripOverlayPreferences overlayPreferences;
    private AlertDialog resetDialog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() { render(controller.snapshot()); handler.postDelayed(this, 500); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_trip);
        controller=TripController.get(this);
        overlayPreferences=new TripOverlayPreferences(this);
        overlayToggle=findViewById(R.id.trip_overlay_toggle);
        overlayToggle.setOnClickListener(v -> {
            if(!overlayPreferences.setVisible(!overlayPreferences.visible()))
                Toast.makeText(this,R.string.trip_overlay_save_error,Toast.LENGTH_LONG).show();
            render(controller.snapshot());
        });
        locality=findViewById(R.id.trip_locality); street=findViewById(R.id.trip_street);
        distance=findViewById(R.id.trip_distance); status=findViewById(R.id.trip_status);
        duration=findViewById(R.id.trip_time); durationLabel=findViewById(R.id.trip_time_label);
        primary=findViewById(R.id.trip_primary); reset=findViewById(R.id.trip_reset);
        findViewById(R.id.trip_back).setOnClickListener(v -> finish());
        primary.setOnClickListener(v -> {
            String current=controller.snapshot().state();
            if(!current.equals("ACTIVE") && !HomeMonitorService.ready(this)) { status.setText(R.string.trip_permissions); return; }
            controller.command(current.equals("ACTIVE") ? "PAUSE" : "START");
            HomeMonitorService.ensureStarted(this);
        });
        reset.setOnClickListener(v -> {
            TripController.Snapshot current=controller.snapshot();
            if (current.meters()>0 || current.millis()>0)
                resetDialog=new AlertDialog.Builder(this).setMessage("Wyzerować czas i dystans?")
                    .setNegativeButton("Anuluj", null)
                    .setPositiveButton("Reset", (dialog, which) -> controller.command("RESET")).show();
            else controller.command("RESET");
        });
        HomeMonitorService.ensureStarted(this);
    }
    /** Shared renderer; instrumentation supplies synthetic snapshots without starting executors. */
    public void render(TripController.Snapshot state) {
        overlayToggle.setText(overlayPreferences.visible() ? R.string.trip_overlay_hide : R.string.trip_overlay_show);
        String title=state.title(),subtitle=state.subtitle();
        locality.setText(title);street.setText(subtitle);street.setVisibility(subtitle.isEmpty()?View.GONE:View.VISIBLE);
        controller.presented("trip_full",visible,state,title,subtitle);
        distance.setText(String.format(Locale.forLanguageTag("pl"), "%.1f km", state.meters()/1000));
        duration.setText(TripTime.format(state.millis()));
        durationLabel.setText(state.partialTime() ? "Czas od aktualizacji" : "Czas sesji");
        primary.setText(state.active() ? "Pauza" : "Start");
        primary.setEnabled(state.loaded() && state.error().isEmpty());
        reset.setEnabled(state.loaded() && state.error().isEmpty());
        status.setText(!state.error().isEmpty() ? state.error() : !state.loaded() ? "Odczytywanie sesji…"
                : state.state().equals("PAUSED") ? "Pomiar wstrzymany"
                : state.active() ? "Czas i dystans są liczone również w tle" : "Pomiar wstrzymany");
    }
    @Override public void onResume() { super.onResume(); visible=true; handler.post(tick); }
    @Override public void onPause() { visible=false;controller.presented("trip_full",false,controller.snapshot(),"",""); handler.removeCallbacks(tick); super.onPause(); }
}
