package com.pbuchman.duduhome.startup;

import com.pbuchman.duduhome.location.HomeMonitorService;

import android.app.Activity;
import android.os.Bundle;

/** Optional DUDU ignition-task entry point. Starts monitoring, never a call or menu. */
public final class HomeWakeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        HomeMonitorService.ensureStarted(this);
        finish();
    }
}
