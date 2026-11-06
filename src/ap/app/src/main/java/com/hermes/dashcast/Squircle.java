package com.hermes.dashcast;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;

/**
 * ══════════════════════════════════════════════════════════════════
 * Squircle 圆角工厂 —— 按 MiUIX 组件规范产出背景
 * ══════════════════════════════════════════════════════════════════
 *
 * 圆角值直接取自 MiUIX 源码：
 *   basic/Card.kt     CardDefaults.CornerRadius   = 16.dp
 *   basic/Button.kt   ButtonDefaults.CornerRadius = 16.dp
 *   TabRow.kt                                      = 12.dp
 *   胶囊                                          = 999dp（全圆，用普通胶囊即可）
 *
 * 用法：
 *   btn.setBackground(Squircle.of(ctx).button(R.color.accent))
 *   card.setBackground(Squircle.of(ctx).card(R.color.surface_card))
 */
public final class Squircle {

    /** MiUIX CardDefaults.CornerRadius */
    public static final float R_CARD = 16f;
    /** MiUIX ButtonDefaults.CornerRadius */
    public static final float R_BUTTON = 16f;
    /** MiUIX TabRow */
    public static final float R_TAB = 12f;
    /** 胶囊 */
    public static final float R_PILL = 999f;

    private final Context ctx;

    private Squircle(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public static Squircle of(Context c) {
        return new Squircle(c);
    }

    private float px(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }

    private int col(int colorRes) {
        return ctx.getColor(colorRes);
    }

    /* ══ 基础形状 ══ */

    /** 纯填充 squircle */
    public Drawable fill(int colorRes, float radiusDp) {
        return new SquircleDrawable(col(colorRes), px(radiusDp));
    }

    /** 填充 + 描边 squircle */
    public Drawable border(int fillColorRes, int strokeColorRes,
                          float strokeDp, float radiusDp) {
        return new SquircleDrawable(col(fillColorRes), px(radiusDp),
                col(strokeColorRes), px(strokeDp));
    }

    /* ══ 预设尺寸 ══ */

    /** 卡片：填充 + 1dp 描边（MiUIX 卡片靠描边分层，不靠色差） */
    public Drawable card(int fillColorRes) {
        return border(fillColorRes, R.color.surface_border, 1f, R_CARD);
    }

    /** 主按钮：实底强调色（按下深一档） */
    public Drawable primaryButton() {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed},
                fill(R.color.accent_press, R_BUTTON));
        sl.addState(new int[]{},
                fill(R.color.accent, R_BUTTON));
        return sl;
    }

    /** 次按钮：抬升底 + 描边 */
    public Drawable secondaryButton() {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed},
                fill(R.color.surface_raised_press, R_BUTTON));
        sl.addState(new int[]{},
                border(R.color.surface_raised, R.color.surface_border, 1f, R_BUTTON));
        return sl;
    }

    /** 危险按钮：透明底 + 红描边 */
    public Drawable dangerButton() {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed},
                fill(R.color.state_error_tint, R_BUTTON));
        sl.addState(new int[]{},
                borderTransparent(R.color.state_error, 1.5f, R_BUTTON));
        return sl;
    }

    /** 透明填充 + 描边（危险按钮用） */
    public Drawable borderTransparent(int strokeColorRes, float strokeDp, float radiusDp) {
        return new SquircleDrawable(0, px(radiusDp), col(strokeColorRes), px(strokeDp));
    }
}