package com.hud.dash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;

import static com.hud.dash.HudLayout.A0;
import static com.hud.dash.HudLayout.A1;
import static com.hud.dash.HudLayout.CX;
import static com.hud.dash.HudLayout.CY;
import static com.hud.dash.HudLayout.H;
import static com.hud.dash.HudLayout.MAX;
import static com.hud.dash.HudLayout.OVER;
import static com.hud.dash.HudLayout.R;
import static com.hud.dash.HudLayout.RING;
import static com.hud.dash.HudTheme.*;

/**
 * 渲染内核（Java 版）。Paint / Path / RectF 全部成员复用，每帧零分配。
 * 全部 10 个模块已从 Kotlin 迁移完成：背景 / 速度卡（圆盘+NOA+功率+限速）/
 * 续航卡 / 灯光栏 / 地图（路网+路线+车道+自车）/ 横条（导航+行程+胎压）/
 * 时钟 / 媒体 / 档位 / 车道引导。数据契约与交接文档 §4 一致。
 */
class DashRenderer {

    private DashView view;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        // 关键：line 只做描边。Java Paint 默认 FILL，漏了这句弧线/描边会全部变成填充块
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final Path p = new Path();
    private final RectF rf = new RectF();
    private final RectF rf2 = new RectF();

    private float t = 0f;

    void attach(DashView v) { view = v; }

    /*
     * ══════════════════════════════════════════════════════════════
     * **主题访问器**（2026-11-12，DashTheme 重构第二步）
     * ══════════════════════════════════════════════════════════════
     *
     * 第一步（commit 11fb954）只把接口建起来，DashRenderer 仍在用
     * 91 处硬编码 0xFF... 和 54 处 HudTheme 常量。
     *
     * 这一步开始把引用换成 theme.xxx()。为了**每批改动都能编译、
     * 能 dump 对比**，theme() 在 view 未挂载时回落到一个默认实例，
     * 保证任何调用点都不会 NPE。
     *
     * 关键约束：**换引用时取值必须与原常量逐一相等**，
     * 否则就不是重构而是改配色 —— 那要等阶段 2 单独做、单独验证。
     */
    private static final DashTheme DEFAULT_THEME = new DashTheme() {};

    DashTheme theme() {
        if (view != null && view.getTheme() != null) return view.getTheme();
        return DEFAULT_THEME;
    }

    void draw(Canvas c, float time) {
        t = time;
        drawBackdrop(c);
        drawSpeedo(c);
        drawRangeCard(c, HudLayout.EV_CARD, true);
        drawRangeCard(c, HudLayout.FU_CARD, false);
        drawLightRail(c);
        drawMapPanel(c);
        drawStrip(c);
        drawClockCard(c);
        drawMediaCard(c);
        drawGearCard(c);
        drawBuildTag(c);
    }

    /* ------------------------------ 背景 ------------------------------ */

    private void drawBackdrop(Canvas c) {
        fill.setShader(new LinearGradient(
                0, 0, 0, H,
                new int[]{0xFF0A1113, 0xFF070C10, 0xFF04080A},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, HudLayout.W, H, fill);
        fill.setShader(null);

        drawGlow(c, CX, -40f, 760f, theme().accent(), 0.075f);
        drawGlow(c, 1664f, H + 40f, 620f, theme().accent(), 0.05f);
    }

    private void drawBuildTag(Canvas c) {
        txt(c, DashView.BUILD_ID, 8f, H - 6f, 9f, rgba(0xFFFFFF, 0.22f),
                Paint.Align.LEFT, 0f, Typeface.DEFAULT);
    }

    /* ------------------------------ 基础绘制 ------------------------------ */

    /** 面板底：圆角 + 渐变 + 描边 + 顶部高光 */
    void panel(Canvas c, RectF rect, float radius) {
        p.reset();
        p.addRoundRect(rect, radius, radius, Path.Direction.CW);

        fill.setShader(new LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                new int[]{0xFF121E23, 0xFF0B1418},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP));
        c.drawPath(p, fill);
        fill.setShader(null);

        line.setShader(null);
        line.setStrokeWidth(1f);
        line.setColor(rgba(0xFFFFFF, 0.06f));
        c.drawPath(p, line);

        line.setColor(rgba(0xFFFFFF, 0.05f));
        c.drawLine(rect.left + radius, rect.top + 0.5f, rect.right - radius, rect.top + 0.5f, line);
    }

