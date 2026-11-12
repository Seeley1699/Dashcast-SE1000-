package com.hud.dash;

import android.graphics.RectF;

/** 设计稿坐标 1920 × 538，全部卡片位置集中在此 */
public final class HudLayout {
    private HudLayout() {}

    public static final float W = 1920f;
    public static final float H = 532f;

    // 左列
    public static final RectF GAUGE  = new RectF(16f, 16f, 528f, 404f);
    public static final RectF EV_CARD  = new RectF(16f, 412f, 268f, 516f);
    public static final RectF FU_CARD  = new RectF(276f, 412f, 528f, 516f);

    // 中列
    public static final RectF RAIL  = new RectF(544f, 16f, 1408f, 56f);
    public static final RectF MAP   = new RectF(544f, 64f, 1408f, 446f);
    public static final RectF STRIP = new RectF(544f, 452f, 1408f, 516f);

    // 右列
    public static final RectF CLOCK = new RectF(1424f, 16f, 1904f, 108f);
    public static final RectF MEDIA = new RectF(1424f, 120f, 1904f, 446f);
    public static final RectF GEAR  = new RectF(1424f, 452f, 1904f, 516f);

    // 表盘几何：量程 220，超 120 整盘转红
    public static final float CX = 272f, CY = 196f, R = 160f;
    public static final float A0 = -122f, A1 = 122f;
    public static final float MAX = 220f, OVER = 120f;
    public static final float RING = R + 12f;

    // 地图虚拟坐标系（cover 缩放）
    public static final float MAP_W = 1040f, MAP_H = 520f;
}
