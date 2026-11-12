package com.hermes.dashcast;

import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * WebView 性能自检 v7 —— 虚拟屏（Presentation）方案验证
 *
 * ════════════════════════════════════════════════════════════════
 * 证据链（v6 实测，2026-11-10 23:25）
 * ════════════════════════════════════════════════════════════════
 *
 *   A 全尺寸 1920x538 canvas:  66.7ms/帧
 *   B 半尺寸  960x269 canvas:  66.7ms/帧   ← 比率 1.00
 *
 *   帧耗时与绘制内容面积完全无关 → 不是光栅化，是**固定开销**。
 *   66.7ms = 恰好 4 个 vsync → 渲染管线按 vsync 粒度在堵车。
 *   gfxinfo：GPU 50th=14ms，Slow issue draw commands 100%。
 *
 * 关键疑点：Activity 窗口是 2560×1600（主屏全屏），
 * 而仪表只要 1920×538 —— 窗口面积是内容的 4 倍。
 * 若固定开销 ∝ 窗口面积，VD 上渲染应降到 ~17ms → 30fps 达标。
 *
 * ════════════════════════════════════════════════════════════════
 * 本测试同时验证最终架构
 * ════════════════════════════════════════════════════════════════
 *
 *   VirtualDisplay(1920×538) + ImageReader
 *        ↑ Presentation.show() —— WebView 就渲在这块 VD 上
 *   ImageReader 出帧 = dcapd2 要抓的内容（顺带验证采集路径）
 *
 *   对比两组数据：
 *     主屏（2560×1600）里的 WebView        ← 上轮 14.8fps
 *     VD（1920×538）里的 Presentation WebView ← 本轮
 *
 * 结果看 logcat -s DashcastPerf
 */
public class WebViewPerfActivity extends android.app.Activity {

    private static final String TAG = "DashcastPerf";
    private static final int VD_W = 1920, VD_H = 538;

    private VirtualDisplay vd;
    private ImageReader reader;
    private Presentation pres;
    private WebView presWebView;
    private long imgCount = 0;
    private long imgT0 = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i(TAG, "isWebViewAvailable = " + MapDashView.isWebViewAvailable(this));
        Log.i(TAG, "webViewVersion     = " + MapDashView.webViewVersion());
        if (!MapDashView.isWebViewAvailable(this)) {
            Log.e(TAG, "WebView 不可用，测试终止");
            finish();
            return;
        }

        /* ── ① 建 VD + ImageReader（最终架构的采集端） ───────────── */
        reader = ImageReader.newInstance(VD_W, VD_H,
                android.graphics.PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img != null) {
                        imgCount++;
                        if (imgT0 == 0) imgT0 = System.currentTimeMillis();
                        img.close();
                        img = null;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) try { img.close(); } catch (Throwable ignored) {}
                }
            }
        }, ui);

        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        /* 只用 PRESENTATION 标志（私有屏）—— PUBLIC 需要
         * CAPTURE_VIDEO_OUTPUT（签名权限，普通安装拿不到，实测 23:30 崩溃）。
         * Presentation 本来就要求私有屏，语义也更对。 */
        vd = dm.createVirtualDisplay("DashcastPerfVD", VD_W, VD_H, 160,
                reader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        int vdId = vd.getDisplay().getDisplayId();
        Log.i(TAG, "VD 建好 displayId=" + vdId + " " + VD_W + "x" + VD_H);

        /* ── ② Presentation 挂到 VD，WebView 加载 perf-test ──────── */
        pres = new Presentation(this, vd.getDisplay());
        pres.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        presWebView = new WebView(this);
        presWebView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        presWebView.setBackgroundColor(0xFF000000);
        /* 注意：不给 WebView 设 layer type（Android 文档明确不建议） */

        WebSettings ws = presWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setUseWideViewPort(false);
        ws.setLoadWithOverviewMode(false);
        presWebView.setVerticalScrollBarEnabled(false);
        presWebView.setHorizontalScrollBarEnabled(false);
        presWebView.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);

        presWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "[JS] " + m.message());
                return true;
            }
        });
        presWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "Presentation 页面加载完成: " + url);
                /* 8 秒后报 ImageReader 采集速率 */
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        long dt = System.currentTimeMillis() - imgT0;
                        if (dt > 0 && imgCount > 0) {
                            double fps = imgCount * 1000.0 / dt;
                            Log.i(TAG, "[采集] ImageReader 出帧 " + imgCount
                                    + " 张 / " + dt + "ms = "
                                    + String.format("%.1f", fps) + " fps");
                        } else {
                            Log.i(TAG, "[采集] ImageReader 无帧输出");
                        }
                    }
                }, 8000);
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(presWebView);
        pres.setContentView(root);
        pres.show();
        Log.i(TAG, "Presentation 已显示在 VD 上");

        presWebView.loadUrl("file:///android_asset/perf-test.html");
    }

    @Override
    protected void onDestroy() {
        if (pres != null) {
            try { pres.dismiss(); } catch (Throwable ignored) {}
            pres = null;
        }
        if (presWebView != null) {
            try {
                presWebView.loadUrl("about:blank");
                presWebView.removeAllViews();
                presWebView.destroy();
            } catch (Throwable ignored) {}
            presWebView = null;
        }
        if (vd != null) { try { vd.release(); } catch (Throwable ignored) {} vd = null; }
        if (reader != null) { try { reader.close(); } catch (Throwable ignored) {} reader = null; }
        super.onDestroy();
    }
}
