package com.hermes.dashcast;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.SeekBar;

/**
 * ══════════════════════════════════════════════════════════════════
 * MiUIX 风格滑块
 * ══════════════════════════════════════════════════════════════════
 *
 * 替代系统默认 SeekBar（那条细线 + 小圆点，和 MiUIX 的观感差距很大）。
 *
 * 规格直接取自 miuix-ui/.../basic/Slider.kt：
 *
 *   SliderDefaults.MinHeight = 28.dp          ← 控件总高
 *   轨道粗细 = barHeight                       ← 就是控件高度
 *   thumb 半径 = barHeight / 2
 *   knob 半径  = thumbRadius × 0.72           ← 滑块圆点比轨道细
 *   按下时放大 = 1.127×
 *   轨道圆头 cap = StrokeCap.Round            ← 两端全圆
 *
 * 绘制逻辑（Slider.kt 第 891..923 行，水平方向）：
 *   ① 已选段：从 0 到 centerX 画一条圆头线，颜色 foregroundColor
 *   ② 未选段：从 centerX 到末端画圆头线，颜色 backgroundColor
 *   ③ 滑块圆点：在 centerX 画圆，半径 thumbRadius × 0.72 × scale
 *
 * ── 为什么不用 setProgressTintList ──
 *   系统的 tint 只能改颜色，改不了：
 *     · 轨道粗细（系统是 4dp 固定，MiUIX 是整块 28dp 高）
 *     · 圆点大小（系统根据轨道自动算，MiUIX 是 0.72×）
 *     · 按下放大动画
 *   必须重写 onDraw。
 */
public class MiuixSeekBar extends SeekBar {

    /** SliderDefaults.MinHeight = 28dp */
    public static final float HEIGHT_DP = 28f;
    /** knobRadius = thumbRadius × 0.72 */
    private static final float KNOB_RATIO = 0.72f;
    /** 按下放大 */
    private static final float PRESS_SCALE = 1.127f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private int activeColor = 0xFF277AF7;    /* MiUIX primary */
    private int inactiveColor = 0x26FFFFFF;  /* MiUIX sliderBackground */

    /**
     * **自己保存一份监听器引用**（2026-11-05）。
     *
     * AbsSeekBar 的 mOnSeekBarChangeListener 是私有的，拿不到；
     * 而代码里调 setProgress() 不会触发它。
     * 所以重写 setOnSeekBarChangeListener，自己存一份，
     * 在 applyTouchProgress 里手动调用。
     */
    private OnSeekBarChangeListener mListener;
    private int knobColor = 0xFF277AF7;

    private float pressScale = 1f;

    public MiuixSeekBar(Context c) {
        super(c);
        init();
    }