    /** 圆弧：设计角度（0° = 正上方）→ Android 角度（0° = 3 点方向） */
    void arcStroke(Canvas c, float cx, float cy, float radius, float a0, float a1) {
        c.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, a0 - 90f, a1 - a0, false, line);
    }

    /*
     * ══════════════════════════════════════════════════════════════
     * 字号全局缩放（2026-11-12 用户要求「字体太小看不见」）
     * ══════════════════════════════════════════════════════════════
     *
     * **为什么在 txt()/measure() 里缩放，而不是改 53 处调用**
     *
     * 全项目只有这两个方法会设置字号（已核对：setTextSize 只出现在这里）。
     * 在入口处乘一个系数，等于所有文字同步放大，且**坐标不用动** ——
     * 因为放大是围绕各自的对齐基准点（baseline + align）来的，
     * 位置参数 x/y 保持不变，视觉上就是字变大了。
     *
     * 但也正因为坐标不动，放大到一定程度会互相压字。所以系数不能太大，
     * 1.28 是实测下来卡片内还放得下的上限：
     *   · 续航卡高 104px，标签 16px → 20.5px，仍有余量
     *   · 灯光栏 7 个图标，图标内 "A" 8.5px → 10.9px，仍在圆内
     *   · 胎压行 4 组，16px → 20.5px，行高够
     *
     * **配合 layer 99 从 528×416 放大到 1280×532**（见 LayerConfig），
     * 实际显示尺寸的变化：
     *   改前：528/1920 = 0.275   11px → 3.0px（看不见）
     *   改后：1280/1920 = 0.667  11px × 1.28 → 9.4px（看得清）
     *   综合改善 3.1 倍
     *
     * 想再调就改这一个数字，不用翻 53 处。
     */
    static final float TEXT_SCALE = 1.28f;

    void txt(Canvas c, String s, float x, float y, float size, int color,
             Paint.Align align, float ls, Typeface tf) {
        /*
         * size <= 0 是「别画」的约定（调用方用 0 表示隐藏），
         * 别让它被乘成 0 之后又误画。
         */
        float sz = size > 0f ? size * TEXT_SCALE : size;
        text.setTypeface(tf);
        text.setTextSize(sz);
        text.setColor(color);
        text.setTextAlign(align);
        /*
         * 字间距按**缩放后的字号**折算，保持视觉比例一致 ——
         * 否则字变大而间距没变，密度会显得更挤。
         */
        text.setLetterSpacing(sz > 0f ? ls / sz : 0f);
        text.setFontFeatureSettings("tnum");
        c.drawText(s, x, y, text);
    }

    /**
     * 量文字宽度。
     *
     * **必须跟着 TEXT_SCALE 一起缩**：否则排版算出来的宽度偏小，
     * 该换行/该省略的地方会算错，右边会超出去。
     * 实测影响：媒体卡的标题省略（measure + ellipsis）、
     * 续航卡的数字右对齐、胎压四列的列宽分配。
     */
    float measure(String s, float size, Typeface tf) {
        float sz = size > 0f ? size * TEXT_SCALE : size;
        text.setTypeface(tf);
        text.setTextSize(sz);
        text.setLetterSpacing(0f);
        return text.measureText(s);
    }

    /* ------------------------------ 光晕 ------------------------------ */

    /**
     * 硬件加速下 BlurMaskFilter 无效、setShadowLayer 对图形无效，
     * 光晕全部走预渲染贴图：同色只生成一次，之后只 drawBitmap。
     */
    private final java.util.HashMap<Integer, Bitmap> glowCache = new java.util.HashMap<>();

    private Bitmap glowBitmap(int color) {
        Bitmap b = glowCache.get(color);
        if (b != null) return b;
        int size = 128;
        Bitmap bmpNew = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(bmpNew);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setShader(new RadialGradient(
                size / 2f, size / 2f, size / 2f,
                new int[]{rgba(color, 0.9f), rgba(color, 0.35f), rgba(color, 0f)},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        bc.drawCircle(size / 2f, size / 2f, size / 2f, bp);
        glowCache.put(color, bmpNew);
        return bmpNew;
    }

    void drawGlow(Canvas c, float x, float y, float radius, int color, float alpha) {
        bmp.setAlpha(Math.round(HudTheme.clamp(alpha, 0f, 1f) * 255f));
        rf2.set(x - radius, y - radius, x + radius, y + radius);
        c.drawBitmap(glowBitmap(color), null, rf2, bmp);
        bmp.setAlpha(255);
    }

    /** 线条光晕：三遍加宽描边（3.2× @10% / 2× @16% / 1× 实色） */
    void glowArc(Canvas c, float cx, float cy, float radius, float a0, float a1, int color, float width) {
        line.setStrokeWidth(width * 3.2f);
        line.setColor(rgba(color, 0.10f));
        arcStroke(c, cx, cy, radius, a0, a1);
        line.setStrokeWidth(width * 2.0f);
        line.setColor(rgba(color, 0.16f));
        arcStroke(c, cx, cy, radius, a0, a1);
        line.setStrokeWidth(width);
        line.setColor(color);
        arcStroke(c, cx, cy, radius, a0, a1);
    }

    /* ============================== 速度卡 ============================== */

    private void drawSpeedo(Canvas c) {
        HudData d = view.data;
        panel(c, HudLayout.GAUGE, 20f);

        float r = R;
        float sim = view.sim;
        float hot = HudTheme.clamp((sim - OVER) / 4f, 0f, 1f);

        // 超速时整盘染色
        int colTrack = lerpColor(0xFF13242B, 0xFF341A1C, hot);
        int colMajor = lerpColor(0xFF7A9096, 0xFFC46A6A, hot);
        int colMinor = lerpColor(0xFF33474E, 0xFF5E2C2E, hot);
        int colLabel = lerpColor(0xFF63787D, 0xFFD98C8C, hot);
        int colInner = lerpColor(0xFF8A9A9E, 0xFFB06A6A, hot);

        // 轨道
        line.setShader(null);
        line.setStrokeWidth(10f);
        line.setColor(colTrack);
        arcStroke(c, CX, CY, r, A0, A1);

        line.setStrokeWidth(1f);
        line.setColor(rgba(colInner, 0.9f));
        arcStroke(c, CX, CY, r - 30f, A0, A1);

        // 刻度：外端让出弧线空间，指针尖端抵住刻度根部
        for (int v = 0; v <= (int) MAX; v += 10) {
            float a = A0 + (A1 - A0) * (v / MAX);
            boolean major = v % 20 == 0;
            boolean isThr = v == (int) OVER;
            float len = major ? 14f : 6f;
            float x1 = polarX(CX, r - 8f, a);
            float y1 = polarY(CY, r - 8f, a);
            float x2 = polarX(CX, r - 8f - len, a);
            float y2 = polarY(CY, r - 8f - len, a);

            if (isThr) line.setColor(hot > 0.5f ? 0xFFFFFFFF : rgba(theme().danger(), 0.85f));
            else if (major) line.setColor(colMajor);
            else line.setColor(colMinor);
            line.setStrokeWidth(isThr ? 3f : major ? 2.2f : 1.3f);
            c.drawLine(x1, y1, x2, y2, line);

            if (isThr) drawGlow(c, (x1 + x2) / 2f, (y1 + y2) / 2f, 14f, theme().danger(), 0.35f);

            if (major) {
                float lx = polarX(CX, r - 40f, a);
                float ly = polarY(CY, r - 40f, a);
                int col = isThr
                        ? (hot > 0.5f ? 0xFFFFFFFF : rgba(0xFFFF8C8C, 0.95f))
                        : colLabel;
                txt(c, String.valueOf(v), lx, ly + 4f, 11.5f, col, Paint.Align.CENTER, 0.4f, NUM);
            }
        }

        // 进度弧：整条随 hot 染色
        float frac = HudTheme.clamp(sim / MAX, 0f, 1f);
        float aNow = A0 + (A1 - A0) * frac;
        if (frac > 0f) {
            line.setShader(new LinearGradient(
                    CX - r, CY + r, CX + r, CY - r,
                    new int[]{
                            lerpColor(0xFF1E8E7C, 0xFFC43C3C, hot),
                            lerpColor(theme().accent(), 0xFFFF7B7B, hot),
                            lerpColor(theme().accentLight(), 0xFFFFD9D9, hot)},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            line.setStrokeWidth(10f);
            line.setStrokeCap(Paint.Cap.ROUND);
            arcStroke(c, CX, CY, r, A0, aNow);
            line.setShader(null);
        }

        if (d.noa) drawNoaRing(c, aNow, hot);
        drawNeedle(c, aNow, hot);

        // 数字车速
        txt(c, String.valueOf(Math.round(sim)), CX, CY + 98f, 62f,
                hot > 0.5f ? 0xFFFF9E9E : 0xFFF0FBFA, Paint.Align.CENTER, -2f, NUM_MED);
        txt(c, "km / h", CX, CY + 121f, 11f,
                hot > 0.5f ? 0xFFB06060 : theme().textFaint(), Paint.Align.CENTER, 3.6f, NUM);

        float barY = HudLayout.GAUGE.bottom - 20f;
        drawNoaBadge(c, barY);
        drawPowerBar(c, barY);
        drawLimitRing(c, barY);
    }

    /* ------------------------------ NOA 外环 ------------------------------ */

    private void drawNoaRing(Canvas c, float aNow, float hot) {
        float ringR = RING;
        HudData d = view.data;
        int ringCol = hot > 0.5f ? theme().danger() : theme().accent();

        // 底环
        line.setShader(null);
        line.setStrokeWidth(2.5f);
        line.setColor(rgba(theme().accent(), 0.10f));
        arcStroke(c, CX, CY, ringR, A0, A1);

        // 彗尾
        if (aNow - A0 > 4f) {
            float tail = Math.max(A0, aNow - 30f);
            glowArc(c, CX, CY, ringR, tail, aNow, rgba(ringCol, 0.62f), 2.5f);
            drawGlow(c, polarX(CX, ringR, aNow), polarY(CY, ringR, aNow), 12f, ringCol, 0.8f);
        }

        // 设定车速标记
        float setA = A0 + (A1 - A0) * (d.noaSpeed / MAX);
        float sx = polarX(CX, ringR, setA);
        float sy = polarY(CY, ringR, setA);
        fill.setColor(0xFF0A1114);
        c.drawCircle(sx, sy, 5f, fill);
        fill.setColor(hot > 0.5f ? theme().danger() : theme().accent());
        c.drawCircle(sx, sy, 3f, fill);

        // 接管闪环
        float pulse = view.noaPulseAt;
        if (pulse >= 0f) {
            float pr = (t - pulse) / 0.45f;
            if (pr >= 1f) {
                view.noaPulseAt = -1f;
            } else {
                float a = 1f - pr;
                glowArc(c, CX, CY, ringR, A0, A1, rgba(theme().accent(), 0.55f * a), 3.5f);
            }
        }
    }

    /* ------------------------------ 指针 ------------------------------ */

    private void drawNeedle(Canvas c, float ang, float hot) {
        float r = R;
        int tipCol = lerpColor(0xFFEAFFFA, 0xFFFFE2E2, hot);
        int midCol = lerpColor(theme().accent(), theme().danger(), hot);
        int baseCol = lerpColor(0xFF123A38, 0xFF3A1414, hot);

        c.save();
        c.rotate(ang, CX, CY);

        p.reset();
        p.moveTo(CX, CY - r + 10f);
        p.lineTo(CX + 5.4f, CY - 6f);
        p.lineTo(CX + 2.6f, CY + 20f);
        p.lineTo(CX - 2.6f, CY + 20f);
        p.lineTo(CX - 5.4f, CY - 6f);
        p.close();

        drawGlow(c, CX, CY - r * 0.62f, 26f, hot > 0.5f ? theme().danger() : theme().accent(), 0.22f);

        line.setShader(new LinearGradient(
                CX, CY - r + 6f, CX, CY + 20f,
                new int[]{tipCol, midCol, baseCol},
                new float[]{0f, 0.62f, 1f},
                Shader.TileMode.CLAMP));
        fill.setShader(line.getShader());
        c.drawPath(p, fill);
        fill.setShader(null);
        line.setShader(null);

        line.setStrokeWidth(1f);
        line.setColor(rgba(0xFFFFFF, 0.18f));
        c.drawLine(CX - 2.2f, CY - r + 10f, CX - 1.2f, CY - 8f, line);
        c.restore();

        fill.setColor(0xFF0A1114);
        c.drawCircle(CX, CY, 11f, fill);
        line.setStrokeWidth(1.2f);
        line.setColor(hot > 0.5f ? 0xFF5A2020 : 0xFF2B424A);
        c.drawCircle(CX, CY, 11f, line);
        fill.setColor(lerpColor(theme().accent(), theme().danger(), hot));
        c.drawCircle(CX, CY, 4f, fill);
        fill.setColor(0xFF0A1114);
        c.drawCircle(CX, CY, 1.6f, fill);
    }

    /* ------------------------------ 底栏：NOA + 功率 + 限速 ------------------------------ */

    private void drawNoaBadge(Canvas c, float barY) {
        HudData d = view.data;
        boolean on = d.noa;
        int col = on ? theme().accent() : 0xFFE4EFEF;

        float br = on ? 0.55f + 0.45f * absf(sinf(t * 2.2f)) : 1f;
        fill.setColor(on ? rgba(theme().accent(), br) : rgba(0xFFE6F1F1, 0.42f));
        c.drawCircle(27f, barY, 3f, fill);
        if (on) drawGlow(c, 27f, barY, 16f, theme().accent(), 0.5f * br);

        txt(c, d.noaLabel, 38f, barY + 5f, 15f, col, Paint.Align.LEFT, 2.2f, NUM_MED);
        float labelW = measure(d.noaLabel, 15f, NUM_MED);

        float bx = 38f + labelW + 10f;
        float by = barY - 13f;
        float bw = 40f;
        float bh = 26f;
        p.reset();
        p.addRoundRect(bx, by, bx + bw, by + bh, 9f, 9f, Path.Direction.CW);
        fill.setShader(new LinearGradient(
                bx, by, bx, by + bh,
                on ? rgba(theme().accent(), 0.24f) : rgba(0xFFEBF5F5, 0.08f),
                on ? rgba(theme().accent(), 0.06f) : rgba(0xFFEBF5F5, 0.02f),
                Shader.TileMode.CLAMP));
        c.drawPath(p, fill);
        fill.setShader(null);

        line.setShader(null);
        line.setStrokeWidth(1.5f);
        line.setColor(on ? rgba(theme().accent(), 0.9f) : rgba(0xFFE8F2F2, 0.42f));
        c.drawPath(p, line);

        txt(c, String.valueOf(d.noaSpeed), bx + bw / 2f, by + bh / 2f + 5.5f, 15f,
                on ? 0xFFE8FFFA : 0xFFDDE9E9, Paint.Align.CENTER, 0.6f, NUM_MED);
    }

    private void drawPowerBar(Canvas c, float barY) {
        HudData d = view.data;
        float x0 = CX - 132f;
        float x1 = CX + 56f;
        float ty = barY - 6f;
        float th = 12f;
        float valKw = HudTheme.clamp(view.pow, 0f, d.powerMax);
        float frac = valKw / d.powerMax;

        // 读数与底栏同基线，放在管身右侧
        txt(c, Math.round(valKw) + " kW", 410f, barY + 5f, 11f,
                frac > 0.85f ? theme().dangerLight() : 0xFFCFE1E1, Paint.Align.RIGHT, 0.8f, NUM_MED);

        // 槽体
        rf.set(x0, ty, x1, ty + th);
        p.reset();
        p.addRoundRect(rf, th / 2f, th / 2f, Path.Direction.CW);
        fill.setColor(0xFF0F191D);
        c.drawPath(p, fill);
        line.setStrokeWidth(1f);
        line.setColor(rgba(0xFF78AAB4, 0.18f));
        c.drawPath(p, line);

        // 填充：整条渐变按位置取色（青 → 黄 → 红）
        float fw = (x1 - x0) * frac;
        if (fw > 1f) {
            c.save();
            c.clipPath(p);
            line.setShader(new LinearGradient(
                    x0, 0, x0 + (x1 - x0), 0,
                    new int[]{theme().accentDark(), theme().accent(), 0xFFFFC93C, theme().danger()},
                    new float[]{0f, 0.45f, 0.75f, 1f},
                    Shader.TileMode.CLAMP));
            line.setStrokeWidth(th);
            line.setColor(0xFFFFFFFF);
            c.drawLine(x0, ty + th / 2f, x0 + fw, ty + th / 2f, line);
            line.setShader(null);
            c.restore();
        }

        // 球泡
        float bulbX = x0 + 6f;
        fill.setColor(0xFF0F191D);
        c.drawCircle(bulbX, ty + th / 2f, 10f, fill);
        line.setStrokeWidth(1f);
        line.setColor(rgba(0xFF78AAB4, 0.18f));
        c.drawCircle(bulbX, ty + th / 2f, 10f, line);

        int bulbCol = frac > 0.86f ? theme().danger() : frac > 0.62f ? theme().warn() : theme().accent();
        fill.setColor(bulbCol);
        c.drawCircle(bulbX, ty + th / 2f, 5f, fill);
        drawGlow(c, bulbX, ty + th / 2f, 16f, bulbCol, 0.5f);
    }

    private void drawLimitRing(Canvas c, float barY) {
        float lx = 466f, ly = barY, lr = 18f;
        fill.setColor(rgba(0xFF0C1316, 0.92f));
        c.drawCircle(lx, ly, lr, fill);
        line.setStrokeWidth(2.6f);
        line.setColor(rgba(0xFFE04840, 0.8f));
        c.drawCircle(lx, ly, lr, line);
        txt(c, "120", lx, ly + 4.4f, 12f, 0xFFEAF6F6, Paint.Align.CENTER, 0.5f, NUM_MED);
    }

    /* ============================== 续航卡 ============================== */

    private void drawRangeCard(Canvas c, RectF card, boolean isEv) {
        HudData d = view.data;
        panel(c, card, 20f);

        float range = isEv ? d.evRange : d.fuelRange;
        float cap = isEv ? d.evCap : d.fuelCap;
        float ratio = cap > 0f ? HudTheme.clamp(range / cap, 0f, 1f) : 0f;
        boolean low = ratio < 0.2f;
        boolean critical = ratio < 0.1f;

        int main, light, g0, g1;
        if (isEv) {
            if (critical) { main = theme().danger(); light = theme().dangerLight(); g0 = theme().dangerDark(); g1 = theme().danger(); }
            else if (low) { main = theme().warn(); light = theme().warnLight(); g0 = theme().warnDark(); g1 = theme().warn(); }
            else { main = theme().evColor(); light = theme().evLight(); g0 = theme().evDark(); g1 = theme().evColor(); }
        } else {
            if (low) { main = theme().danger(); light = theme().dangerLight(); g0 = theme().dangerDark(); g1 = theme().danger(); }
            else { main = theme().fuelColor(); light = theme().fuelLight(); g0 = theme().fuelDark(); g1 = theme().fuelColor(); }
        }

        // 告警描边
        if (low) {
            rf.set(card.left + 1.5f, card.top + 1.5f, card.right - 1.5f, card.bottom - 1.5f);
            p.reset();
            p.addRoundRect(rf, 18.5f, 18.5f, Path.Direction.CW);
            line.setStrokeWidth(1.5f);
            line.setColor(rgba(main, critical ? 0.45f : 0.26f));
            c.drawPath(p, line);
        }

        // 色晕
        c.save();
        rf.set(card.left + 1f, card.top + 1f, card.right - 1f, card.bottom - 1f);
        p.reset();
        p.addRoundRect(rf, 19f, 19f, Path.Direction.CW);
        c.clipPath(p);
        fill.setShader(new RadialGradient(
                card.left + 24f, card.top + 6f, 230f,
                new int[]{rgba(main, low ? 0.13f : 0.09f), rgba(main, 0.02f), rgba(main, 0f)},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(card, fill);
        fill.setShader(null);
        c.restore();

        // 能量色边条：左缘 3px 主色竖条
        rf.set(card.left + 1f, card.top + 10f, card.left + 4f, card.bottom - 10f);
        p.reset();
        p.addRoundRect(rf, 1.5f, 1.5f, Path.Direction.CW);
        fill.setColor(rgba(main, low ? 0.95f : 0.55f));
        c.drawPath(p, fill);

        // 图标胶囊：36×36
        float ts = 36f;
        float ix = card.left + 12f;
        float iy = card.top + 16f;
        rf.set(ix, iy, ix + ts, iy + ts);
        p.reset();
        p.addRoundRect(rf, 10f, 10f, Path.Direction.CW);
        fill.setShader(new LinearGradient(ix, iy, ix + ts, iy + ts,
                rgba(main, 0.18f), rgba(main, 0.035f), Shader.TileMode.CLAMP));
        c.drawPath(p, fill);
        fill.setShader(null);
        line.setStrokeWidth(1f);
        line.setColor(rgba(main, 0.26f));
        c.drawPath(p, line);

        // 图标：以胶囊中心为原点
        c.save();
        c.translate(ix + ts / 2f, iy + ts / 2f);
        fill.setColor(main);
        line.setColor(main);
        line.setStrokeWidth(1.6f);
        if (isEv) {
            p.reset();
            p.moveTo(1.6f, -8f);
            p.lineTo(-4.4f, 1f);
            p.lineTo(0f, 1f);
            p.lineTo(-1.6f, 8f);
            p.lineTo(4.4f, -1f);
            p.lineTo(0f, -1f);
            p.close();
            c.drawPath(p, fill);
        } else {
            p.reset();
            p.moveTo(0f, -8.5f);
            p.cubicTo(5f, -2.5f, 7f, 0.8f, 7f, 3.4f);
            p.cubicTo(7f, 7.2f, 3.9f, 8.5f, 0f, 8.5f);
            p.cubicTo(-3.9f, 8.5f, -7f, 7.2f, -7f, 3.4f);
            p.cubicTo(-7f, 0.8f, -5f, -2.5f, 0f, -8.5f);
            p.close();
            c.drawPath(p, fill);
        }
        c.restore();

        // 名称与副标题（满值信息已删）
        int pct = Math.round(ratio * 100f);
        txt(c, isEv ? "纯电续航" : "燃油续航", card.left + 56f, card.top + 42f, 16f,
                light, Paint.Align.LEFT, 1.8f, NUM_MED);
        txt(c, low ? "低于 20% · 请尽快补充" : "剩余 " + pct + "%",
                card.left + 56f, card.top + 66f, 10f,
                low ? rgba(main, 0.95f) : 0xFF8FA5AA, Paint.Align.LEFT, 1.2f, CN);

        // 数值
        float right = card.right - 12f;
        txt(c, "km", right, card.top + 50f, 10f, 0xFF9DB2B7, Paint.Align.RIGHT, 0.8f, CN);
        float kmW = measure("km", 10f, CN);
        txt(c, String.valueOf((int) range), right - kmW - 6f, card.top + 50f, 30f,
                light, Paint.Align.RIGHT, -0.5f, NUM_MED);

        // 进度条
        float by = card.top + 80f;
        float bh = 9f;
        float bx = card.left + 14f;
        float bw = card.width() - 28f;

        rf.set(bx, by, bx + bw, by + bh);
        p.reset();
        p.addRoundRect(rf, bh / 2f, bh / 2f, Path.Direction.CW);
        fill.setColor(0xFF0A1114);
        c.drawPath(p, fill);
        line.setStrokeWidth(1f);
        line.setColor(rgba(0xFFFFFF, 0.055f));
        c.drawPath(p, line);

        float fw = bw * ratio;
        if (fw > 1f) {
            c.save();
            c.clipPath(p);
            line.setShader(new LinearGradient(bx, 0, bx + fw, 0, g0, g1, Shader.TileMode.CLAMP));
            line.setStrokeWidth(bh);
            line.setColor(0xFFFFFFFF);
            c.drawLine(bx, by + bh / 2f, bx + fw, by + bh / 2f, line);
            line.setShader(null);

            fill.setShader(new LinearGradient(0, by, 0, by + bh,
                    new int[]{rgba(0xFFFFFF, 0.30f), rgba(0xFFFFFF, 0.04f), rgba(0x000000, 0.14f)},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawRect(bx, by, bx + fw, by + bh, fill);
            fill.setShader(null);
            c.restore();

            drawGlow(c, bx + fw, by + bh / 2f, 18f, main, low ? 0.45f : 0.35f);
        }

        // 20% 阈值刻度
        float tx = bx + bw * 0.2f;
        line.setStrokeWidth(1.5f);
        line.setColor(rgba(0xFFFFFF, 0.18f));
        c.drawLine(tx, by - 2f, tx, by + bh + 2f, line);
    }

    /* ============================== 灯光栏 ============================== */

    private static final String[] LIGHT_KINDS = {"left", "auto", "low", "high", "hold", "fogR", "right"};

    /*
     * **从 static final 改成实例字段**（2026-11-12，DashTheme 重构第二步）。
     *
     * 这个数组以前是 `static final int[]`，初始化时直接写死 HudTheme 常量。
     * 换引用后要调 theme()，而 theme() 依赖 view 字段（实例状态），
     * 静态初始化阶段没有 this —— 编译器直接报「无法从静态上下文引用非静态方法」。
     *
     * 改成实例字段后：
     *   · 保留 final（仍是不可变引用，每个 renderer 实例初始化一次）
     *   · 初始化时机从「类加载」推迟到「实例构造」，那时 view 可能还是 null，
     *     theme() 会回落到 DEFAULT_THEME —— 但 drawLightRail 每帧都读
     *     lightCols()，所以真正生效的是渲染时的主题。
     *
     * 更稳的做法是每次取值，见 lightCols()。数组保留只为少建临时对象。
     */
    private int[] LIGHT_COLS_CACHE;

    /** 灯光栏配色 —— 每次按当前主题现算，保证 setTheme() 后立即生效 */
    private int[] lightCols() {
        if (LIGHT_COLS_CACHE == null) {
            LIGHT_COLS_CACHE = new int[]{
                    theme().amber(), theme().accent(), theme().textSecondary(),
                    theme().highBeam(), theme().accent(), theme().danger(), theme().amber()
            };
        }
        return LIGHT_COLS_CACHE;
    }

    /** 主题切换后清缓存（由 attach / 外部 setTheme 触发） */
    void invalidateThemeCache() { LIGHT_COLS_CACHE = null; }

    private void drawLightRail(Canvas c) {
        HudData d = view.data;
        RectF rail = HudLayout.RAIL;
        panel(c, rail, 20f);

        float cy = rail.centerY();
        boolean blink = ((int) (t * 1.7f) % 2) == 0;
        boolean hazard = d.lightHazard;

        boolean[] states = {
                (hazard || d.lightLeft) && blink,
                d.lightAuto,
                d.lightLow,
                d.lightHigh,
                d.lightHold,
                d.lightFogR,
                (hazard || d.lightRight) && blink
        };

        float gap = 96f;
        float startX = rail.left + rail.width() / 2f - gap * (LIGHT_KINDS.length - 1) / 2f;

        for (int i = 0; i < LIGHT_KINDS.length; i++) {
            String kind = LIGHT_KINDS[i];
            int color = lightCols()[i];
            float x = startX + gap * i;
            boolean on = states[i];
            int col = on ? color : 0xFF35484E;

            if (on) {
                rf.set(x - 19f, cy - 15f, x + 19f, cy + 15f);
                p.reset();
                p.addRoundRect(rf, 10f, 10f, Path.Direction.CW);
                fill.setColor(rgba(color, 0.10f));
                c.drawPath(p, fill);
                line.setStrokeWidth(1f);
                line.setColor(rgba(color, 0.18f));
                c.drawPath(p, line);
                drawGlow(c, x, cy, 26f, color, 0.24f);
            }

            c.save();
            c.translate(x, cy);
            c.scale(1.2f, 1.2f);
            drawLightIcon(c, 0f, 0f, kind, col);
            c.restore();
        }
    }

    private void drawLightIcon(Canvas c, float cx, float cy, String kind, int color) {
        c.save();
        c.translate(cx, cy);
        line.setShader(null);
        fill.setShader(null);
        line.setColor(color);
        fill.setColor(color);
        line.setStrokeWidth(2f);

        if (kind.equals("left") || kind.equals("right")) {
            float s = kind.equals("left") ? -1f : 1f;
            p.reset();
            p.moveTo(10f * s, 0f);
            p.lineTo(-1f * s, -8f);
            p.lineTo(-1f * s, -3f);
            p.lineTo(-9f * s, -3f);
            p.lineTo(-9f * s, 3f);
            p.lineTo(-1f * s, 3f);
            p.lineTo(-1f * s, 8f);
            p.close();
            c.drawPath(p, fill);
            c.restore();
            return;
        }

        if (kind.equals("hold")) {
            line.setStrokeWidth(2f);
            c.drawCircle(0f, 0f, 8f, line);
            c.drawArc(-11.6f, -11.6f, 11.6f, 11.6f, -25f, 50f, false, line);
            c.drawArc(-11.6f, -11.6f, 11.6f, 11.6f, 155f, 50f, false, line);
            txt(c, "A", 0f, 4f, 9.5f, color, Paint.Align.CENTER, 0f, NUM_BOLD);
            c.restore();
            return;
        }

        // 灯碗
        rf.set(-6.5f, -8.5f, 4.5f, 8.5f);
        c.save();
        c.rotate(-17f);
        c.drawOval(rf, line);
        c.restore();

        if (kind.equals("auto")) {
            txt(c, "A", -1f, 3.5f, 8.5f, color, Paint.Align.CENTER, 0f, NUM_BOLD);
            c.drawLine(8f, -5f, 12f, -5f, line);
            c.drawLine(9f, 0f, 13f, 0f, line);
            c.drawLine(8f, 5f, 12f, 5f, line);
        } else if (kind.equals("high")) {
            c.drawLine(8f, -6f, 15f, -6f, line);
            c.drawLine(9f, 0f, 16f, 0f, line);
            c.drawLine(8f, 6f, 15f, 6f, line);
        } else if (kind.equals("low")) {
            c.drawLine(7f, -5f, 13f, 1f, line);
            c.drawLine(8f, 0.5f, 14f, 6f, line);
            c.drawLine(7f, 5f, 13f, 11f, line);
        } else { // fogR
            c.drawLine(7f, -5f, 12f, 0f, line);
            c.drawLine(8f, 1f, 13f, 6f, line);
            line.setStrokeWidth(1.8f);
            c.drawLine(-6f, 11f, 10f, 11f, line);
        }
        c.restore();
    }

    /* ============================== 时钟卡 ============================== */

    private static final String[] WEEK_DAYS = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    private void drawClockCard(Canvas c) {
        RectF card = HudLayout.CLOCK;
        panel(c, card, 20f);

        java.util.Calendar cal = java.util.Calendar.getInstance();
        String hh = pad2(cal.get(java.util.Calendar.HOUR_OF_DAY));
        String mm = pad2(cal.get(java.util.Calendar.MINUTE));
        String wd = WEEK_DAYS[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1];
        HudData d = view.data;

        txt(c, hh + ":" + mm, card.left + 20f, card.top + 54f, 44f, theme().textPrimary(), Paint.Align.LEFT, 2f, NUM);
        txt(c, wd + " · " + (cal.get(java.util.Calendar.MONTH) + 1) + "月" + cal.get(java.util.Calendar.DAY_OF_MONTH)
                        + "日 · 车内 " + d.indoor,
                card.left + 22f, card.top + 76f, 11.5f, theme().textFaint(), Paint.Align.LEFT, 2f, CN);

        txt(c, d.outdoor, card.right - 20f, card.top + 46f, 26f, theme().textPrimary(), Paint.Align.RIGHT, 1f, NUM_MED);
        txt(c, d.weather, card.right - 20f, card.top + 66f, 11f, theme().textFaint(), Paint.Align.RIGHT, 1.6f, CN);
    }

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static boolean intArrayContains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }

    /* ============================== 档位卡 ============================== */

    private void drawGearCard(Canvas c) {
        HudData d = view.data;
        RectF card = HudLayout.GEAR;
        panel(c, card, 20f);

        float cy = card.centerY();

        txt(c, d.gear, card.left + 34f, cy + 9f, 24f, theme().textPrimary(), Paint.Align.CENTER, 1f, NUM_MED);

        line.setStrokeWidth(1f);
        line.setColor(theme().line());
        c.drawLine(card.left + 58f, card.top + 14f, card.left + 58f, card.bottom - 14f, line);

        float padL = card.left + 70f;
        float padR = card.right - 16f;
        int n = Math.max(d.modes.length, 1);
        float gap = 8f;
        float cw = (padR - padL - gap * (n - 1)) / n;
        float ch = 26f;
        float top = cy - ch / 2f;

        for (int i = 0; i < d.modes.length; i++) {
            String m = d.modes[i];
            float x = padL + (cw + gap) * i;
            boolean on = m.equals(d.driveMode);

            rf.set(x, top, x + cw, top + ch);
            p.reset();
            p.addRoundRect(rf, 8f, 8f, Path.Direction.CW);

            if (on) {
                fill.setShader(new LinearGradient(x, top, x, top + ch,
                        rgba(theme().accent(), 0.24f), rgba(theme().accent(), 0.08f), Shader.TileMode.CLAMP));
            } else {
                fill.setColor(rgba(0xFFFFFF, 0.035f));
            }
            c.drawPath(p, fill);
            fill.setShader(null);

            line.setStrokeWidth(1f);
            line.setColor(on ? rgba(theme().accent(), 0.55f) : rgba(0xFF78AAB4, 0.16f));
            c.drawPath(p, line);

            if (on) drawGlow(c, x + cw / 2f, top + ch / 2f, 30f, theme().accent(), 0.16f);

            txt(c, m, x + cw / 2f, top + ch / 2f + 4.5f, 11.5f,
                    on ? theme().accentLight() : 0xFF6E8287, Paint.Align.CENTER, 1.8f, CN);
        }
    }

    /* ============================== 底部横条 ============================== */

    private void drawStrip(Canvas c) {
        HudData d = view.data;
        RectF r0 = HudLayout.STRIP;
        panel(c, r0, 20f);

        boolean navOn = d.navActive;
        boolean arrived = d.navEnd.equals("arrived");

        if (!navOn && !arrived) {
            drawTireStrip(c);
            return;
        }

        float cy = r0.centerY();
        float ts = 34f;
        float tx = r0.left + 18f;
        float ty = cy - ts / 2f;

        // 图标胶囊
        rf.set(tx, ty, tx + ts, ty + ts);
        p.reset();
        p.addRoundRect(rf, 10f, 10f, Path.Direction.CW);
        fill.setShader(new LinearGradient(tx, ty, tx + ts, ty + ts,
                rgba(theme().accent(), 0.18f), rgba(theme().accent(), 0.04f), Shader.TileMode.CLAMP));
        c.drawPath(p, fill);
        fill.setShader(null);
        line.setStrokeWidth(1f);
        line.setColor(rgba(theme().accent(), 0.28f));
        c.drawPath(p, line);

        float icx = tx + ts / 2f;
        float icy = cy;
        line.setShader(null);
        line.setColor(theme().accent());
        fill.setColor(theme().accent());
        if (navOn) {
            line.setStrokeWidth(2f);
            p.reset();
            p.moveTo(icx - 4f, icy + 5f);
            p.lineTo(icx - 4f, icy - 1f);
            p.quadTo(icx - 4f, icy - 4f, icx - 1f, icy - 4f);
            p.lineTo(icx + 5f, icy - 4f);
            c.drawPath(p, line);
            p.reset();
            p.moveTo(icx + 2f, icy - 7f);
            p.lineTo(icx + 6f, icy - 4f);
            p.lineTo(icx + 2f, icy - 1f);
            c.drawPath(p, line);
        } else {
            line.setStrokeWidth(1.8f);
            c.drawLine(icx - 7f, icy + 8f, icx - 3f, icy - 8f, line);
            c.drawLine(icx + 7f, icy + 8f, icx + 3f, icy - 8f, line);
            line.setStrokeWidth(1.4f);
            float yy = -8f;
            while (yy < 8f) {
                c.drawLine(icx, icy + yy, icx, icy + Math.min(yy + 3f, 8f), line);
                yy += 6f;
            }
        }

        // 主文字
        float textX = tx + ts + 14f;
        if (navOn) {
            txt(c, d.navDist, textX, cy - 2f, 22f, theme().textPrimary(), Paint.Align.LEFT, 0.5f, NUM_MED);
            float dw = measure(d.navDist, 22f, NUM_MED);
            txt(c, d.navUnit, textX + dw + 5f, cy - 2f, 11f, theme().textFaint(), Paint.Align.LEFT, 1f, CN);

            String prefix = d.navTurn + "转进入 ";
            txt(c, prefix, textX, cy + 16f, 11.5f, 0xFF93A9AE, Paint.Align.LEFT, 1.2f, CN);
            float tw = measure(prefix, 11.5f, CN);
            txt(c, d.navStreet, textX + tw + 2f, cy + 16f, 11.5f, theme().textSecondary(), Paint.Align.LEFT, 1.2f, CN);
        } else {
            txt(c, "本次行程", textX, cy - 2f, 16f, theme().textPrimary(), Paint.Align.LEFT, 1f, NUM_MED);
            txt(c, "平均车速 " + d.tripAvg + " km/h", textX, cy + 16f, 11f, theme().textFaint(), Paint.Align.LEFT, 1.2f, CN);
        }

        // 竖分隔
        line.setStrokeWidth(1f);
        line.setColor(theme().line());
        c.drawLine(r0.left + 320f, r0.top + 14f, r0.left + 320f, r0.bottom - 14f, line);

        String[][] cols;
        if (navOn) {
            cols = new String[][]{
                    {"剩余里程", d.navLeft, ""},
                    {"预计到达", d.navEta, ""},
                    {"剩余时间", d.navTime, ""}};
        } else {
            cols = new String[][]{
                    {"行驶里程", d.tripKm, "km"},
                    {"平均电耗", d.tripEnergy, "kWh/100km"},
                    {"平均油耗", d.tripFuel, "L/100km"},
                    {"行驶时间", d.tripMin, "分钟"}};
        }
        drawColumns(c, r0, cols);
    }

    /** 等分信息列：数值与小号单位作为整体居中 */
    private void drawColumns(Canvas c, RectF r0, String[][] cols) {
        float x0 = r0.left + 330f;
        float x1 = r0.right - 22f;
        float step = (x1 - x0) / cols.length;

        for (int i = 0; i < cols.length; i++) {
            String label = cols[i][0], value = cols[i][1], unit = cols[i][2];
            float ccx = x0 + step * (i + 0.5f);

            txt(c, label, ccx, r0.top + 26f, 10f, theme().textFaint(), Paint.Align.CENTER, 1.8f, CN);

            float vw = measure(value, 15f, NUM_MED);
            float uw = unit.isEmpty() ? 0f : measure(unit, 9.5f, CN) + 4f;
            float sx = ccx - (vw + uw) / 2f;

            txt(c, value, sx, r0.top + 47f, 15f, theme().textSecondary(), Paint.Align.LEFT, 0.6f, NUM_MED);
            if (!unit.isEmpty()) {
                txt(c, unit, sx + vw + 4f, r0.top + 47f, 9.5f, theme().textFaint(), Paint.Align.LEFT, 0.8f, CN);
            }

            if (i < cols.length - 1) {
                float dx = x0 + step * (i + 1);
                line.setStrokeWidth(1f);
                line.setColor(theme().line());
                c.drawLine(dx, r0.top + 16f, dx, r0.top + 48f, line);
            }
        }
    }

    /* ------------------------------ 胎压 ------------------------------ */

    private void drawTireStrip(Canvas c) {
        HudData d = view.data;
        RectF r0 = HudLayout.STRIP;
        float cy = r0.centerY();
        float ts = 34f;
        float tx = r0.left + 18f;
        float ty = cy - ts / 2f;

        rf.set(tx, ty, tx + ts, ty + ts);
        p.reset();
        p.addRoundRect(rf, 10f, 10f, Path.Direction.CW);
        fill.setShader(new LinearGradient(tx, ty, tx + ts, ty + ts,
                rgba(theme().accent(), 0.18f), rgba(theme().accent(), 0.04f), Shader.TileMode.CLAMP));
        c.drawPath(p, fill);
        fill.setShader(null);
        line.setStrokeWidth(1f);
        line.setColor(rgba(theme().accent(), 0.28f));
        c.drawPath(p, line);

        float icx = tx + ts / 2f;
        float icy = cy;
        line.setColor(theme().accent());
        line.setStrokeWidth(1.6f);
        rf.set(icx - 5f, icy - 9f, icx + 5f, icy + 9f);
        p.reset();
        p.addRoundRect(rf, 4f, 4f, Path.Direction.CW);
        c.drawPath(p, line);
        c.drawLine(icx - 3f, icy - 3f, icx + 3f, icy - 3f, line);
        c.drawLine(icx - 3f, icy + 3f, icx + 3f, icy + 3f, line);

        boolean lowAny = false;
        for (float b : d.tireBars) if (b < 2.2f) lowAny = true;
        boolean hotAny = false;
        for (float tmp : d.tireTemps) if (tmp > 75f) hotAny = true;

        float textX = tx + ts + 14f;
        txt(c, "胎压监测", textX, cy - 3f, 15f, theme().textPrimary(), Paint.Align.LEFT, 1f, NUM_MED);
        txt(c, lowAny ? "右后偏低 · 建议检查"
                : hotAny ? "胎温偏高 · 请检查" : "四轮正常",
                textX, cy + 15f, 11f,
                lowAny || hotAny ? theme().warn() : theme().accent(), Paint.Align.LEFT, 1.2f, CN);

        float colW = 140f;
        for (int i = 0; i < d.tireBars.length; i++) {
            String name = d.tireNames[i];
            float bar = d.tireBars[i];
            float x = r0.left + 300f + i * colW;
            boolean warn = bar < 2.2f;

            txt(c, name, x, r0.top + 22f, 10f, theme().textFaint(), Paint.Align.LEFT, 1.8f, CN);
            String val = String.format(java.util.Locale.US, "%.1f", bar);
            txt(c, val, x, r0.top + 44f, 16f, warn ? theme().warn() : theme().textSecondary(), Paint.Align.LEFT, 0.6f, NUM_MED);
            float vw = measure(val, 16f, NUM_MED);
            txt(c, "bar", x + vw + 4f, r0.top + 44f, 9.5f, theme().textFaint(), Paint.Align.LEFT, 0f, CN);

            // 胎温：>75°C 告警
            float temp = d.tireTemps[i];
            boolean hot = temp > 75f;
            String tstr = String.format(java.util.Locale.US, "%.0f", temp) + "°C";
            txt(c, tstr, x + 78f, r0.top + 44f, 12f,
                hot ? theme().warn() : 0xFF9DB2B7, Paint.Align.LEFT, 0.4f, NUM_MED);

            float gw = 100f;
            float gy = r0.top + 53f;
            float map = x + gw * HudTheme.clamp((bar - 2.0f) / 1.0f, 0f, 1f);

            rf.set(x, gy, x + gw, gy + 3f);
            p.reset();
            p.addRoundRect(rf, 1.5f, 1.5f, Path.Direction.CW);
            fill.setColor(rgba(0xFFFFFF, 0.07f));
            c.drawPath(p, fill);

            float g0x = x + gw * HudTheme.clamp((2.2f - 2.0f) / 1.0f, 0f, 1f);
            float g1x = x + gw * HudTheme.clamp((2.6f - 2.0f) / 1.0f, 0f, 1f);
            rf.set(g0x, gy, g1x, gy + 3f);
            p.reset();
            p.addRoundRect(rf, 1.5f, 1.5f, Path.Direction.CW);
            fill.setColor(rgba(theme().accent(), 0.20f));
            c.drawPath(p, fill);

            rf.set(map - 1f, gy - 3f, map + 1f, gy + 6f);
            p.reset();
            p.addRoundRect(rf, 1f, 1f, Path.Direction.CW);
            fill.setColor(warn ? theme().warn() : theme().accent());
            c.drawPath(p, fill);
            if (warn) drawGlow(c, map, gy + 1.5f, 12f, theme().warn(), 0.5f);
        }
    }

    /* ============================== 车道引导条 ============================== */

    private void drawLaneGuide(Canvas c) {
        HudData d = view.data;
        RectF map = HudLayout.MAP;
        if (d.lanes == null || d.lanes.length == 0) return;

        int[] actIdx = d.laneActive;
        int n = d.lanes.length;
        String[] dirs = new String[n];
        boolean[] ons = new boolean[n];
        for (int i = 0; i < n; i++) {
            String s = d.lanes[i];
            if (s.contains("|")) {
                String[] parts = s.split("\\|");
                dirs[i] = parts[0].trim();
                ons[i] = parts.length > 1 && parts[1].trim().equals("1");
            } else {
                dirs[i] = s;
                ons[i] = intArrayContains(actIdx, i);
            }
        }

        float cell = 50f;
        float pad = 12f;
        float h = 46f;
        float w = n * cell + pad * 2;
        float x = map.left + (map.width() - w) / 2f;
        float y = map.top + 14f;

        rf.set(x, y, x + w, y + h);
        p.reset();
        p.addRoundRect(rf, 12f, 12f, Path.Direction.CW);
        fill.setColor(rgba(0xFF070C0F, 0.86f));
        c.drawPath(p, fill);
        line.setStrokeWidth(1f);
        line.setColor(rgba(0xFF78AAB4, 0.20f));
        c.drawPath(p, line);

        float cy = y + h / 2f;
        for (int i = 0; i < n; i++) {
            String dir = dirs[i];
            boolean on = ons[i];
            float cx = x + pad + cell * (i + 0.5f);
            int col = on ? theme().accent() : rgba(0xFFE2F1F2, 0.5f);

            if (on) drawGlow(c, cx, cy + 8f, 26f, theme().accent(), 0.22f);

            c.save();
            c.translate(cx, cy);
            line.setShader(null);
            fill.setShader(null);
            line.setColor(col);
            fill.setColor(col);
            line.setStrokeWidth(2f);
            if (dir.equals("straight")) {
                p.reset();
                p.moveTo(0f, -11f);
                p.lineTo(6.5f, -3f);
                p.lineTo(2.7f, -3f);
                p.lineTo(2.7f, 11f);
                p.lineTo(-2.7f, 11f);
                p.lineTo(-2.7f, -3f);
                p.lineTo(-6.5f, -3f);
                p.close();
                c.drawPath(p, fill);
            } else {
                float s = dir.equals("left") ? -1f : 1f;
                c.scale(s, 1f);
                line.setStrokeWidth(3.4f);
                p.reset();
                p.moveTo(-1f, 11f);
                p.lineTo(-1f, -2f);
                p.quadTo(-1f, -7.5f, 4.5f, -7.5f);
                p.lineTo(6.5f, -7.5f);
                c.drawPath(p, line);
                p.reset();
                p.moveTo(6f, -13f);
                p.lineTo(13f, -7.5f);
                p.lineTo(6f, -2f);
                p.close();
                c.drawPath(p, fill);
            }
            c.restore();

            if (i < n - 1) {
                float dx = x + pad + cell * (i + 1);
                line.setStrokeWidth(1f);
                line.setColor(rgba(0xFF78AAB4, 0.22f));
                c.drawLine(dx, cy - 9f, dx, cy + 9f, line);
            }
        }
    }

    /* ============================== 媒体卡 ============================== */

    /* 封面 Bitmap 缓存（路径+版本变了才重新解码） */
    private android.graphics.Bitmap mCoverBmp;
    private String mCoverPath;
    private long mCoverRev = -1;

    /** 按 HudData 的封面路径/版本取 Bitmap，失败返回 null */
    private android.graphics.Bitmap coverBitmap(HudData d) {
        try {
            String path = d.mediaCoverPath;
            long rev = d.mediaCoverRev;
            if (path == null || path.isEmpty()) return null;
            if (mCoverBmp != null && path.equals(mCoverPath) && rev == mCoverRev) {
                return mCoverBmp;
            }
            /* 路径或版本变了 → 重新解码 */
            if (mCoverBmp != null) { mCoverBmp.recycle(); mCoverBmp = null; }
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(path);
            if (b == null) return null;
            mCoverBmp = b;
            mCoverPath = path;
            mCoverRev = rev;
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private void drawMediaCard(Canvas c) {
        RectF card = HudLayout.MEDIA;
        panel(c, card, 20f);
        if (!view.data.mediaPlaying) drawMediaIdle(c);
        else drawMediaPlaying(c);
    }

    private void drawMediaPlaying(Canvas c) {
        HudData d = view.data;
        RectF card = HudLayout.MEDIA;

        float cs = 132f;
        float cx0 = card.left + 22f;
        float cy0 = card.top + 26f;
        float ccx = cx0 + cs / 2f;
        float ccy = cy0 + cs / 2f;
        float cr = cs / 2f;
        float artR = cr - 9f;

        fill.setShader(null);
        fill.setColor(0xFF0A1215);
        c.drawCircle(ccx, ccy, cr, fill);
        line.setStrokeWidth(1f);
        for (int i = 0; i < 4; i++) {
            line.setColor(rgba(0xFFFFFF, 0.035f));
            c.drawCircle(ccx, ccy, artR + 2f + i * 3f, line);
        }

        c.save();
        p.reset();
        p.addCircle(ccx, ccy, artR, Path.Direction.CW);
        c.clipPath(p);

        /*
         * 真实封面（2026-11-12 新增）
         *
         * EVCC 的 openMusicArtwork 拿到的图片存在 files/cover.jpg，
         * HudData.mediaCoverPath 里是路径，mediaCoverRev 是版本号。
         *
         * 只在路径/版本变化时重新解码（BitmapFactory 是重操作，
         * 30fps 每帧解码会直接吃掉整个 CPU）。
         * 拿不到封面时退回原来的旋转星河背景。
         */
        android.graphics.Bitmap cover = coverBitmap(d);
        if (cover != null) {
            RectF dst = new RectF(cx0, cy0, cx0 + cs, cy0 + cs);
            c.drawBitmap(cover, null, dst, null);
        } else {
            /* 没封面时用原来的旋转星河背景 */
            c.rotate(t * 25.8f, ccx, ccy);

        fill.setShader(new LinearGradient(cx0, cy0, cx0 + cs, cy0 + cs,
                new int[]{0xFF0D2C36, 0xFF123A46, 0xFF241A3E},
                null, Shader.TileMode.CLAMP));
        c.drawRect(cx0 - 4f, cy0 - 4f, cx0 + cs + 4f, cy0 + cs + 4f, fill);
        fill.setShader(null);

        fill.setColor(rgba(0xFFC8FFF6, 0.55f));
        float[] stars = {0.18f, 0.20f, 0.30f, 0.34f, 0.12f, 0.44f, 0.46f, 0.16f,
                0.86f, 0.52f, 0.72f, 0.70f, 0.56f, 0.88f, 0.22f, 0.72f};
        for (int i = 0; i < stars.length; i += 2) {
            c.drawCircle(cx0 + cs * stars[i], cy0 + cs * stars[i + 1], 1f, fill);
        }

        float mx = cx0 + cs * 0.66f;
        float my = cy0 + cs * 0.30f;
        fill.setColor(rgba(0xFF7BFFE6, 0.10f));
        c.drawCircle(mx, my, cs * 0.23f, fill);
        fill.setColor(rgba(0xFFB6FFF2, 0.9f));
        c.drawCircle(mx, my, cs * 0.15f, fill);
        drawGlow(c, mx, my, cs * 0.42f, theme().accent(), 0.30f);

        p.reset();
        p.moveTo(cx0 - 16f, cy0 + cs * 0.70f);
        p.lineTo(cx0 + cs + 16f, cy0 + cs * 0.40f);
        p.lineTo(cx0 + cs + 16f, cy0 + cs * 0.56f);
        p.lineTo(cx0 - 16f, cy0 + cs * 0.86f);
        p.close();
        fill.setColor(rgba(0xFF7BFFE6, 0.10f));
        c.drawPath(p, fill);

        p.reset();
        p.moveTo(cx0 - 16f, cy0 + cs * 0.88f);
        p.lineTo(cx0 + cs + 16f, cy0 + cs * 0.62f);
        p.lineTo(cx0 + cs + 16f, cy0 + cs * 0.71f);
        p.lineTo(cx0 - 16f, cy0 + cs);
        p.close();
        fill.setColor(rgba(0xFFFFFF, 0.06f));
        c.drawPath(p, fill);

        fill.setShader(new RadialGradient(ccx, ccy, artR,
                new int[]{rgba(0xFF04080A, 0f), rgba(0xFF04080A, 0.55f)},
                new float[]{0.45f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(cx0 - 4f, cy0 - 4f, cx0 + cs + 4f, cy0 + cs + 4f, fill);
        fill.setShader(null);
        }
        c.restore();

        fill.setColor(0xFF05090B);
        c.drawCircle(ccx, ccy, 4.5f, fill);
        line.setStrokeWidth(1f);
        line.setColor(rgba(0xFF78C8BE, 0.35f));
        c.drawCircle(ccx, ccy, 4.5f, line);
        line.setColor(rgba(0xFF78C8BE, 0.28f));
        c.drawCircle(ccx, ccy, cr - 0.5f, line);

        float tx = cx0 + cs + 18f;
        txt(c, d.mediaTitle, tx, card.top + 58f, 15f, theme().textSecondary(), Paint.Align.LEFT, 0.8f, NUM_MED);
        float titleW = measure(d.mediaTitle, 15f, NUM_MED);
        txt(c, "· " + d.mediaArtist, tx + titleW + 4f, card.top + 58f, 10.5f, theme().textFaint(), Paint.Align.LEFT, 1.2f, CN);

        float lx = tx + 10f;
        txt(c, d.lyric0, lx, card.top + 104f, 11f, 0xFF46595E, Paint.Align.LEFT, 1.2f, CN);

        fill.setColor(theme().accent());
        rf.set(lx - 11f, card.top + 121f, lx - 8.5f, card.top + 139f);
        p.reset();
        p.addRoundRect(rf, 1.25f, 1.25f, Path.Direction.CW);
        c.drawPath(p, fill);
        drawGlow(c, lx - 9.7f, card.top + 130f, 14f, theme().accent(), 0.35f);

        txt(c, d.lyric1, lx, card.top + 140f, 17f, 0xFFF2FBFA, Paint.Align.LEFT, 1.6f, CN);
        txt(c, d.lyric2, lx, card.top + 168f, 13f, 0xFF75898E, Paint.Align.LEFT, 1.2f, CN);
        txt(c, d.lyric3, lx, card.top + 192f, 11.5f, 0xFF46595E, Paint.Align.LEFT, 1.2f, CN);

        float eqX = card.left + 22f;
        float eqW = card.width() - 44f;
        float eqY = card.top + 214f;
        float eqH = 40f;
        int bars = 42;
        float bw = 5f;
        float gap = (eqW - bars * bw) / (bars - 1);
        for (int i = 0; i < bars; i++) {
            float hh = 6f + (eqH - 6f)
                    * (0.35f + 0.65f * absf(sinf(t * 2.4f + i * 0.55f)))
                    * (0.55f + 0.45f * sinf(i * 0.9f));
            float bxx = eqX + i * (bw + gap);
            rf.set(bxx, eqY + eqH - hh, bxx + bw, eqY + eqH);
            p.reset();
            p.addRoundRect(rf, 2.5f, 2.5f, Path.Direction.CW);
            fill.setColor(rgba(theme().accent(), 0.16f + 0.30f * (hh / eqH)));
            c.drawPath(p, fill);
        }

        float px = card.left + 22f;
        float pw = card.width() - 44f;
        float py = card.top + 288f;

        rf.set(px, py, px + pw, py + 3f);
        p.reset();
        p.addRoundRect(rf, 1.5f, 1.5f, Path.Direction.CW);
        fill.setColor(rgba(0xFFFFFF, 0.08f));
        c.drawPath(p, fill);

        float fw = pw * (d.mediaPct / 100f);
        if (fw > 0.5f) {
            rf.set(px, py, px + fw, py + 3f);
            p.reset();
            p.addRoundRect(rf, 1.5f, 1.5f, Path.Direction.CW);
            fill.setShader(new LinearGradient(px, 0, px + fw, 0, theme().accentDark(), theme().accent(), Shader.TileMode.CLAMP));
            c.drawPath(p, fill);
            fill.setShader(null);
            fill.setColor(0xFFEAFFFA);
            c.drawCircle(px + fw, py + 1.5f, 3.2f, fill);
            drawGlow(c, px + fw, py + 1.5f, 14f, theme().accent(), 0.5f);
        }

        txt(c, d.mediaTime, px, card.top + 306f, 9f, theme().textFaint(), Paint.Align.LEFT, 0.6f, CN);
        txt(c, d.mediaDur, px + pw, card.top + 306f, 9f, theme().textFaint(), Paint.Align.RIGHT, 0.6f, CN);
    }

    private void drawMediaIdle(Canvas c) {
        RectF card = HudLayout.MEDIA;
        float cs = 132f;
        float cx0 = card.left + 22f;
        float cy0 = card.top + 26f;
        float ccx = cx0 + cs / 2f;
        float ccy = cy0 + cs / 2f;
        float cr = cs / 2f;

        fill.setShader(new LinearGradient(cx0, cy0, cx0 + cs, cy0 + cs,
                new int[]{0xFF0B1B22, 0xFF0E2129, 0xFF171430},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(ccx, ccy, cr, fill);
        fill.setShader(null);
        fill.setColor(rgba(0xFF0A1215, 0.5f));
        c.drawCircle(ccx, ccy, cr, fill);

        fill.setColor(0xFF0B1B22);
        c.drawCircle(ccx, ccy, cr - 9f, fill);
        line.setStrokeWidth(1f);
        for (int i = 0; i < 4; i++) {
            line.setColor(rgba(0xFFFFFF, 0.03f));
            c.drawCircle(ccx, ccy, cr - 7f + i * 3f, line);
        }
        line.setColor(rgba(0xFF78C8BE, 0.16f));
        c.drawCircle(ccx, ccy, cr - 0.5f, line);

        fill.setColor(rgba(0xFF060C0F, 0.9f));
        c.drawCircle(ccx, ccy, 4.5f, fill);

        float tx = cx0 + cs + 18f;
        txt(c, "未在播放", tx, card.top + 58f, 15f, theme().textSecondary(), Paint.Align.LEFT, 0.8f, NUM_MED);

        fill.setColor(theme().textFaint());
        c.drawCircle(tx + 3f, card.top + 80f, 2.4f, fill);
        txt(c, "等待媒体信号", tx + 12f, card.top + 84f, 9.5f, theme().textFaint(), Paint.Align.LEFT, 1.6f, CN);

        float eqX = card.left + 22f;
        float eqW = card.width() - 44f;
        float eqY = card.top + 236f;
        float eqH = 18f;
        int bars = 42;
        float bw = 5f;
        float gap = (eqW - bars * bw) / (bars - 1);
        fill.setColor(rgba(theme().accent(), 0.07f));
        for (int i = 0; i < bars; i++) {
            float hh = 4f + 5f * absf(sinf(i * 0.7f));
            rf.set(eqX + i * (bw + gap), eqY + eqH - hh, eqX + i * (bw + gap) + bw, eqY + eqH);
            p.reset();
            p.addRoundRect(rf, 2f, 2f, Path.Direction.CW);
            c.drawPath(p, fill);
        }

        float px = card.left + 22f;
        float pw = card.width() - 44f;
        float py = card.top + 288f;
        rf.set(px, py, px + pw, py + 3f);
        p.reset();
        p.addRoundRect(rf, 1.5f, 1.5f, Path.Direction.CW);
        fill.setColor(rgba(0xFFFFFF, 0.06f));
        c.drawPath(p, fill);

        txt(c, "0:00", px, card.top + 306f, 9f, theme().textFaint(), Paint.Align.LEFT, 0.6f, CN);
        txt(c, "--:--", px + pw, card.top + 306f, 9f, theme().textFaint(), Paint.Align.RIGHT, 0.6f, CN);
    }

    /* ============================== 地图 ============================== */

    private static final float[] CTRL = {
            262f, 470f, 330f, 442f, 430f, 398f, 520f, 346f,
            596f, 286f, 664f, 236f, 730f, 196f, 790f, 162f, 842f, 128f
    };

    private static final float[][] HWY = {
            {-40f, 176f, 240f, 168f, 520f, 190f, 800f, 152f, 1080f, 138f},
            {-40f, 336f, 260f, 316f, 540f, 344f, 820f, 330f, 1080f, 358f}
    };

    private static final float[] MINOR_V = {150f, 330f, 470f, 620f, 760f, 900f};
    private static final float[] MINOR_H = {70f, 240f, 410f, 470f};

    private static final float[][] BUILDINGS = {
            {96f, 60f, 210f, 108f},
            {300f, 40f, 400f, 96f},
            {600f, 70f, 700f, 130f},
            {860f, 60f, 980f, 118f},
            {120f, 250f, 214f, 300f},
            {430f, 262f, 522f, 316f},
            {700f, 250f, 800f, 306f},
            {880f, 396f, 990f, 452f},
            {300f, 400f, 410f, 456f}
    };

    private float[] routePts;
    private float[] laneA, laneB, laneC, laneD;

    /*
     * ══════════════════════════════════════════════════════════════
     * 静态底图缓存（2026-11-12 性能修复）
     * ══════════════════════════════════════════════════════════════
     *
     * **问题**：VD 实测只产出 8fps（dcapd2 capture=16/2s），
     * gfxinfo 显示 Slow UI thread 3770 次、90 分位 77ms。
     * 设计节拍是 33ms（30fps），实际每帧要 77ms —— 慢了 2.3 倍。
     *
     * **根因**：drawMapContent 每帧都重画静态底图：
     *   19 × drawPath（水系贝塞尔 + 9 个建筑圆角矩形）
     *   10 × drawLine（遍历 MINOR_V 6 条 + MINOR_H 4 条）
     *    1 × RadialGradient 暗角（每帧重建 shader）
     * 这些内容**永远不变**，却占了大半渲染时间。
     *
     * **修法**：第一次画到 Bitmap 上，之后每帧只 drawBitmap。
     *   · 缓存尺寸 = 地图虚拟坐标系 1060 × 560（HudLayout.MAP_W/H 外加余量）
     *   · 水系/路网/建筑/路名全部进缓存
     *   · 动态部分（导航路线、车道、自车）仍然每帧画 —— 它们会动
     *
     * 预期收益：77ms → 20-25ms，VD 恢复到 30fps，
     * 端到端延迟 158ms → ~70ms。
     */
    private Bitmap mapStatic;          /* 静态底图缓存 */
    private Canvas mapStaticCanvas;    /* 写缓存用 */
    private static final int MAP_CACHE_W = 1120;   /* 覆盖 -40..1080 */
    private static final int MAP_CACHE_H = 600;    /* 覆盖 -40..560 */
    /** 缓存图左上角对应的设计稿坐标（静态内容有负数起点，所以要偏移） */
    private static final float MAP_CACHE_ORIGIN = 40f;

    /**
     * 把静态底图画进缓存 Bitmap（只跑一次）。
     *
     * 内容与优化前完全一致，只是从"每帧画到屏幕"改成"一次画到 Bitmap"：
     *   水系 → 次干道 → 主干道 → 建筑 → 路名
     *
     * 注意缓存 Canvas 的坐标系：先 translate(+40, +40)，
     * 这样画的时候可以直接用设计稿坐标（含负数的 -40 / -20）。
     */
    private void buildMapStatic() {
        try {
            mapStatic = Bitmap.createBitmap(MAP_CACHE_W, MAP_CACHE_H,
                    Bitmap.Config.ARGB_8888);
            mapStaticCanvas = new Canvas(mapStatic);
            Canvas c = mapStaticCanvas;
            c.translate(MAP_CACHE_ORIGIN, MAP_CACHE_ORIGIN);

            // 水系
            p.reset();
            p.moveTo(-40f, -40f);
            p.lineTo(150f, -40f);
            p.cubicTo(96f, 120f, 190f, 260f, 120f, 420f);
            p.lineTo(60f, 560f);
            p.lineTo(-40f, 560f);
            p.close();
            fill.setColor(rgba(0xFF0E2130, 0.62f));
            c.drawPath(p, fill);

            // 次干道
            line.setStrokeWidth(2f);
            line.setColor(rgba(0xFF1A2C34, 0.90f));
            for (float x : MINOR_V) c.drawLine(x, -20f, x, 540f, line);
            for (float y : MINOR_H) c.drawLine(-20f, y, 1060f, y, line);

            // 主干道
            for (float[] road : HWY) {
                p.reset();
                p.moveTo(road[0], road[1]);
                for (int i = 2; i < road.length; i += 2) p.lineTo(road[i], road[i + 1]);
                line.setStrokeWidth(24f);
                line.setColor(rgba(0xFF28404C, 0.95f));
                c.drawPath(p, line);
                line.setStrokeWidth(1.4f);
                line.setColor(rgba(0xFFFFFFFF, 0.075f));
                c.drawPath(p, line);
            }

            // 建筑
            for (float[] b : BUILDINGS) {
                rf.set(b[0], b[1], b[2], b[3]);
                p.reset();
                p.addRoundRect(rf, 6f, 6f, Path.Direction.CW);
                fill.setColor(rgba(0xFF1A2E38, 0.80f));
                c.drawPath(p, fill);
            }

            // 路名
            txt(c, "北环路", 300f, 176f, 11f, rgba(0xFF7C9095, 0.85f), Paint.Align.CENTER, 2f, CN);
            txt(c, "中山北路", 620f, 336f, 11f, rgba(0xFF7C9095, 0.85f), Paint.Align.CENTER, 2f, CN);

            Log.i("DashRenderer", "静态底图缓存已生成 "
                    + MAP_CACHE_W + "x" + MAP_CACHE_H);
        } catch (Throwable t) {
            /* 缓存失败不影响功能 —— buildMapStatic 里画不出来，
             * 下面 drawBitmap 会画空白，最多地图没底图 */
            Log.w("DashRenderer", "静态底图缓存失败: " + t);
            mapStatic = null;
        }
    }

    /** Catmull-Rom 采样成折线 */
    private float[] catmull(float[] ctrl, int samples) {
        int n = ctrl.length / 2;
        java.util.ArrayList<Float> out = new java.util.ArrayList<>(n * samples * 2);
        for (int i = 0; i < n - 1; i++) {
            int p0 = i == 0 ? 0 : i - 1;
            int p1 = i;
            int p2 = i + 1;
            int p3 = i + 2 < n ? i + 2 : n - 1;
            for (int s = 0; s < samples; s++) {
                float u = s / (float) samples;
                float u2 = u * u;
                float u3 = u2 * u;
                float x = 0.5f * ((2 * ctrl[p1 * 2]) + (-ctrl[p0 * 2] + ctrl[p2 * 2]) * u
                        + (2 * ctrl[p0 * 2] - 5 * ctrl[p1 * 2] + 4 * ctrl[p2 * 2] - ctrl[p3 * 2]) * u2
                        + (-ctrl[p0 * 2] + 3 * ctrl[p1 * 2] - 3 * ctrl[p2 * 2] + ctrl[p3 * 2]) * u3);
                float y = 0.5f * ((2 * ctrl[p1 * 2 + 1]) + (-ctrl[p0 * 2 + 1] + ctrl[p2 * 2 + 1]) * u
                        + (2 * ctrl[p0 * 2 + 1] - 5 * ctrl[p1 * 2 + 1] + 4 * ctrl[p2 * 2 + 1] - ctrl[p3 * 2 + 1]) * u2
                        + (-ctrl[p0 * 2 + 1] + 3 * ctrl[p1 * 2 + 1] - 3 * ctrl[p2 * 2 + 1] + ctrl[p3 * 2 + 1]) * u3);
                out.add(x);
                out.add(y);
            }
        }
        out.add(ctrl[(n - 1) * 2]);
        out.add(ctrl[(n - 1) * 2 + 1]);
        float[] arr = new float[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    /** 沿法线偏移，用于生成车道边界 */
    private float[] offsetPoly(float[] pts, float dist) {
        int n = pts.length / 2;
        float[] out = new float[pts.length];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1);
            int b = Math.min(n - 1, i + 1);
            float dx = pts[b * 2] - pts[a * 2];
            float dy = pts[b * 2 + 1] - pts[a * 2 + 1];
            float len = (float) Math.hypot(dx, dy);
            if (len < 0.0001f) len = 1f;
            out[i * 2] = pts[i * 2] - dy / len * dist;
            out[i * 2 + 1] = pts[i * 2 + 1] + dx / len * dist;
        }
        return out;
    }

    private void ensureRoute() {
        if (routePts != null) return;
        routePts = catmull(CTRL, 14);
        float lw = 18f;
        laneA = offsetPoly(routePts, -1.5f * lw);
        laneB = offsetPoly(routePts, -0.5f * lw);
        laneC = offsetPoly(routePts, 0.5f * lw);
        laneD = offsetPoly(routePts, 1.5f * lw);
    }

    private void tracePoly(Path path, float[] pts) {
        path.reset();
        path.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) path.lineTo(pts[i], pts[i + 1]);
    }

    private void drawMapPanel(Canvas c) {
        HudData d = view.data;
        RectF map = HudLayout.MAP;

        c.save();
        rf.set(map);
        p.reset();
        p.addRoundRect(rf, 20f, 20f, Path.Direction.CW);
        c.clipPath(p);

        // 底
        fill.setShader(new RadialGradient(
                map.centerX(), map.top + map.height() * 0.46f, map.width() * 0.72f,
                0xFF0F1B21, 0xFF060B0E, Shader.TileMode.CLAMP));
        c.drawRect(map, fill);
        fill.setShader(null);

        // cover 缩放到虚拟坐标系
        float s = Math.max(map.width() / HudLayout.MAP_W, map.height() / HudLayout.MAP_H);
        float ox = map.left + (map.width() - HudLayout.MAP_W * s) / 2f;
        float oy = map.top + (map.height() - HudLayout.MAP_H * s) / 2f;

        c.save();
        c.translate(ox, oy);
        c.scale(s, s);
        drawMapContent(c, d);
        c.restore();

        c.restore(); // clip

        // 暗角
        c.save();
        rf.set(map);
        p.reset();
        p.addRoundRect(rf, 20f, 20f, Path.Direction.CW);
        c.clipPath(p);
        fill.setShader(new RadialGradient(
                map.centerX(), map.centerY(), map.width() * 0.62f,
                new int[]{rgba(0x000000, 0f), rgba(0x000000, 0.42f)},
                new float[]{0.6f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(map, fill);
        fill.setShader(null);
        c.restore();

        // 边框
        rf.set(map);
        p.reset();
        p.addRoundRect(rf, 20f, 20f, Path.Direction.CW);
        line.setStrokeWidth(1f);
        line.setColor(rgba(0xFF78AAB4, 0.12f));
        c.drawPath(p, line);

        drawMapChips(c, map);
        drawLaneGuide(c);
    }

    private void drawMapContent(Canvas c, HudData d) {
        /*
         * ── 静态底图：走缓存 ──
         *
         * 水系 / 次干道 / 主干道 / 建筑 / 路名 这五样永不变化，
         * 第一次画进 mapStatic，之后每帧只 drawBitmap（约 1ms）。
         *
         * 原来这些每帧重画，占了大半 UI 线程时间 —— 见字段声明处的注释。
         */
        if (mapStatic == null) buildMapStatic();

        /*
         * 缓存的原点在 (-40, -40)：静态内容有负数坐标
         * （水系从 -40 起、MINOR_V/H 从 -20 起），
         * 所以画的时候要偏移回去。
         */
        c.drawBitmap(mapStatic, -MAP_CACHE_ORIGIN, -MAP_CACHE_ORIGIN, bmp);

        // 非导航态不画自车小点与路线（以下全是动态内容）
        if (!d.navActive) {
            return;
        }

        ensureRoute();
        float[] rp = routePts;
        float[] a = laneA, b = laneB, cc = laneC, dd = laneD;

        // 车道面
        p.reset();
        p.moveTo(a[0], a[1]);
        for (int i = 2; i < a.length; i += 2) p.lineTo(a[i], a[i + 1]);
        for (int j = dd.length - 2; j >= 0; j -= 2) p.lineTo(dd[j], dd[j + 1]);
        p.close();
        fill.setColor(rgba(0xFF17262D, 0.60f));
        c.drawPath(p, fill);

        // 本车道高亮
        p.reset();
        p.moveTo(b[0], b[1]);
        for (int i = 2; i < b.length; i += 2) p.lineTo(b[i], b[i + 1]);
        for (int j = cc.length - 2; j >= 0; j -= 2) p.lineTo(cc[j], cc[j + 1]);
        p.close();
        fill.setColor(rgba(theme().accent(), 0.055f));
        c.drawPath(p, fill);

        // 路缘
        line.setStrokeWidth(1.4f);
        line.setColor(rgba(0xFFB4D7DC, 0.13f));
        tracePoly(p, a);
        c.drawPath(p, line);
        tracePoly(p, dd);
        c.drawPath(p, line);

        // 车道分隔虚线
        line.setStrokeWidth(1.4f);
        line.setColor(rgba(0xFFE2F5F6, 0.26f));
        line.setPathEffect(new android.graphics.DashPathEffect(new float[]{9f, 11f}, 0f));
        tracePoly(p, b);
        c.drawPath(p, line);
        tracePoly(p, cc);
        c.drawPath(p, line);
        line.setPathEffect(null);

        // 路线：光晕 + 主线 + 流光
        tracePoly(p, rp);
        glowStroke(c, rp, rgba(theme().accent(), 0.55f), 6f);

        tracePoly(p, rp);
        line.setShader(new LinearGradient(262f, 470f, 842f, 128f,
                theme().accentDark(), 0xFF7BFFE6, Shader.TileMode.CLAMP));
        line.setStrokeWidth(6f);
        line.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(p, line);
        line.setShader(null);

        tracePoly(p, rp);
        line.setStrokeWidth(1.6f);
        line.setColor(rgba(0xFFC9FFF6, 0.85f));
        line.setPathEffect(new android.graphics.DashPathEffect(new float[]{10f, 12f}, -(t * 20f) % 22f));
        c.drawPath(p, line);
        line.setPathEffect(null);

        // 终点
        float ex = rp[rp.length - 2];
        float ey = rp[rp.length - 1];
        drawGlow(c, ex, ey, 26f, theme().accent(), 0.55f);
        fill.setColor(0xFF0A1114);
        c.drawCircle(ex, ey, 9f, fill);
        line.setStrokeWidth(2.5f);
        line.setColor(theme().accent());
        c.drawCircle(ex, ey, 9f, line);
        fill.setColor(theme().accent());
        c.drawCircle(ex, ey, 3.4f, fill);
        txt(c, "科技园", ex + 16f, ey + 4f, 11f, rgba(0xFFB6FFF2, 0.9f), Paint.Align.LEFT, 1.4f, CN);
        txt(c, "09:52 到达", ex + 16f, ey + 20f, 9.5f, rgba(0xFF57726F, 1f), Paint.Align.LEFT, 1.2f, CN);
    }

    /** 线条光晕：三遍加宽描边（用于路线） */
    private void glowStroke(Canvas c, float[] pts, int color, float width) {
        tracePoly(p, pts);
        line.setStrokeWidth(width * 3.2f);
        line.setColor(rgba(color, 0.10f));
        c.drawPath(p, line);
        line.setStrokeWidth(width * 2.0f);
        line.setColor(rgba(color, 0.16f));
        c.drawPath(p, line);
        line.setStrokeWidth(width);
        line.setColor(color);
        c.drawPath(p, line);
    }

    private void drawMapChips(Canvas c, RectF map) {
        // 实时路况
        chip(c, map.left + 14f, map.top + 14f, 122f, "实时路况 · 畅通", "traffic");
        // 指北
        chip(c, map.right - 14f - 118f, map.top + 14f, 118f, "N · 北偏东 12°", "compass");

        // 比例尺
        float sw = 62f;
        float sx = map.right - 14f - sw;
        float sy = map.bottom - 14f - 20f;
        rf.set(sx, sy, sx + sw, sy + 20f);
        p.reset();
        p.addRoundRect(rf, 10f, 10f, Path.Direction.CW);
        fill.setColor(rgba(0xFF080E11, 0.78f));
        c.drawPath(p, fill);
        line.setStrokeWidth(1f);
        line.setColor(theme().line());
        c.drawPath(p, line);
        txt(c, "200 m", sx + sw / 2f, sy + 14.5f, 10.5f, 0xFF7C9095, Paint.Align.CENTER, 1.6f, CN);
    }

    private void chip(Canvas c, float x, float y, float w, String label, String kind) {
        rf.set(x, y, x + w, y + 28f);
        p.reset();
        p.addRoundRect(rf, 14f, 14f, Path.Direction.CW);
        fill.setColor(rgba(0xFF080E11, 0.78f));
        c.drawPath(p, fill);
        line.setStrokeWidth(1f);
        line.setColor(theme().line());
        c.drawPath(p, line);

        c.save();
        c.translate(x + 15f, y + 14f);
        if (kind.equals("traffic")) {
            float pulse = (t * 0.45f) % 1f;
            fill.setAlpha(Math.round((1f - pulse * 0.6f) * 255f));
            c.drawCircle(0f, 0f, 3f, fill);
            fill.setAlpha(255);
            drawGlow(c, 0f, 0f, 12f, theme().accent(), 0.5f);
        } else { // compass
            line.setStrokeWidth(1.8f);
            line.setColor(0xFF8FB6BC);
            p.reset();
            p.moveTo(0f, -6f);
            p.lineTo(-6f, 6f);
            p.lineTo(0f, 2.5f);
            p.lineTo(6f, 6f);
            p.close();
            c.drawPath(p, line);
        }
        c.restore();

        txt(c, label, x + 26f, y + 18.5f, 11.5f, theme().textDim(), Paint.Align.LEFT, 1.4f, CN);
    }

}
