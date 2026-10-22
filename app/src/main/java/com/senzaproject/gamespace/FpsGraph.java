package com.senzaproject.gamespace;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Grafik FPS live sederhana. */
final class FpsGraph extends View {
    private float[] data = new float[0];
    private float hz = 60;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Path path = new Path();

    FpsGraph(Context c) {
        super(c);
        line.setColor(Ui.ACCENT);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 2));
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(0x2200E5FF);
        fill.setStyle(Paint.Style.FILL);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(1f);
    }

    void set(float[] d, float refreshHz) {
        data = d;
        hz = refreshHz > 0 ? refreshHz : 60;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        canvas.drawLine(0, h / 2, w, h / 2, grid);
        canvas.drawLine(0, h - 1, w, h - 1, grid);
        int n = data.length;
        if (n < 2) return;
        float max = hz;
        for (float v : data) if (v > max) max = v;
        path.reset();
        for (int i = 0; i < n; i++) {
            float x = w * i / (n - 1);
            float y = h - h * Math.min(data[i], max) / max;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        Path area = new Path(path);
        area.lineTo(w, h);
        area.lineTo(0, h);
        area.close();
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
    }
}
