package com.hermes.dashcast;

import android.app.Activity;
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
import android.widget.FrameLayout;

import com.hud.dash.DashView;
import com.hud.dash.HudData;

/**
 * Java 渲染性能验证：DashView（Choreographer + onDraw，零 Chromium）
 * 直接渲到 VirtualDisplay(1920×538)，ImageReader 采集出帧。
 *
 * 这就是最终架构的骨架：
 *   DashView ──onDraw──▶ VD surface ──▶ ImageReader ──▶ dcapd2 ──▶ CP ──▶ 仪表
 *
 * 车上真跑时把 ImageReader 换成直连 dcapd2 的输入面即可；
 * 本 Activity 用 ImageReader 计帧，同时验证：
 *   ① Java 渲染在 VD 上的真实帧率（rAF 15fps 的对照组）
 *   ② 采集出口速率
 *   ③ 数据接口（setData(HudData) 演示动画）
 *
 * 结果：adb logcat -s DashPerf
 */
public class DashPerfActivity extends Activity {

    private static final String TAG = "DashPerf";
    private static final int VD_W = 1920, VD_H = 538;

    private VirtualDisplay vd;
    private ImageReader reader;
    private Presentation pres;
    private DashView dashView;

    private long imgCount = 0, imgT0 = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i(TAG, "DashRenderer BUILD_ID = " + DashView.BUILD_ID);

        /* ── ① VD + ImageReader（私有屏，无需签名权限） ──────────── */
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
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) try { img.close(); } catch (Throwable ignored) {}
                }
            }
        }, ui);

        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        vd = dm.createVirtualDisplay("DashcastJavaVD", VD_W, VD_H, 160,
                reader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        Log.i(TAG, "VD displayId=" + vd.getDisplay().getDisplayId()
                + " " + VD_W + "x" + VD_H);

        /* ── ② Presentation 里放 DashView ─────────────────────────── */
        pres = new Presentation(this, vd.getDisplay());
        pres.getWindow().requestFeature(Window.FEATURE_NO_TITLE);

        dashView = new DashView(this);
        dashView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        /* 演示动画开着（autoSpeed/autoPower 默认 true），先看帧率上限 */
        dashView.data.mapExternal = false;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(dashView);
        pres.setContentView(root);
        pres.show();
        Log.i(TAG, "Presentation(DashView) 已显示在 VD 上");

        /* ── ③ 定时报告：渲染帧率 + 采集帧率 ─────────────────────── */
        /* DashView 没暴露帧计数，借 Choreographer 数 invalidate 的实际出帧 */
        final long[] frames = {0};
        final long[] t0 = {0};
        dashView.post(new Runnable() {   /* 等 view 挂上 */
            @Override public void run() {
                android.view.Choreographer.getInstance().postFrameCallback(
                        new android.view.Choreographer.FrameCallback() {
                    @Override
                    public void doFrame(long nanos) {
                        if (t0[0] == 0) t0[0] = nanos;
                        frames[0]++;
                        android.view.Choreographer.getInstance().postFrameCallback(this);
                    }
                });
            }
        });

        ui.postDelayed(new Runnable() {
            @Override public void run() {
                /* 渲染侧：Choreographer 回调数 */
                long dtMs = 5000;
                Log.i(TAG, "[渲染] Choreographer 回调 " + frames[0]
                        + " 次 / " + dtMs + "ms = "
                        + String.format("%.1f", frames[0] * 1000.0 / dtMs) + " fps");
                frames[0] = 0; t0[0] = 0;

                /* 采集侧：ImageReader 出帧数 */
                long now = System.currentTimeMillis();
                if (imgT0 > 0) {
                    Log.i(TAG, "[采集] ImageReader " + imgCount
                            + " 张 / " + (now - imgT0) + "ms = "
                            + String.format("%.1f", imgCount * 1000.0 / (now - imgT0)) + " fps");
                } else {
                    Log.i(TAG, "[采集] ImageReader 无帧输出");
                }
                imgCount = 0; imgT0 = now;
                ui.postDelayed(this, dtMs);
            }
        }, 6000);

        /* ── ④ 演示 setData 接口（5 秒后喂一组静态数据） ─────────── */
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                HudData d = new HudData();
                d.speed = 88f; d.noa = true; d.noaSpeed = 120;
                d.evRange = 130f; d.evCap = 180f;
                d.fuelRange = 1370f; d.fuelCap = 1560f;
                d.gear = "D"; d.power = 142f;
                d.navActive = true;
                d.mediaPlaying = true;
                /* mapExternal 演示：留洞给外部地图 */
                // d.mapExternal = true;
                dashView.setData(d);
                dashView.autoSpeed = false;
                dashView.autoPower = false;
                Log.i(TAG, "setData 演示已注入（speed=88, mapExternal=false）");
            }
        }, 12000);
    }

    @Override
    protected void onDestroy() {
        if (pres != null) { try { pres.dismiss(); } catch (Throwable ignored) {} pres = null; }
        if (dashView != null) { try { dashView.stop(); } catch (Throwable ignored) {} }
        if (vd != null) { try { vd.release(); } catch (Throwable ignored) {} vd = null; }
        if (reader != null) { try { reader.close(); } catch (Throwable ignored) {} reader = null; }
        super.onDestroy();
    }
}
