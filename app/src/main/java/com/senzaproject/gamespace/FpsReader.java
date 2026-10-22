package com.senzaproject.gamespace;

import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pembaca FPS (butuh root) dengan beberapa sumber data, dicoba berurutan:
 * 1) SurfaceFlinger --latency  : waktu present frame per layer (paling presisi)
 * 2) SurfaceFlinger --timestats: penghitung frame per layer
 * 3) Page-flip layar           : cadangan terakhir (perkiraan, seluruh layar)
 * Tidak bergantung pada kesamaan jam dengan aplikasi; keadaan basi dideteksi
 * dari timestamp yang berhenti berubah.
 */
final class FpsReader {
    static final class Sample {
        float fps, frameMs, hz;
        String layer = "";
        String note = "";
        boolean ok;
    }

    private static final class Frames {
        long[] ts;
        float hz;
    }

    private static final class Snap {
        long t;
        Map<String, Long> v;
    }

    private static final int M_NONE = 0, M_LATENCY = 1, M_STATS = 2, M_FLIP = 3;
    private static final String[] M_LABEL = {
            "", "SurfaceFlinger latency", "SurfaceFlinger timestats", "Page-flip layar (perkiraan)"};

    private static final Pattern PKG =
            Pattern.compile("\\s([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/");
    private static final Pattern FLIP = Pattern.compile(
            "Parcel\\(\\s*(?:0x[0-9a-fA-F]+:\\s*)?([0-9a-fA-F]{8})\\s+([0-9a-fA-F]{8})");

    private static boolean statsEnabled;

    private String curPkg = "";
    private int method = M_NONE;
    private int zero;
    private long lastUpgrade;

    // metode latency
    private String layer;
    private long lastNewest = -1, lastChange, pickNewest = -1;
    private float lastFps;

    // metode penghitung (timestats / flip)
    private final ArrayDeque<Snap> snaps = new ArrayDeque<>();

    void reset() {
        curPkg = "";
        method = M_NONE;
        zero = 0;
        resetState();
    }

    private void resetState() {
        layer = null;
        lastNewest = -1;
        pickNewest = -1;
        lastFps = 0;
        snaps.clear();
    }

    // ------------------------------------------------------------------
    // paket yang sedang di depan layar
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // pembacaan utama
    // ------------------------------------------------------------------

    Sample read(String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (!pkg.equals(curPkg)) {
            reset();
            curPkg = pkg;
        }

        if (method == M_NONE) {
            method = firstAvailable(pkg, true);
            if (method == M_NONE) {
                Sample s = new Sample();
                s.note = "mencari sumber FPS...";
                return s;
            }
            lastUpgrade = now;
        } else if (method == M_FLIP && now - lastUpgrade >= 3000) {
            // saat memakai cadangan, cek berkala apakah sumber yang lebih akurat sudah muncul
            lastUpgrade = now;
            int m = firstAvailable(pkg, false);
            if (m != M_NONE) {
                method = m;
                zero = 0;
            }
        }

        Sample s = readMethod(method, pkg, now);
        if (s.ok && s.fps > 0) {
            zero = 0;
            return s;
        }
        zero++;
        if (!s.ok) {
            rotate();
            zero = 0;
        } else if (zero % 6 == 0) {
            rotate();
        } else if (method == M_LATENCY && zero == 3) {
            layer = null; // cari ulang layer
            lastNewest = -1;
        }
        return s;
    }

    private void rotate() {
        method = method % 3 + 1;
        resetState();
    }

    private int firstAvailable(String pkg, boolean includeFlip) {
        String l = pickLayer(pkg);
        if (l != null) {
            layer = l;
            lastNewest = -1;
            return M_LATENCY;
        }
        Map<String, Long> st = statsFrames(pkg);
        if (st != null && !st.isEmpty()) {
            snaps.clear();
            return M_STATS;
        }
        if (includeFlip && flipValue() >= 0) {
            snaps.clear();
            return M_FLIP;
        }
        return M_NONE;
    }

    private Sample readMethod(int m, String pkg, long now) {
        switch (m) {
            case M_LATENCY:
                return readLatency(pkg);
            case M_STATS:
                return readStats(pkg, now);
            default:
                return readFlip(now);
        }
    }

