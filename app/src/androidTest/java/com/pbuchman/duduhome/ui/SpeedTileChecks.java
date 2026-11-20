package com.pbuchman.duduhome.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.gate.GateNumberStore;
import com.pbuchman.duduhome.speed.SpeedConnection;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;

/** Tests the real menu on a fresh emulator. Its activity monitor blocks the measurement screen. */
public final class SpeedTileChecks extends Instrumentation {
    private static final int[] MENU_TILES = {
            R.id.navigation_slot_1, R.id.navigation_slot_2, R.id.navigation_slot_3, R.id.internet_speed_tile
    };

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"),
                    "Emulator only");
            runChecks();
            result.putString("result", "PASS: speed tile order, narrow and wide layouts, accessibility, "
                    + "live label, guard and explicit launch without measurement");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            result.putString("result", "FAIL: " + error.getMessage());
            result.putString("stack", trace.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runChecks() throws ReflectiveOperationException {
        Context target = getTargetContext();
        check(target.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED,
                "Use a fresh emulator with location denied");
        check(new GateNumberStore(target).read() == null, "Use fresh emulator app data");
        ActivityMonitor monitor = addMonitor(SpeedTestActivity.class.getName(),
                new ActivityResult(Activity.RESULT_CANCELED, null), true);
        setInTouchMode(false);
        MainActivity screen = (MainActivity) startActivitySync(new Intent(target, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            var showMenu = MainActivity.class.getDeclaredMethod("showMenu");
            showMenu.setAccessible(true);
            var field = MainActivity.class.getDeclaredField("navigation");
            field.setAccessible(true);
            NavigationPanel panel = (NavigationPanel) field.get(screen);
            main(() -> {
                // Render the actual menu without persisting any phone, navigation or automation data.
                try {
                    showMenu.invoke(screen);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                LinearLayout row = screen.findViewById(R.id.navigation_tiles);
                verifyLayout(screen, panel, row, 960, false);
                verifyLayout(screen, panel, row, 600, false);
                verifyLayout(screen, panel, row, 480, false);
                verifyLayout(screen, panel, row, 320, true);
                panel.render();
                View tile = screen.findViewById(R.id.internet_speed_tile);
                TextView subtitle = tile.findViewById(R.id.internet_speed_status);
                SpeedConnection connection = SpeedConnection.current(screen);
                String expected = screen.getString(connection.network() == null
                        ? R.string.speed_tile_no_network : connection.label());
                String budget = screen.getString(R.string.speed_tile_budget);
                check(screen.getString(R.string.speed_tile_status, expected, budget).contentEquals(subtitle.getText()),
                        "menu reflects current network and shows the payload budget");
                String description = tile.getContentDescription().toString();
                check(description.contains(expected) && description.contains(budget),
                        "accessibility includes the network and payload budget");
                check(monitor.getHits() == 0 && !HomeActions.busy(), "render and status are passive");
            });

            long deadline = SystemClock.uptimeMillis() + 3000;
            while (!screen.hasWindowFocus() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25);
            main(() -> {
                View tile = screen.findViewById(R.id.internet_speed_tile);
                tile.requestRectangleOnScreen(new Rect(0, 0, tile.getWidth(), tile.getHeight()), true);
            });
            main(() -> {
                check(screen.hasWindowFocus(), "menu is focused");
                View tile = screen.findViewById(R.id.internet_speed_tile);
                Rect visible = new Rect();
                check(tile.getGlobalVisibleRect(visible) && visible.width() == tile.getWidth()
                                && visible.height() == tile.getHeight(),
                        "entire tile is reachable in the launcher scroll view");
                check(HomeActions.begin(), "simulate existing action lease");
                try {
                    tile.performClick();
                    check(monitor.getHits() == 0, "tile cannot open during another action");
                } finally {
                    HomeActions.end();
                }
                tile.performClick();
                tile.performClick();
                check(monitor.getHits() == 1, "one explicit speed activity launch per selection");
                check(!HomeActions.busy(), "tile does not acquire an action lease");
                check(!target.getSharedPreferences("gate_settings", 0).contains("last_dial_started_at"),
                        "tile does not reserve a gate call");
            });
        } finally {
            removeMonitor(monitor);
            main(screen::finish);
        }
    }

    private static void verifyLayout(MainActivity screen, NavigationPanel panel, LinearLayout row,
                                     int widthDp, boolean enlargeText) {
        panel.render(widthDp);
        check(row.getChildCount() == MENU_TILES.length, "three destination slots and one speed tile");
        boolean narrow = widthDp < 600;
        check(row.getOrientation() == (narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL), "row orientation");
        for (int index = 0; index < MENU_TILES.length; index++) {
            check(row.getChildAt(index).getId() == MENU_TILES[index], "shortcut order");
        }
        LinearLayout tile = screen.findViewById(R.id.internet_speed_tile);
        TextView subtitle = tile.findViewById(R.id.internet_speed_status);
        TextView label = (TextView) ((LinearLayout) subtitle.getParent()).getChildAt(0);
        check(screen.getString(R.string.speed_title).contentEquals(label.getText()), "tile renders its name");
        if (enlargeText) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, label.getTextSize() * 1.6f);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, subtitle.getTextSize() * 1.6f);
        }
        int width = dp(screen, widthDp - 64);
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, width, row.getMeasuredHeight());

        check(tile.isClickable() && tile.isFocusable(), "touch and rotary/keyboard focus");
        check(Button.class.getName().contentEquals(tile.createAccessibilityNodeInfo().getClassName()),
                "accessibility identifies a button");
        check(tile.getWidth() >= dp(screen, 48) && tile.getHeight() >= dp(screen, 48), "48dp touch target");
        check(tile.getLeft() >= 0 && tile.getRight() <= width && tile.getBottom() <= row.getHeight(),
                "tile stays inside the menu at " + widthDp + "dp");
        checkComplete(label);
        checkComplete(subtitle);
        View previous = row.getChildAt(2);
        int direction = narrow ? View.FOCUS_DOWN : View.FOCUS_RIGHT;
        check(FocusFinder.getInstance().findNextFocus(row, previous, direction) == tile,
                "directional focus moves from destination three to the speed tile");
        if (narrow) {
            check(tile.getWidth() == row.getWidth() && tile.getTop() > previous.getBottom(),
                    "each compact shortcut has its own full-width row");
        } else {
            check(tile.getLeft() > previous.getRight() && tile.getHeight() == previous.getHeight()
                            && Math.abs(tile.getWidth() - previous.getWidth()) <= 1,
                    "wide shortcuts remain equal-sized and side by side");
        }
    }

    private static void checkComplete(TextView text) {
        Layout layout = text.getLayout();
        check(layout != null && layout.getLineCount() > 0, "tile text is laid out");
        check(layout.getLineEnd(layout.getLineCount() - 1) == text.length(), "text wraps without truncation");
        for (int line = 0; line < layout.getLineCount(); line++) {
            check(layout.getEllipsisCount(line) == 0 && layout.getLineMax(line) <= layout.getWidth() + 1,
                    "tile text fits its column");
        }
    }

    private void main(Runnable work) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                work.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        waitForIdleSync();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
