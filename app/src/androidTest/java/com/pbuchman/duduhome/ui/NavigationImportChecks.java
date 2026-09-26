package com.pbuchman.duduhome.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import com.pbuchman.duduhome.navigation.NavigationStore;
import java.nio.file.Files;

/** Tests document result handling with local synthetic data; called behind the emulator guard. */
public final class NavigationImportChecks {
    public static void run(Instrumentation i) throws Exception {
        MainActivity activity = (MainActivity) i.startActivitySync(new Intent(i.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        NavigationPanel panel = new NavigationPanel(activity, () -> true, null);
        NavigationStore store = new NavigationStore(activity);
        String before = store.load().serialize();
        IntentFilter documents = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
        documents.addCategory(Intent.CATEGORY_OPENABLE);
        documents.addDataType("*/*");
        Instrumentation.ActivityMonitor chooser = i.addMonitor(documents,
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        i.runOnMainSync(panel::pick);
        i.waitForIdleSync();
        require(chooser.getHits() == 1, "system document picker requested once");
        i.removeMonitor(chooser);
        i.runOnMainSync(() -> panel.result(201, Activity.RESULT_CANCELED, null));
        require(!panel.busy() && store.load().serialize().equals(before), "cancel keeps destinations");
        java.io.File file = new java.io.File(activity.getCacheDir(), "navigation-test-input");
        try {
            Files.write(file.toPath(), "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            deliver(i, panel, file);
            require(store.load().serialize().equals(before), "bad document keeps destinations");
            Files.write(file.toPath(), ("{\"schema_version\":1,\"slots\":[{\"slot\":3,\"destination\":{"
                    + "\"label\":\"Fixture C\",\"icon\":\"pin\",\"latitude\":0,\"longitude\":0}}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            deliver(i, panel, file);
            require(store.load().destination(2).label().equals("Fixture C") && store.load().destination(0) == null,
                    "selected document replaces slots atomically");
            store.save(com.pbuchman.duduhome.navigation.NavigationConfig.empty());
        } finally { file.delete(); i.runOnMainSync(activity::finish); }
    }
    private static void deliver(Instrumentation i, NavigationPanel panel, java.io.File file) throws Exception {
        i.runOnMainSync(() -> panel.result(201, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(file))));
        long end = android.os.SystemClock.elapsedRealtime() + 5000;
        boolean[] busy = {true};
        do {
            i.waitForIdleSync(); i.runOnMainSync(() -> busy[0] = panel.busy());
            if (busy[0]) android.os.SystemClock.sleep(25);
        } while (busy[0] && android.os.SystemClock.elapsedRealtime() < end);
        require(!busy[0], "document read completes");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