    // ------------------------------------------------------------------
    // metode 1: --latency
    // ------------------------------------------------------------------

    private Sample readLatency(String pkg) {
        Sample s = new Sample();
        s.layer = M_LABEL[M_LATENCY];
        long now = SystemClock.elapsedRealtime();
        if (layer == null) {
            layer = pickLayer(pkg);
            lastNewest = -1;
            lastFps = 0;
        }
        if (layer == null) {
            s.note = "mencari sumber FPS...";
            return s;
        }
        Frames f = frames(layer);
        if (f == null || f.ts.length == 0) {
            layer = null;
            s.note = "mencari sumber FPS...";
            return s;
        }
        s.ok = true;
        s.layer = M_LABEL[M_LATENCY] + " \u2022 " + layer;
        s.hz = f.hz;
        long[] ts = f.ts;
        long newest = ts[ts.length - 1];
        if (lastNewest < 0) {
            lastNewest = pickNewest > 0 ? pickNewest : newest;
            lastChange = now;
            pickNewest = -1;
        }
        if (newest != lastNewest) {
            lastNewest = newest;
            lastChange = now;
            lastFps = windowFps(ts);
        } else if (now - lastChange > 1200) {
            lastFps = 0;
        }
        s.fps = lastFps;
        s.frameMs = lastFps > 0 ? 1000f / lastFps : 0;
        if (s.fps <= 0) s.note = "menunggu frame...";
        return s;
    }

    /** FPS dari frame dalam 1 detik terakhir buffer. */
    private static float windowFps(long[] ts) {
        int last = ts.length - 1;
        long newest = ts[last];
        int first = last;
        while (first > 0 && ts[first - 1] >= newest - 1_000_000_000L) first--;
        int n = last - first + 1;
        if (n >= 2) {
            long span = newest - ts[first];
            return span > 0 ? (n - 1) * 1e9f / span : 0;
        }
        if (last > 0) {
            long d = newest - ts[last - 1];
            return d > 0 ? 1e9f / d : 0;
        }
        return 0;
    }

    /** Cari layer penghasil frame milik game (SurfaceView lebih diutamakan). */
    private String pickLayer(String pkg) {
        ArrayList<String> cand = candidates(pkg);
        int max = Math.min(cand.size(), 8);
        String best = null;
        long bestNewest = Long.MIN_VALUE;
        for (int i = 0; i < max; i++) {
            Frames f = frames(cand.get(i));
            if (f == null || f.ts.length == 0) continue;
            long nw = f.ts[f.ts.length - 1];
            if (best == null || nw > bestNewest + 100_000_000L) {
                best = cand.get(i);
                bestNewest = nw;
            }
        }
        pickNewest = best == null ? -1 : bestNewest;
        return best;
    }

    private ArrayList<String> candidates(String pkg) {
        ArrayList<String> cand = new ArrayList<>();
        String list = RootShell.run("dumpsys SurfaceFlinger --list");
        if (list == null) return cand;
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
        cand.addAll(a);
        cand.addAll(b);
        cand.addAll(c);
        cand.addAll(d);
        return cand;
    }

    private Frames frames(String name) {
        Frames f = framesRaw(name);
        if (f == null) {
            String alt = name.replaceAll("#\\d+$", "");
            if (!alt.equals(name)) f = framesRaw(alt);
        }
        return f;
    }

