package com.hermes.dashcast;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 图层遮罩预览视图（可复用组件）。
 *
 * 把画布等比缩到控件里，画出图层形状（矩形/圆角/梯形/椭圆），
 * 拖动图层内部 = 移动，拖右下角 = 缩放，都按网格吸附。
 *
 * 2026-10-31 从 LayerEditorActivity 抽出，供首页右栏和独立编辑器共用：
 * 同一套代码，同样的手感 —— 否则两处维护必然走样。
 */
public class LayerPreviewView extends View {

    /** 尺寸变化回调（拖动时实时更新旁边那行「1920×532 · (0,0) 图层 …」） */
    public interface OnGeometryChanged {
        void onChanged(LayerConfig cfg);
    }

    private final Paint pBg     = new Paint();
    private final Paint pLayer  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEdge   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid   = new Paint();

    private final LayerConfig cfg;
    private final Context ctx;
    private OnGeometryChanged listener;

    private boolean zoomed;
    private float scale = 1f, ox, oy;

    /* 拖拽状态 */
    private int mode = 0;       /* 0=无 1=移动 2=右下缩放 */
    private float downX, downY;
    private int startX, startY, startW, startH;

    public LayerPreviewView(Context ctx, LayerConfig cfg) {
        super(ctx);
        this.cfg = cfg;
        this.ctx = ctx;

        pBg.setColor(ctx.getColor(R.color.surface_root));
        pLayer.setColor(ctx.getColor(R.color.accent));
        pLayer.setAlpha(200);
        pEdge.setColor(ctx.getColor(R.color.accent_soft));
        pEdge.setStyle(Paint.Style.STROKE);
        pEdge.setStrokeWidth(2f);
        pHandle.setColor(ctx.getColor(R.color.accent));
        pGrid.setColor(0x22FFFFFF);
        pGrid.setStrokeWidth(1f);
    }

    public void setOnGeometryChanged(OnGeometryChanged l) {
        this.listener = l;
    }

    public void setZoomed(boolean z) {
        zoomed = z;
        invalidate();
    }

    public boolean isZoomed() {
        return zoomed;
    }

    public void refresh() {
        cfg.sanitize();
        invalidate();
    }

