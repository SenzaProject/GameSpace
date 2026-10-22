package com.senzaproject.gamespace;

import java.util.ArrayList;
import java.util.Arrays;

/** Data FPS bersama antara service dan UI. */
final class FpsState {
    static volatile boolean running;
    static volatile float fps;
    static volatile float frameMs;
    static volatile float hz;
    static volatile String pkg = "";
    static volatile String layer = "";

    private static final ArrayList<Float> hist = new ArrayList<>();

    private FpsState() {}

    static synchronized void push(float f) {
        hist.add(f);
        if (hist.size() > 3600) hist.remove(0);
    }

    static synchronized void reset() {
        hist.clear();
    }

    static synchronized float[] recent(int n) {
        int size = hist.size();
        int from = Math.max(0, size - n);
        float[] r = new float[size - from];
        for (int i = from; i < size; i++) r[i - from] = hist.get(i);
        return r;
    }

    /** {rata-rata, min, max, 1% low} atau null jika belum ada data. */
    static synchronized float[] stats() {
        int n = hist.size();
        if (n == 0) return null;
        float[] a = new float[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            a[i] = hist.get(i);
            sum += a[i];
        }
        Arrays.sort(a);
        int k = Math.max(1, n / 100);
        double low = 0;
        for (int i = 0; i < k; i++) low += a[i];
        return new float[]{(float) (sum / n), a[0], a[n - 1], (float) (low / k)};
    }
}
