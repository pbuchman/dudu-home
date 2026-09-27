package com.pbuchman.duduhome.ui;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.navigation.*;
import org.json.*;
import java.util.ArrayList;
import java.util.List;

/** Synthetic fixtures only; SafetyChecks refuses hardware devices before invoking this suite. */
public final class NavigationGroupChecks {
    public static String fixture(int count) throws Exception {
        JSONArray places = new JSONArray();
        for (int n = 0; n < count; n++) places.put(new JSONObject()
                .put("label", "Fixture " + (char) ('A' + n)).put("icon", "pin")
                .put("address", "Synthetic address " + (char) ('A' + n))
                .put("latitude", n * .001).put("longitude", n * .002));
        JSONObject slot = new JSONObject().put("slot", 2).put("label", "Fixture group")
                .put("icon", "squash").put("destinations", places);
        return new JSONObject().put("schema_version", 2).put("slots", new JSONArray().put(slot)).toString();
    }
    public static void run(Instrumentation i) throws Exception {
        for (int count : new int[]{0, 1, 2, 3, 12}) {
            NavigationConfig config = NavigationConfig.parse(fixture(count));
            require(count == 0 ? config.slot(1) == null : config.slot(1).destinations().size() == count, "list count");
            require(NavigationConfig.parse(config.serialize()).serialize().equals(config.serialize()), "group round trip");
            require(count == 1 || config.destination(1) == null, "no implicit first group destination");
        }
        String sample = fixture(3);
        for (String bad : new String[]{fixture(13), sample.replace("\"destinations\":", "\"destination\":null,\"destinations\":"),
                sample.replace("\"label\":\"Fixture group\",", ""),
                sample.replace("\"slot\":2", "\"slot\":true"),
                sample.replace("\"icon\":\"squash\"", "\"icon\":\"unknown\""),
                sample.replace("Synthetic address A", "bad\\nline")}) {
            boolean rejected = false;
            try { NavigationConfig.parse(bad); } catch (Exception expected) { rejected = true; }
            require(rejected, "invalid group rejected");
        }
        JSONObject address = new JSONObject(sample);
        address.getJSONArray("slots").getJSONObject(0).getJSONArray("destinations").getJSONObject(1).put("navigate_by", "address");
        NavigationConfig addressConfig = NavigationConfig.parse(address.toString());
        require(MapsLauncher.intent(addressConfig.slot(1).destinations().get(1)).getDataString()
                .equals("google.navigation:q=Synthetic%20address%20B&mode=d"), "per-group destination address mode");
        NavigationStore store = new NavigationStore(i.getTargetContext()); store.save(NavigationConfig.parse(sample));
        MainActivity screen = (MainActivity) i.startActivitySync(new Intent(i.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        var field = MainActivity.class.getDeclaredField("navigation"); field.setAccessible(true);
        NavigationPanel realPanel = (NavigationPanel) field.get(screen);
        i.runOnMainSync(() -> {
            screen.findViewById(R.id.navigation_slot_2).performClick();
            require(realPanel.busy() && !screen.allowsExternalLaunch(), "modal protects actual Activity from external launch");
            realPanel.destroy();
            require(!realPanel.busy(), "dismiss releases manual UI");
        });
        List<Intent> opens = new ArrayList<>(); int[] reservations = {0}; boolean[] available = {true}, protectedWrite = {true}, allowed = {true};
        MapsLauncher maps = new MapsLauncher(new MapsLauncher.Transport() {
            public boolean available(Intent intent) { return available[0]; }
            public void open(Intent intent) { opens.add(intent); }
        }, () -> { reservations[0]++; return protectedWrite[0]; });
        NavigationPanel panel = new NavigationPanel(screen, () -> allowed[0], null, maps);
        try {
            i.runOnMainSync(() -> {
                panel.render();
                require(screen.findViewById(R.id.navigation_slot_2).getContentDescription().toString().contains("3 miejsca"),
                        "Polish plural follows UI language, not system locale");
                screen.findViewById(R.id.navigation_slot_2).performClick();
                require(panel.busy() && opens.isEmpty() && reservations[0] == 0, "opening is inert");
                Dialog first = dialog(panel);
                screen.findViewById(R.id.navigation_slot_2).performClick();
                require(first == dialog(panel), "no duplicate selector");
                first.cancel();
                require(!panel.busy() && reservations[0] == 0, "cancel is inert");
                screen.findViewById(R.id.navigation_slot_2).performClick();
                click(panel, "Zamknij");
                require(!panel.busy(), "close button releases selector");
                for (String label : new String[]{"Fixture A", "Fixture B", "Fixture C"}) {
                    panel.resumed(); screen.findViewById(R.id.navigation_slot_2).performClick();
                    View choice = find(dialog(panel).getWindow().getDecorView(), label);
                    require(choice != null, "destination is accessible"); choice.performClick(); choice.performClick();
                    require(!panel.busy(), "choice closes selector");
                }
                require(opens.size() == 3 && reservations[0] == 3, "one launch per selection despite double tap");
                require(opens.get(0).getDataString().equals("google.navigation:q=0.0,0.0&mode=d")
                        && opens.get(1).getDataString().equals("google.navigation:q=0.001,0.002&mode=d")
                        && opens.get(2).getDataString().equals("google.navigation:q=0.002,0.004&mode=d"), "exact selection order including third row");
                panel.resumed(); screen.findViewById(R.id.navigation_slot_2).performClick();
                available[0] = false; click(panel, "Fixture A");
                require(panel.busy() && opens.size() == 3 && reservations[0] == 3, "missing Maps keeps selector without reservation");
                available[0] = true; protectedWrite[0] = false; click(panel, "Fixture A");
                require(panel.busy() && opens.size() == 3, "storage error never opens Maps");
                protectedWrite[0] = true; allowed[0] = false; click(panel, "Fixture A");
                require(!panel.busy() && opens.size() == 3, "guard rechecked on selection"); allowed[0] = true;
                screen.findViewById(R.id.navigation_slot_2).performClick();
                Bundle state = new Bundle(); panel.saveState(state); panel.destroy();
                NavigationPanel restored = new NavigationPanel(screen, () -> true, state, maps);
                restored.render(); require(restored.busy() && opens.size() == 3, "recreation restores selector without navigation");
                restored.destroy();
            });
            store.save(NavigationConfig.parse(fixture(1)));
            i.runOnMainSync(() -> {
                panel.render(); panel.resumed(); screen.findViewById(R.id.navigation_slot_2).performClick();
                require(!panel.busy() && opens.size() == 4, "singleton bypasses modal");
            });
            store.save(NavigationConfig.parse(fixture(12)));
            i.runOnMainSync(() -> { panel.resumed(); panel.render(); screen.findViewById(R.id.navigation_slot_2).performClick();
                require(find(dialog(panel).getWindow().getDecorView(), "Fixture L") != null, "all configured rows exist in scroll view");
                panel.destroy(); });
            store.save(NavigationConfig.parse(sample));
            i.runOnMainSync(() -> { panel.render(); screen.findViewById(R.id.navigation_slot_2).performClick(); });
            i.waitForIdleSync();
            try (var out = new java.io.FileOutputStream(new java.io.File(screen.getExternalFilesDir(null), "navigation-groups.png"))) {
                i.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
        } finally { i.runOnMainSync(() -> { panel.destroy(); screen.finish(); }); store.save(NavigationConfig.empty()); }
    }
    private static Dialog dialog(NavigationPanel panel) {
        try { var field = NavigationPanel.class.getDeclaredField("selector"); field.setAccessible(true); return (Dialog) field.get(panel); }
        catch (Exception e) { throw new AssertionError("selector unavailable"); }
    }
    private static View find(View view, String prefix) {
        if (view.isClickable() && view.getContentDescription() != null && view.getContentDescription().toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup group) for (int n = 0; n < group.getChildCount(); n++) {
            View found = find(group.getChildAt(n), prefix); if (found != null) return found;
        }
        return null;
    }
    private static void click(NavigationPanel panel, String prefix) {
        View view = find(dialog(panel).getWindow().getDecorView(), prefix);
        require(view != null, "expected control"); view.performClick();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
