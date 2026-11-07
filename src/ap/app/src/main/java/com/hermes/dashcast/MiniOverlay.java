package com.hermes.dashcast;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * **桌面浮窗（overlay）** —— 真正浮在所有 App 之上，按 HOME 也不消失。
 *
 * ══════════════════════════════════════════════════════════════════
 * 为什么之前那个 Activity 方案做不到
 * ══════════════════════════════════════════════════════════════════
 *
 * MiniWindowActivity 已经能让窗口不铺满（`resizeableActivity` + WRAP_CONTENT），
 * 但它仍然是**一个 Activity**：
 *   · 按 HOME → 任务栈退到后台 → 窗口消失
 *   · 桌面/别的 App 盖上来 → 被挡住
 *
 * 而你给的那张截图（AVM 小窗浮在地图上）是 **WindowManager overlay**：
 * 窗口挂在 WindowManager 上，不在任何任务栈里，所以永远在最上层。
 *
 * overlay 的唯一硬门槛是 SYSTEM_ALERT_WINDOW 权限。
 *
 * ══════════════════════════════════════════════════════════════════
 * 本类的结构
 * ══════════════════════════════════════════════════════════════════
 *
 *   WindowManager.addView(窗口)  →  浮在整个屏幕上
 *      ├─ 标题栏（可拖动）
 *      ├─ TextureView（挂 VirtualDisplay，显示被搬进来的 App）
 *      └─ 状态行
 *
 * 虚拟屏链路和 MiniWindowActivity 完全一样：
 * createVirtualDisplay → am display move-stack → 画面流到 TextureView
 *
 * ══════════════════════════════════════════════════════════════════
 * 权限
 * ══════════════════════════════════════════════════════════════════
 *
 *   · SYSTEM_ALERT_WINDOW —— 必须。没有它 addView 会抛 BadTokenException
 *   · 授权方式：Settings.canDrawOverlays() 检查，没有就跳系统设置页
 */
public final class MiniOverlay {

    private static final String TAG = "DashcastOverlay";

    private static final int VD_W = 960;
    private static final int VD_H = 600;
    private static final int VD_DPI = 160;

    private static final int VD_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

    /* ── 单例 ── */
    private static MiniOverlay sInstance;

