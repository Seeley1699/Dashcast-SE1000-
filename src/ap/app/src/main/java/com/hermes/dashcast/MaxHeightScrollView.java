package com.hermes.dashcast;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * ══════════════════════════════════════════════════════════════════
 * 有最大高度限制的 ScrollView
 * ══════════════════════════════════════════════════════════════════
 *
 * 标准 ScrollView 的语义是：**给自己一个固定高度，内容超出就滚动**。
 * 这导致一个问题（2026-11-04 用户反馈"截取区展开后下面一大片空白"）：
 *
 *   内容只有 500px，但容器被设成 1248px（屏幕高的 78%），
 *   于是内容下面空出 748px —— 白花花一片。
 *
 *   反过来，如果按内容给高度（WRAP_CONTENT），内容超出时又没法滚，
 *   底部的「应用并投屏 / 保存并应用」会被挤出屏幕。
 *
 * 这个类解决两难：**高度 = min(内容高度, maxHeight)**。
 *   · 内容短 → 容器跟着缩短，没有空白
 *   · 内容长 → 容器封顶在 maxHeight，内部可滚动
 *
 * 实现方式：onMeasure 里先算内容想要多高，再和 maxHeight 取小。
 */
public class MaxHeightScrollView extends ScrollView {

    private int maxHeightPx = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context c) {
        super(c);
    }

    public MaxHeightScrollView(Context c, AttributeSet a) {
        super(c, a);
    }

    public MaxHeightScrollView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
    }

    /** 设置最大高度（px）。传 0 或负数等于不限制。 */
    public void setMaxHeight(int px) {
        if (px <= 0) px = Integer.MAX_VALUE;
        if (maxHeightPx != px) {
            maxHeightPx = px;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        /*
         * 先解开父级给的高度约束（EXACTLY），改成 UNSPECIFIED，
         * 让 super.onMeasure 按内容真实高度测量。
         */
        int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        super.onMeasure(widthSpec, unbounded);

        int contentH = getMeasuredHeight();
        if (contentH > maxHeightPx) {
            /* 内容超高 → 封顶，允许内部滚动 */
            setMeasuredDimension(getMeasuredWidth(), maxHeightPx);
        }
        /* 否则保留按内容测得的高度（无空白） */
    }
}