    private void computeScale() {
        float availW = getWidth() - 16f;
        float availH = getHeight() - 16f;
        if (availW <= 0 || availH <= 0) return;
        scale = Math.min(availW / cfg.canvasW, availH / cfg.canvasH);
        if (zoomed) scale *= 2f;
        ox = (getWidth() - cfg.canvasW * scale) / 2f;
        oy = (getHeight() - cfg.canvasH * scale) / 2f;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDraw(Canvas c) {
        computeScale();
        c.drawRect(0, 0, getWidth(), getHeight(), pBg);

        /* 画布边界 */
        pEdge.setColor(0x44FFFFFF);
        c.drawRect(ox, oy, ox + cfg.canvasW * scale, oy + cfg.canvasH * scale, pEdge);

        /* 网格 */
        if (cfg.grid > 0) {
            pEdge.setColor(0x22FFFFFF);
            for (int gx = 0; gx <= cfg.canvasW; gx += cfg.grid) {
                float px = ox + gx * scale;
                c.drawLine(px, oy, px, oy + cfg.canvasH * scale, pGrid);
            }
            for (int gy = 0; gy <= cfg.canvasH; gy += cfg.grid) {
                float py = oy + gy * scale;
                c.drawLine(ox, py, ox + cfg.canvasW * scale, py, pGrid);
            }
        }

        /* 图层形状 */
        RectF r = new RectF(ox + cfg.x * scale, oy + cfg.y * scale,
                ox + (cfg.x + cfg.w) * scale, oy + (cfg.y + cfg.h) * scale);
        Path path = buildPath(r);
        pLayer.setColor(cfg.enabled ? ctx.getColor(R.color.accent)
                                    : ctx.getColor(R.color.content_tertiary));
        pLayer.setAlpha(cfg.enabled ? 200 : 120);
        c.drawPath(path, pLayer);

        pEdge.setColor(cfg.enabled ? ctx.getColor(R.color.accent)
                                  : ctx.getColor(R.color.content_secondary));
        pEdge.setStrokeWidth(2f);
        c.drawPath(path, pEdge);

        /* 右下角拖拽柄 */
        c.drawCircle(r.right, r.bottom, dp(9), pHandle);
    }

    /** 按形状类型构造路径（与 C 侧 shape_distance 视觉一致） */
    private Path buildPath(RectF r) {
        Path p = new Path();
        switch (cfg.shape) {
            case LayerConfig.SHAPE_ELLIPSE: {
                p.addOval(r, Path.Direction.CW);
                break;
            }
            case LayerConfig.SHAPE_TRAP: {
                float insT = cfg.trapTop * scale;
                float insB = cfg.trapBot * scale;
                p.moveTo(r.left + insT, r.top);
                p.lineTo(r.right - insT, r.top);
                p.lineTo(r.right - insB, r.bottom);
                p.lineTo(r.left + insB, r.bottom);
                p.close();
                break;
            }
            case LayerConfig.SHAPE_ROUND: {
                /* 四角不同半径 → 用 4 段 arcTo 拼（addRoundRect 只能统一半径） */
                float tl = cfg.rTl * scale, tr = cfg.rTr * scale;
                float br = cfg.rBr * scale, bl = cfg.rBl * scale;
                p.moveTo(r.left + tl, r.top);
                p.lineTo(r.right - tr, r.top);
                if (tr > 0) p.arcTo(new RectF(r.right - 2 * tr, r.top,
                        r.right, r.top + 2 * tr), -90, 90, false);
                p.lineTo(r.right, r.bottom - br);
                if (br > 0) p.arcTo(new RectF(r.right - 2 * br, r.bottom - 2 * br,
                        r.right, r.bottom), 0, 90, false);
                p.lineTo(r.left + bl, r.bottom);
                if (bl > 0) p.arcTo(new RectF(r.left, r.bottom - 2 * bl,
                        r.left + 2 * bl, r.bottom), 90, 90, false);
                p.lineTo(r.left, r.top + tl);
                if (tl > 0) p.arcTo(new RectF(r.left, r.top,
                        r.left + 2 * tl, r.top + 2 * tl), 180, 90, false);
                p.close();
                break;
            }
            default:
                p.addRect(r, Path.Direction.CW);
                break;
        }
        return p;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        computeScale();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX(); downY = e.getY();
                startX = cfg.x; startY = cfg.y;
                startW = cfg.w; startH = cfg.h;

                /* 靠近右下角 = 缩放，否则 = 移动 */
                float hx = ox + (cfg.x + cfg.w) * scale;
                float hy = oy + (cfg.y + cfg.h) * scale;
                float dist = (float) Math.hypot(e.getX() - hx, e.getY() - hy);
                mode = (dist < dp(24)) ? 2 : 1;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = (e.getX() - downX) / scale;
                float dy = (e.getY() - downY) / scale;

                if (mode == 1) {
                    cfg.x = cfg.snap(startX + (int) dx);
                    cfg.y = cfg.snap(startY + (int) dy);
                } else if (mode == 2) {
                    cfg.w = cfg.snap(startW + (int) dx);
                    cfg.h = cfg.snap(startH + (int) dy);
                }
                cfg.sanitize();
                invalidate();
                notifyChanged();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode != 0) {
                    mode = 0;
                    cfg.sanitize();
                    /* 只改内存，点「保存」才落盘并同步仪表 */
                    refresh();
                    notifyChanged();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void notifyChanged() {
        if (listener != null) listener.onChanged(cfg);
    }

    /** 统一的几何描述文案（两处显示一致） */
    public static String geometryText(LayerConfig cfg) {
        return String.format(Locale.US, "%d × %d  ·  (%d, %d)  ·  图层 %d × %d",
                cfg.canvasW, cfg.canvasH, cfg.x, cfg.y, cfg.w, cfg.h);
    }
}
