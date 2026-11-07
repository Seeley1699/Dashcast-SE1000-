package com.hermes.dashcast;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 小窗（浮窗）：**可选 App，在里面运行**。
 *
 * ══════════════════════════════════════════════════════════════════
 * 结构
 * ══════════════════════════════════════════════════════════════════
 *
 *   ① 建一块 VirtualDisplay（960×600），输出面 = TextureView 的 SurfaceTexture
 *   ② 让用户挑一个 App
 *   ③ 把那个 App 的 task 搬进虚拟屏（`am display move-stack <task> <display>`）
 *   ④ App 的画面流过 TextureView → 显示在小窗里
 *
 * ══════════════════════════════════════════════════════════════════
 * 为什么不用 `am start --display`（实测教训）
 * ══════════════════════════════════════════════════════════════════
 *
 * 对 `com.geely.map` 有效（task 确实搬走了），但对 **AVM** 完全无效：
 * 它返回 "Activity not started, its current task has been brought to the front"，
 * 窗口仍留在主屏。
 *
 * 根因（dumpsys window 实测）：
 *   com.ecarx.avm    mDisplayId=0  ty=2062   ← 系统级窗口类型，display 由系统服务定
 *   com.geely.map    ty=BASE_APPLICATION     ← 普通 App 窗口，可搬
 *
 * 所以正确顺序是：
 *   ① 先 `am display move-stack`（搬已在运行的 task，不重启 App）
 *   ② 失败再 `am start --display`（App 没运行时）
 *   ③ 每次都**回读 am stack list 复核**，不信返回码
 *
 * 这条结论和 VdMirror.moveTaskIn() 完全一致。
 *
 * ══════════════════════════════════════════════════════════════════
 * 不碰车机
 * ══════════════════════════════════════════════════════════════════
 *
 *   · 虚拟屏是进程内资源，onDestroy 里 release
 *   · 退出时把搬走的 App 任务**移回主屏**（不留残留在虚拟屏上）
 *   · 不用 MediaProjection，不改系统配置
 */
public class MiniWindowActivity extends Activity {

    private static final String TAG = "DashcastMini";
    private static final String PREF = "mini_window";
    private static final String KEY_PKG = "target_pkg";
    private static final String KEY_LABEL = "target_label";

    private static final int VD_W = 960;
    private static final int VD_H = 600;
    private static final int VD_DPI = 160;

    /*
     * flags：不带 AUTO_MIRROR → 不需要 CAPTURE_VIDEO_OUTPUT 权限。
     * OWN_CONTENT_ONLY → 不镜像主屏，避免搬 App 之前先闪一下桌面。
     */
    private static final int VD_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

    // ── 视图 ──
    private LinearLayout root;
    private FrameLayout previewBox;
    private TextureView preview;
    private LinearLayout pickerBox;          // App 列表容器（未选 App 时显示）
    private ScrollView pickerScroll;
    private TextView titleView;
    private TextView info;

    // ── 虚拟屏 ──
    private VirtualDisplay vd;
    private int displayId = -1;

