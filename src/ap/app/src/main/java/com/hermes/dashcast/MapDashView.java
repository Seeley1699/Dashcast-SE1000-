package com.hermes.dashcast;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.io.IOException;

/**
 * 仪表盘视图：WebView 渲染 UI + SurfaceView 承载真地图，两层叠加。
 *
 * ══════════════════════════════════════════════════════════════════
 * 为什么这么搭
 * ══════════════════════════════════════════════════════════════════
 *
 * 一、UI 走 WebView
 *
 *   dashboard.js 本来就是浏览器代码（2170 行 Canvas 2D）。
 *   放进 Frida 用 JS→Java 适配器跑，实测单帧 558ms —— 3000 余次
 *   跨语言调用的代价（每次 0.1~0.2ms）。放进 WebView 走的是原生
 *   Skia + 硬件加速，同样代码 16ms，差 30 倍。
 *
 * 二、地图走 SurfaceView（不塞进 WebView）
 *
 *   WebView 的 JS 拿不到 Android Bitmap。想塞进去只能转 data URL
 *   或像素数组 —— 864×382 每帧 1.3 MB，实测 30~50ms，白折腾。
 *
 *   所以干脆不塞：地图用 SurfaceView 垫在 WebView 下面，
 *   dashboard.js 打开 mapExternal 把地图区留成透明洞，
 *   由 SurfaceFlinger 硬件合成。零拷贝、零转换。
 *
 * 三、地图从哪来
 *
 *   VirtualDisplay（尺寸 = 地图区 864×382）的 surface 直接挂到
 *   这个 SurfaceView 上，然后把地图 App 的 task move-stack 搬进
 *   这块 VD。地图渲染直接进 SurfaceView，中间没有任何拷贝。
 *
 *   ┌─────────────────────────────┐
 *   │  WebView（透明背景，UI 层）  │  ← dashboard.js
 *   ├─────────────────────────────┤
 *   │  SurfaceView（地图区）       │  ← map 进程经 VD 直连
 *   └─────────────────────────────┘
 *            ↓ SurfaceFlinger 硬件合成
 *        整屏 → dcapd2 → CP → 仪表盘
 *
 * ══════════════════════════════════════════════════════════════════
 * 用法
 * ══════════════════════════════════════════════════════════════════
 *
 *   MapDashView v = new MapDashView(context);
 *   frameLayout.addView(v, MATCH_PARENT, MATCH_PARENT);
 *   v.setMapPackage("com.geely.map");     // 可选，默认就是它
 *   v.loadDashboard();                    // 加载 dashboard.js
 *   v.startMapBridge();                   // 起 VD 并搬地图进来
 *
 *   // 结束时
 *   v.release();
 *
 * ══════════════════════════════════════════════════════════════════
 * 已知限制
 * ══════════════════════════════════════════════════════════════════
 *
 *   · move-stack 需要 shell 权限（走 AdbShell.exec）
 *   · 地图被搬走后，主屏上的地图 App 会消失 —— 这是预期行为
 *   · WebView 在部分车机 ROM 上可能被裁剪，需实测
 */
public class MapDashView extends FrameLayout {

    private static final String TAG = "DashcastMapDash";

    /* ── 布局常量：必须和 dashboard.js 的 L.map 完全一致 ──────────
     *
     * dashboard.js 顶部：
     *   map: { x: 544, y: 64, w: 864, h: 382 }
     * 圆角 20（drawMap 里 rr(..., 20)）
     */
    private static final int MAP_X = 544;
    private static final int MAP_Y = 64;
    private static final int MAP_W = 864;
    private static final int MAP_H = 382;
    private static final float MAP_RADIUS_DP = 20f;

    private static final String DEFAULT_MAP_PKG = "com.geely.map";

    /* ── 视图 ─────────────────────────────────────────────────── */
    private SurfaceView mapSurface;
    private WebView webView;

