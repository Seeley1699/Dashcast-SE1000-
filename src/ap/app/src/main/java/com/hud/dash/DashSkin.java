package com.hud.dash;

import android.graphics.Canvas;

/**
 * ══════════════════════════════════════════════════════════════════
 * 仪表皮肤接口 —— 整个画面怎么画，可以整套替换
 * ══════════════════════════════════════════════════════════════════
 *
 * **和 DashTheme 的分工**（2026-11-12）
 *
 *   DashTheme   改「颜色/字体/圆角」，画法规矩不变
 *   DashSkin    改「画什么、画在哪」，整套重画
 *
 * 举个例子：
 *   · 只想把青绿换成橙色           → 写个 Theme，Skin 不用动
 *   · 想把速度盘从圆弧改成长条      → 写个 Skin
 *   · 完全重新设计整个仪表          → 写个 Skin + 可能的 Theme
 *
 * ── 坐标系 ────────────────────────────────────────────────────
 *
 * Canvas 传进来时已经做好缩放和平移：
 *   · 原点在仪表有效区左上角
 *   · 坐标范围固定 0..HudLayout.W × 0..HudLayout.H（1920 × 532）
 *   · 已 clip 到该矩形，画出去的内容会被裁掉
 *
 * 所以 Skin 里直接按设计稿像素坐标画就行，不用管 View 实际多大。
 * （缩放逻辑见 DashView.onDraw）
 *
 * ── 可用数据 ──────────────────────────────────────────────────
 *
 * HudData 提供（节选，完整见 HudData.java）：
 *
 *   速度/功率   speed(km/h) power powerMax
 *   档位       gear("P"/"R"/"N"/"D")
 *   续航       evRange evCap fuelRange fuelCap
 *   驾驶模式   driveMode("纯电"/"增程"/"性能"/"智能")
 *   灯光       lightLeft lightRight lightLow lightHigh
 *              lightHold(自动驻车) lightFogR lightHazard lightAuto
 *   胎压       tireBars[4]（单位 Bar，已归一到 1.0~4.5）
 *   空调       outdoor indoor
 *   导航       navActive navDist navUnit navStreet navTurn
 *   媒体       mediaTitle mediaArtist mediaPlaying
 *   时间       取 System.currentTimeMillis()
 *
 * ── 动画 ──────────────────────────────────────────────────────
 *
 * t 是累计秒数（float），随帧递增。需要做呼吸灯、闪烁、指针缓动
 * 之类的动画时用它做相位。不要用 System.nanoTime 自己算 ——
 * t 是按 DashView 实际步进积分的，跟帧率解耦。
 */
public interface DashSkin {

    /**
     * 画一帧。
     *
     * @param c    画布（已缩放+平移+clip，坐标系 1920×532）
     * @param data 当前车辆数据（只读，不要修改）
     * @param theme 配色/字体（同上，只读）
     * @param t    累计时间（秒），用于动画相位
     */
    void draw(Canvas c, HudData data, DashTheme theme, float t);

    /** 皮肤名（App 里选皮肤时显示） */
    default String name() { return "默认"; }
}
