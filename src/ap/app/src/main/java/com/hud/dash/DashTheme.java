package com.hud.dash;

import android.graphics.Typeface;

/**
 * ══════════════════════════════════════════════════════════════════
 * 仪表主题接口 —— 只管「长什么样」
 * ══════════════════════════════════════════════════════════════════
 *
 * **为什么要抽这个接口**（2026-11-12）
 *
 * 抽之前：DashRenderer 里 112 处硬编码 0xFF... 颜色，只引用了 9 次
 * HudTheme.xxx。想换个配色得全文件翻一遍，漏一处就花。
 *
 * 抽之后：DashRenderer 只问 theme.xxx()，换主题 = 换一个实现类，
 * 绘制逻辑一行不动。
 *
 * ── 设计边界 ──────────────────────────────────────────────────
 *
 * 这个接口**只管颜色/字体/形状**，不管画什么、画在哪。
 * 「画什么」是 DashSkin 的事，「画在哪」是 HudLayout 的事。
 *
 * 三层职责：
 *   DashTheme    颜色、字体、圆角、线宽        ← 本文件
 *   DashSkin     整个仪表盘怎么画（可整套替换）  ← DashSkin.java
 *   HudLayout    卡片矩形、表盘几何（固定）      ← HudLayout.java
 *
 * ── 默认值 ────────────────────────────────────────────────────
 *
 * 接口方法全部给 default 实现，取值就是现在这套青绿赛博风
 * （TealTheme 的值）。这样：
 *   · 只想改一两个颜色 → 匿名类覆写那一两个方法即可
 *   · 想整套换 → 实现全部方法，或者 extends 一个现有主题
 *
 * ── 语义约定（沿用交接文档 §6）──────────────────────────────
 *
 *   青绿 = 系统/导航/智驾
 *   绿   = 电量
 *   蓝   = 燃油
 *   琥珀 = 转向
 *   黄/红 = 告警
 */
public interface DashTheme {

    /* ══════════════ 配色：背景与容器 ══════════════ */

    /** 画布底色 */
    default int bg() { return 0xFF05080A; }

    /** 卡片填充 */
    default int cardBg() { return 0xFF182630; }

    /** 卡片描边 */
    default int cardStroke() { return 0xFF22353D; }

    /** 分隔线 / 次一级描边 */
    default int line() { return 0xFF22353D; }

    /* ══════════════ 配色：强调 ══════════════ */

    /** 主强调色 —— 速度弧、指针、激活态。当前是品牌青绿 */
    default int accent() { return 0xFF43E8C8; }

    /** 主色高亮版（渐变亮端） */
    default int accentLight() { return 0xFFB6FFF2; }

    /** 主色暗版（渐变暗端、底弧） */
    default int accentDark() { return 0xFF1E8E7C; }

    /* ══════════════ 配色：能量 ══════════════ */

    /** 电量 —— 绿 */
    default int evColor() { return 0xFF5CE38A; }
    default int evLight() { return 0xFFD2FFE4; }
    default int evDark() { return 0xFF1E7A48; }

    /** 燃油 —— 蓝 */
    default int fuelColor() { return 0xFF4FC3FF; }
    default int fuelLight() { return 0xFFC6ECFF; }
    default int fuelDark() { return 0xFF175D80; }

    /* ══════════════ 配色：告警与灯光 ══════════════ */

    /** 转向灯 —— 琥珀 */
    default int amber() { return 0xFFFFB24D; }

    /** 警告 —— 黄 */
    default int warn() { return 0xFFFFC93C; }
    default int warnLight() { return 0xFFFFE7A6; }
    default int warnDark() { return 0xFF8E6A14; }

    /** 危险 —— 红（超速、低电量） */
    default int danger() { return 0xFFFF5252; }
    default int dangerLight() { return 0xFFFFD2D2; }
    default int dangerDark() { return 0xFF8E2626; }

    /** 远光灯 —— 冷蓝白 */
    default int highBeam() { return 0xFF7FD2FF; }

    /* ══════════════ 配色：文字层级 ══════════════ */

    /** 主文字（速度数字、档位） */
    default int textPrimary() { return 0xFFF0FBFA; }

    /** 次文字（单位、标签） */
    default int textSecondary() { return 0xFFDCEAEA; }

    /** 弱文字（辅助说明） */
    default int textDim() { return 0xFFA9BEC2; }

    /** 更弱（分隔线旁的小字） */
    default int textFaint() { return 0xFF8A9FA3; }

    /* ══════════════ 字体 ══════════════ */

    /** 数字字体（速度值、电量百分比） */
    default Typeface numberFont() { return HudTheme.NUM_MED; }

    /** 标签字体（单位、栏目标题） */
    default Typeface labelFont() { return HudTheme.CN; }

    /** 标题字体（媒体卡标题） */
    default Typeface titleFont() { return HudTheme.NUM_BOLD; }

    /* ══════════════ 形状与度量 ══════════════ */

    /** 卡片圆角半径（px，设计稿坐标系） */
    default float cardRadius() { return 12f; }

    /** 卡片内边距 */
    default float cardPadding() { return 16f; }

    /** 主描边线宽 */
    default float strokeWidth() { return 3f; }

    /** 细线线宽（分隔线、辅助弧） */
    default float hairline() { return 1.5f; }

    /*
     * ── 兼容旧常量 ──
     *
     * DashRenderer 里还有大量 `import static com.hud.dash.HudTheme.*;`
     * 直接引用 TEAL / EV / FUEL 等常量。重构分两步走：
     *   第一步（本次）：接口就位，DashRenderer 继续用旧常量，功能不变
     *   第二步：把 DashRenderer 里的常量引用逐个换成 theme.xxx()
     *
     * 这样每一步都能编译、能对比截图，不会一次性炸掉。
     */
}
