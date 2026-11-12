package com.hermes.dashcast;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.hud.dash.HudLayout;

/**
 * DashViewHook —— 在 DashViewEx.onDraw 里把真地图画进 MAP 矩形。
 *
 * 设计原则：**不碰 com.hud.dash 包的任何一行**（那是完整验证过的渲染器，
 * 且 DashRenderer 是包私有类，跨包反射不可靠）。
 *
 * 绘制顺序（在 DashViewEx.onDraw 中调用）：
 *   1. super.onDraw → renderer 全量（含示意路网）
 *   2. 本方法：真地图 drawBitmap 盖住 MAP 矩形（圆角裁剪）
 *   3. 浮层（路况 chip/指北/比例尺/车道条）由本方法自带实现——
 *      与 dashboard.js 的 drawMapOverlay 同源，绘制量极小（<0.5ms）
 *
 * 引擎未起或暂无地图帧时不调用本方法，自然回落示意路网。
 */
final class DashViewHook {

    private static final String TAG = "JavaDash";
    private static Paint paint;
    private static Paint txt;
    private static Paint line;
    private static Paint fill;

    private DashViewHook() {}

    /** 把引擎提供的地图帧画进 MAP 洞 + 重画浮层 */
    static void drawMapLayer(Canvas canvas, Bitmap map, float t) {
        if (map == null || map.isRecycled()) return;
        try {
            RectF m = HudLayout.MAP;
            float radius = 20f;

            if (paint == null) {
                paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                txt  = new Paint(Paint.ANTI_ALIAS_FLAG);
                line = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            }

            /* ── ① 真地图：圆角裁剪 + 填满 MAP ── */
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(m.left, m.top, m.right, m.bottom,
                    radius, radius, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawBitmap(map, null, m, paint);
            canvas.restore();

            /* ── ② 浮层：同 dashboard.js drawMapOverlay（简化 chip 版） ── */
            chip(canvas, m.left + 14f, m.top + 14f, 122f, "实时路况 · 畅通", t);
            chip(canvas, m.right - 14f - 118f, m.top + 14f, 118f, "N · 北偏东 12°", -1f);

            /* 比例尺 */
            float sw = 62f;
            float sx = m.right - 14f - sw;
            float sy = m.bottom - 14f - 20f;
            RectF rf = new RectF(sx, sy, sx + sw, sy + 20f);
            android.graphics.Path p = new android.graphics.Path();
            p.addRoundRect(rf, 10f, 10f, android.graphics.Path.Direction.CW);
            fill.setColor(0xC7080E11);
            canvas.drawPath(p, fill);
            line.setStrokeWidth(1f);
            line.setColor(0xFF1B2A30);
            canvas.drawPath(p, line);
            txt.setColor(0xFF7C9095);
            txt.setTextSize(10.5f);
            txt.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("200 m", sx + sw / 2f, sy + 14.5f, txt);
            txt.setTextAlign(Paint.Align.LEFT);
        } catch (Throwable err) {
            android.util.Log.w(TAG, "drawMapLayer: " + err);
        }
    }

    /** 半透明圆角 chip（与 renderer.drawMapChips 同视觉） */
    private static void chip(Canvas c, float x, float y, float w, String label, float pulse) {
        RectF rf = new RectF(x, y, x + w, y + 28f);
        android.graphics.Path p = new android.graphics.Path();
        p.addRoundRect(rf, 14f, 14f, android.graphics.Path.Direction.CW);
        fill.setColor(0xC7080E11);
        c.drawPath(p, fill);
        line.setStrokeWidth(1f);
        line.setColor(0xFF1B2A30);
        c.drawPath(p, line);

        if (pulse >= 0f) {
            /* 路况 chip：青绿呼吸点 */
            float k = (pulse * 0.45f) % 1f;
            fill.setAlpha(Math.round((1f - k * 0.6f) * 255f));
            fill.setColor(0xFF43E8C8);
            c.drawCircle(x + 15f, y + 14f, 3f, fill);
            fill.setAlpha(255);
        }
        txt.setColor(0xFFA9BEC2);
        txt.setTextSize(11.5f);
        txt.setTextAlign(Paint.Align.LEFT);
        c.drawText(label, x + 26f, y + 18.5f, txt);
    }
}
