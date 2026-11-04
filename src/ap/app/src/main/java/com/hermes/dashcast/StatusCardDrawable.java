package com.hermes.dashcast;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * ══════════════════════════════════════════════════════════════════
 * 状态卡背景 —— squircle 卡片 + 左侧状态色条
 * ══════════════════════════════════════════════════════════════════
 *
 * 替代原来的 status_card.xml（layer-list + 标准 16dp 圆弧）。
 *
 * 为什么必须自绘：
 *   XML 的 layer-list 里那条 5dp 竖条用的是矩形 + topLeft/bottomLeft 圆角，
 *   圆角是**标准圆弧**。现在卡片主体已改成 squircle（超椭圆），
 *   竖条的左侧圆角必须跟着变成超椭圆，否则两者曲率对不上 ——
 *   放大看会有一条 1px 的豁口。
 *
 * 所以这里一次画两样东西：
 *   ① squircle 卡片底（带 1dp 描边）
 *   ② 左侧 5dp 状态色条，左半边用同一套 squircle 曲线裁切
 *
 * 色条做法：用 clipPath 把卡片形状裁出来，再在左侧画一条竖矩形 ——
 * 这样竖条自然继承卡片的圆角，不用自己算曲线。
 */
public class StatusCardDrawable extends Drawable {

    private static final float EXTENSION = 1.1f;
    private static final float CONTROL = 0.643f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tmpShadow = new Path();
    private final Path shapePath = new Path();
    private final Path clipPath = new Path();
    private final RectF r = new RectF();

    private final int cardColor;
    private final int strokeColor;
    private final int barColor;
    private final float radius;
    private final float barWidth;

    private int alpha = 255;

    public StatusCardDrawable(int cardColor, int strokeColor,
                              int barColor, float radius, float barWidth) {
        this.cardColor = cardColor;
        this.strokeColor = strokeColor;
        this.barColor = barColor;
        this.radius = Math.max(0f, radius);
        this.barWidth = Math.max(0f, barWidth);

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        barPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        float stroke = 1f;
        float half = stroke / 2f;
        r.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        if (r.width() <= 0 || r.height() <= 0) return;

        /*
         * ══ 阴影（2026-11-04 新加）══
         *
         * 浅色 UI 里白卡片浮在灰底上，**唯一的分层线索就是阴影**。
         * 原来这个 Drawable 没有阴影，状态卡和页面底几乎融在一起，
         * 整片看起来"一片白"（用户反馈"左边这也要改"）。
         *
         * 实现方式同 SquircleDrawable：多层偏移填充轮廓，
         * 从外到内逐层收缩，形成柔和的投影。
         */
        int shadowA = 0x0A;   /* 4% 黑，和 SWKPICard 的 .opacity(0.04) 一致 */
        for (int li = 5; li >= 0; li--) {
            float grow = li + 1f;
            int a1 = (int) (shadowA * (1f - (float) li / 6f));
            if (a1 <= 0) continue;
            shadowPaint.setColor(0x00000000 | (a1 * 0x1000000));
            shadowPaint.setAlpha(a1);
            buildSquircle(tmpShadow, r.width() + grow, r.height() + grow, radius + grow * 0.5f);
            tmpShadow.offset(r.left, r.top + 2f * grow / 6f);
            canvas.drawPath(tmpShadow, shadowPaint);
        }

        buildSquircle(shapePath, r.width(), r.height());
        shapePath.offset(r.left, r.top);

        // ② 填充
        fillPaint.setColor(cardColor);
        fillPaint.setAlpha(alpha);
        canvas.drawPath(shapePath, fillPaint);

        // ③ 左侧状态色条（裁到卡片形状内）
        if (barWidth > 0f && barColor != 0) {
            canvas.save();
            clipPath.set(shapePath);
            canvas.clipPath(clipPath);
            barPaint.setColor(barColor);
            barPaint.setAlpha(alpha);
            canvas.drawRect(r.left, r.top, r.left + barWidth, r.bottom, barPaint);
            canvas.restore();
        }

        // ④ 描边
        strokePaint.setColor(strokeColor);
        strokePaint.setAlpha(alpha);
        canvas.drawPath(shapePath, strokePaint);
    }

    /** MiUIX SquirclePath.addSquircleRect 的等价实现 */
    /** 用字段里的 radius（原版） */
    private void buildSquircle(Path p, float w, float h) {
        buildSquircle(p, w, h, radius);
    }

    /** 指定 radius 的版本（阴影层要用更大的半径） */
    private void buildSquircle(Path p, float w, float h, float rad) {
        p.reset();
        float halfMin = Math.min(w, h) * 0.5f;
        float tile = Math.min(rad * EXTENSION, halfMin);
        if (tile <= 0f) {
            p.addRect(0f, 0f, w, h, Path.Direction.CW);
            return;
        }
        float handle = tile * (1f - CONTROL);

        p.moveTo(tile, 0f);
        p.lineTo(w - tile, 0f);
        p.cubicTo(w - handle, 0f, w, handle, w, tile);

        p.lineTo(w, h - tile);
        p.cubicTo(w, h - handle, w - handle, h, w - tile, h);

        p.lineTo(tile, h);
        p.cubicTo(handle, h, 0f, h - handle, 0f, h - tile);

        p.lineTo(0f, tile);
        p.cubicTo(0f, handle, handle, 0f, tile, 0f);
        p.close();
    }

    /** 状态色变了要重画 */
    public void setBarColor(int color) {
        if (this.barColor == color) return;
        invalidateSelf();
    }

    @Override
    public void setAlpha(int a) {
        this.alpha = a;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fillPaint.setColorFilter(cf);
        strokePaint.setColorFilter(cf);
        barPaint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