    private Frames framesRaw(String name) {
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
        long[][] cols = new long[3][lines.length];
        int[] n = new int[3];
        for (int i = 1; i < lines.length; i++) {
            String[] t = lines[i].trim().split("\\s+");
            if (t.length < 3) continue;
            for (int c = 0; c < 3; c++) {
                try {
                    long v = Long.parseLong(t[c]);
                    if (v > 0 && v < Long.MAX_VALUE / 2) cols[c][n[c]++] = v;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // kolom 1 = waktu present; di sebagian ROM kosong, pakai frame-ready (2) lalu desired (0)
        int use = -1;
        int[] order = {1, 2, 0};
        for (int c : order) {
            if (n[c] >= 2) {
                use = c;
                break;
            }
        }
        if (use < 0) {
            for (int c : order) {
                if (n[c] > 0) {
                    use = c;
                    break;
                }
            }
        }
        if (use < 0) return null;
        Frames f = new Frames();
        f.ts = Arrays.copyOf(cols[use], n[use]);
        Arrays.sort(f.ts);
        f.hz = period > 0 ? 1e9f / period : 0;
        return f;
    }

    // ------------------------------------------------------------------
    // metode 2: --timestats
    // ------------------------------------------------------------------

    private Sample readStats(String pkg, long now) {
        Sample s = new Sample();
        s.layer = M_LABEL[M_STATS];
        Map<String, Long> cur = statsFrames(pkg);
        if (cur == null || cur.isEmpty()) {
            s.note = "mencari sumber FPS...";
            return s;
        }
        s.ok = true;
        return counter(s, cur, now);
    }

    private Map<String, Long> statsFrames(String pkg) {
        if (!statsEnabled) {
            RootShell.run("dumpsys SurfaceFlinger --timestats -enable");
            statsEnabled = true;
        }
        String out = RootShell.run("dumpsys SurfaceFlinger --timestats -dump -maxlayers 100");
        if (out == null) return null;
        return parseStats(out, pkg);
    }

    private static Map<String, Long> parseStats(String out, String pkg) {
        HashMap<String, Long> res = new HashMap<>();
        String name = null, pk = null;
        long total = -1;
        for (String raw : out.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("layerName")) {
                putStats(res, name, pk, total, pkg);
                name = valueOf(l);
                pk = null;
                total = -1;
            } else if (name != null && l.startsWith("packageName")) {
                pk = valueOf(l);
            } else if (name != null && total < 0 && l.startsWith("totalFrames")) {
                try {
                    total = Long.parseLong(valueOf(l));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        putStats(res, name, pk, total, pkg);
        return res;
    }

    private static void putStats(Map<String, Long> m, String name, String pk, long total, String pkg) {
        if (name == null || total < 0) return;
        if (name.contains(pkg) || pkg.equals(pk)) {
            Long old = m.get(name);
            m.put(name, (old == null ? 0 : old) + total);
        }
    }

    private static String valueOf(String l) {
        int i = l.indexOf('=');
        return i < 0 ? "" : l.substring(i + 1).trim();
    }

    // ------------------------------------------------------------------
    // metode 3: page-flip layar
    // ------------------------------------------------------------------

    private Sample readFlip(long now) {
        Sample s = new Sample();
        s.layer = M_LABEL[M_FLIP];
        long v = flipValue();
        if (v < 0) {
            s.note = "mencari sumber FPS...";
            return s;
        }
        s.ok = true;
        HashMap<String, Long> cur = new HashMap<>();
        cur.put("flip", v);
        return counter(s, cur, now);
    }

    private static long flipValue() {
        String out = RootShell.run("service call SurfaceFlinger 1013");
        if (out == null) return -1;
        Matcher m = FLIP.matcher(out);
        if (!m.find()) return -1;
        try {
            if (Long.parseLong(m.group(1), 16) != 0) return -1;
            return Long.parseLong(m.group(2), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // penghitung frame -> FPS
    // ------------------------------------------------------------------

    private Sample counter(Sample s, Map<String, Long> cur, long now) {
        Snap base = null;
        for (Snap sn : snaps) {
            if (now - sn.t <= 1100) {
                base = sn;
                break;
            }
        }
        if (base == null && !snaps.isEmpty()) base = snaps.peekLast();
        Snap n = new Snap();
        n.t = now;
        n.v = cur;
        snaps.addLast(n);
        while (snaps.size() > 1 && now - snaps.peekFirst().t > 3000) snaps.pollFirst();

        if (base == null || now - base.t < 300) {
            s.note = "mengukur...";
            return s;
        }
        long dt = now - base.t;
        float best = 0;
        for (Map.Entry<String, Long> e : cur.entrySet()) {
            Long old = base.v.get(e.getKey());
            if (old == null) continue;
            long d = e.getValue() - old;
            if (d > 0) best = Math.max(best, d * 1000f / dt);
        }
        s.fps = best;
        s.frameMs = best > 0 ? 1000f / best : 0;
        if (best <= 0) s.note = "menunggu frame...";
        return s;
    }

    // ------------------------------------------------------------------
    // diagnosa (disimpan ke Download/GameSpace-diagnosa.txt)
    // ------------------------------------------------------------------

    String diagnose(String pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("== Game Space diagnosa (v1.0.1) ==\n");
        sb.append("Perangkat: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ")
                .append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Chipset: ").append(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE).append('\n');
        sb.append("Root: ").append(head(RootShell.run("id"), 2, 200)).append('\n');
        sb.append("Game: ").append(pkg).append('\n');
        sb.append("Metode aktif: ").append(M_LABEL[method]).append(" | zero=").append(zero)
                .append(" | layer=").append(layer).append("\n\n");

        sb.append("[1] dumpsys SurfaceFlinger --list\n");
        String list = RootShell.run("dumpsys SurfaceFlinger --list");
        if (list == null) {
            sb.append("(gagal)\n");
        } else {
            String[] ls = list.split("\n");
            sb.append("total baris: ").append(ls.length).append('\n');
            int shown = 0;
            for (String l : ls) {
                if (l.contains(pkg) && shown < 15) {
                    sb.append("  ").append(l.trim()).append('\n');
                    shown++;
                }
            }
            if (shown == 0) {
                sb.append("(tidak ada layer berisi nama paket) contoh 10 baris pertama:\n");
                sb.append(head(list, 10, 1200)).append('\n');
            }
        }

        sb.append("\n[2] --latency (3 kandidat)\n");
        ArrayList<String> cand = candidates(pkg);
        for (int i = 0; i < Math.min(3, cand.size()); i++) {
            String name = cand.get(i);
            sb.append("layer: ").append(name).append('\n');
            String raw = RootShell.run(
                    "dumpsys SurfaceFlinger --latency '" + name.replace("'", "'\\''") + "'");
            if (raw == null) {
                sb.append("  (gagal)\n");
                continue;
            }
            String[] rl = raw.trim().split("\n");
            sb.append("  jumlah baris: ").append(rl.length).append('\n');
            for (int k = 0; k < Math.min(4, rl.length); k++) sb.append("  ").append(rl[k]).append('\n');
            sb.append("  ...\n");
            for (int k = Math.max(4, rl.length - 3); k < rl.length; k++) sb.append("  ").append(rl[k]).append('\n');
        }
        if (cand.isEmpty()) sb.append("(tidak ada kandidat)\n");

        sb.append("\n[3] --timestats\n");
        RootShell.run("dumpsys SurfaceFlinger --timestats -enable");
        String st = RootShell.run("dumpsys SurfaceFlinger --timestats -dump -maxlayers 100");
        if (st == null) {
            sb.append("(gagal)\n");
        } else {
            sb.append("parse untuk paket: ").append(parseStats(st, pkg)).append('\n');
            sb.append("30 baris pertama:\n").append(head(st, 30, 2500)).append('\n');
            String[] sl = st.split("\n");
            for (int i = 0; i < sl.length; i++) {
                if (sl[i].contains(pkg)) {
                    sb.append("cuplikan di sekitar paket:\n");
                    for (int k = i; k < Math.min(sl.length, i + 10); k++) sb.append("  ").append(sl[k]).append('\n');
                    break;
                }
            }
        }

        sb.append("\n[4] service call SurfaceFlinger 1013\n");
        sb.append(head(RootShell.run("service call SurfaceFlinger 1013"), 4, 400)).append('\n');

        sb.append("\n[5] FPSGO (MediaTek)\n");
        sb.append(head(RootShell.run("ls /sys/kernel/fpsgo /proc/fpsgo /sys/kernel/debug/fpsgo 2>&1 | head -30"), 30, 1500)).append('\n');
        sb.append(head(RootShell.run("cat /sys/kernel/fpsgo/fstb/fpsgo_status 2>&1 | head -15"), 15, 1500)).append('\n');
        return sb.toString();
    }

    private static String head(String s, int maxLines, int maxChars) {
        if (s == null) return "(null)";
        String[] ls = s.trim().split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(maxLines, ls.length); i++) b.append(ls[i]).append('\n');
        String r = b.toString().trim();
        return r.length() > maxChars ? r.substring(0, maxChars) + "..." : r;
    }
}
