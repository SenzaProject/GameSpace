package com.senzaproject.gamespace;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String[] NAMES = {"Beranda", "Game", "Setelan", "Tentang"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FrameLayout content;
    private final TextView[] nav = new TextView[4];
    private int tab = 0;

    private TextView liveFps, liveGame, liveSub, liveLayer;
    private TextView stAvg, stMin, stMax, stLow, vRoot, vOverlay, vMon;
    private FpsGraph graph;
    private Switch swMonitor;
    private boolean suppress;
    private String labelPkg = "", labelText = "";

    private static final class AppItem {
        String pkg, label;
        Drawable icon;
        boolean detected;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshLive();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Ui.CARD);
        for (int i = 0; i < 4; i++) {
            TextView t = Ui.tv(this, NAMES[i], 13, Ui.MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 15), 0, Ui.dp(this, 15));
            final int idx = i;
            t.setOnClickListener(v -> show(idx));
            nav[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        io.execute(RootShell::isRoot);
    }

    @Override
    protected void onResume() {
        super.onResume();
        show(tab);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    private void show(int i) {
        tab = i;
        content.removeAllViews();
        for (int k = 0; k < 4; k++) nav[k].setTextColor(k == i ? Ui.ACCENT : Ui.MUTED);
        View v;
        switch (i) {
            case 1:
                v = buildGames();
                break;
            case 2:
                v = buildSettings();
                break;
            case 3:
                v = buildAbout();
                break;
            default:
                v = buildHome();
        }
        content.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private ScrollView page(LinearLayout[] colOut) {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = Ui.col(this);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        sv.addView(col);
        colOut[0] = col;
        return sv;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // =====================================================================
    // BERANDA
    // =====================================================================

    private View buildHome() {
        LinearLayout[] o = new LinearLayout[1];
        ScrollView sv = page(o);
        LinearLayout col = o[0];

        col.addView(Ui.tv(this, "Game Space", 28, Ui.TEXT, true));
        col.addView(Ui.tv(this, "Monitor FPS real-time \u2022 SenzaProject", 13, Ui.MUTED, false),
                Ui.lp(this, WRAP, WRAP, 0, 0, 0, 14));

        // --- kartu FPS ---
        LinearLayout fc = Ui.card(this);
        swMonitor = Ui.sw(this, "FPS Melayang", FpsState.running);
        swMonitor.setOnCheckedChangeListener((b, on) -> {
            if (suppress) return;
            if (on) {
                if (!startMonitor()) resetSwitch();
            } else {
                stopMonitor();
            }
        });
        fc.addView(swMonitor);
        liveGame = Ui.tv(this, "Menunggu game...", 13, Ui.MUTED, false);
        fc.addView(liveGame);
        liveFps = Ui.tv(this, "--", 60, Ui.ACCENT, true);
        fc.addView(liveFps);
        liveSub = Ui.tv(this, "", 13, Ui.MUTED, false);
        fc.addView(liveSub);
        graph = new FpsGraph(this);
        fc.addView(graph, Ui.lp(this, MATCH, Ui.dp(this, 90), 0, 10, 0, 10));
        LinearLayout sr = new LinearLayout(this);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        stAvg = statCell(sr, "Rata-rata");
        stMin = statCell(sr, "Min");
        stMax = statCell(sr, "Max");
        stLow = statCell(sr, "1% Low");
        fc.addView(sr);
        liveLayer = Ui.tv(this, "", 10, Ui.MUTED, false);
        fc.addView(liveLayer, Ui.lp(this, WRAP, WRAP, 0, 8, 0, 0));
        Ui.addCard(col, fc);

        // --- kartu status ---
        LinearLayout sc = Ui.card(this);
        sc.addView(Ui.tv(this, "Status sistem", 15, Ui.TEXT, true));
        LinearLayout r1 = Ui.kv(this, "Akses root", "-", Ui.MUTED);
        vRoot = (TextView) r1.getChildAt(1);
        sc.addView(r1);
        LinearLayout r2 = Ui.kv(this, "Izin overlay", "-", Ui.MUTED);
        vOverlay = (TextView) r2.getChildAt(1);
        sc.addView(r2);
        LinearLayout r3 = Ui.kv(this, "Monitor FPS", "-", Ui.MUTED);
        vMon = (TextView) r3.getChildAt(1);
        sc.addView(r3);
        TextView perm = Ui.button(this, "Beri izin otomatis (via root)", Ui.CARD2, Ui.ACCENT);
        perm.setOnClickListener(v -> grantViaRoot());
        sc.addView(perm, Ui.lp(this, MATCH, WRAP, 0, 8, 0, 0));
        Ui.addCard(col, sc);

        // --- optimasi ---
        LinearLayout oc = Ui.card(this);
        oc.addView(Ui.tv(this, "Optimasi cepat", 15, Ui.TEXT, true));
        final TextView boost = Ui.button(this, "Boost RAM sekarang", Ui.ACCENT, Ui.BG);
        boost.setOnClickListener(v -> {
            boost.setText("Membersihkan...");
            io.execute(() -> {
                final long d = Optimizer.boost(this);
                ui.post(() -> {
                    boost.setText("Boost RAM sekarang");
                    toast(d < 0 ? "Butuh akses root" : "RAM dibebaskan: " + Math.max(0, d) + " MB");
                });
            });
        });
        oc.addView(boost, Ui.lp(this, MATCH, WRAP, 0, 10, 0, 6));

        Switch perf = Ui.sw(this, "Mode Performa (governor CPU)", Optimizer.perfOn(this));
        perf.setOnCheckedChangeListener((b, on) -> io.execute(() -> Optimizer.setPerformance(this, on)));
        oc.addView(perf);

        boolean dndNow = Settings.Global.getInt(getContentResolver(), "zen_mode", 0) != 0;
        Switch dnd = Ui.sw(this, "Jangan Ganggu", dndNow);
        dnd.setOnCheckedChangeListener((b, on) -> io.execute(() -> Optimizer.setDnd(on)));
        oc.addView(dnd);
        Ui.addCard(col, oc);

        return sv;
    }

    private TextView statCell(LinearLayout row, String label) {
        LinearLayout c = Ui.col(this);
        c.setGravity(Gravity.CENTER);
        TextView v = Ui.tv(this, "--", 18, Ui.TEXT, true);
        v.setGravity(Gravity.CENTER);
        c.addView(v);
        TextView l = Ui.tv(this, label, 11, Ui.MUTED, false);
        l.setGravity(Gravity.CENTER);
        c.addView(l);
        row.addView(c, new LinearLayout.LayoutParams(0, WRAP, 1f));
        return v;
    }

    private void refreshLive() {
        if (tab != 0 || liveFps == null) return;
        boolean run = FpsState.running;
        if (swMonitor != null && swMonitor.isChecked() != run) {
            suppress = true;
            swMonitor.setChecked(run);
            suppress = false;
        }
        vMon.setText(run ? "Berjalan" : "Mati");
        vMon.setTextColor(run ? Ui.GREEN : Ui.MUTED);
        boolean ov = Settings.canDrawOverlays(this);
        vOverlay.setText(ov ? "Diizinkan" : "Belum diizinkan");
        vOverlay.setTextColor(ov ? Ui.GREEN : Ui.YELLOW);
        int rs = RootShell.state;
        vRoot.setText(rs == 1 ? "Aktif" : rs == 2 ? "Tidak tersedia" : "Memeriksa...");
        vRoot.setTextColor(rs == 1 ? Ui.GREEN : rs == 2 ? Ui.RED : Ui.YELLOW);

        String pkg = FpsState.pkg;
        float f = FpsState.fps;
        if (!run) {
            liveGame.setText("Monitor mati");
            liveFps.setText("--");
            liveFps.setTextColor(Ui.MUTED);
            liveSub.setText("");
            liveLayer.setText("");
        } else if (pkg.isEmpty()) {
            liveGame.setText(rs == 2 ? "Root tidak tersedia, FPS tidak bisa dibaca"
                    : "Menunggu game dibuka... (tambahkan game di tab Game)");
            liveFps.setText("--");
            liveFps.setTextColor(Ui.MUTED);
            liveSub.setText("");
            liveLayer.setText("");
        } else {
            liveGame.setText(labelOf(pkg));
            liveFps.setText(f > 0 ? String.valueOf(Math.round(f)) : "--");
            liveFps.setTextColor(Ui.fpsColor(f, FpsState.hz));
            liveSub.setText(f > 0 ? String.format(Locale.US, "FPS \u2022 %.1f ms \u2022 %d Hz",
                    FpsState.frameMs, Math.round(FpsState.hz)) : "FPS");
            liveLayer.setText(FpsState.layer.isEmpty() ? "" : "Sumber: SurfaceFlinger \u2022 " + FpsState.layer);
        }
        graph.set(FpsState.recent(60), FpsState.hz);
        float[] st = FpsState.stats();
        if (st == null) {
            stAvg.setText("--");
            stMin.setText("--");
            stMax.setText("--");
            stLow.setText("--");
        } else {
            stAvg.setText(String.valueOf(Math.round(st[0])));
            stMin.setText(String.valueOf(Math.round(st[1])));
            stMax.setText(String.valueOf(Math.round(st[2])));
            stLow.setText(String.valueOf(Math.round(st[3])));
        }
    }

    private String labelOf(String pkg) {
        if (pkg.equals(labelPkg)) return labelText;
        String t = pkg;
        try {
            PackageManager pm = getPackageManager();
            t = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {
        }
        labelPkg = pkg;
        labelText = t;
        return t;
    }

    private void resetSwitch() {
        if (swMonitor == null) return;
        suppress = true;
        swMonitor.setChecked(false);
        suppress = false;
    }

    private boolean startMonitor() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Izinkan \"Tampil di atas aplikasi lain\" untuk Game Space");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return false;
        }
        if (RootShell.state == 2) {
            toast("Root tidak tersedia, FPS tidak bisa dibaca");
            return false;
        }
        FpsState.running = true;
        try {
            startForegroundService(new Intent(this, FpsService.class));
        } catch (Exception e) {
            FpsState.running = false;
            toast("Gagal menjalankan monitor");
            return false;
        }
        Store.sp(this).edit().putBoolean("monitor_on", true).apply();
        return true;
    }

    private void stopMonitor() {
        stopService(new Intent(this, FpsService.class));
        FpsState.running = false;
        Store.sp(this).edit().putBoolean("monitor_on", false).apply();
    }

    private void grantViaRoot() {
        final String pkg = getPackageName();
        io.execute(() -> {
            final String r = RootShell.run("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                    + "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS; "
                    + "cmd deviceidle whitelist +" + pkg);
            ui.post(() -> toast(r == null ? "Gagal: root tidak tersedia" : "Izin diberikan via root"));
        });
    }

    // =====================================================================
    // GAME
    // =====================================================================

    private View buildGames() {
        LinearLayout[] o = new LinearLayout[1];
        ScrollView sv = page(o);
        LinearLayout col = o[0];
        col.addView(Ui.tv(this, "Pustaka Game", 24, Ui.TEXT, true));
        col.addView(Ui.tv(this, "Pilih game agar FPS Melayang dan Boost otomatis aktif saat game dibuka.",
                13, Ui.MUTED, false), Ui.lp(this, WRAP, WRAP, 0, 2, 0, 14));
        final LinearLayout list = Ui.col(this);
        col.addView(list);
        list.addView(Ui.tv(this, "Memuat daftar aplikasi...", 13, Ui.MUTED, false));
        io.execute(() -> {
            final List<AppItem> items = loadApps();
            ui.post(() -> {
                if (tab == 1) renderGames(list, items);
            });
        });
        return sv;
    }

    @SuppressWarnings("deprecation")
    private List<AppItem> loadApps() {
        PackageManager pm = getPackageManager();
        Intent mi = new Intent(Intent.ACTION_MAIN, null);
        mi.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(mi, 0);
        Set<String> seen = new HashSet<>();
        ArrayList<AppItem> out = new ArrayList<>();
        SharedPreferences sp = Store.sp(this);
        boolean seeded = sp.getBoolean("seeded", false);
        Set<String> games = Store.games(this);
        for (ResolveInfo ri : ris) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || !seen.add(p)) continue;
            AppItem a = new AppItem();
            a.pkg = p;
            a.label = ri.loadLabel(pm).toString();
            a.icon = ri.loadIcon(pm);
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            a.detected = ai.category == ApplicationInfo.CATEGORY_GAME
                    || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
            if (!seeded && a.detected) games.add(p);
            out.add(a);
        }
        if (!seeded) {
            Store.setGames(this, games);
            sp.edit().putBoolean("seeded", true).apply();
        }
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return out;
    }

    private void renderGames(final LinearLayout list, final List<AppItem> items) {
        list.removeAllViews();
        final Set<String> games = Store.games(this);
        ArrayList<AppItem> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (x, y) -> {
            boolean gx = games.contains(x.pkg), gy = games.contains(y.pkg);
            if (gx != gy) return gx ? -1 : 1;
            if (x.detected != y.detected) return x.detected ? -1 : 1;
            return x.label.compareToIgnoreCase(y.label);
        });
        for (final AppItem a : sorted) {
            boolean on = games.contains(a.pkg);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            row.setBackground(Ui.box(this, on ? Ui.CARD2 : Ui.CARD, 14));

            ImageView ic = new ImageView(this);
            ic.setImageDrawable(a.icon);
            row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

            LinearLayout names = Ui.col(this);
            names.addView(Ui.tv(this, a.label, 15, Ui.TEXT, true));
            names.addView(Ui.tv(this, a.detected ? "Game terdeteksi" : a.pkg, 11,
                    a.detected ? Ui.GREEN : Ui.MUTED, false));
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, WRAP, 1f);
            nl.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
            row.addView(names, nl);

            if (on) {
                TextView play = Ui.button(this, "Main", Ui.ACCENT, Ui.BG);
                play.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
                play.setOnClickListener(v -> launchGame(a.pkg));
                row.addView(play, new LinearLayout.LayoutParams(WRAP, WRAP));
            }
            CheckBox cb = new CheckBox(this);
            cb.setChecked(on);
            cb.setOnCheckedChangeListener((btn, checked) -> {
                Set<String> g = Store.games(MainActivity.this);
                if (checked) g.add(a.pkg);
                else g.remove(a.pkg);
                Store.setGames(MainActivity.this, g);
                ui.post(() -> renderGames(list, items));
            });
            row.addView(cb);
            list.addView(row, Ui.lp(this, MATCH, WRAP, 0, 0, 0, 8));
        }
    }

    private void launchGame(String pkg) {
        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) {
            toast("Tidak bisa membuka aplikasi ini");
            return;
        }
        if (!FpsState.running && Settings.canDrawOverlays(this) && RootShell.state != 2) {
            startMonitor();
        }
        startActivity(li);
    }

    // =====================================================================
    // SETELAN
    // =====================================================================

    private View buildSettings() {
        LinearLayout[] o = new LinearLayout[1];
        ScrollView sv = page(o);
        LinearLayout col = o[0];
        col.addView(Ui.tv(this, "Setelan", 24, Ui.TEXT, true), Ui.lp(this, WRAP, WRAP, 0, 0, 0, 14));

        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.tv(this, "Overlay FPS", 15, Ui.TEXT, true));
        c1.addView(prefSwitch("Tampil hanya saat game dari daftar", "only_games", true));
        c1.addView(prefSwitch("Tampilkan frame time dan refresh rate", "show_ft", true));
        c1.addView(seek("Ukuran teks", "text_size", 10, 40, 18));
        c1.addView(seek("Opasitas latar (%)", "opacity", 20, 100, 85));
        TextView reset = Ui.button(this, "Reset posisi overlay", Ui.CARD2, Ui.ACCENT);
        reset.setOnClickListener(v -> {
            Store.sp(this).edit().remove("ov_x").remove("ov_y").apply();
            toast("Posisi direset. Matikan lalu nyalakan lagi FPS Melayang.");
        });
        c1.addView(reset, Ui.lp(this, MATCH, WRAP, 0, 12, 0, 0));
        Ui.addCard(col, c1);

        LinearLayout c2 = Ui.card(this);
        c2.addView(Ui.tv(this, "Otomatisasi", 15, Ui.TEXT, true));
        c2.addView(prefSwitch("Auto Boost RAM saat game dibuka", "auto_boost", false));
        c2.addView(Ui.tv(this, "Boost menutup proses latar belakang dan membersihkan cache (butuh root).",
                12, Ui.MUTED, false));
        Ui.addCard(col, c2);
        return sv;
    }

    private Switch prefSwitch(String label, final String key, boolean def) {
        final SharedPreferences sp = Store.sp(this);
        Switch s = Ui.sw(this, label, sp.getBoolean(key, def));
        s.setOnCheckedChangeListener((b, c) -> sp.edit().putBoolean(key, c).apply());
        return s;
    }

    private View seek(final String label, final String key, final int min, int max, int def) {
        final SharedPreferences sp = Store.sp(this);
        LinearLayout l = Ui.col(this);
        l.setPadding(0, Ui.dp(this, 8), 0, 0);
        int cur = sp.getInt(key, def);
        final TextView t = Ui.tv(this, label + ": " + cur, 14, Ui.TEXT, false);
        l.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                sp.edit().putInt(key, v).apply();
                t.setText(label + ": " + v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        l.addView(sb);
        return l;
    }

    // =====================================================================
    // TENTANG
    // =====================================================================

    @SuppressWarnings("deprecation")
    private View buildAbout() {
        LinearLayout[] o = new LinearLayout[1];
        ScrollView sv = page(o);
        LinearLayout col = o[0];

        String ver = "1.0.0";
        long code = 1;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
            code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception ignored) {
        }

        LinearLayout head = Ui.col(this);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(getPackageManager().getApplicationIcon(getApplicationInfo()));
        head.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
        TextView name = Ui.tv(this, "Game Space", 26, Ui.TEXT, true);
        name.setGravity(Gravity.CENTER);
        head.addView(name, Ui.lp(this, WRAP, WRAP, 0, 8, 0, 0));
        TextView sub = Ui.tv(this, "Versi " + ver + " (build " + code + ")", 13, Ui.ACCENT, false);
        sub.setGravity(Gravity.CENTER);
        head.addView(sub);
        TextView dev = Ui.tv(this, "Dikembangkan oleh SenzaProject", 13, Ui.MUTED, false);
        dev.setGravity(Gravity.CENTER);
        head.addView(dev, Ui.lp(this, WRAP, WRAP, 0, 2, 0, 14));
        col.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout about = Ui.card(this);
        about.addView(Ui.tv(this, "Tentang aplikasi", 15, Ui.TEXT, true));
        about.addView(Ui.tv(this, "Game Space adalah pusat kontrol gaming untuk perangkat root: "
                + "memantau FPS game secara nyata, mengoptimalkan performa, dan menjaga fokus saat bermain.",
                13, Ui.MUTED, false), Ui.lp(this, WRAP, WRAP, 0, 6, 0, 0));
        Ui.addCard(col, about);

        LinearLayout feat = Ui.card(this);
        feat.addView(Ui.tv(this, "Fitur spesial", 15, Ui.TEXT, true));
        String[] f = {
                "Pembaca FPS asli via root: membaca waktu present frame langsung dari SurfaceFlinger, bukan estimasi.",
                "FPS melayang yang bisa digeser, warna otomatis (hijau/kuning/merah) mengikuti refresh rate layar.",
                "Deteksi game otomatis; overlay hanya muncul saat game aktif sehingga hemat baterai.",
                "Statistik sesi: rata-rata, min, max, 1% low, plus grafik live.",
                "Boost RAM, Mode Performa (governor CPU) dan Jangan Ganggu sekali tap.",
                "Izin otomatis via root, tanpa iklan, tanpa internet, tanpa pelacakan."
        };
        for (String s : f) {
            feat.addView(Ui.tv(this, "\u2022 " + s, 13, Ui.MUTED, false), Ui.lp(this, WRAP, WRAP, 0, 8, 0, 0));
        }
        Ui.addCard(col, feat);

        String chip = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE;
        int rs = RootShell.state;
        LinearLayout info = Ui.card(this);
        info.addView(Ui.tv(this, "Informasi", 15, Ui.TEXT, true));
        info.addView(Ui.kv(this, "Versi", ver, Ui.TEXT));
        info.addView(Ui.kv(this, "Build", String.valueOf(code), Ui.TEXT));
        info.addView(Ui.kv(this, "Pengembang", "SenzaProject", Ui.TEXT));
        info.addView(Ui.kv(this, "Package", getPackageName(), Ui.TEXT));
        info.addView(Ui.kv(this, "Perangkat", Build.MANUFACTURER + " " + Build.MODEL, Ui.TEXT));
        info.addView(Ui.kv(this, "Chipset", chip, Ui.TEXT));
        info.addView(Ui.kv(this, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", Ui.TEXT));
        info.addView(Ui.kv(this, "Root", rs == 1 ? "Aktif" : rs == 2 ? "Tidak tersedia" : "Belum dicek",
                rs == 1 ? Ui.GREEN : rs == 2 ? Ui.RED : Ui.YELLOW));
        Ui.addCard(col, info);

        TextView foot = Ui.tv(this, "\u00A9 2026 SenzaProject. Seluruh hak dilindungi.", 11, Ui.MUTED, false);
        foot.setGravity(Gravity.CENTER);
        col.addView(foot, new LinearLayout.LayoutParams(MATCH, WRAP));
        return sv;
    }
}
