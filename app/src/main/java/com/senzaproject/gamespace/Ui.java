package com.senzaproject.gamespace;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

final class Ui {
    static final int BG = 0xFF0B0F1A, CARD = 0xFF141B2D, CARD2 = 0xFF1B2440;
    static final int ACCENT = 0xFF00E5FF, VIOLET = 0xFF7C4DFF;
    static final int GREEN = 0xFF00E676, YELLOW = 0xFFFFD740, RED = 0xFFFF5252;
    static final int TEXT = 0xFFE8ECF8, MUTED = 0xFF8A94B0;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable box(Context c, int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, rDp));
        return g;
    }

    static TextView tv(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        l.setBackground(box(c, CARD, 18));
        return l;
    }

    /** Margin dalam dp, ukuran w/h dalam px atau MATCH/WRAP. */
    static LinearLayout.LayoutParams lp(Context c, int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static void addCard(LinearLayout parent, View v) {
        parent.addView(v, lp(parent.getContext(), MATCH, WRAP, 0, 0, 0, 12));
    }

    static TextView button(Context c, String label, int bg, int fg) {
        TextView t = tv(c, label, 14, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        t.setBackground(box(c, bg, 12));
        t.setClickable(true);
        return t;
    }

    /** Baris "kunci ..... nilai"; nilai = child index 1. */
    static LinearLayout kv(Context c, String k, String v, int vColor) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(c, 6), 0, dp(c, 6));
        TextView a = tv(c, k, 13, MUTED, false);
        row.addView(a, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView b = tv(c, v, 13, vColor, true);
        b.setGravity(Gravity.END);
        row.addView(b, new LinearLayout.LayoutParams(0, WRAP, 1.4f));
        return row;
    }

    static Switch sw(Context c, String label, boolean on) {
        Switch s = new Switch(c);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(15);
        s.setChecked(on);
        s.setPadding(0, dp(c, 8), 0, dp(c, 8));
        return s;
    }

    static int fpsColor(float fps, float hz) {
        if (fps <= 0) return MUTED;
        float target = hz > 0 ? hz : 60f;
        if (fps >= target * 0.9f) return GREEN;
        if (fps >= target * 0.6f) return YELLOW;
        return RED;
    }
}
