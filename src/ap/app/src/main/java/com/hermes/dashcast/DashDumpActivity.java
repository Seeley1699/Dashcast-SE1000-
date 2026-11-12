package com.hermes.dashcast;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import com.hud.dash.DashView;
import com.hud.dash.HudData;

import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * DashPerfActivity 的扩展：跑通后把 VD 内容 dump 成 PNG 存到 /sdcard，
 * 用 adb pull 拉回来做视觉验证（screencap 不支持虚拟屏，实测 0 字节）。
 *
 * dump 一张即退，避免持续占用 ImageReader。
 */
public class DashDumpActivity extends Activity {

    private static final String TAG = "DashPerf";
    private static final int VD_W = 1920, VD_H = 538;

    private VirtualDisplay vd;
    private ImageReader reader;
    private Presentation pres;
    private DashView dashView;
    private boolean dumped = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        reader = ImageReader.newInstance(VD_W, VD_H, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                if (dumped) return;
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    /* 等第 30 张再 dump（确保 setData 的演示数据已画上） */
                    dumpedCount++;
                    /* 等第 30 张：确保画面稳定（也避开合成中途的撕裂帧） */
                    if (dumpedCount < 30) { img.close(); img = null; return; }
                    /* 连续两帧一致才 dump（防撕裂帧：合成中途图像下半是黑的） */
                    if (lastHash == hashFrame(img)) {
                        stableCount++;
                    } else {
                        stableCount = 0;
                        lastHash = hashFrame(img);
                    }
                    if (stableCount < 2 && dumpedCount < 90) {
                        img.close(); img = null; return;
                    }
                    dumped = true;

                    int w = img.getWidth(), h = img.getHeight();
                    Image.Plane p = img.getPlanes()[0];
                    ByteBuffer buf = p.getBuffer();
                    int rowStride = p.getRowStride(), pixStride = p.getPixelStride();
                    Log.i(TAG, "dump: " + w + "x" + h
                            + " rowStride=" + rowStride + " pixStride=" + pixStride);
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    /* RGBA_8888 字节序 = R,G,B,A → ARGB int
                     * （v1 版本把 R/B 写反，整幅青绿变黄橄榄） */
                    int[] pixels = new int[w * h];
                    for (int y = 0; y < h; y++) {
                        int rowOff = y * rowStride;
                        for (int x = 0; x < w; x++) {
                            int i = rowOff + x * pixStride;
                            int rr = buf.get(i) & 0xFF;
                            int gg = buf.get(i + 1) & 0xFF;
                            int bb = buf.get(i + 2) & 0xFF;
                            int aa = buf.get(i + 3) & 0xFF;
                            pixels[y * w + x] = (aa << 24) | (rr << 16) | (gg << 8) | bb;
                        }
                    }
                    bmp.setPixels(pixels, 0, w, 0, 0, w, h);
                    /* u11 用户空间对 /sdcard 无写权限（EPERM 实测），
                     * 存 App 私有外部目录，adb pull 走 run-as 可达 */
                    /* u11 空间 adb run-as 够不到（实测 unknown package），
                     * 把 PNG 转 base64 分段打进 logcat，PC 侧拼回来 */
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 80, bos);
                    String b64 = android.util.Base64.encodeToString(
                            bos.toByteArray(), android.util.Base64.NO_WRAP);
                    Log.i(TAG, "PNG_B64_BEGIN " + w + "x" + h + " len=" + b64.length());
                    for (int off = 0; off < b64.length(); off += 3000) {
                        Log.i(TAG, "PNG_B64 " + b64.substring(off, Math.min(off + 3000, b64.length())));
                    }
                    Log.i(TAG, "PNG_B64_END");
                    ui.post(new Runnable() {
                        @Override public void run() { finish(); }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "dump 失败: " + t);
                    ui.post(new Runnable() {
                        @Override public void run() { finish(); }
                    });
                } finally {
                    if (img != null) try { img.close(); } catch (Throwable ignored) {}
                }
            }
        }, ui);

        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        vd = dm.createVirtualDisplay("DashDumpVD", VD_W, VD_H, 160,
                reader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);

        pres = new Presentation(this, vd.getDisplay());
        pres.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        dashView = new DashView(this);
        dashView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        /* 静态演示数据（对照 dashboard.js 浏览器版） */
        HudData d = dashView.data;
        d.speed = 88f; d.noa = true; d.noaSpeed = 120;
        d.evRange = 130f; d.evCap = 180f;
        d.fuelRange = 1370f; d.fuelCap = 1560f;
        d.gear = "D"; d.power = 142f;
        d.navActive = true; d.navEnd = "exited";
        d.mediaPlaying = true; d.mediaTitle = "夜航西飞"; d.mediaArtist = "陈明";
        dashView.autoSpeed = false;
        dashView.autoPower = false;
        dashView.invalidate();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(dashView);
        pres.setContentView(root);
        pres.show();

        /* 10 秒兜底退出 */
        ui.postDelayed(new Runnable() {
            @Override public void run() { finish(); }
        }, 10000);
    }

    private int dumpedCount = 0;
    private int stableCount = 0;
    private long lastHash = -1;

    /** 画面粗哈希：采 4 行像素比对，检测撕裂帧 */
    private long hashFrame(Image img) {
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer b = p.getBuffer();
            int rs = p.getRowStride(), ps = p.getPixelStride();
            long hsh = 17;
            int[] rows = {img.getHeight() / 4, img.getHeight() / 2,
                          img.getHeight() * 3 / 4, img.getHeight() - 2};
            for (int y : rows) {
                int off = y * rs;
                for (int x = 0; x < img.getWidth(); x += 64) {
                    hsh = hsh * 31 + b.get(off + x * ps);
                    hsh = hsh * 31 + b.get(off + x * ps + 1);
                }
            }
            return hsh;
        } catch (Throwable t) { return -2; }
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
