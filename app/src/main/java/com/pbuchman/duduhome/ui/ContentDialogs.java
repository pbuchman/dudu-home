package com.pbuchman.duduhome.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowManager;

/** Native alerts retain their scrolling/buttons; only their available reading width is explicit. */
final class ContentDialogs {
    private ContentDialogs() { }
    static AlertDialog show(Activity owner, AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        View decor = owner.getWindow().getDecorView();
        Runnable fit = () -> {
            if (dialog.getWindow() == null) return;
            int maximum = dialog.getListView() == null ? 520 : 600;
            int width = width(owner, maximum);
            if (dialog.getWindow().getAttributes().width != width)
                dialog.getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        };
        View.OnLayoutChangeListener resize = (v, l, t, r, b, ol, ot, or, ob) -> fit.run();
        decor.addOnLayoutChangeListener(resize);
        dialog.setOnDismissListener(ignored -> decor.removeOnLayoutChangeListener(resize));
        fit.run();
        return dialog;
    }
    static Rect available(Activity owner) {
        View decor = owner.getWindow().getDecorView();
        Rect frame = new Rect(); decor.getWindowVisibleDisplayFrame(frame);
        int width = decor.getWidth(), height = decor.getHeight();
        if (width <= 0) width = owner.getResources().getDisplayMetrics().widthPixels;
        if (height <= 0) height = owner.getResources().getDisplayMetrics().heightPixels;
        if (frame.isEmpty()) return new Rect(0, 0, width, height);
        return new Rect(0, 0, Math.min(width, frame.width()), Math.min(height, frame.height()));
    }
    static int width(Activity owner, int maximumDp) {
        float density = owner.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.min(Math.round(maximumDp * density), available(owner).width() - Math.round(32 * density)));
    }
}
