package com.hud.dash;

import android.graphics.Typeface;

/**
 * ══════════════════════════════════════════════════════════════════
 * 原厂黑白主题 —— 示例：只换配色，画法不变
 * ══════════════════════════════════════════════════════════════════
 *
 * 这个类演示「只改颜色」的最小写法：
 *   · 继承 TealTheme（藉此拿到全部 default 值）
 *   · 只覆写想改的那几个方法
 *   · 其余全部沿用
 *
 * 用法：
 *   view.setTheme(new ClassicTheme());
 *
 * 对照点：原厂 cluster-hmi-p171 是深黑底 + 白字 + 蓝色强调，
 * 卡片用极细灰线分隔，没有圆角渐变。
 *
 * 注意：这只是**配色**层面接近原厂。要真正复刻原厂布局
 * （速度盘在中间、两侧对称那种），得另写一个 DashSkin。
 */
public class ClassicTheme extends TealTheme {

    /* ── 背景更黑，卡片更暗 ── */
    @Override public int bg()         { return 0xFF000000; }
    @Override public int cardBg()     { return 0xFF0A0A0C; }
    @Override public int cardStroke() { return 0xFF242428; }
    @Override public int line()       { return 0xFF1C1C20; }

    /* ── 强调色换成原厂那种偏冷的蓝 ── */
    @Override public int accent()      { return 0xFF3D8BFD; }
    @Override public int accentLight() { return 0xFF9EC5FF; }
    @Override public int accentDark()  { return 0xFF1A4C99; }

    /* ── 文字更纯白，层级拉得更开 ── */
    @Override public int textPrimary()   { return 0xFFFFFFFF; }
    @Override public int textSecondary() { return 0xFFC8CDD2; }
    @Override public int textDim()       { return 0xFF8A9098; }
    @Override public int textFaint()     { return 0xFF5A6068; }

    /* ── 告警色更饱和一点 ── */
    @Override public int warn()   { return 0xFFFFB020; }
    @Override public int danger() { return 0xFFFF3B30; }

    /* ── 形状：原厂偏硬朗，圆角小、线细 ── */
    @Override public float cardRadius()  { return 6f; }
    @Override public float strokeWidth() { return 2f; }
    @Override public float hairline()    { return 1f; }

    /* ── 字体：数字用粗体，更接近原厂观感 ── */
    @Override public Typeface numberFont() { return HudTheme.NUM_BOLD; }
    @Override public Typeface titleFont()  { return HudTheme.NUM_MED; }
}
