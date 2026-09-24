package com.pbuchman.duduhome.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.navigation.*;
import java.io.InputStream;

/** Owns only manual destination UI. Text and file data never become diagnostics. */
final class NavigationPanel {
    private static final int PICK_DOCUMENT = 201;
    private final Activity activity;
    private final java.util.function.BooleanSupplier allowed;
    private boolean picking, reading, launching;
    NavigationPanel(Activity activity, java.util.function.BooleanSupplier allowed, Bundle saved) {
        this.activity = activity; this.allowed = allowed;
        picking = saved != null && saved.getBoolean("navigation_picker");
    }
    boolean busy() { return picking || reading; }
    void saveState(Bundle state) { state.putBoolean("navigation_picker", picking); }
    void resumed() { launching = false; }
    void render() {
        NavigationConfig config;
        try { config = new NavigationStore(activity).load(); }
        catch (Exception ignored) { config = NavigationConfig.empty(); toast(R.string.navigation_invalid); }
        LinearLayout row = activity.findViewById(R.id.navigation_tiles);
        row.removeAllViews();
        boolean narrow = activity.getResources().getConfiguration().screenWidthDp < 600;
        row.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int slot = 0; slot < NavigationConfig.SLOT_COUNT; slot++) {
            NavigationConfig.Destination destination = config.destination(slot);
            boolean empty = destination == null;
            LinearLayout tile = new LinearLayout(activity);
            tile.setId(new int[]{R.id.navigation_slot_1, R.id.navigation_slot_2, R.id.navigation_slot_3}[slot]);
            tile.setOrientation(LinearLayout.HORIZONTAL); tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(dp(16), dp(12), dp(16), dp(12));
            tile.setMinimumHeight(dp(132));
            tile.setBackgroundResource(empty ? R.drawable.navigation_empty
                    : new int[]{R.drawable.tile_gate, R.drawable.tile_cleaning, R.drawable.tile_mop}[slot]);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(narrow ? -1 : 0, -2, narrow ? 0 : 1);
            if (slot > 0) { if (narrow) size.topMargin = dp(12); else size.setMarginStart(dp(16)); }
            row.addView(tile, size);
            ImageView icon = new ImageView(activity);
            icon.setImageResource(empty ? R.drawable.ic_navigation_empty : switch (destination.icon()) {
                case "home" -> R.drawable.art_navigation_home;
                case "squash" -> R.drawable.art_navigation_squash;
                default -> R.drawable.art_navigation_pin;
            });
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(88), dp(94)));
            LinearLayout words = new LinearLayout(activity); words.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams wordsSize = new LinearLayout.LayoutParams(0, -2, 1); wordsSize.setMarginStart(dp(10));
            tile.addView(words, wordsSize);
            String title = empty ? activity.getString(R.string.navigation_slot, slot + 1) : destination.label();
            TextView label = new TextView(activity); label.setText(title); label.setTextSize(22);
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            label.setTextColor(activity.getColor(empty ? R.color.text_secondary : R.color.tile_ink));
            label.setMaxLines(2); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); words.addView(label);
            TextView subtitle = new TextView(activity);
            subtitle.setText(empty ? R.string.navigation_empty : R.string.navigation_go); subtitle.setTextSize(16);
            subtitle.setTextColor(activity.getColor(empty ? R.color.text_secondary : R.color.tile_ink));
            subtitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams subSize = new LinearLayout.LayoutParams(-1, -2); subSize.topMargin = dp(6);
            words.addView(subtitle, subSize);
            if (!empty) {
                ImageView arrow = new ImageView(activity); arrow.setImageResource(R.drawable.ic_navigation_arrow);
                arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams arrowSize = new LinearLayout.LayoutParams(dp(28), dp(28)); arrowSize.setMarginStart(dp(8));
                tile.addView(arrow, arrowSize);
            }
            tile.setContentDescription(title + ", " + activity.getString(empty ? R.string.navigation_empty : R.string.navigation_go));
            tile.setClickable(true); tile.setFocusable(true);
            tile.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
                }
            });
            tile.setOnClickListener(v -> {
                if (busy() || launching || !allowed.getAsBoolean()) return;
                if (empty) {
                    new AlertDialog.Builder(activity).setTitle(R.string.navigation_empty)
                            .setMessage(R.string.navigation_empty_help).setPositiveButton("OK", null).show();
                    return;
                }
                MapsLauncher.Result result = new MapsLauncher(activity).launch(destination);
                switch (result) {
                    case REQUESTED -> launching = true;
                    case MISSING -> toast(R.string.navigation_maps_missing);
                    case STORAGE_FAILED -> toast(R.string.navigation_storage_failed);
                    case FAILED -> toast(R.string.navigation_launch_failed);
                }
            });
        }
    }
    void pick() {
        if (busy() || !allowed.getAsBoolean()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/json", "text/json", "text/plain", "application/octet-stream"});
        picking = true; HomeActions.schedulingChanged();
        try { activity.startActivityForResult(intent, PICK_DOCUMENT); }
        catch (RuntimeException ignored) { picking = false; toast(R.string.navigation_picker_missing); }
    }
    boolean result(int request, int result, Intent data) {
        if (request != PICK_DOCUMENT) return false;
        picking = false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            HomeActions.schedulingChanged(); return true;
        }
        if (!allowed.getAsBoolean()) { HomeActions.schedulingChanged(); return true; }
        reading = true; toast(R.string.navigation_loading); HomeActions.schedulingChanged();
        android.net.Uri uri = data.getData();
        // Read on a worker. No persistent URI grant, permission to broad storage, or URI logging.
        new Thread(() -> {
            NavigationConfig parsed = null;
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                parsed = NavigationStore.readDocument(input);
            } catch (Exception ignored) { /* Only the fixed user-facing error is exposed. */ }
            NavigationConfig document = parsed;
            activity.runOnUiThread(() -> {
                reading = false;
                if (activity.isDestroyed() || activity.isFinishing()) return;
                try {
                    if (document == null || !allowed.getAsBoolean()) throw new java.io.IOException();
                    new NavigationStore(activity).save(document);
                    render(); toast(R.string.navigation_imported);
                } catch (Exception ignored) { toast(R.string.navigation_import_failed); }
                HomeActions.schedulingChanged();
            });
        }, "navigation-document").start();
        return true;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void toast(int text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
}
