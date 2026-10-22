package com.senzaproject.gamespace;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Optimasi cepat berbasis root. */
final class Optimizer {
    private static final String GOV = "/sys/devices/system/cpu/cpu*/cpufreq/scaling_governor";

    private Optimizer() {}

    static long freeMb(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem >> 20;
    }

    /** Return RAM yang dibebaskan (MB) atau -1 jika tanpa root. */
    static long boost(Context c) {
        if (RootShell.state == 0) RootShell.isRoot();
        if (RootShell.state != 1) return -1;
        long before = freeMb(c);
        RootShell.run("am kill-all; sync; echo 3 > /proc/sys/vm/drop_caches; "
                + "echo 1 > /proc/sys/vm/compact_memory");
        SystemClock.sleep(700);
        return freeMb(c) - before;
    }

    static boolean perfOn(Context c) {
        SharedPreferences sp = Store.sp(c);
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return sp.getBoolean("perf_on", false)
                && Math.abs(sp.getLong("perf_boot", 0) - boot) < 10000;
    }

    static void setPerformance(Context c, boolean on) {
        SharedPreferences sp = Store.sp(c);
        if (on) {
            String orig = RootShell.run("for g in " + GOV + "; do echo \"$g $(cat $g)\"; done");
            if (orig != null && !orig.contains("performance")) {
                sp.edit().putString("orig_gov", orig).apply();
            }
            RootShell.run("for g in " + GOV + "; do echo performance > $g; done");
            long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            sp.edit().putBoolean("perf_on", true).putLong("perf_boot", boot).apply();
        } else {
            String orig = sp.getString("orig_gov", null);
            if (orig != null) {
                StringBuilder sb = new StringBuilder();
                for (String line : orig.split("\n")) {
                    String[] p = line.trim().split(" ");
                    if (p.length == 2 && p[0].startsWith("/sys/")) {
                        sb.append("echo ").append(p[1]).append(" > ").append(p[0]).append('\n');
                    }
                }
                RootShell.run(sb.toString());
            }
            sp.edit().putBoolean("perf_on", false).apply();
        }
    }

    static void setDnd(boolean on) {
        RootShell.run("settings put global zen_mode " + (on ? 1 : 0));
    }
}
