package com.hud.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

/**
 * 仪表盘 View（Java 版）。只读，不处理任何触摸事件。
 * 外部流程：拿到 data 改字段 → invalidate()；或调 setData(HudData) 全量写入。
 */
public class DashView extends View {

    public static final String BUILD_ID = "J20261110.1";

    public final HudData data = new HudData();

    /*
     * ── 可插拔的主题与皮肤（2026-11-12）──────────────────────────
     *
     * 默认值 = 改造前那套（经典皮肤 + 青绿主题），所以行为完全不变。
     *
     * 换配色：
     *   view.setTheme(new ClassicTheme());      // 只换颜色/字体/圆角
     *
     * 换整套界面：
     *   view.setSkin(new MySkin());             // 重画整个仪表
     *
     * 两者正交：一个 Skin 可以配任意 Theme。
     * 详见 DashTheme.java / DashSkin.java 的注释。
     */
    private DashTheme theme = new TealTheme();
    private DashSkin skin;

    /** 老的绘制内核。只有 ClassicSkin 需要它（Skin 内部会转发过来） */
    private final DashRenderer rd = new DashRenderer();

    /** 演示用：跟随内置模拟车速；接真实数据前置 false */
    public boolean autoSpeed = true;
    public boolean autoPower = true;

    float sim = 0f, pow = 142f, t = 0f, noaPulseAt = -1f;
    private boolean running = false;
    private long lastNanos = 0L;

