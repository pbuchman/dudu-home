package com.pbuchman.duduhome.speedqa;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** A real competing Activity, installed only in the instrumentation APK. */
public final class SpeedTakeoverActivity extends Activity {
    static final String CONTENT = "Testowe przejęcie ekranu";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText(CONTENT);
        label.setTextSize(24);
        label.setGravity(Gravity.CENTER);
        setContentView(label);
    }
}
