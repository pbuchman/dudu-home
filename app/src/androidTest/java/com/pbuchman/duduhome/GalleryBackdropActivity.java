package com.pbuchman.duduhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Test-APK-only background with a hand-drawn, fictional street plan. */
public final class GalleryBackdropActivity extends Activity {
    static final String TITLE = "Mapa syntetyczna";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        FrameLayout layout = new FrameLayout(this);
        layout.addView(new MapView(this));
        TextView title = new TextView(this);
        title.setText(TITLE);
        title.setTextSize(24);
        title.setTextColor(0xff203145);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int gap = Math.round(32 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        position.setMargins(gap, gap, gap, gap);
        layout.addView(title, position);
        setContentView(layout);
    }

    private static final class MapView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        MapView(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 2000f, getHeight() / 1200f);
            canvas.drawColor(0xffe3e9e4);

            area(canvas, 0xffc4d8be, 1320, -40, 2040, -40, 2040, 400, 1810, 430, 1570, 344, 1370, 148);
            area(canvas, 0xffcadac3, -40, 760, 267, 699, 440, 783, 515, 1030, 688, 1220, -40, 1220);
            area(canvas, 0xffd2e0c7, 980, 751, 1265, 759, 1450, 962, 1198, 1130, 1010, 1042);
            area(canvas, 0xffdadfd6, 423, 189, 692, 126, 843, 329, 590, 417);
            area(canvas, 0xffd6ddd5, 896, 49, 1111, 19, 1300, 212, 1040, 281);
            area(canvas, 0xffdadfd6, 235, 371, 439, 344, 556, 576, 416, 668);
            area(canvas, 0xffd5dfd4, 1431, 612, 1660, 621, 1801, 826, 1636, 890);
            area(canvas, 0xffd4dcd2, 1690, 452, 1968, 506, 1902, 688, 1769, 655);

            Path river = new Path();
            river.moveTo(2130, 1020);
            river.cubicTo(1620, 1010, 1590, 1260, 1220, 1190);
            river.cubicTo(870, 1110, 763, 852, 775, 631);
            river.cubicTo(810, 299, 1200, 430, 1155, -70);
            stroke(canvas, river, 0xffabc8d9, 90);

            road(canvas, 46, -100, 60, 591, 307, 475, 526, 612, 745, 895, 850, 1055, 1250);
            road(canvas, 30, 105, -45, 244, 322, 391, 575, 416, 765, 294, 1220);
            road(canvas, 36, -100, 503, 218, 481, 478, 459, 702, 398, 1010, 332, 1326, 245, 1717, 264, 2080, 352);
            road(canvas, 28, 1300, -100, 1425, 163, 1480, 391, 1603, 540, 1829, 720, 2070, 726);
            road(canvas, 43, 1250, 1240, 1389, 975, 1526, 771, 1433, 637, 1266, 613, 1073, 623, 801, 648, 612, 745, 394, 879, -40, 928);
            road(canvas, 28, 968, -81, 974, 119, 1070, 286, 1220, 435, 1421, 506, 1670, 485, 2070, 534);
            road(canvas, 26, 320, -69, 431, 182, 590, 417, 733, 545, 950, 620, 1130, 748, 1270, 900);
            road(canvas, 24, 1870, -64, 1816, 116, 1717, 264, 1743, 433, 1829, 720, 1860, 965);
            road(canvas, 27, -20, 194, 230, 282, 455, 258, 710, 186, 974, 119, 1335, 62, 1526, 101, 1840, 167, 2050, 164);

            Path route = path(335, 893, 394, 879, 612, 745, 801, 648, 1073, 623, 1266, 613, 1433, 637, 1526, 771);
            stroke(canvas, route, 0xfff9feff, 27);
            stroke(canvas, route, 0xff427fa8, 15);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xfff9feff);
            canvas.drawCircle(1073, 623, 26, paint);
            paint.setColor(0xff427fa8);
            canvas.drawCircle(1073, 623, 19, paint);
            canvas.restore();
        }

        private void area(Canvas canvas, int color, float... points) {
            Path path = path(points);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }

        private void road(Canvas canvas, float width, float... points) {
            Path path = path(points);
            stroke(canvas, path, 0xffc2cbc8, width + 3);
            stroke(canvas, path, 0xfff7f8f4, width);
        }

        private void stroke(Canvas canvas, Path path, int color, float width) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
            paint.setStrokeWidth(width);
            canvas.drawPath(path, paint);
        }

        private static Path path(float... points) {
            Path path = new Path();
            path.moveTo(points[0], points[1]);
            for (int at = 2; at < points.length; at += 2) path.lineTo(points[at], points[at + 1]);
            return path;
        }
    }
}
