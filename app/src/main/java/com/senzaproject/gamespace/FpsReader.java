package com.senzaproject.gamespace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pembaca FPS asli dari SurfaceFlinger (butuh root).
 * Sumbernya adalah waktu present frame ke layar (fence HWC) milik layer game,
 * sama seperti yang dipakai tool profiling sistem, bukan estimasi.
 */
final class FpsReader {
    static final class Sample {
        float fps, frameMs, hz;
        String layer = "";
    }

    private static final class Frames {
        long[] ts;
        float hz;
    }

    private static final Pattern PKG =
            Pattern.compile("\\s([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/");

    private String layer;
    private int bad;

    void reset() {
        layer = null;
        bad = 0;
    }

    /** Paket aplikasi yang sedang di depan layar. */
    String foregroundPackage() {
        String out = RootShell.run("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'");
        if (out == null) return null;
        String[] lines = out.split("\n");
        for (String line : lines) {
            if (line.contains("mCurrentFocus") && !line.contains("null")) {
                String p = match(line);
                if (p != null) return p;
            }
        }
        for (String line : lines) {
            if (line.contains("mFocusedApp")) {
                String p = match(line);
                if (p != null) return p;
            }
        }
        return null;
    }

    private static String match(String line) {
        Matcher m = PKG.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    Sample read(String pkg) {
        Sample s = new Sample();
        if (layer == null || bad >= 6) {
            layer = pickLayer(pkg);
            bad = 0;
        }
        if (layer == null) return s;
        s.layer = layer;

        Frames f = frames(layer);
        if (f == null || f.ts.length == 0) {
            bad++;
            return s;
        }
        s.hz = f.hz;
        long[] ts = f.ts;
        int last = ts.length - 1;
        long newest = ts[last];
        if (System.nanoTime() - newest > 1_500_000_000L) {
            bad++;
            return s;
        }
        int first = last;
        while (first > 0 && ts[first - 1] >= newest - 1_000_000_000L) first--;
        int n = last - first + 1;
        float fps = 0;
        if (n >= 2) {
            long span = newest - ts[first];
            if (span > 0) fps = (n - 1) * 1e9f / span;
        } else if (last > 0) {
            long d = newest - ts[last - 1];
            if (d > 0) fps = 1e9f / d;
        }
        bad = 0;
        s.fps = fps;
        s.frameMs = fps > 0 ? 1000f / fps : 0;
        return s;
    }

    /** Cari layer penghasil frame milik game (SurfaceView lebih diutamakan). */
    private String pickLayer(String pkg) {
        String list = RootShell.run("dumpsys SurfaceFlinger --list");
        if (list == null) return null;
        ArrayList<String> a = new ArrayList<>(), b = new ArrayList<>(),
                c = new ArrayList<>(), d = new ArrayList<>();
        for (String raw : list.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty() || !l.contains(pkg)) continue;
            if (l.startsWith("Background for") || l.startsWith("Bounds for")) continue;
            boolean sv = l.contains("SurfaceView");
            boolean blast = l.contains("BLAST");
            if (sv && blast) a.add(l);
            else if (sv) b.add(l);
            else if (blast) c.add(l);
            else d.add(l);
        }
        ArrayList<String> cand = new ArrayList<>();
        cand.addAll(a);
        cand.addAll(b);
        cand.addAll(c);
        cand.addAll(d);
        if (cand.isEmpty()) return null;
        int max = Math.min(cand.size(), 6);
        for (int i = 0; i < max; i++) {
            Frames f = frames(cand.get(i));
            if (f != null && f.ts.length > 0
                    && System.nanoTime() - f.ts[f.ts.length - 1] < 2_000_000_000L) {
                return cand.get(i);
            }
        }
        return cand.get(0);
    }

    private Frames frames(String name) {
        String out = RootShell.run(
                "dumpsys SurfaceFlinger --latency '" + name.replace("'", "'\\''") + "'");
        if (out == null) return null;
        String[] lines = out.trim().split("\n");
        if (lines.length < 2) return null;
        long period;
        try {
            period = Long.parseLong(lines[0].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        long[] tmp = new long[lines.length];
        int n = 0;
        for (int i = 1; i < lines.length; i++) {
            String[] t = lines[i].trim().split("\\s+");
            if (t.length < 3) continue;
            try {
                long actual = Long.parseLong(t[1]);
                if (actual > 0 && actual < Long.MAX_VALUE / 2) tmp[n++] = actual;
            } catch (NumberFormatException ignored) {
            }
        }
        Frames f = new Frames();
        f.ts = Arrays.copyOf(tmp, n);
        Arrays.sort(f.ts);
        f.hz = period > 0 ? 1e9f / period : 0;
        return f;
    }
}