    /* ── 状态 ─────────────────────────────────────────────────── */
    private VirtualDisplay mapVd;
    private Surface mapSurfaceRef;
    private String mapPackage = DEFAULT_MAP_PKG;
    private String dashboardAsset = "file:///android_asset/dashboard.html";
    private boolean mapStarted = false;
    private int density;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /* ══════════════════════════════════════════════════════════
     * WebView 可用性检测
     * ══════════════════════════════════════════════════════════
     *
     * 车机上实测（2026-11-10，SE1000 / Android 11）：
     *
     *   package:com.android.webview
     *   Current WebView package: (com.android.webview, 83.0.4103.120)
     *   targetSdkVersion: 30
     *   Multiprocess enabled: true      ← 渲染在独立进程，主进程不卡
     *
     * 也就是说 WebView 是**可用**的，但版本停在 Chromium 83（2020-05），
     * 且是 AOSP 版（无 Play 更新、无 V8 优化）。
     *
     * 这份 dashboard.js 对 Chrome 83 的兼容性核查结果：
     *   ✅ 渐变 / shadowBlur / Path2D        Chrome 1~36
     *   ✅ ResizeObserver                    Chrome 64
     *   ✅ 可选链 ?. / 空值合并 ??            Chrome 80
     *   ✅ class 字段 / 私有字段              Chrome 74
     *   ❌ ctx.letterSpacing                  Chrome 99  ← 唯一不兼容
     *
     * letterSpacing 在 dashboard.js 里由 setLS() 包着：
     *   function setLS(ctx, px) { if ('letterSpacing' in ctx) ... }
     * Chrome 83 上该属性不存在，判断为 false，静默跳过 —— 不报错、不崩，
     * 只是字距失效（数字和中文标题会挤一点）。可接受。
     *
     * 但别的 ROM 可能整个裁掉 WebView（省内存）。所以这里必须检测，
     * 不能假设一定有 —— 没有就得降级，否则白屏。
     */

