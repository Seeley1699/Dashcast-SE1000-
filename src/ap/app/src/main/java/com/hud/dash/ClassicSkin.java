package com.hud.dash;

import android.graphics.Canvas;

/**
 * ══════════════════════════════════════════════════════════════════
 * 经典皮肤 —— 把现有的 DashRenderer 包成 DashSkin
 * ══════════════════════════════════════════════════════════════════
 *
 * **为什么这样包**（2026-11-12）
 *
 * DashRenderer 是 1742 行的绘制内核（23 个 drawXxx 方法），
 * 拆成 Skin 接口不需要重写它 —— 加一层适配即可：
 *
 *   DashView.onDraw
 *     → skin.draw(canvas, data, theme, t)
 *       → ClassicSkin 转发给 DashRenderer.draw()
 *
 * 好处：
 *   · 功能零变化（还是那套画法），可以逐帧对比截图
 *   · 新的 Skin 可以完全不碰 DashRenderer，另起一套画法
 *   · App 里能实时切换：currentSkin = new ClassicSkin() / new MySkin()
 *
 * ── DashRenderer 需要的两个依赖 ──────────────────────────────
 *
 * 1. view —— 它从 DashView 读 autoSpeed/autoPower 和模拟量
 * 2. 数据 —— 它直接读 view.data（HudData 实例，不是副本）
 *
 * 所以构造时要把 DashView 传进去。draw() 里 data 参数会被忽略
 * （用 view.data），保证和第 1742 行那套逻辑完全一致。
 */
public class ClassicSkin implements DashSkin {

    /*
     * **复用宿主已有的 DashRenderer**（2026-11-12 修重复实例）
     *
     * 一开始这里写的是 `new DashRenderer()`，于是：
     *   DashView.rd              → 实例 A（兜底路径用）
     *   ClassicSkin.renderer     → 实例 B（正常路径用）
     * 实例 A 永远走不到，白养一份 Paint/Path/RectF
     * （DashRenderer 靠成员复用实现每帧零分配，一份不便宜）。
     *
     * 更麻烦的是它有自己的 `private float t` —— 两个实例的 t 各存各的，
     * 以后要是有人在 A 上设了状态，B 看不见。
     *
     * 现在统一走 DashView 那一份，Skin 只是转发的壳。
     */
    private DashRenderer renderer;
    private DashView view;

    /** 无参构造：延迟绑定，bind(view) 时再挂 */
    public ClassicSkin() {
    }

    /** 常规构造：由 DashView.init 调用，直接把它的 rd 交进来 */
    public ClassicSkin(DashView host) {
        bind(host);
    }

    /**
     * 绑定宿主 View 和它的渲染器。
     *
     * DashRenderer 内部要读 view.data（真实数据源）和
     * view.autoSpeed / view.autoPower（演示模式开关），
     * 所以必须拿到 DashView 实例。
     *
     * @param host 宿主 View，null 表示解绑
     */
    public ClassicSkin bind(DashView host) {
        this.view = host;
        if (host != null) {
            this.renderer = host.renderer();
            this.renderer.attach(host);
        }
        return this;
    }

    @Override
    public void draw(Canvas c, HudData data, DashTheme theme, float t) {
        if (view == null || renderer == null) return;
        /*
         * 传 t 而不是 data —— DashRenderer 自己从 view.data 取数据。
         * 这样和改造前的行为完全一致，不会因为多传一份 data 引入不一致。
         */
        renderer.draw(c, t);
    }

    @Override
    public String name() {
        return "经典";
    }
}
