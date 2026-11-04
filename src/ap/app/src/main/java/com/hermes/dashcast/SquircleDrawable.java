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
 * Squircle（超椭圆圆角）Drawable
 * ══════════════════════════════════════════════════════════════════
 *
 * 算法来源：MiUIX miuix-squircle/squircle/SquirclePath.kt
 *   · SquircleDefaults.Extension = 1.1f
 *   · SQUIRCLE_CONTROL = 0.643f
 *   · tile   = cornerRadius × 1.1
 *   · handle = tile × (1 − 0.643) = tile × 0.357
 *   · 四段三次贝塞尔
 *
 * 阴影来源：ShipSwift SWKPICard.swift:145
 *   .shadow(color: .black.opacity(0.04), radius: 6, x: 0, y: 2)
 *
 * ── 阴影为什么不用 setShadowLayer ──
 *   Paint.setShadowLayer 只在软件层（LAYER_TYPE_SOFTWARE）生效。
 *   Drawable 挂在 View 背景上，View 默认硬件加速，阴影会被直接忽略。
 *   给每个 View 关硬件加速会掉帧，车机上代价太大。
 *
 *   所以用「多层填充轮廓」：从外到内逐层收缩、透明度递增，
 *   叠起来就是从边缘向外淡出的柔和投影，全程硬件加速。
 */
public class SquircleDrawable extends Drawable {

    /** MiUIX SquircleDefaults.Extension */
    public static final float EXTENSION = 1.1f;

    /** MiUIX SQUIRCLE_CONTROL */
    private static final float SQUIRCLE_CONTROL = 0.643f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path tmpPath = new Path();
    private final RectF bounds = new RectF();

    private final int fillColor;
    private final int strokeColor;
    private final float cornerRadius;      /* px */
    private final float strokeWidth;       /* px */
    private final float extension;

    /* 阴影参数 */
    private float shadowRadius = 0f;
    private float shadowDy = 0f;
    private int shadowColor = 0;

    private int alpha = 255;

    public SquircleDrawable(int fillColor, float cornerRadius) {
        this(fillColor, cornerRadius, 0, 0f, EXTENSION);
    }

    public SquircleDrawable(int fillColor, float cornerRadius,
                            int strokeColor, float strokeWidth) {
        this(fillColor, cornerRadius, strokeColor, strokeWidth, EXTENSION);
    }

    public SquircleDrawable(int fillColor, float cornerRadius,
                            int strokeColor, float strokeWidth, float extension) {
        this.fillColor = fillColor;
        this.cornerRadius = Math.max(0f, cornerRadius);
        this.strokeColor = strokeColor;
        this.strokeWidth = Math.max(0f, strokeWidth);
        this.extension = Math.min(Math.max(extension, 1f), 2f);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fillColor);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(strokeColor);
        strokePaint.setStrokeWidth(this.strokeWidth);

        shadowPaint.setStyle(Paint.Style.FILL);
    }

    /** 设置阴影（浅色 UI 的层级全靠它） */
    public void setShadow(float radius, float dy, int color) {
        this.shadowRadius = radius;
        this.shadowDy = dy;
        this.shadowColor = color;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        /* 描边内缩半笔宽，避免被裁一半（MiUIX SquircleBorder.kt:42） */
        float half = strokeWidth / 2f;
        bounds.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        if (bounds.width() <= 0 || bounds.height() <= 0) return;

        float w = bounds.width();
        float h = bounds.height();

        /*
         * ── ① 阴影：只在下方/右侧扩展 ──
         *
         * **关键约束**：Drawable 会被 View 裁剪到自身边界内。
         * 阴影如果向左/向上扩展（bounds.left - grow），那部分直接被裁掉，
         * 结果就是一条贴着卡片下沿的暗带 —— 既不是阴影，还把内容压扁。
         *
         * 所以阴影轮廓的左上角**钉在 bounds 原点**，只向右下两方向长，
         * 靠 shadowDy 制造"投影在下方"的观感。
         */
        int baseAlpha = (shadowColor >>> 24) & 0xFF;
        if (shadowRadius > 0f && baseAlpha > 0) {
            int layers = Math.max(2, (int) shadowRadius);
            for (int li = layers - 1; li >= 0; li--) {
                float grow = li + 1f;
                int a1 = (int) (baseAlpha * 0.55f * (1f - (float) li / layers));
                if (a1 <= 0) continue;
                shadowPaint.setColor(shadowColor);
                shadowPaint.setAlpha(a1);
                /* 轮廓：左/上不动，向右/下各长 grow */
                buildPathInto(tmpPath, w + grow, h + grow, cornerRadius + grow * 0.5f);
                tmpPath.offset(bounds.left, bounds.top + shadowDy);
                canvas.drawPath(tmpPath, shadowPaint);
            }
        }

        /* ── ② 主路径 ── */
        buildPathInto(path, w, h, cornerRadius);
        path.offset(bounds.left, bounds.top);

        /* ── ③ 填充 ── */
        int fillA = (fillColor >>> 24) & 0xFF;
        if (fillColor != 0 && fillA != 0) {
            fillPaint.setColor(fillColor);
            fillPaint.setAlpha(alpha * fillA / 255);
            canvas.drawPath(path, fillPaint);
        }

        /* ── ④ 描边 ── */
        if (strokeWidth > 0f && strokeColor != 0) {
            strokePaint.setColor(strokeColor);
            strokePaint.setAlpha(alpha);
            canvas.drawPath(path, strokePaint);
        }
    }

    /**
     * 构建 squircle 路径到指定 Path（原点在 0,0）。
     *
     * 严格对应 MiUIX SquirclePath.kt:74-91：
     *   tile   = radius × extension，再钳到短边的一半
     *   handle = tile × (1 − 0.643)
     */
    private void buildPathInto(Path target, float w, float h, float radius) {
        target.reset();
        if (w <= 0f || h <= 0f) return;

        float halfMin = Math.min(w, h) * 0.5f;
        float tile = Math.min(radius * extension, halfMin);
        if (tile <= 0f) {
            target.addRect(0f, 0f, w, h, Path.Direction.CW);
            return;
        }
        float handle = tile * (1f - SQUIRCLE_CONTROL);

        target.moveTo(tile, 0f);
        target.lineTo(w - tile, 0f);
        target.cubicTo(w - handle, 0f, w, handle, w, tile);

        target.lineTo(w, h - tile);
        target.cubicTo(w, h - handle, w - handle, h, w - tile, h);

        target.lineTo(tile, h);
        target.cubicTo(handle, h, 0f, h - handle, 0f, h - tile);

        target.lineTo(0f, tile);
        target.cubicTo(0f, handle, handle, 0f, tile, 0f);

        target.close();
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
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** 让背景能响应 View 的 padding 计算 */
    @Override
    public boolean getPadding(Rect padding) {
        int p = (int) Math.ceil(strokeWidth);
        padding.set(p, p, p, p);
        return p > 0;
    }
}
