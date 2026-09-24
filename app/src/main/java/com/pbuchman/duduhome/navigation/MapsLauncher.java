package com.pbuchman.duduhome.navigation;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.automation.JourneySession;

/** Explicit one-tap Maps request; URI and destination never enter application logs. */
public final class MapsLauncher {
    public static final String PACKAGE = "com.google.android.apps.maps";
    public enum Result { REQUESTED, MISSING, FAILED, STORAGE_FAILED }
    public interface Transport { boolean available(Intent intent); void open(Intent intent); }
    private final Transport transport;
    private final java.util.function.BooleanSupplier protectMedia;
    public MapsLauncher(Context context) {
        this(new Transport() {
            public boolean available(Intent intent) { return intent.resolveActivity(context.getPackageManager()) != null; }
            public void open(Intent intent) { context.startActivity(intent); }
        }, () -> {
            if (!new JourneySession(context).chooseManualNavigation()) return false;
            HomeActions.schedulingChanged();
            return true;
        });
    }
    public MapsLauncher(Transport transport, java.util.function.BooleanSupplier protectMedia) {
        this.transport = transport; this.protectMedia = protectMedia;
    }
    public static Intent intent(NavigationConfig.Destination place) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q="
                + Double.toString(place.latitude()) + "," + Double.toString(place.longitude()) + "&mode=d"))
                .setPackage(PACKAGE);
    }
    public Result launch(NavigationConfig.Destination place) {
        try {
            Intent intent = intent(place);
            if (!transport.available(intent)) return Result.MISSING;
            if (!protectMedia.getAsBoolean()) return Result.STORAGE_FAILED;
            transport.open(intent);
            return Result.REQUESTED;
        } catch (RuntimeException ignored) { return Result.FAILED; }
    }
}