    private HandlerThread bgThread;
    private Handler bgHandler;
    /** 主线程 Handler —— 所有 View 操作必须经过它 */
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());

    // ── 统计 ──
    private long startAt;
    private long lastFrameAt;
    private int frameCount;
    private int lastTickFrames;
    private long lastTickAt;

    private volatile boolean destroyed;

    // ── 当前内容源 ──
    private String targetPkg;
    private String targetLabel;

    /**
     * 统一入口：**用 Flyme 系统小窗模式启动**。
     *
     * ══════════════════════════════════════════════════════════════════
     * 两条路都能进系统小窗（2026-11-07 实测）
     * ══════════════════════════════════════════════════════════════════
     *
     *   ① adb 命令行：`am start ... --windowingMode 11`
     *      实测 Configuration 里出现 `mWindowingMode=flyme-mini-window`
     *      —— 带系统原生标题栏（× / 分屏 / 全屏），拖动缩放系统接管
     *
     *   ② 代码里 ActivityOptions 放 `start_windowmode=1`
     *      framework-flyme.jar / service-flyme.jar 里都有这个 key
     *      银河视界 SrMiniActivity:96 就是这么做的
     *
     * 这里走 ②，因为正式使用只能从 App 内拉起。
     *
     * 失败兜底：putBoolean 抛异常（非 Flyme 车机）就退回普通启动，
     * 那时至少还有 Manifest 的 resizeableActivity + WRAP_CONTENT，
     * 窗口仍然不铺满（只是没有系统标题栏）。
     */
    public static void launch(Context ctx) {
        Intent it = new Intent(ctx, MiniWindowActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Bundle opts = null;
        try {
            opts = ActivityOptions.makeBasic().toBundle();
            opts.putBoolean("start_windowmode", true);
        } catch (Throwable t) {
            DashLog.w(TAG, "构造系统小窗 options 失败: " + t);
            opts = null;
        }

        try {
            if (opts != null) {
                ctx.startActivity(it, opts);
            } else {
                ctx.startActivity(it);
            }
            DashLog.i(TAG, "MINI_LAUNCH start_windowmode=" + (opts != null));
        } catch (Throwable t) {
            DashLog.w(TAG, "系统小窗启动失败，退回普通启动: " + t);
            try {
                ctx.startActivity(it);
            } catch (Throwable t2) {
                DashLog.e(TAG, "小窗彻底启动失败", t2);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        targetPkg = sp.getString(KEY_PKG, null);
        targetLabel = sp.getString(KEY_LABEL, null);

        Window w = getWindow();
        try {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.0f;
            w.setAttributes(lp);
        } catch (Throwable t) {
            DashLog.w(TAG, "设置窗口尺寸失败: " + t);
        }

        bgThread = new HandlerThread("mini-vd");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        buildUi();
        setContentView(root);

        preview.setSurfaceTextureListener(surfaceListener);

        if (targetPkg == null) {
            /* 还没选过 App → 直接显示列表 */
            showPicker();
        } else {
            updateTitle();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // UI
    // ══════════════════════════════════════════════════════════════════

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Squircle.of(this).card(R.color.surface_card));
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        /* ── 标题行：标题 + 切换 + 关闭 ── */
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(this);
        titleView.setTextSize(18);
        titleView.setTextColor(getColor(R.color.content_primary));
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setText("小窗");
        titleRow.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button switchBtn = new Button(this);
        switchBtn.setText("切换");
        switchBtn.setTextSize(15);
        switchBtn.setAllCaps(false);
        switchBtn.setTextColor(getColor(R.color.accent_soft));
        switchBtn.setBackground(null);
        switchBtn.setPadding(dp(12), 0, dp(12), 0);
        switchBtn.setOnClickListener(v -> showPicker());
        titleRow.addView(switchBtn);

        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(18);
        close.setAllCaps(false);
        close.setTextColor(getColor(R.color.content_secondary));
        close.setBackground(null);
        close.setPadding(dp(8), 0, dp(8), 0);
        close.setOnClickListener(v -> finish());
        titleRow.addView(close);
        root.addView(titleRow);

        /* ── 画面区 ── */
        previewBox = new FrameLayout(this);
        previewBox.setBackground(Squircle.of(this).border(
                R.color.surface_deep, R.color.surface_border, 1f, Squircle.R_TAB));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(dp(480), dp(300));
        boxLp.setMargins(0, dp(12), 0, 0);
        previewBox.setLayoutParams(boxLp);

        preview = new TextureView(this);
        previewBox.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(previewBox);

        /* ── 状态行 ── */
        info = new TextView(this);
        info.setTextSize(13);
        info.setTextColor(getColor(R.color.content_tertiary));
        info.setPadding(0, dp(8), 0, 0);
        info.setText("准备中…");
        root.addView(info);

        /* ── App 选择器（浮在画面区上方，默认隐藏） ── */
        pickerBox = new LinearLayout(this);
        pickerBox.setOrientation(LinearLayout.VERTICAL);
        pickerBox.setBackground(Squircle.of(this).card(R.color.surface_card));
        pickerBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        pickerBox.setVisibility(LinearLayout.GONE);

        pickerScroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420));
        pickerScroll.setLayoutParams(scrollLp);
        pickerBox.addView(pickerScroll);

        LinearLayout.LayoutParams pkLp = new LinearLayout.LayoutParams(dp(480), dp(460));
        pkLp.setMargins(0, dp(12), 0, 0);
        pickerBox.setLayoutParams(pkLp);
        root.addView(pickerBox);
    }

    private void updateTitle() {
        if (titleView == null) return;
        String name = (targetLabel != null && !targetLabel.isEmpty())
                ? targetLabel : (targetPkg == null ? "未选择" : targetPkg);
        titleView.setText("小窗 · " + name);
    }

    /** 显示 App 选择列表 */
    private void showPicker() {
        if (pickerBox == null) return;

        List<AppEntry> apps = loadLaunchableApps();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有可选的 App");
            empty.setTextSize(15);
            empty.setTextColor(getColor(R.color.content_tertiary));
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty);
        } else {
            for (AppEntry e : apps) {
                Button b = new Button(this);
                b.setText(e.label);
                b.setTextSize(15);
                b.setAllCaps(false);
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setTextColor(getColor(R.color.content_primary));
                b.setBackground(null);
                b.setPadding(dp(12), 0, dp(12), 0);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
                b.setLayoutParams(blp);
                b.setOnClickListener(v -> selectApp(e));
                list.addView(b);
            }
        }

        pickerScroll.removeAllViews();
        pickerScroll.addView(list);
        pickerBox.setVisibility(LinearLayout.VISIBLE);
        previewBox.setVisibility(LinearLayout.GONE);
    }

    private void hidePicker() {
        if (pickerBox != null) pickerBox.setVisibility(LinearLayout.GONE);
        if (previewBox != null) previewBox.setVisibility(LinearLayout.VISIBLE);
    }

    /** 用户选中一个 App */
    private void selectApp(AppEntry e) {
        targetPkg = e.pkg;
        targetLabel = e.label;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_PKG, targetPkg)
                .putString(KEY_LABEL, targetLabel)
                .apply();
        updateTitle();
        hidePicker();

        if (displayId >= 0) {
            /* 虚拟屏已就绪 → 立刻搬 */
            bgHandler.post(() -> moveAppToDisplay(targetPkg, e.component));
        } else {
            setInfo("虚拟屏准备中，稍后自动搬入 " + targetLabel);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // App 列表
    // ══════════════════════════════════════════════════════════════════

    private static class AppEntry {
        final String label;
        final String pkg;
        final String component;
        final boolean isSystem;

        AppEntry(String label, String pkg, String component, boolean isSystem) {
            this.label = label;
            this.pkg = pkg;
            this.component = component;
            this.isSystem = isSystem;
        }
    }

    /**
     * 列出所有带 LAUNCHER 入口的 App。
     *
     * 排除：自身、桌面类（投桌面等于镜像整个中控）。
     * 排序规则：用户 App 在前，系统 App 在后（系统 App 里很多是服务，
     * 点了也不会有画面，但保留是为了让用户能找到车机自带的地图等）。
     */
    private List<AppEntry> loadLaunchableApps() {
        List<AppEntry> user = new ArrayList<>();
        List<AppEntry> system = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
            String selfPkg = getPackageName();
            Set<String> homes = homePackages();

            for (ResolveInfo ri : infos) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (selfPkg.equals(pkg)) continue;
                if (homes.contains(pkg)) continue;

                String label;
                try {
                    label = ri.loadLabel(pm).toString();
                } catch (Throwable t) {
                    label = pkg;
                }
                if (label == null || label.isEmpty()) label = pkg;

                String comp = pkg + "/" + ri.activityInfo.name;
                boolean sys = (ri.activityInfo.applicationInfo.flags
                        & ApplicationInfo.FLAG_SYSTEM) != 0;

                AppEntry e = new AppEntry(label, pkg, comp, sys);
                if (sys) system.add(e); else user.add(e);
            }

            Collections.sort(user, (a, b) -> a.label.compareTo(b.label));
            Collections.sort(system, (a, b) -> a.label.compareTo(b.label));
        } catch (Throwable t) {
            DashLog.w(TAG, "枚举 App 失败: " + t);
        }
        List<AppEntry> all = new ArrayList<>(user);
        all.addAll(system);
        return all;
    }

    private Set<String> homePackages() {
        Set<String> set = new HashSet<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) set.add(ri.activityInfo.packageName);
            }
        } catch (Throwable t) {
            DashLog.w(TAG, "查询桌面包名失败: " + t);
        }
        set.add("com.flyme.auto.launcher");
        set.add("com.android.launcher3");
        set.add("com.android.systemui");
        return set;
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
                    (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            android.view.Surface surface = new android.view.Surface(st);

            vd = dm.createVirtualDisplay("DashcastMini", VD_W, VD_H, VD_DPI,
                    surface, VD_FLAGS);

            if (vd == null) {
                DashLog.w(TAG, "createVirtualDisplay 返回 null");
                setInfo("虚拟屏创建失败");
                return;
            }

            android.view.Display d = vd.getDisplay();
            displayId = d == null ? -1 : d.getDisplayId();
            DashLog.i(TAG, "MINI_VD created displayId=" + displayId
                    + " " + VD_W + "x" + VD_H + "@" + VD_DPI
                    + " flags=0x" + Integer.toHexString(VD_FLAGS));

            if (targetPkg != null) {
                final String pkg = targetPkg;
                final String comp = findComponent(pkg);
                if (bgHandler != null) bgHandler.post(() -> moveAppToDisplay(pkg, comp));
            } else {
                setInfo("请选择一个 App");
            }
        } catch (Throwable t) {
            DashLog.e(TAG, "建立虚拟屏失败", t);
            setInfo("建立虚拟屏失败: " + t.getMessage());
        }
    }

    /** 从已保存的 pkg 找回 component（重进小窗时用） */
    private String findComponent(String pkg) {
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            launcher.setPackage(pkg);
            List<ResolveInfo> infos = getPackageManager().queryIntentActivities(launcher, 0);
            if (!infos.isEmpty() && infos.get(0).activityInfo != null) {
                return pkg + "/" + infos.get(0).activityInfo.name;
            }
        } catch (Throwable t) {
            DashLog.w(TAG, "找回 component 失败: " + t);
        }
        return pkg;
    }

    /**
     * 把 App 搬进虚拟屏。
     *
     * 顺序（与 VdMirror.moveTaskIn 同一套已验证逻辑）：
     *   ① `am display move-stack <taskId> <displayId>`
     *      —— 搬已在运行的 task，不重启 App。这是**首选**。
     *   ② 失败 → `am start --display <id> -n <component>`
     *      —— App 没在跑时用。
     *   ③ 每次都回读 `am stack list` 复核。
     *
     * 为什么不能只用 ②：实测对 AVM 完全无效（系统级窗口 ty=2062），
     * 它返回 "Activity not started, its current task has been brought to the front"，
     * 窗口还在主屏。
     */
    private void moveAppToDisplay(String pkg, String component) {
        if (displayId < 0 || pkg == null) return;

        startAt = SystemClock.elapsedRealtime();
        lastTickAt = startAt;
        lastTickFrames = frameCount;

        /* ① 找 taskId（不排除任何屏 —— 它现在可能在主屏） */
        int taskId = findTaskId(pkg);
        if (taskId > 0) {
            String r = safeExec("am display move-stack " + taskId + " " + displayId);
            DashLog.i(TAG, "MINI_MOVE move-stack " + taskId + "→" + displayId + ": " + r);
            if (taskOnDisplay(pkg, displayId)) {
                DashLog.i(TAG, "MINI_MOVE 复核通过（move-stack）");
                afterMoved(pkg);
                return;
            }
            DashLog.w(TAG, "MINI_MOVE move-stack 复核失败，改试 am start --display");
        } else {
            DashLog.i(TAG, "MINI_MOVE " + pkg + " 没有运行中的 task，走 am start");
        }

        /* ② 兜底：am start --display */
        String comp = (component != null && component.contains("/"))
                ? component : pkg + "/" + "";
        String r2 = safeExec("am start --display " + displayId + " -n " + comp);
        DashLog.i(TAG, "MINI_MOVE am start: " + r2);

        if (taskOnDisplay(pkg, displayId)) {
            DashLog.i(TAG, "MINI_MOVE 复核通过（am start）");
            afterMoved(pkg);
        } else {
            DashLog.w(TAG, "MINI_MOVE 全部失败：目标 App 搬不进虚拟屏");
            setInfo(
                    "无法把「" + pkg + "」搬进小窗\n"
                    + "（系统级窗口类型的 App 不支持搬屏）");
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
        } catch (Throwable t) {
            DashLog.w(TAG, "findTaskId 失败: " + t);
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
        } catch (Throwable t) {
            DashLog.w(TAG, "taskOnDisplay 失败: " + t);
        }
        return false;
    }

    private String safeExec(String cmd) {
        try {
            return AdbShell.exec(cmd, 8000);
        } catch (Throwable t) {
            DashLog.w(TAG, "exec 失败 " + cmd + ": " + t);
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 统计
    // ══════════════════════════════════════════════════════════════════

    private Runnable statsTick;

    /*
     * **统计必须跑在主线程**（2026-11-07 修闪退）。
     *
     * 崩溃实证（logcat -b crash）：
     *   android.view.ViewRootImpl$CalledFromWrongThreadException:
     *     Only the original thread that created a view hierarchy can touch its views.
     *     at MiniWindowActivity.refreshInfo(MiniWindowActivity.java:662)
     *     at MiniWindowActivity$2.run(...)  ← bgHandler 的 HandlerThread
     *
     * 原因：上一版把 statsTick 挂在 bgHandler（HandlerThread）上，
     * 里面直接 info.setText(...)。View 只能在**创建它的那个线程**（主线程）操作，
     * 一碰就抛异常直接闪退。
     *
     * 修法：改用主线程 Handler（mainHandler）。统计只是读几个 int + 算 fps，
     * 耗时微秒级，放主线程不会有任何卡顿。
     */
    private void startStats() {
        if (statsTick != null) return;
        statsTick = new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                refreshInfo();
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.postDelayed(statsTick, 1000);
    }

    /**
     * 三态判定（2026-11-07 修：静止内容被误判成"无帧"）。
     *
     * 实测：地图搬上虚拟屏后画面完全正确，但日志是 `帧=463 6.2 fps alive=false`。
     * 因为地图静止时不重绘，onSurfaceTextureUpdated 只在有新帧时回调。
     *
     *   frameCount == 0                 → 真的没帧，App 没搬上来
     *   frameCount > 0 且在 2.5s 内无帧  → 画面静止（内容在，只是不刷新）
     *   frameCount > 0 且最近有新帧      → 在动
     */
    private void refreshInfo() {
        if (info == null) return;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - startAt;

        int deltaFrames = frameCount - lastTickFrames;
        long deltaMs = now - lastTickAt;
        float instFps = (deltaMs > 0) ? (deltaFrames * 1000f / deltaMs) : 0f;
        lastTickFrames = frameCount;
        lastTickAt = now;

        String stat = "display=" + displayId + "  帧=" + frameCount
                + "  运行=" + (elapsed / 1000) + "s";
        if (frameCount > 0 && elapsed > 0) {
            float avgFps = frameCount * 1000f / elapsed;
            stat += String.format("  均 %.1f / 瞬时 %.1f fps", avgFps, instFps);
        }

        String state;
        if (frameCount == 0) {
            state = "无帧（App 没搬上来？）";
        } else if ((now - lastFrameAt) >= 2500) {
            state = "画面静止（已有 " + frameCount + " 帧，当前不刷新）";
        } else {
            state = String.format("画面在动 ✓  %.1f fps", instFps);
        }

        setInfo(stat + "\n" + state);
        DashLog.i(TAG, "MINI_STAT " + stat.replace('\n', ' ') + " | " + state);
    }

    // ══════════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onDestroy() {
        destroyed = true;

        /*
         * **把搬走的 App 送回主屏**（不留残留在虚拟屏上）。
         *
         * 虚拟屏一 release，系统会把上面的 task 自动迁回主屏（实测有效），
         * 但显式再发一次 move-stack 更稳妥 —— 万一系统没自动迁，用户
         * 那个 App 就"消失"了。
         */
        if (targetPkg != null) {
            try {
                int tid = findTaskId(targetPkg);
                if (tid > 0) {
                    safeExec("am display move-stack " + tid + " 0");
                    DashLog.i(TAG, "MINI_RESTORE " + targetPkg + " task " + tid + " → display 0");
                }
            } catch (Throwable t) {
                DashLog.w(TAG, "还原 App 到主屏失败: " + t);
            }
        }

        releaseVirtualDisplay();

        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
            bgHandler = null;
        }
        super.onDestroy();
        DashLog.i(TAG, "MINI_VD destroyed displayId=" + displayId + " frames=" + frameCount);
    }

    private void releaseVirtualDisplay() {
        VirtualDisplay v = vd;
        vd = null;
        if (v != null) {
            try {
                v.release();
                DashLog.i(TAG, "releaseVirtualDisplay 完成");
            } catch (Throwable t) {
                DashLog.w(TAG, "release 虚拟屏失败: " + t);
            }
        }
        displayId = -1;
    }

    /**
     * **安全地更新状态文字**（2026-11-07）。
     *
     * 所有 setText 都必须经过这里 —— 因为调用方可能在后台线程
     * （moveAppToDisplay 跑在 bgHandler 的 HandlerThread）。
     * 直接 setText 会抛 CalledFromWrongThreadException 闪退，
     * 实测崩溃栈：
     *   MiniWindowActivity.refreshInfo(...)
     *   MiniWindowActivity$2.run(...)
     *   android.os.HandlerThread.run(HandlerThread.java:67)
     */
    private void setInfo(String text) {
        if (info == null) return;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            info.setText(text);
        } else {
            final String t = text;
            mainHandler.post(() -> {
                if (info != null && !destroyed) info.setText(t);
            });
        }
    }
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}