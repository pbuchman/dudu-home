package com.pbuchman.duduhome.startup;

import com.pbuchman.duduhome.location.HomeMonitorService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class HomeBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            HomeMonitorService.ensureStarted(context);
        }
    }
}