    public MiuixSeekBar(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public MiuixSeekBar(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init();
    }

    private void init() {
        /* 关掉系统自带绘制（我们全自己画） */
        setSplitTrack(false);
        trackPaint.setStyle(Paint.Style.FILL);
        knobPaint.setStyle(Paint.Style.FILL);

        /* 从色板取默认色 —— 不要硬编码，跟着主题走 */
        try {
            activeColor   = getContext().getColor(R.color.accent);
            inactiveColor = getContext().getColor(R.color.surface_border);
            knobColor     = getContext().getColor(R.color.accent);
            /*
             * **必须清空系统 progressDrawable**（2026-11-04 修复）。
             *
             * setSplitTrack(false) 只关掉"分段"绘制，系统仍会在我们
             * 下面画一条默认轨道 —— 两套图形叠在一起，视觉上就是
             * "已选段到中间就停了，右边还有一段浅色"。
             *
             * 置 null 后系统什么都不画，完全由 onDraw 接管。
             */
            setProgressDrawable(null);
            setThumb(null);
        } catch (Throwable ignored) {
            /* 取不到就保留字段默认值 */
        }
    }

    public void setActiveColor(int c) {
        activeColor = c;
        invalidate();
    }

    public void setInactiveColor(int c) {
        inactiveColor = c;
        invalidate();
    }

    public void setKnobColor(int c) {
        knobColor = c;
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;

        float cx0 = getPaddingLeft();
        float cy = getPaddingTop() + h / 2f;
        float trackH = h;                    /* MiUIX: 轨道高 = 控件高 */
        float thumbR = trackH / 2f;
        float knobR = thumbR * KNOB_RATIO * pressScale;

        float max = getMax();
        float progress = getProgress();
        float fraction = max > 0 ? (progress / max) : 0f;

        /* 可用宽度（两端各留 thumbR，否则圆点会被裁一半） */
        float available = Math.max(0f, w - 2f * thumbR);
        float centerX = cx0 + thumbR + fraction * available;

        /*
         * 三段绘制顺序：未选 → 已选 → 圆点
         *
         * 原来的问题（用户截图反馈"已选段停在中间、右边没接上"）：
         *   未选段画的是**整条** (cx0 → cx0+w)，已选段盖上去 (cx0 → centerX)。
         *   如果 centerX 之后还有一段颜色更深的浅蓝，说明
         *   **系统 SeekBar 的默认轨道没被关掉**，和我们画的叠在一起。
         *
         * 系统 SeekBar 的轨道由 progressDrawable 控制，
         * setSplitTrack(false) 是不够的 —— 必须显式置空 progressDrawable，
         * 否则它仍会在我们下面画一条系统轨道。
         */

        /* ① 未选段（整条，从最左到最右） */
        trackPaint.setColor(inactiveColor);
        tmp.set(cx0, cy - trackH / 2f, cx0 + w, cy + trackH / 2f);
        canvas.drawRoundRect(tmp, thumbR, thumbR, trackPaint);

        /* ② 已选段（0 → centerX） */
        trackPaint.setColor(activeColor);
        tmp.set(cx0, cy - trackH / 2f, centerX, cy + trackH / 2f);
        canvas.drawRoundRect(tmp, thumbR, thumbR, trackPaint);

        /* ③ 滑块圆点（按下放大） */
        knobPaint.setColor(knobColor);
        canvas.drawCircle(centerX, cy, knobR, knobPaint);
    }

    /**
     * ══════════════════════════════════════════════════════════════
     * **拖动手势自己算，不依赖 AbsSeekBar**（2026-11-05 修复"滑块拖不动"）
     * ══════════════════════════════════════════════════════════════
     *
     * 原来只处理按下缩放动画，然后 `return super.onTouchEvent(e)`
     * 把拖动交给系统 AbsSeekBar —— **但那条路是断的**。
     *
     * 根因：init() 里为了关掉系统默认轨道调了
     *   setProgressDrawable(null)   → 系统轨道没了
     *   setThumb(null)              → 系统滑块也没了
     *
     * 而 AbsSeekBar.onTouchEvent 内部是这样的：
     *   if (mThumb != null) { 计算可拖动区域 ... }
     *   mThumb 为 null → 整段拖动逻辑被跳过 → 滑块纹丝不动。
     *
     * 这就是"羽化/边框/网格等所有滑块都拖不动"的原因：
     * 值一直停在初始值，怎么点都没反应。
     *
     * 修法：**不调 super**，按 x 坐标自己算 progress。
     * 映射规则和 onDraw 保持一致：
     *   可用宽度 = w - 2*thumbR（两端各留一个圆点的半径，否则圆点会被裁）
     *   fraction  = (x - paddingLeft - thumbR) / available
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                /* fall through */
            case MotionEvent.ACTION_MOVE:
                if (pressScale != PRESS_SCALE) {
                    pressScale = PRESS_SCALE;
                    invalidate();
                }
                applyTouchProgress(e.getX());
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressScale != 1f) {
                    pressScale = 1f;
                    invalidate();
                }
                applyTouchProgress(e.getX());
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return true;
    }

    /**
     * **覆盖父类的监听器注册，自己存一份**（2026-11-05）。
     *
     * AbsSeekBar 的 mOnSeekBarChangeListener 是 private 的，拿不到；
     * 而代码里调 setProgress() 不会触发它。
     *
     * 这里保存一份引用，applyTouchProgress 里手动调用，
     * 保证上层（maskCfg.xxx = v）真的拿到滑块的新值。
     */
    @Override
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener l) {
        mListener = l;
        super.setOnSeekBarChangeListener(l);
    }

    /** 把触摸 x 换算成 progress（映射和 onDraw 完全一致） */
    private void applyTouchProgress(float x) {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;

        float trackH = h;
        float thumbR = trackH / 2f;
        float available = Math.max(1f, w - 2f * thumbR);

        /* 相对可用区的比例（夹到 0..1） */
        float fraction = (x - getPaddingLeft() - thumbR) / available;
        if (fraction < 0f) fraction = 0f;
        if (fraction > 1f) fraction = 1f;

        int max = getMax();
        int p = (int) Math.round(fraction * max);
        if (p != getProgress()) {
            /*
             * **必须手动通知监听器**（2026-11-05 修复"滑块动了但配置没变"）。
             *
             * AbsSeekBar 的 setProgress() 只在**用户触摸**时回调
             * onProgressChanged；代码里主动调是**静默的**。
             *
             * 原来只调 setProgress(p) 的效果：
             *   · 圆点位置变了（onDraw 读 getProgress()）
             *   · 界面数字变了（[95]）
             *   · 但 maskCfg.feather 完全没变 → 保存时还是旧值
             * 实测：羽化拖到 95，点保存，dcapd2.layer 里还是 feather=3。
             */
            setProgress(p);
            if (mListener != null) {
                mListener.onProgressChanged(this, p, true);
            }
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        /* 固定高度 28dp（MiUIX SliderDefaults.MinHeight） */
        int h = (int) (HEIGHT_DP * getResources().getDisplayMetrics().density);
        int height = MeasureSpec.makeMeasureSpec(
                h + getPaddingTop() + getPaddingBottom(), MeasureSpec.EXACTLY);
        super.onMeasure(widthSpec, height);
    }
}
