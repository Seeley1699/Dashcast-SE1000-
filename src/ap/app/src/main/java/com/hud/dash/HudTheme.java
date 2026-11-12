package com.hud.dash;

import android.graphics.Color;
import android.graphics.Typeface;

/**
 * 色板与工具函数。语义约定见交接文档 §6：
 * 青绿 = 系统/导航/智驾，绿 = 电量，蓝 = 燃油，琥珀 = 转向，黄/红 = 告警。
 */
public final class HudTheme {
    private HudTheme() {}

    // 品牌青绿
    public static final int TEAL    = 0xFF43E8C8;
    public static final int TEAL_LT = 0xFFB6FFF2;
    public static final int TEAL_DK = 0xFF1E8E7C;

    // 能量：电 = 绿，油 = 蓝
    public static final int EV      = 0xFF5CE38A;
    public static final int EV_LT   = 0xFFD2FFE4;
    public static final int EV_DK   = 0xFF1E7A48;
    public static final int FUEL    = 0xFF4FC3FF;
    public static final int FUEL_LT = 0xFFC6ECFF;
    public static final int FUEL_DK = 0xFF175D80;

    // 告警与灯光
    public static final int AMBER    = 0xFFFFB24D;
    public static final int WARN     = 0xFFFFC93C;
    public static final int WARN_LT  = 0xFFFFE7A6;
    public static final int WARN_DK  = 0xFF8E6A14;
    public static final int RED      = 0xFFFF5252;
    public static final int RED_LT   = 0xFFFFD2D2;
    public static final int RED_DK   = 0xFF8E2626;
    public static final int BLUE_HB  = 0xFF7FD2FF;

    // 文本层级
    public static final int TXT  = 0xFFF0FBFA;
    public static final int TXT2 = 0xFFDCEAEA;
    public static final int TXT3 = 0xFFA9BEC2;
    public static final int DIM  = 0xFF6B8085;
    public static final int DIM2 = 0xFF4E6165;
    public static final int DIM3 = 0xFF3D5054;
    public static final int LINE = 0xFF1B2A30;

    public static final Typeface NUM     = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface NUM_MED = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface NUM_BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    public static final Typeface CN = Typeface.create("sans-serif", Typeface.NORMAL);

    public static int rgba(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    public static int lerpColor(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * k);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * k);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * k);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k);
        return Color.argb(a, r, g, b);
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /* Float 版数学：Java 的 Math.sin 接收 double，这里包一层避免到处强转 */
    public static float sinf(float v) { return (float) Math.sin(v); }
    public static float expf(float v) { return (float) Math.exp(v); }
    public static float absf(float v) { return v < 0f ? -v : v; }

    /** 设计角度：0° = 正上方，顺时针为正 */
    public static float polarX(float cx, float r, float deg) {
        return cx + r * (float) Math.sin(Math.toRadians(deg));
    }

    public static float polarY(float cy, float r, float deg) {
        return cy - r * (float) Math.cos(Math.toRadians(deg));
    }
}
