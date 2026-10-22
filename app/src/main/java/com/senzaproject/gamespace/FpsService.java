package com.senzaproject.gamespace;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Set;

/** Service foreground: membaca FPS via root dan menampilkan overlay melayang. */
public class FpsService extends Service {
    static final String ACTION_STOP = "com.senzaproject.gamespace.STOP";
    private static final String CH = "gs_monitor";

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread ht;
    private Handler bg;
    private volatile boolean alive;
    private final FpsReader reader = new FpsReader();

    private String fgPkg = "", lastPkg = "", homePkg = "";
    private long lastFg, lastGameLoad;
    private Set<String> gameSet;
    private volatile boolean wantShown;

    // overlay (hanya diakses dari main thread)
    private WindowManager wm;
    private LinearLayout box;
    private TextView tvFps, tvSub;
    private WindowManager.LayoutParams lp;
    private boolean attached;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Store.sp(this).edit().putBoolean("monitor_on", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        startFg();
        if (!alive) {
            alive = true;
            FpsState.running = true;
            homePkg = findHome();
            ht = new HandlerThread("gs-fps");
            ht.start();
            bg = new Handler(ht.getLooper());
            main.post(this::buildOverlay);
            bg.post(loop);
        }
        return START_STICKY;
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            long t0 = SystemClock.uptimeMillis();
            try {
                tick();
            } catch (Throwable ignored) {
            }
            long dt = SystemClock.uptimeMillis() - t0;
            if (alive) bg.postDelayed(this, Math.max(100, 500 - dt));
        }
    };

    private void tick() {
        if (RootShell.state == 0) RootShell.isRoot();
        if (RootShell.state != 1) {
            idle();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (gameSet == null || now - lastGameLoad > 2000) {
            gameSet = Store.games(this);
            lastGameLoad = now;
        }
        if (now - lastFg >= 1000 || fgPkg.isEmpty()) {
            String p = reader.foregroundPackage();
            if (p != null) fgPkg = p;
            lastFg = now;
        }
        String pkg = fgPkg;
        SharedPreferences sp = Store.sp(this);
        boolean onlyGames = sp.getBoolean("only_games", true);
        boolean isGame = gameSet.contains(pkg);
        boolean relevant = !pkg.isEmpty()
                && !pkg.equals(getPackageName())
                && !pkg.equals(homePkg)
                && !pkg.startsWith("com.android.systemui")
                && (isGame || !onlyGames);
        if (!relevant) {
            idle();
            return;
        }
        if (!pkg.equals(lastPkg)) {
            lastPkg = pkg;
            reader.reset();
            FpsState.reset();
            FpsState.hz = 0;
            if (isGame && sp.getBoolean("auto_boost", false)) Optimizer.boost(this);
        }
        FpsReader.Sample s = reader.read(pkg);
        FpsState.pkg = pkg;
        FpsState.fps = s.fps;
        FpsState.frameMs = s.frameMs;
        FpsState.layer = s.layer;
        if (s.hz > 0) FpsState.hz = s.hz;
        if (s.fps > 0) FpsState.push(s.fps);

        final float f = s.fps, ms = s.frameMs, hz = FpsState.hz;
        wantShown = true;
        main.post(() -> {
            setOverlayVisible(true);
            updateOverlay(f, ms, hz);
        });
    }

    private void idle() {
        FpsState.fps = 0;
        FpsState.pkg = "";
        FpsState.layer = "";
        lastPkg = "";
        reader.reset();
        if (wantShown) {
            wantShown = false;
            main.post(() -> setOverlayVisible(false));
        }
    }

    private String findHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
    }

    // ---------- overlay ----------

    private void buildOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        tvFps = Ui.tv(this, "-- FPS", 18, Ui.GREEN, true);
        tvSub = Ui.tv(this, "", 11, 0xFFB8C0D8, false);
        box.addView(tvFps);
        box.addView(tvSub);

        final SharedPreferences sp = Store.sp(this);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt("ov_x", Ui.dp(this, 16));
        lp.y = sp.getInt("ov_y", Ui.dp(this, 90));

        box.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float tx, ty;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x;
                        sy = lp.y;
                        tx = e.getRawX();
                        ty = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = sx + (int) (e.getRawX() - tx);
                        lp.y = sy + (int) (e.getRawY() - ty);
                        if (attached) wm.updateViewLayout(box, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        sp.edit().putInt("ov_x", lp.x).putInt("ov_y", lp.y).apply();
                        return true;
                    default:
                        return false;
                }
            }
        });
        applyStyle();
    }

    private void applyStyle() {
        SharedPreferences sp = Store.sp(this);
        float size = sp.getInt("text_size", 18);
        tvFps.setTextSize(size);
        tvSub.setTextSize(Math.max(9f, size * 0.6f));
        int a = Math.round(sp.getInt("opacity", 85) * 2.55f);
        box.setBackground(Ui.box(this, (a << 24) | 0x0B0F1A, 12));
        tvSub.setVisibility(sp.getBoolean("show_ft", true) ? View.VISIBLE : View.GONE);
    }

    private void setOverlayVisible(boolean show) {
        if (box == null || wm == null) return;
        if (show && !attached) {
            if (!Settings.canDrawOverlays(this)) return;
            try {
                wm.addView(box, lp);
                attached = true;
            } catch (Exception ignored) {
            }
        } else if (!show && attached) {
            try {
                wm.removeView(box);
            } catch (Exception ignored) {
            }
            attached = false;
        }
    }

    private void updateOverlay(float fps, float ms, float hz) {
        if (box == null) return;
        applyStyle();
        tvFps.setTextColor(Ui.fpsColor(fps, hz));
        tvFps.setText(fps > 0 ? Math.round(fps) + " FPS" : "-- FPS");
        float target = hz > 0 ? hz : 60f;
        tvSub.setText(fps > 0
                ? String.format(Locale.US, "%.1f ms \u00B7 %d Hz", ms, Math.round(target))
                : "menunggu frame...");
    }

    // ---------- notifikasi ----------

    private void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CH, "Monitor FPS", NotificationManager.IMPORTANCE_LOW));
        }
        int imm = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), imm);
        PendingIntent stop = PendingIntent.getService(
                this, 1, new Intent(this, FpsService.class).setAction(ACTION_STOP), imm);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Game Space")
                .setContentText("Monitor FPS aktif")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat), "Matikan", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public void onDestroy() {
        alive = false;
        FpsState.running = false;
        FpsState.fps = 0;
        FpsState.pkg = "";
        if (bg != null) bg.removeCallbacksAndMessages(null);
        if (ht != null) ht.quitSafely();
        setOverlayVisible(false);
        stopForeground(true);
        super.onDestroy();
    }
}