    /** WebView 是否真的能用 */
    public static boolean isWebViewAvailable(Context ctx) {
        try {
            /* ① 类存在 */
            Class.forName("android.webkit.WebView");

            /* ② Provider 已安装（Android 5+） */
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                if (WebView.getCurrentWebViewPackage() == null) {
                    Log.w(TAG, "WebView Provider 未安装");
                    return false;
                }
            }

            /* ③ 能真的 new 出来（有些 ROM 类在但初始化崩） */
            WebView probe = new WebView(ctx.getApplicationContext());
            probe.destroy();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "WebView 不可用: " + t);
            return false;
        }
    }

    /**
     * 返回 WebView 版本号，取不到返回 "unknown"。
     * 用于日志排查（Chrome 83 和 Chrome 120 的表现差很多）。
     */
    public static String webViewVersion() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) {
                    return pi.packageName + " " + pi.versionName;
                }
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }

    public MapDashView(Context context) {
        super(context);
        density = (int) (context.getResources().getDisplayMetrics().density * MAP_RADIUS_DP);
        build();
    }

    /* ══════════════════════════════════════════════════════════
     * 视图搭建
     * ══════════════════════════════════════════════════════════ */

    private void build() {
        setBackgroundColor(Color.BLACK);

        /* WebView 不可用时直接摆一块提示，不要白屏。
         *
         * 上层逻辑（MainActivity）应该先调 isWebViewAvailable() 决定
         * 走 WebView 方案还是回落 Frida 方案；但万一漏判，这里兜底，
         * 至少让人看出「不是崩了，是没 WebView」。 */
        if (!isWebViewAvailable(getContext())) {
            Log.e(TAG, "WebView 不可用，本视图无法渲染 UI");
            android.widget.TextView tv = new android.widget.TextView(getContext());
            tv.setText("WebView 不可用\n请改用 Frida 渲染方案");
            tv.setTextColor(Color.RED);
            tv.setTextSize(24);
            tv.setGravity(Gravity.CENTER);
            addView(tv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        Log.i(TAG, "WebView 版本: " + webViewVersion());

        /* ① 底层：地图 SurfaceView */
        mapSurface = new SurfaceView(getContext());
        FrameLayout.LayoutParams mlp =
                new FrameLayout.LayoutParams(MAP_W, MAP_H, Gravity.TOP | Gravity.LEFT);
        mlp.leftMargin = MAP_X;
        mlp.topMargin = MAP_Y;
        mapSurface.setLayoutParams(mlp);

        /* 圆角：和 dashboard.js 的 rr(..., 20) 对齐。
         * clipToOutline 只在 API 21+ 有效，我们是 30，没问题。 */
        mapSurface.setClipToOutline(true);
        mapSurface.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), density);
            }
        });

        mapSurface.setZOrderMediaOverlay(true);   /* 让它在 WebView 之下仍能正常合成 */
        addView(mapSurface);

        /* ② 上层：WebView（透明背景） */
        webView = new WebView(getContext());
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        /* ★ 透明是关键：不透明的话地图永远看不到 */
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        /* 车机屏宽固定，禁掉缩放省事 */
        ws.setUseWideViewPort(false);
        ws.setLoadWithOverviewMode(false);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        /* 关掉滚动条和长按选择，避免误触 */
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setLongClickable(false);
        webView.setHapticFeedbackEnabled(false);

        addView(webView);

        /* ③ 地图 Surface 就绪后再建 VD —— 必须等 surfaceCreated，
         *    否则 holder.getSurface() 是无效的 */
        mapSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.i(TAG, "地图 Surface 已创建 " + MAP_W + "x" + MAP_H);
                mapSurfaceRef = holder.getSurface();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                mapSurfaceRef = holder.getSurface();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i(TAG, "地图 Surface 已销毁");
                mapSurfaceRef = null;
            }
        });
    }

    /* ══════════════════════════════════════════════════════════
     * 对外接口
     * ══════════════════════════════════════════════════════════ */

    /** 设置要投到仪表盘的地图包名，默认 com.geely.map */
    public void setMapPackage(String pkg) {
        if (pkg != null && pkg.length() > 0) mapPackage = pkg;
    }

    /** 自定义 dashboard 页面地址，默认 file:///android_asset/dashboard.html */
    public void setDashboardUrl(String url) {
        if (url != null && url.length() > 0) dashboardAsset = url;
    }

    public WebView webView() {
        return webView;
    }

    /** 加载 dashboard.js 宿主页 */
    public void loadDashboard() {
        if (webView == null) {
            Log.w(TAG, "webView 为 null（WebView 不可用？），跳过加载");
            return;
        }
        webView.loadUrl(dashboardAsset);
        Log.i(TAG, "加载 dashboard: " + dashboardAsset);
    }

    /**
     * 起地图桥接：建 VD → 把 map 的 task 搬进来。
     *
     * 必须在地图 Surface 创建之后调用（surfaceCreated 回调里，或之后）。
     * 重复调用无害，第二次起会直接返回。
     */
    public void startMapBridge() {
        if (mapStarted) {
            Log.i(TAG, "地图桥接已在运行，忽略重复调用");
            return;
        }
        /* WebView 不可用时 build() 提前返回，mapSurface 是 null，
         * 这条链路整条都不该跑 */
        if (mapSurface == null) {
            Log.w(TAG, "mapSurface 为 null（WebView 不可用？），跳过地图桥接");
            return;
        }
        if (mapSurfaceRef == null || !mapSurfaceRef.isValid()) {
            Log.w(TAG, "地图 Surface 还没就绪，300ms 后重试");
            ui.postDelayed(new Runnable() {
                @Override public void run() { startMapBridge(); }
            }, 300);
            return;
        }
        mapStarted = true;

        /* ① 建 VirtualDisplay，surface 直接指向 SurfaceView */
        try {
            DisplayManager dm =
                    (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
            /* flags：PUBLIC | PRESENTATION
             *
             * PRESENTATION 是 Presentation 类能工作的前提；
             * 这里不用 Presentation，但保持和 Frida 那边一致，
             * 免得某些 ROM 上建出来是空白。 */
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                      | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;

            mapVd = dm.createVirtualDisplay(
                    "DashcastMapSurface", MAP_W, MAP_H, 160, mapSurfaceRef, flags);

            if (mapVd == null) {
                Log.e(TAG, "createVirtualDisplay 返回 null");
                mapStarted = false;
                return;
            }
            int vdId = mapVd.getDisplay().getDisplayId();
            Log.i(TAG, "地图 VirtualDisplay 建好 displayId=" + vdId
                    + " " + MAP_W + "x" + MAP_H);

            /* ② 把地图 task 搬进这块 VD */
            moveMapTaskTo(vdId);

        } catch (Throwable t) {
            Log.e(TAG, "起地图桥接失败: " + t);
            mapStarted = false;
        }
    }

    /**
     * 把地图 App 的 task 搬到指定 display。
     *
     * 两步：
     *   ① am stack list / dumpsys 找 taskId
     *   ② am display move-stack <taskId> <displayId>
     *
     * 实测教训（来自 VdMirror）：
     *   move-stack 比 `am start --display` 好 —— 后者会重建 Activity，
     *   状态全丢；move-stack 只是把已有 task 挪过去，地图不重新加载。
     *
     *   但 move-stack 需要 shell 身份。普通 App 调会 SecurityException，
     *   所以走 AdbShell.exec（App 内已有 adb root 通道）。
     */
    private void moveMapTaskTo(final int displayId) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    /* 先把地图拉起来（如果没有在跑） */
                    String pid = AdbShell.exec("pidof " + mapPackage + " 2>/dev/null", 3000);
                    if (pid == null || pid.trim().length() == 0) {
                        Log.i(TAG, "地图未运行，先启动");
                        AdbShell.exec("monkey -p " + mapPackage
                                + " -c android.intent.category.LAUNCHER 1", 8000);
                        Thread.sleep(2500);
                    }

                    /* 找 taskId。dumpsys 输出里形如：
                     *   A=1110128:com.geely.map U=11 StackId=1102596
                     * 或者
                     *   * Task{...#1102596 ... A=...:com.geely.map ...} */
                    String dump = AdbShell.exec(
                            "dumpsys activity activities 2>/dev/null"
                            + " | grep '" + mapPackage + "'", 6000);
                    String taskId = pickTaskId(dump, mapPackage);
                    if (taskId == null) {
                        Log.w(TAG, "没找到 " + mapPackage + " 的 taskId，退回 am start --display");
                        AdbShell.exec("am start --display " + displayId
                                + " -n " + mapPackage + "/.MainActivity", 6000);
                        return;
                    }

                    Log.i(TAG, "找到 taskId=" + taskId + "，搬到 display " + displayId);
                    String r = AdbShell.exec(
                            "am display move-stack " + taskId + " " + displayId, 6000);

                    if (r != null && (r.contains("Exception") || r.contains("Error"))) {
                        Log.w(TAG, "move-stack 失败: " + r.trim());
                    } else {
                        Log.i(TAG, "move-stack 完成");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "move-stack 异常: " + e);
                } catch (InterruptedException e) {
                    Log.e(TAG, "move-stack 被中断");
                }
            }
        }, "map-bridge").start();
    }

    /**
     * 从 dumpsys 输出里挑出属于指定包的 taskId。
     *
     * 输出格式不稳定（不同 Android 版本差异大），所以这里用宽松匹配：
     *   找含包名的那一行，在同一行或邻近几行里取 #数字。
     */
    static String pickTaskId(String dump, String pkg) {
        if (dump == null || dump.length() == 0) return null;
        String[] lines = dump.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.indexOf(pkg) < 0) continue;

            /* 本行里的 #数字 */
            String id = firstHashNumber(ln);
            if (id != null) return id;

            /* 往后看 3 行 */
            for (int k = 1; k <= 3 && i + k < lines.length; k++) {
                id = firstHashNumber(lines[i + k]);
                if (id != null) return id;
            }
        }
        return null;
    }

    /** 取 "#1102596" 里的 1102596；没有就返回 null */
    private static String firstHashNumber(String s) {
        int h = s.indexOf('#');
        if (h < 0) return null;
        int i = h + 1, n = s.length();
        StringBuilder sb = new StringBuilder();
        while (i < n && Character.isDigit(s.charAt(i))) {
            sb.append(s.charAt(i));
            i++;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /* ══════════════════════════════════════════════════════════
     * 数据注入
     * ══════════════════════════════════════════════════════════ */

    /**
     * 把数据推给 dashboard.js。
     *
     * obj 会被序列化成 JSON，在 webView 里执行：
     *   hud.setData({...})
     *
     * 必须在 UI 线程调。
     */
    public void pushData(org.json.JSONObject obj) {
        if (obj == null || webView == null) return;
        final String js = "if(window.hud&&window.hud.setData){window.hud.setData("
                + obj.toString() + ");}";
        ui.post(new Runnable() {
            @Override public void run() {
                if (webView != null) webView.evaluateJavascript(js, null);
            }
        });
    }

    /** 直接执行一段 JS（调试用） */
    public void eval(final String js) {
        if (webView == null) return;
        ui.post(new Runnable() {
            @Override public void run() {
                if (webView != null) webView.evaluateJavascript(js, null);
            }
        });
    }

    /* ══════════════════════════════════════════════════════════
     * 释放
     * ══════════════════════════════════════════════════════════ */

    /** 释放 VD 和 WebView。Activity 销毁时必须调，否则 VD 泄漏。 */
    public void release() {
        mapStarted = false;
        if (mapVd != null) {
            try { mapVd.release(); } catch (Throwable ignored) {}
            mapVd = null;
            Log.i(TAG, "地图 VirtualDisplay 已释放");
        }
        if (webView != null) {
            try {
                webView.loadUrl("about:blank");
                webView.removeAllViews();
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
    }
}