    public static MiniOverlay get(Context ctx) {
        if (sInstance == null) {
            sInstance = new MiniOverlay(ctx.getApplicationContext());
        }
        return sInstance;
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /* ── 视图 ── */
    private LinearLayout root;
    private FrameLayout contentBox;
    private TextureView preview;
    private TextView info;
    private TextView titleView;

    /* ── 窗口参数（拖动时改 x/y 再 updateViewLayout）── */
    private WindowManager.LayoutParams lp;

    /* ── 虚拟屏 ── */
    private VirtualDisplay vd;
    private int displayId = -1;
    private HandlerThread bgThread;
    private Handler bgHandler;

    /* ── 统计 ── */
    private int frameCount;
    private long lastFrameAt;
    private long startAt;
    private int lastTickFrames;
    private long lastTickAt;
    private Runnable statsTick;

    private volatile boolean showing;

    private String targetPkg;
    private String targetLabel;

    private MiniOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    // ══════════════════════════════════════════════════════════════════
    // 权限
    // ══════════════════════════════════════════════════════════════════

    /** 有没有悬浮窗权限 */
    public static boolean canDrawOverlay(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(ctx);
            }
            return true;
        } catch (Throwable t) {
            DashLog.w(TAG, "检查悬浮窗权限失败: " + t);
            return false;
        }
    }

    /** 跳系统设置页去授权 */
    public static void requestOverlayPermission(Context ctx) {
        try {
            Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) {
            DashLog.w(TAG, "跳转悬浮窗授权页失败: " + t);
            try {
                Intent it2 = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                it2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it2);
            } catch (Throwable t2) {
                DashLog.e(TAG, "悬浮窗授权页完全打不开", t2);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 显示 / 隐藏
    // ══════════════════════════════════════════════════════════════════

    /**
     * 显示浮窗。
     *
     * @return true = 成功浮起；false = 没权限或失败
     */
    public boolean show() {
        if (showing) return true;

        if (!canDrawOverlay(ctx)) {
            DashLog.w(TAG, "没有 SYSTEM_ALERT_WINDOW 权限，浮窗无法显示");
            return false;
        }

        try {
            buildView();

            /*
             * 窗口类型：Android 8+ 必须用 TYPE_APPLICATION_OVERLAY。
             * 老的 TYPE_PHONE / TYPE_SYSTEM_ALERT 在新系统上会被拒。
             */
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    /*
                     * flags：
                     *   NOT_FOCUSABLE        —— 不抢焦点，桌面/别的 App 仍能操作
                     *   LAYOUT_NO_LIMITS     —— 不被屏幕边界裁剪（拖动超出时也画得出）
                     *
                     * 不加 NOT_TOUCH_MODAL：窗口内的触摸要能被自己吃掉，
                     * 否则点小窗会穿透到下层。
                     */
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);

            lp.gravity = Gravity.TOP | Gravity.START;
            /* 初始位置：右上角留 24dp 边距（参考截图那种靠边浮窗） */
            lp.x = 0;
            lp.y = dp(80);

            wm.addView(root, lp);
            showing = true;

            DashLog.i(TAG, "OVERLAY shown type=0x" + Integer.toHexString(type)
                    + " size=" + lp.width + "x" + lp.height);
            return true;
        } catch (Throwable t) {
            DashLog.e(TAG, "浮窗 addView 失败", t);
            showing = false;
            return false;
        }
    }

    public void hide() {
        if (!showing || root == null) return;
        try {
            /* 先把搬走的 App 送回主屏，再拆窗 */
            restoreTargetToMain();
            releaseVirtualDisplay();
            stopStats();
            wm.removeView(root);
        } catch (Throwable t) {
            DashLog.w(TAG, "移除浮窗失败: " + t);
        }
        root = null;
        showing = false;
        DashLog.i(TAG, "OVERLAY hidden");
    }

    public boolean isShowing() {
        return showing;
    }

    // ══════════════════════════════════════════════════════════════════
    // 视图构建
    // ══════════════════════════════════════════════════════════════════

    private void buildView() {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Squircle.of(ctx).card(R.color.surface_card));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        /* ── 标题栏（可拖动）── */
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(ctx);
        titleView.setTextSize(16);
        titleView.setTextColor(ctx.getColor(R.color.content_primary));
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setText("小窗");
        titleRow.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button close = new Button(ctx);
        close.setText("×");
        close.setTextSize(16);
        close.setAllCaps(false);
        close.setTextColor(ctx.getColor(R.color.content_secondary));
        close.setBackground(null);
        close.setPadding(dp(10), 0, dp(10), 0);
        close.setOnClickListener(v -> hide());
        titleRow.addView(close);
        root.addView(titleRow);

        /* 标题栏拖动 */
        attachDrag(titleRow);

        /* ── 画面区 ── */
        contentBox = new FrameLayout(ctx);
        contentBox.setBackground(Squircle.of(ctx).border(
                R.color.surface_deep, R.color.surface_border, 1f, Squircle.R_TAB));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(dp(400), dp(250));
        boxLp.setMargins(0, dp(8), 0, 0);
        contentBox.setLayoutParams(boxLp);

        preview = new TextureView(ctx);
        contentBox.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(contentBox);

        /* ── 状态行 ── */
        info = new TextView(ctx);
        info.setTextSize(12);
        info.setTextColor(ctx.getColor(R.color.content_tertiary));
        info.setPadding(0, dp(6), 0, 0);
        info.setText("准备中…");
        root.addView(info);

        preview.setSurfaceTextureListener(surfaceListener);

        bgThread = new HandlerThread("overlay-vd");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    /**
     * 给某个 View 装上「按住拖动整个浮窗」的手势。
     *
     * 实现要点：ACTION_DOWN 时记下手指位置与窗口位置，
     * ACTION_MOVE 时算出位移，改 lp.x / lp.y 后 updateViewLayout。
     * 这样拖动是**改窗口坐标**，不是移动 View 内容 —— 内容不会跟着空白。
     */
    private void attachDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        if (lp != null) {
                            startX = lp.x;
                            startY = lp.y;
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (lp != null) {
                            lp.x = startX + (int) (e.getRawX() - downX);
                            lp.y = startY + (int) (e.getRawY() - downY);
                            try {
                                wm.updateViewLayout(root, lp);
                            } catch (Throwable t) {
                                DashLog.w(TAG, "拖动更新窗口失败: " + t);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        /* 记住位置，下次浮起还在原地 */
                        if (lp != null) {
                            ctx.getSharedPreferences("mini_overlay", Context.MODE_PRIVATE)
                                    .edit()
                                    .putInt("x", lp.x)
                                    .putInt("y", lp.y)
                                    .apply();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // 虚拟屏
    // ══════════════════════════════════════════════════════════════════

    private final TextureView.SurfaceTextureListener surfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    createVirtualDisplay(st);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    releaseVirtualDisplay();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    frameCount++;
                    lastFrameAt = SystemClock.elapsedRealtime();
                }
            };

    private void createVirtualDisplay(SurfaceTexture st) {
        try {
            DisplayManager dm =
                    (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            android.view.Surface surface = new android.view.Surface(st);

            vd = dm.createVirtualDisplay("DashcastOverlay", VD_W, VD_H, VD_DPI,
                    surface, VD_FLAGS);

            if (vd == null) {
                setInfo("虚拟屏创建失败");
                return;
            }
            android.view.Display d = vd.getDisplay();
            displayId = d == null ? -1 : d.getDisplayId();
            DashLog.i(TAG, "OVERLAY_VD displayId=" + displayId
                    + " " + VD_W + "x" + VD_H + "@" + VD_DPI);

            /* 恢复上次选中的 App */
            targetPkg = ctx.getSharedPreferences("mini_overlay", Context.MODE_PRIVATE)
                    .getString("pkg", null);
            targetLabel = ctx.getSharedPreferences("mini_overlay", Context.MODE_PRIVATE)
                    .getString("label", null);

            if (targetPkg != null) {
                final String p = targetPkg;
                if (bgHandler != null) bgHandler.post(() -> moveAppToDisplay(p));
            } else {
                setInfo("未指定内容（先用 MiniWindowActivity 选一个 App）");
            }
        } catch (Throwable t) {
            DashLog.e(TAG, "OVERLAY_VD 建立失败", t);
            setInfo("虚拟屏建立失败");
        }
    }

    private void moveAppToDisplay(String pkg) {
        if (displayId < 0) return;
        startAt = SystemClock.elapsedRealtime();
        lastTickAt = startAt;
        lastTickFrames = frameCount;

        int taskId = findTaskId(pkg);
        if (taskId > 0) {
            String r = safeExec("am display move-stack " + taskId + " " + displayId);
            DashLog.i(TAG, "OVERLAY_MOVE move-stack " + taskId + "→" + displayId + ": " + r);
            if (taskOnDisplay(pkg, displayId)) {
                afterMoved(pkg);
                return;
            }
        }
        /* 兜底：am start --display */
        safeExec("am start --display " + displayId + " -n " + pkg);
        if (taskOnDisplay(pkg, displayId)) {
            afterMoved(pkg);
        } else {
            setInfo("无法把「" + pkg + "」搬进小窗");
        }
    }

    private void afterMoved(String pkg) {
        final String label = targetLabel != null ? targetLabel : pkg;
        setInfo("已搬入：" + label);
        startStats();
    }

    private int findTaskId(String pkg) {
        try {
            String out = safeExec("am stack list");
            if (out == null) return -1;
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.startsWith("taskId=") && t.contains(pkg + "/")) {
                    int eq = t.indexOf('=');
                    int colon = t.indexOf(':', eq);
                    if (eq > 0 && colon > eq) {
                        try {
                            return Integer.parseInt(t.substring(eq + 1, colon).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private boolean taskOnDisplay(String pkg, int want) {
        try {
            String out = safeExec("am stack list");
            if (out == null) return false;
            int curDisplay = -1;
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.startsWith("Stack id=")) {
                    int di = t.indexOf("displayId=");
                    if (di > 0) {
                        try {
                            curDisplay = Integer.parseInt(
                                    t.substring(di + 10).split(" ")[0].trim());
                        } catch (Throwable ignored) {
                            curDisplay = -1;
                        }
                    }
                } else if (t.startsWith("taskId=") && t.contains(pkg + "/")) {
                    if (curDisplay == want) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String safeExec(String cmd) {
        try {
            return AdbShell.exec(cmd, 8000);
        } catch (Throwable t) {
            return null;
        }
    }

    private void restoreTargetToMain() {
        if (targetPkg == null) return;
        try {
            int tid = findTaskId(targetPkg);
            if (tid > 0) {
                safeExec("am display move-stack " + tid + " 0");
                DashLog.i(TAG, "OVERLAY_RESTORE " + targetPkg + " → display 0");
            }
        } catch (Throwable ignored) {
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 统计（主线程，避免 CalledFromWrongThreadException）
    // ══════════════════════════════════════════════════════════════════

    private void startStats() {
        if (statsTick != null) return;
        statsTick = new Runnable() {
            @Override
            public void run() {
                if (!showing) return;
                refreshInfo();
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.postDelayed(statsTick, 1000);
    }

    private void stopStats() {
        if (statsTick != null) {
            mainHandler.removeCallbacks(statsTick);
            statsTick = null;
        }
    }

    private void refreshInfo() {
        if (info == null) return;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - startAt;

        int deltaFrames = frameCount - lastTickFrames;
        long deltaMs = now - lastTickAt;
        float instFps = (deltaMs > 0) ? (deltaFrames * 1000f / deltaMs) : 0f;
        lastTickFrames = frameCount;
        lastTickAt = now;

        String stat = "display=" + displayId + "  帧=" + frameCount;
        String state;
        if (frameCount == 0) {
            state = "无帧";
        } else if ((now - lastFrameAt) >= 2500) {
            state = "静止";
        } else {
            state = String.format("%.1f fps", instFps);
        }
        info.setText(stat + "  " + state);
    }

    private void setInfo(String text) {
        if (info == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            info.setText(text);
        } else {
            final String t = text;
            mainHandler.post(() -> {
                if (info != null && showing) info.setText(t);
            });
        }
    }

    private void releaseVirtualDisplay() {
        VirtualDisplay v = vd;
        vd = null;
        if (v != null) {
            try {
                v.release();
            } catch (Throwable t) {
                DashLog.w(TAG, "release 虚拟屏失败: " + t);
            }
        }
        displayId = -1;
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
            bgHandler = null;
        }
    }

    /** 让外部指定要显示哪个 App */
    public void setTarget(String pkg, String label) {
        this.targetPkg = pkg;
        this.targetLabel = label;
        ctx.getSharedPreferences("mini_overlay", Context.MODE_PRIVATE)
                .edit().putString("pkg", pkg).putString("label", label).apply();
        if (displayId >= 0 && bgHandler != null) {
            bgHandler.post(() -> moveAppToDisplay(pkg));
        }
    }

    public String getTargetPkg() {
        return targetPkg;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