    /**
     * 渲染节拍（2026-11-11 性能优化）
     *
     * 原来跑满 vsync（60fps），但 dcapd2 出口只有 12fps ——
     * 渲染 60 帧扔掉 48 帧，白白吃掉一个多核的 CPU
     * （实测 dashcast 进程占 152%，系统 load 23）。
     *
     * 现在按 FRAME_INTERVAL_MS 限帧：到点才 step+invalidate，
     * 否则只重新注册回调。动画平滑靠 step(dt) 按真实间隔积分。
     */
    /*
     * 渲染节拍（历史：60fps → 33ms → 16ms → 20ms → 24ms）
     *
     * ══════════════════════════════════════════════════════════════
     * 四档实测（2026-11-12，地图投屏，dcapd2 推送帧率 / CPU / Janky）
     * ══════════════════════════════════════════════════════════════
     *
     *   33ms   推送 30fps    CPU 53%    Janky 1.9%
     *   24ms   推送 30fps    CPU 52%    Janky 0.5%   ← 选定
     *   20ms   推送 17fps    CPU 97%    Janky 92.5%  ✗ 反直觉，比 24 差得多
     *   16ms   推送 58~61fps CPU 120%   Janky 77.4%  （帧率最高，CPU 吃满）
     *
     * **20ms 为什么比 24ms 还差**（本次最反直觉的发现）：
     *   gfxinfo 显示每帧实际只画 9~11ms，20ms 的节拍理论上绰绰有余。
     *   但 Choreographer 回调是 **vsync 对齐**的（本机 120Hz → 8.3ms，
     *   60Hz → 16.7ms 一个节拍），20ms 的判断会落在两个节拍之间：
     *     有时隔 1 个 vsync（8.3ms）就画，有时要等 2~3 个（16.6~25ms）
     *   于是 VD 侧看到的帧流忽快忽慢，抖动放大到 44ms（50 分位），
     *   最终推送反而掉到 17fps。
     *
     *   24ms 则稳定落在 8.3 的整数倍附近（8.3×3=24.9），节拍与 vsync 对齐，
     *   抖动降到 0.5% —— 比 33ms(1.9%) 还稳。
     *
     * **结论：节拍值必须尽量贴 vsync 周期的整数倍，别取中间值。**
     *   本机 120Hz → 8.3ms 的倍数：16.7 / 25 / 33.3
     *
     * **只影响地图投屏**（DashView 仅被 JavaDashEngine 使用）。
     * 普通投屏走 MirrorController + VdMirror，不碰这个值。
     *
     * 想改就改这一行：
     *   16  = 60fps（CPU 120%，追平游戏手感）
     *   24  = 30fps（当前，CPU 52%，抖动 0.5%，导航足够）
     *   33  = 30fps（CPU 53%，抖动 1.9%，比 24 略差）
     *   ⚠ 避开 20 / 28 这类非整数比的值
     */
    private static final long FRAME_INTERVAL_MS = 24;   /* 实测最优：30fps + 0.5% 抖动 */
    private long lastDrawMs = 0L;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            if (!running) return;
            long nowMs = nanos / 1_000_000L;
            if (nowMs - lastDrawMs >= FRAME_INTERVAL_MS) {
                float dt = lastNanos == 0L ? 0f : (nanos - lastNanos) / 1_000_000_000f;
                lastNanos = nanos;
                lastDrawMs = nowMs;
                step(Math.min(dt, 0.1f));
                invalidate();
            }
            Choreographer.getInstance().postFrameCallback(frame);
        }
    };

    public DashView(Context context) {
        super(context);
        init();
    }

    public DashView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        rd.attach(this);
        /*
         * 默认皮肤 = 经典（老的 DashRenderer 那套）。
         *
         * 必须在 init 里建，因为 ClassicSkin 需要绑上 this ——
         * 它要读 this.data 和 this.autoSpeed，
         * 并且复用 this.rd（不另 new，见 ClassicSkin 的注释）。
         */
        if (skin == null) skin = new ClassicSkin(this);
        setBackgroundColor(theme.bg());
    }

    /**
     * 暴露内部渲染器（包内可见）。
     *
     * 只给 ClassicSkin 用来复用，别在业务代码里直接调 ——
     * 想画画就实现 DashSkin。
     */
    DashRenderer renderer() { return rd; }

    /* ══════════════ 主题 / 皮肤切换 ══════════════ */

    /** 换配色方案（不动画法） */
    public void setTheme(DashTheme t) {
        if (t == null) return;
        theme = t;
        setBackgroundColor(theme.bg());
        invalidate();
    }

    public DashTheme getTheme() { return theme; }

    /**
     * 换整套画面画法。
     *
     * 传进来的 Skin 会被当作新画法，DashRenderer 不再参与
     * （除非你传 ClassicSkin，它内部会转发回 DashRenderer）。
     */
    public void setSkin(DashSkin s) {
        if (s == null) return;
        /*
         * 新皮肤如果也是 ClassicSkin（或其子类），顺手把宿主绑上 ——
         * 否则它从 view == null 直接返回，画面会空白。
         */
        if (s instanceof ClassicSkin) {
            ((ClassicSkin) s).bind(this);
        }
        skin = s;
        invalidate();
    }

    public DashSkin getSkin() { return skin; }

    private void step(float dt) {
        t += dt;
        float k = 1f - HudTheme.expf(-dt * 3.4f);

        float targetSpeed = autoSpeed
                ? HudTheme.clamp(96f + 62f * HudTheme.sinf(t * 0.22f) + 18f * HudTheme.sinf(t * 0.9f), 0f, 196f)
                : data.speed;
        sim += (targetSpeed - sim) * k;

        float targetPow = autoPower
                ? HudTheme.clamp(56f + 120f * HudTheme.absf(HudTheme.sinf(t * 0.35f)) + 40f * HudTheme.sinf(t * 1.1f), 0f, data.powerMax)
                : data.power;
        pow += (targetPow - pow) * k;
    }

    public void start() {
        if (running) return;
        running = true;
        lastNanos = 0L;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
    }

    /** 全量写入数据（从 CAN / WebSocket 回调里构造一个 HudData 传入） */
    public void setData(HudData src) {
        boolean before = data.noa;
        data.copyFrom(src);
        if (data.noa != before) noaPulseAt = t;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 设计稿 1920×538 等比铺满，居中留边
        float s = Math.min(getWidth() / HudLayout.W, getHeight() / HudLayout.H);
        float ox = (getWidth() - HudLayout.W * s) / 2f;
        float oy = (getHeight() - HudLayout.H * s) / 2f;

        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(s, s);
        canvas.clipRect(0f, 0f, HudLayout.W, HudLayout.H);
        /*
         * **走皮肤接口**（2026-11-12）
         *
         * 改造前这里直接是 `rd.draw(canvas, t)`。
         * 现在统一经 skin.draw —— 默认的 ClassicSkin 内部就是
         * 转发给 rd.draw(canvas, t)，所以画面完全一致。
         *
         * 这样自定义 Skin 只要实现 DashSkin.draw 就能整套替换，
         * 不需要碰 DashView。
         */
        if (skin != null) {
            skin.draw(canvas, data, theme, t);
        } else {
            rd.draw(canvas, t);      /* 兜底：skin 还没建好时走老路径 */
        }
        canvas.restore();
    }
}
