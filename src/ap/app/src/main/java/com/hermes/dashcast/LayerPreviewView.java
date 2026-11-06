package com.hermes.dashcast;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 图层遮罩预览视图（可复用组件）。
 *
 * 把画布等比缩到控件里，画出图层形状（矩形/圆角/梯形/椭圆），
 * 拖动图层内部 = 移动，拖右下角 = 缩放，都按网格吸附。
 *
 * 2026-10-31 从 LayerEditorActivity 抽出，供首页右栏和独立编辑器共用：
 * 同一套代码，同样的手感 —— 否则两处维护必然走样。
 */
public class LayerPreviewView extends View {

    /** 尺寸变化回调（拖动时实时更新旁边那行「1920×532 · (0,0) 图层 …」） */
    public interface OnGeometryChanged {
        void onChanged(LayerConfig cfg);
    }

    private final Paint pBg     = new Paint();
    /** 截取预览区专用底色（深蓝），和 pBg（图层遮罩用的白底）分开 */
    private final Paint pCropBg = new Paint();
    private final Paint pLayer  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEdge   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid   = new Paint();

    /**
     * 羽化 / 边框预览用的画笔（2026-11-05 新增）。
     *
     * 用户反馈"调羽化和边框，预览区看不出变化"。
     * 原来预览只画一个纯色矩形（alpha=200 的 accent），
     * 羽化和边框这两个纯 alpha 特性完全没有体现。
     *
     * pFeather 用 RadialGradient 太麻烦（形状不规则），
     * 改用**多层内缩描边**：从边缘向内画 N 层由透明到不透明的线，
     * 天然形成渐变，且对圆角/梯形/椭圆都适用。
     */
    private final Paint pFeather = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBorder  = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 地图模式的"实际抓取区"参考框 */
    private final Paint pMap    = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final LayerConfig cfg;
    private final Context ctx;
    private OnGeometryChanged listener;

    /** 地图模式：画一块虚线参考框，显示这块遮罩会抓到目标窗口的哪一片 */
    private boolean mapMode;
    /** 目标 App 窗口像素尺寸（默认主屏 2560×1600，enterBg 时会更准） */
    private int winW = 2560, winH = 1600;

    /* zoomed 字段已随「放大」按钮一起删除（2026-11-04） */
    private float scale = 1f, ox, oy;

    /* 拖拽状态 */
    private int mode = 0;       /* 0=无 1=移动 2=右下缩放 */
    private float downX, downY;
    private int startX, startY, startW, startH;

    /* ══════════════════════════════════════════════════════════════
     * App 页面截取模式（方案 D：预览区直接拖框）
     * ══════════════════════════════════════════════════════════════
     *
     * 两种模式共用这一个 View，因为它们表达的是同一件事的不同视角：
     *   遮罩模式（默认）= 画布 1920×532 坐标系 → 内容投到仪表哪一块
     *   截取模式（本组字段）= 屏幕 2560×1600 坐标系 → 抓 App 哪一块像素
     *
     * 为什么不做成两个 View：
     *   用户需要在"抓哪块"和"投哪块"之间反复对照，
     *   同一个 View 里切换比并排两个小图直观得多。
     */
    private boolean appCropMode;
    /** 整屏像素（wm size 拿到；默认本车主屏 2560×1600） */
    private int screenW = 2560, screenH = 1600;
    /** 目标 App 窗口在屏幕坐标系里的矩形；全 0 表示未知（不画参考框） */
    private int winL, winT, winR, winB;
    /** 截取模式的缩放与原点 */
    private float ascale = 1f, aox, aoy;
    /** 截取模式的拖拽状态：0=无 1=移动 2=左上 3=右上 4=左下 5=右下
     *  6=上边 7=下边 8=左边 9=右边 */
    private int aMode = 0;
    private int aStartX, aStartY, aStartW, aStartH;
    /** 框外按下时的起点（拖出阈值之前不动原框） */
    private int aNewStartX, aNewStartY;
    private final Paint pCrop  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pWin   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pInset = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pInset2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pInsetBg = new Paint();

    /**
     * 是否把遮罩比例同步成截取区比例（默认开）。
     *
     * 开：拖截取框时遮罩自动匹配 → dcapd2 零缩放，画面不变形。
     *     代价是用户已经调好的遮罩会被改。
     * 关：保留用户的遮罩，但比例不匹配时画面会被拉伸。
     *
     * 由界面上的勾选框控制，持久化在 prefs（layer_sync_aspect）。
     */
    private boolean syncAspectEnabled = true;

    /**
     * 拖动时是否吸附网格（默认开）。
     *
     * ══════════════════════════════════════════════════════════════
     * 2026-11-03 修「拖动不好拖、拖不出想要的效果」
     * ══════════════════════════════════════════════════════════════
     * 原来 MOVE 里每一帧都 `cfg.snap(startX + dx)` —— 按 grid(默认 32px)
     * 取整。于是：
     *   · 手指移 1..31px 画面完全不动（用户以为卡了/拖不动）
     *   · 一旦跨过 32px 边界，画面**突然跳一大格**
     *   · 想微调 10px 根本做不到
     *
     * 而且拖动用的是 `startX + (int) 整数偏移` —— 每个 MOVE 都重新取整，
     * 累积误差让手感更差。
     *
     * 现在：MOVE 全程写**原始浮点换算出的整数**（跟手），
     * ACTION_UP 那一刻才吸附一次。想彻底精确就把这个开关关掉。
     */
    private boolean snapEnabled = true;

    public LayerPreviewView(Context ctx, LayerConfig cfg) {
        super(ctx);
        this.cfg = cfg;
        this.ctx = ctx;

        /* 预览区画布底色：用 surface_deep（#FFFFFF），和网格线形成对比 */
        pBg.setColor(ctx.getColor(R.color.surface_deep));
        pLayer.setColor(ctx.getColor(R.color.accent));
        pLayer.setAlpha(200);
        pEdge.setColor(ctx.getColor(R.color.accent_soft));
        pEdge.setStyle(Paint.Style.STROKE);
        pEdge.setStrokeWidth(2f);
        pHandle.setColor(ctx.getColor(R.color.accent));
        /*
         * 网格线颜色（2026-11-04 浅色 UI）。
         *
         * 原来是 0x22FFFFFF（22% 白）—— 那是深色背景下的"极淡白线"。
         * 现在预览区背景改成纯白（surface_deep = #FFFFFF），
         * 白线画在白底上 = **完全不可见**，用户反馈"网格栏看不见"。
         *
         * 改成 30% 黑（#4D000000）：在白底上是清晰但不刺眼的浅灰网格，
         * 4.5:1 对比度，既看得见又不会抢主体。
         */
        pGrid.setColor(0x26000000);
        pGrid.setStrokeWidth(1f);

        /* 截取模式的画笔：橙色（和遮罩的蓝色区分开，一眼看清哪个是抓取区） */
        /* 截取框：#FFB454 → #E88A1A（白色背景上 2.1:1 → 3.4:1） */
        pCrop.setColor(0xFFE88A1A);
        pCrop.setStyle(Paint.Style.STROKE);
        pCrop.setStrokeWidth(2f);
        /* 目标窗口参考框：灰色细线 */
        pWin.setColor(0x66000000);
        pWin.setStyle(Paint.Style.STROKE);
        pWin.setStrokeWidth(1.5f);
        /* 拖拽手柄：实心橙圆 */
        pInset.setColor(0xFFE88A1A);
        pInset.setStyle(Paint.Style.FILL);
        /* 截取框外的暗色蒙版（突出"只抓这块"） */
        pInsetBg.setColor(0x99000000);
    }

    /* ==================== App 页面截取模式（方案 D）==================== */

    /**
     * 切到 App 截取模式并在预览上拖框。
     *
     * @param on       true = 画屏幕坐标系、拖的是 srcX/Y/W/H
     * @param screenPx 整屏像素 {w,h}（wm size）；{0,0} 表示未知，用默认值
     */
    public void setAppCropMode(boolean on, int[] screenPx) {
        this.appCropMode = on;
        if (screenPx != null && screenPx.length >= 2) {
            if (screenPx[0] > 0) screenW = screenPx[0];
            if (screenPx[1] > 0) screenH = screenPx[1];
        }
        /*
         * 屏幕尺寸变了要重新夹一次 cfg —— 否则上一次在别的分辨率下
         * 存的截取框可能整个落到屏幕外（画都画不出来，用户以为没生效）。
         */
        cfg.sanitizeCrop(screenW, screenH);
        invalidate();
    }

    public boolean isAppCropMode() {
        return appCropMode;
    }

    /**
     * 目标 App 的窗口矩形（屏幕坐标系）。
     * 传 null 或全 0 表示未知 —— 此时不画灰色参考框。
     * 有值时用户能直接看到"App 窗口在哪、我框的是不是窗口内"。
     */
    public void setTargetWindow(int[] rect) {
        if (rect != null && rect.length >= 4 && rect[2] > 0 && rect[3] > 0) {
            winL = rect[0];
            winT = rect[1];
            winR = rect[0] + rect[2];
            winB = rect[1] + rect[3];
        } else {
            winL = winT = winR = winB = 0;
        }
        invalidate();
    }

    /** 截取模式的缩放计算：整屏等比放进控件 */
    private void computeAppScale() {
        float availW = getWidth() - 16f;
        float availH = getHeight() - 16f;
        if (availW <= 0 || availH <= 0) return;
        ascale = Math.min(availW / screenW, availH / screenH);
        aox = (getWidth() - screenW * ascale) / 2f;
        aoy = (getHeight() - screenH * ascale) / 2f;
    }

    public void setOnGeometryChanged(OnGeometryChanged l) {
        this.listener = l;
    }

    /**
     * 地图模式参考框开关。
     *
     * 开启后在预览上叠加一块暖色虚线框，表示"这块遮罩实际会抓目标窗口的
     * 哪一片"。换算规则和 MirrorController.applyMapCrop 完全一致 ——
     * 用户拖遮罩时能直接看到抓取区跟着动，不用反复投屏试。
     *
     * @param winWindowW 目标 App 窗口像素宽（一般 dumpsys window 拿到）
     */
    public void setMapMode(boolean on, int winWindowW, int winWindowH) {
        this.mapMode = on;
        if (winWindowW > 0) this.winW = winWindowW;
        if (winWindowH > 0) this.winH = winWindowH;
        invalidate();
    }


    public void refresh() {
        cfg.sanitize();
        invalidate();
    }

    private void computeScale() {
        float availW = getWidth() - 16f;
        float availH = getHeight() - 16f;
        if (availW <= 0 || availH <= 0) return;
        scale = Math.min(availW / cfg.canvasW, availH / cfg.canvasH);
        ox = (getWidth() - cfg.canvasW * scale) / 2f;
        oy = (getHeight() - cfg.canvasH * scale) / 2f;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (appCropMode) {
            drawAppCropMode(c);
            return;
        }
        computeScale();
        c.drawRect(0, 0, getWidth(), getHeight(), pBg);

        /* 画布边界 */
        pEdge.setColor(0x44000000);
        c.drawRect(ox, oy, ox + cfg.canvasW * scale, oy + cfg.canvasH * scale, pEdge);

        /* 网格 */
        if (cfg.grid > 0) {
            pEdge.setColor(0x22000000);
            for (int gx = 0; gx <= cfg.canvasW; gx += cfg.grid) {
                float px = ox + gx * scale;
                c.drawLine(px, oy, px, oy + cfg.canvasH * scale, pGrid);
            }
            for (int gy = 0; gy <= cfg.canvasH; gy += cfg.grid) {
                float py = oy + gy * scale;
                c.drawLine(ox, py, ox + cfg.canvasW * scale, py, pGrid);
            }
        }

        /* 图层形状 */
        RectF r = new RectF(ox + cfg.x * scale, oy + cfg.y * scale,
                ox + (cfg.x + cfg.w) * scale, oy + (cfg.y + cfg.h) * scale);
        Path path = buildPath(r);

        /*
         * ══════════════════════════════════════════════════════════════
         * **预览要反映羽化和边框**（2026-11-05）
         * ══════════════════════════════════════════════════════════════
         *
         * 用户反馈："调羽化、边框，预览区看不出变化"。
         *
         * 原来这里只有一个纯色矩形（alpha=200 的 accent），
         * 而羽化（边缘 alpha 渐变）和边框（边缘压暗一圈）
         * 都是**纯 alpha 通道特性**，纯色填充完全体现不出来。
         *
         * 现在按三层画：
         *   ① 主体填充（半透明 accent）
         *   ② **羽化**：从边缘向内画 N 层渐透明的描边
         *      —— 天然形成"边缘渐渐隐去"的观感，
         *         对矩形/圆角/梯形/椭圆都适用，不需要 RadialGradient
         *   ③ **边框**：沿路径画一条压暗的实线
         *
         * 注意：feather 和 border 都是**画布像素**，要先按 scale 缩到控件坐标。
         */

        /* ① 主体填充 */
        pLayer.setColor(cfg.enabled ? ctx.getColor(R.color.accent)
                                    : ctx.getColor(R.color.content_tertiary));
        pLayer.setAlpha(cfg.enabled ? 200 : 120);
        c.drawPath(path, pLayer);

        /* ② 羽化预览 */
        if (cfg.enabled && cfg.feather > 0) {
            float fpx = cfg.feather * scale;       /* 画布 px → 控件 px */
            if (fpx > 0.5f) {
                /*
                 * 层数固定 10 层（不管 feather 多大）：
                 *   · 太少 → 看得出明显的台阶
                 *   · 太多 → 每次 onDraw 画几十条路径，拖动时卡
                 * 10 层在 96px 羽化下每层约 10px，肉眼看是连续渐变。
                 */
                int steps = 10;
                pFeather.setStyle(Paint.Style.STROKE);
                pFeather.setStrokeWidth(fpx / steps + 1f);
                for (int i = 1; i <= steps; i++) {
                    /*
                     * t 从 0（最外层，几乎透明）到 1（最内层，接近主体）
                     * alpha 从 0 到 ~120（不超过主体 200，否则会盖成实心）
                     */
                    float t = (float) i / steps;
                    int a = (int) (t * t * 120f);
                    if (a <= 0) continue;
                    pFeather.setColor(ctx.getColor(R.color.accent));
                    pFeather.setAlpha(a);

                    /* 内缩 i 层 —— 用 Path 缩放实现，保证圆角/梯形也跟着缩 */
                    c.save();
                    float insetPx = (fpx / steps) * i;
                    /* 以路径中心缩放，缩放比 = (宽-2*inset)/宽 */
                    float cx0 = r.centerX(), cy0 = r.centerY();
                    float sx = (r.width() > 2 * insetPx)
                            ? (r.width() - 2 * insetPx) / r.width() : 0f;
                    float sy = (r.height() > 2 * insetPx)
                            ? (r.height() - 2 * insetPx) / r.height() : 0f;
                    if (sx <= 0f || sy <= 0f) { c.restore(); continue; }
                    c.scale(sx, sy, cx0, cy0);
                    c.drawPath(path, pFeather);
                    c.restore();
                }
            }
        }

        /* ③ 边框预览 */
        if (cfg.enabled && cfg.border > 0) {
            float bpx = cfg.border * scale;        /* 画布 px → 控件 px */
            if (bpx > 0.5f) {
                /*
                 * 边框是"从边缘向内 bpx 宽的一圈，颜色压暗到 40%"。
                 * 预览里画一条**居中的粗描边**：宽度 bpx，颜色是 accent 压暗。
                 * 描边以路径为中心，所以会各向外/内扩 bpx/2 —— 视觉上和
                 * 真实效果（全在内部）略有差异，但预览只需要"看得出来"。
                 */
                pBorder.setStyle(Paint.Style.STROKE);
                pBorder.setStrokeWidth(bpx);
                int base = ctx.getColor(R.color.accent);
                /* 压暗到 55%，同时提高不透明度让它在半透明主体上能看见 */
                int rr = (int) (((base >> 16) & 0xFF) * 0.55f);
                int gg = (int) (((base >> 8) & 0xFF) * 0.55f);
                int bb = (int) ((base & 0xFF) * 0.55f);
                pBorder.setColor(0xFF000000 | (rr << 16) | (gg << 8) | bb);
                pBorder.setAlpha(230);
                c.drawPath(path, pBorder);
            }
        }

        /* ④ 轮廓线（细，用来标出精确边界） */
        pEdge.setColor(cfg.enabled ? ctx.getColor(R.color.accent)
                                  : ctx.getColor(R.color.content_secondary));
        pEdge.setStrokeWidth(2f);
        c.drawPath(path, pEdge);

        /* 地图模式：实际抓取区参考框 */
        if (mapMode) drawMapCropHint(c);

        /* 右下角拖拽柄 */
        c.drawCircle(r.right, r.bottom, dp(9), pHandle);
    }

    /**
     * 截取模式绘制：整屏缩略图 + 可拖的橙色截取框 + 灰色目标窗口参考框。
     *
     * 视觉层次（从底到顶）：
     *   ① 背景（控件底色）
     *   ② 屏幕区域（稍亮的矩形，表示整块屏幕）
     *   ③ 目标窗口参考框（灰色细线，"App 窗口在哪"）
     *   ④ 截取框外的暗色蒙版（把"不抓的部分"压暗，突出截取区）
     *   ⑤ 橙色截取框 + 8 个拖拽手柄 + 尺寸标签
     */
    private void drawAppCropMode(Canvas c) {
        computeAppScale();
        /*
         * 截取预览区底色：**深蓝**（2026-11-04 用户要求"蓝底+白网格"）。
         *
         * 用独立的 pCropBg，不复用 pBg —— 因为 pBg 同时被「图层遮罩」
         * 那个预览用着（那里要保持白底）。两个模式共用一块画布，
         * 混用画笔会互相污染。
         */
        /* 底色跟色板走（冷雾灰 #F5F7FA），不再硬编码纯白 */
        pCropBg.setColor(ctx.getColor(R.color.surface_deep));
        c.drawRect(0, 0, getWidth(), getHeight(), pCropBg);

        /*
         * 【方案 C】底图：有截图就直接画满整屏，用户"看着真实界面框选"。
         * 没有截图时退回网格（老行为），不影响使用。
         */
        if (hasBackgroundShot()) {
            android.graphics.RectF dst = new android.graphics.RectF(
                    aox, aoy,
                    aox + screenW * ascale, aoy + screenH * ascale);
            try {
                c.drawBitmap(bgShot, null, dst, pShot);
            } catch (Throwable ignored) {
            }
        }

        /* ② 屏幕区域 */
        pEdge.setColor(0x44000000);
        pEdge.setStrokeWidth(2f);
        c.drawRect(aox, aoy, aox + screenW * ascale, aoy + screenH * ascale, pEdge);

        /*
         * 网格间距与线色（2026-11-04 修正"深灰底"问题）。
         *
         * 现象：截取预览区看起来是深灰底，网格看不清。
         * 实测像素：背景 #FFFFFF（白），网格线 #666666（40% 黑）。
         *
         * 两个原因：
         *   ① 间距太密 —— grid=80（屏幕像素），而 ascale 约 0.22，
         *      换算到控件上每 17px 一条线，密密麻麻叠成灰面。
         *      改成 screenW/16（约 160），控件上是 35px 一条。
         *   ② 线色被污染 —— pEdge 和 pWin 都是 STROKE，
         *      画完窗口框后 pWin 的 0x66 遗留，网格跟着变深。
         *      改用独立的 pGrid 画笔，颜色锁死 0x2E000000（18% 黑）。
         */
        int grid = Math.max(120, screenW / 16);
        /*
         * 网格线：深蓝底上必须用**白线**（黑线在深蓝上完全看不见）。
         *
         *   有底图 → 40% 白（不盖住画面）
         *   无底图 → 55% 白（清晰可数又不刺眼）
         */
        pGrid.setColor(hasBackgroundShot() ? 0x1A000000 : 0x2E000000);
        pGrid.setStrokeWidth(1f);
        pGrid.setStyle(Paint.Style.STROKE);
        for (int gx = 0; gx <= screenW; gx += grid) {
            float px = aox + gx * ascale;
            c.drawLine(px, aoy, px, aoy + screenH * ascale, pGrid);
        }
        for (int gy = 0; gy <= screenH; gy += grid) {
            float py = aoy + gy * ascale;
            c.drawLine(aox, py, aox + screenW * ascale, py, pGrid);
        }

        /* ③ 目标窗口参考框 */
        if (winR > winL && winB > winT) {
            float wl = aox + winL * ascale, wt = aoy + winT * ascale;
            float wr = aox + winR * ascale, wb = aoy + winB * ascale;
            c.drawRect(wl, wt, wr, wb, pWin);
            pWin.setTextSize(dp(11));
            c.drawText("App 窗口", wl + dp(4), wt + dp(14), pWin);
        }

        /* 截取框（屏幕坐标 → 控件坐标） */
        RectF cr = new RectF(
                aox + cfg.srcX * ascale,
                aoy + cfg.srcY * ascale,
                aox + (cfg.srcX + cfg.srcW) * ascale,
                aoy + (cfg.srcY + cfg.srcH) * ascale);

        /*
         * ══ 框外蒙版：**去掉了**（2026-11-04）══
         *
         * 原来用 pInsetBg（60% 黑）填满截取框外的四个矩形：
         *   c.drawRect(上/下/左/右, pInsetBg)
         *
         * 当截取框很大时这是"暗角高亮"效果，挺好。
         * 但当框很小（用户当前 240×176，只占屏幕 0.9%）时，
         * 那 99.1% 的区域全被涂成深灰 —— 整块预览区看起来
         * 是一大片深灰底，网格线全被盖住。
         *
         * 用户反馈"这个深灰请改成跟第二张一样（白底黑网格）"。
         * 所以直接不画蒙版，让白底 + 黑网格始终清晰可见，
         * 只靠橙色框 + 手柄标识截取范围。
         */
        /* 框外不做蒙版（保持底图/网格清晰） */
        float sw = aox + screenW * ascale, sh = aoy + screenH * ascale;

        /* ⑤ 橙色截取框 */
        /* 截取框：#FFB454 → #E88A1A（白色背景上 2.1:1 → 3.4:1） */
        pCrop.setColor(0xFFE88A1A);      /* 每帧显式设色，别依赖上次的值 */
        pCrop.setStyle(Paint.Style.STROKE);
        pCrop.setPathEffect(null);
        pCrop.setStrokeWidth(2.5f);
        c.drawRect(cr, pCrop);

        /* 尺寸标签（框内左上角） */
        pCrop.setStyle(Paint.Style.FILL);
        pCrop.setTextSize(dp(12));
        c.drawText(cfg.srcW + "×" + cfg.srcH, cr.left + dp(6), cr.top + dp(16), pCrop);
        c.drawText("(" + cfg.srcX + "," + cfg.srcY + ")", cr.left + dp(6), cr.top + dp(32), pCrop);

        /* 8 个拖拽手柄 + 4 个角柄 */
        pInset.setColor(0xFFE88A1A);
        drawHandle(c, cr.left, cr.top);
        drawHandle(c, cr.right, cr.top);
        drawHandle(c, cr.left, cr.bottom);
        drawHandle(c, cr.right, cr.bottom);
        drawHandle(c, (cr.left + cr.right) / 2f, cr.top);
        drawHandle(c, (cr.left + cr.right) / 2f, cr.bottom);
        drawHandle(c, cr.left, (cr.top + cr.bottom) / 2f);
        drawHandle(c, cr.right, (cr.top + cr.bottom) / 2f);

        /* 比例警告（左下角，跟仪表比例差太多就变红） */
        float r = cfg.srcH > 0 ? (float) cfg.srcW / cfg.srcH : 0f;
        float target = (float) cfg.canvasW / cfg.canvasH;
        boolean ok = Math.abs(r - target) < 0.15f;
        /* 比例提示文字：白底上要用深灰/深橙，原来的浅灰浅橙看不见 */
            pCrop.setColor(ok ? 0xFF6B6B70 : 0xFFC77800);
        pCrop.setTextSize(dp(11));
        c.drawText(String.format(Locale.US, "%.2f:1  %s  仪表 %.2f:1",
                r, ok ? "✓" : "⚠", target), aox + dp(4), sh - dp(6), pCrop);
    }

    private void drawHandle(Canvas c, float x, float y) {
        c.drawCircle(x, y, dp(7), pInset);
        /* 内圈白点，在深色背景上也看得见 */
        pInset2.setColor(0xFFFFFFFF);
        pInset2.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, dp(2), pInset2);
        pInset.setColor(0xFFE88A1A);
    }

    /**
     * 画"实际抓取区"参考框。
     *
     * 逻辑必须和 MirrorController.applyMapCrop 一致，否则预览和实际不符 ——
     * 那边是：
     *   ① 遮罩转成画布百分比区间
     *   ② 遮罩按原比例放进窗口（居中）
     *   ③ 按百分比切出那一段
     *
     * 这里反过来：把窗口坐标系里的抓取区，按等比映射回画布坐标系，
     * 再叠加在遮罩上。用户看到的就是"这块遮罩会抓到窗口的哪一片"。
     */
    private void drawMapCropHint(Canvas c) {
        if (cfg.w >= cfg.canvasW && cfg.h >= cfg.canvasH) return;   // 整屏不用提示

        float px0 = (float) cfg.x / cfg.canvasW;
        float py0 = (float) cfg.y / cfg.canvasH;
        float px1 = (float) (cfg.x + cfg.w) / cfg.canvasW;
        float py1 = (float) (cfg.y + cfg.h) / cfg.canvasH;

        float maskAspect = (float) cfg.w / cfg.h;
        float winAspect = (float) winW / winH;

        float mw, mh, mx, my;
        if (winAspect >= maskAspect) {
            mh = winH;
            mw = mh * maskAspect;
            mx = (winW - mw) / 2f;
            my = 0f;
        } else {
            mw = winW;
            mh = mw / maskAspect;
            mx = 0f;
            my = (winH - mh) / 2f;
        }

        /* 抓取区（窗口坐标） */
        float gx = mx + mw * px0;
        float gy = my + mh * py0;
        float gw = mw * (px1 - px0);
        float gh = mh * (py1 - py0);

        /* 窗口 → 画布：等比放进画布，居中 */
        float k = (winAspect >= (float) cfg.canvasW / cfg.canvasH)
                ? (float) cfg.canvasH / winH
                : (float) cfg.canvasW / winW;

        float spanX = winW * k;
        float spanY = winH * k;
        float baseX = (cfg.canvasW - spanX) / 2f;
        float baseY = (cfg.canvasH - spanY) / 2f;

        float rx = ox + (baseX + gx * k) * scale;
        float ry = oy + (baseY + gy * k) * scale;
        float rw = gw * k * scale;
        float rh = gh * k * scale;

        pMap.setColor(0xFFFFB454);
        pMap.setStyle(Paint.Style.STROKE);
        pMap.setStrokeWidth(2f);
        pMap.setPathEffect(new DashPathEffect(new float[]{ dp(9), dp(5) }, 0));
        c.drawRect(rx, ry, rx + rw, ry + rh, pMap);

        pMap.setPathEffect(null);
        pMap.setStyle(Paint.Style.FILL);
        pMap.setTextSize(dp(11));
        c.drawText("抓 " + (int) gw + "×" + (int) gh, rx + dp(4), ry - dp(4), pMap);
    }

    /** 按形状类型构造路径（与 C 侧 shape_distance 视觉一致） */
    private Path buildPath(RectF r) {
        Path p = new Path();
        switch (cfg.shape) {
            case LayerConfig.SHAPE_ELLIPSE: {
                p.addOval(r, Path.Direction.CW);
                break;
            }
            case LayerConfig.SHAPE_TRAP: {
                float insT = cfg.trapTop * scale;
                float insB = cfg.trapBot * scale;
                p.moveTo(r.left + insT, r.top);
                p.lineTo(r.right - insT, r.top);
                p.lineTo(r.right - insB, r.bottom);
                p.lineTo(r.left + insB, r.bottom);
                p.close();
                break;
            }
            case LayerConfig.SHAPE_ROUND: {
                /* 四角不同半径 → 用 4 段 arcTo 拼（addRoundRect 只能统一半径） */
                float tl = cfg.rTl * scale, tr = cfg.rTr * scale;
                float br = cfg.rBr * scale, bl = cfg.rBl * scale;
                p.moveTo(r.left + tl, r.top);
                p.lineTo(r.right - tr, r.top);
                if (tr > 0) p.arcTo(new RectF(r.right - 2 * tr, r.top,
                        r.right, r.top + 2 * tr), -90, 90, false);
                p.lineTo(r.right, r.bottom - br);
                if (br > 0) p.arcTo(new RectF(r.right - 2 * br, r.bottom - 2 * br,
                        r.right, r.bottom), 0, 90, false);
                p.lineTo(r.left + bl, r.bottom);
                if (bl > 0) p.arcTo(new RectF(r.left, r.bottom - 2 * bl,
                        r.left + 2 * bl, r.bottom), 90, 90, false);
                p.lineTo(r.left, r.top + tl);
                if (tl > 0) p.arcTo(new RectF(r.left, r.top,
                        r.left + 2 * tl, r.top + 2 * tl), 180, 90, false);
                p.close();
                break;
            }
            default:
                p.addRect(r, Path.Direction.CW);
                break;
        }
        return p;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        /*
         * ══════════════════════════════════════════════════════════════
         * **按下就抢走父容器的触摸事件**（2026-11-03 —— "滑不动"的根因）
         * ══════════════════════════════════════════════════════════════
         *
         * 预览视图在右栏的 ScrollView 里。Android 事件分发规则：
         * 子 View 收到 DOWN 后，父 ScrollView 仍可在 MOVE 阶段
         * **拦截**（onInterceptTouchEvent）把事件抢走用于滚动。
         *
         * 只要手指移动带一点垂直分量（拖框时几乎必然如此），
         * ScrollView 就判定"用户在滚动" → 抢走后续事件 →
         * 我们的 ACTION_MOVE / ACTION_UP 根本收不到 →
         * **框纹丝不动**，用户感受就是"根本滑不动"。
         *
         * 修法：DOWN 那一刻立刻 requestDisallowInterceptTouchEvent(true)，
         * 明确告诉父容器"这段手势归我"。手指抬起后再放开。
         */
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
        }

        if (appCropMode) return onTouchAppCrop(e);

        computeScale();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX(); downY = e.getY();
                startX = cfg.x; startY = cfg.y;
                startW = cfg.w; startH = cfg.h;

                /* 靠近右下角 = 缩放，否则 = 移动 */
                float hx = ox + (cfg.x + cfg.w) * scale;
                float hy = oy + (cfg.y + cfg.h) * scale;
                float dist = (float) Math.hypot(e.getX() - hx, e.getY() - hy);
                mode = (dist < dp(24)) ? 2 : 1;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = (e.getX() - downX) / scale;
                float dy = (e.getY() - downY) / scale;

                /*
                 * **拖动全程不取整**（2026-11-03 手感修复）。
                 *
                 * 原来这里每帧 cfg.snap(...)，按 grid 取整：
                 * 手指移 1~31px 画面纹丝不动，跨过 32px 突然跳一格 ——
                 * 用户的原话就是"拖动不好拖，拖不出想要的效果"。
                 *
                 * 现在写原始值（跟手），松手才吸附一次（见 ACTION_UP）。
                 */
                if (mode == 1) {
                    cfg.x = startX + (int) dx;
                    cfg.y = startY + (int) dy;
                } else if (mode == 2) {
                    cfg.w = startW + (int) dx;
                    cfg.h = startH + (int) dy;
                }
                cfg.sanitize();
                invalidate();
                notifyChanged();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode != 0) {
                    /* 松手才吸附一次（开关关掉就保留用户拖到的精确值） */
                    if (snapEnabled && cfg.grid > 0) {
                        cfg.x = cfg.snap(cfg.x);
                        cfg.y = cfg.snap(cfg.y);
                        cfg.w = cfg.snap(cfg.w);
                        cfg.h = cfg.snap(cfg.h);
                    }
                    mode = 0;
                    cfg.sanitize();
                    /* 只改内存，点「保存」才落盘并同步仪表 */
                    refresh();
                    notifyChanged();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /* ══════════════════════════════════════════════════════════════
     * 截取模式的手势处理（方案 D 核心）
     * ══════════════════════════════════════════════════════════════
     *
     * 命中判定顺序：8 个手柄 → 框内（移动）→ 框外（新建框）
     *
     * 为什么框外按下要"新建框"而不是忽略：
     *   用户想重画一块区域时，直接在新位置拖一下最自然；
     *   如果只允许拖手柄，就得先把旧框缩到最小再重画，很别扭。
     */
    private boolean onTouchAppCrop(MotionEvent e) {
        computeAppScale();
        RectF cr = new RectF(
                aox + cfg.srcX * ascale,
                aoy + cfg.srcY * ascale,
                aox + (cfg.srcX + cfg.srcW) * ascale,
                aoy + (cfg.srcY + cfg.srcH) * ascale);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX();
                downY = e.getY();
                aStartX = cfg.srcX; aStartY = cfg.srcY;
                aStartW = cfg.srcW; aStartH = cfg.srcH;

                aMode = hitTestHandles(e.getX(), e.getY(), cr);
                if (aMode == 0 && cr.contains(e.getX(), e.getY())) {
                    aMode = 1;   /* 框内 → 移动 */
                } else if (aMode == 0) {
                    /*
                     * 框外按下 —— **先不重画**（2026-11-03 手感修复）。
                     *
                     * 原来是"一按框外就把框重置成 16×16 并从这里重画"，
                     * 于是用户想微调时手指稍微偏出框一点点，整块框就被清掉，
                     * 表现就是"拖动不好拖、拖不出想要的效果"。
                     *
                     * 现在记下起点，等**真的拖出 8dp** 才开始重画；
                     * 没拖起来的轻点一律不动原框。
                     */
                    aMode = 10;
                    aNewStartX = clampScreen((int) ((e.getX() - aox) / ascale), 0, screenW);
                    aNewStartY = clampScreen((int) ((e.getY() - aoy) / ascale), 0, screenH);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = (e.getX() - downX) / ascale;
                float dy = (e.getY() - downY) / ascale;

                if (aMode == 10) {
                    /* 拖出阈值才真的开始重画，否则保留原框（微调时不会误清） */
                    double finger = Math.hypot(e.getX() - downX, e.getY() - downY);
                    if (finger < dp(8)) return true;
                    aStartX = aNewStartX;
                    aStartY = aNewStartY;
                    aStartW = 0;
                    aStartH = 0;
                }

                applyCropDrag(dx, dy);
                cfg.sanitizeCrop(screenW, screenH);
                /* 【方案 A】拖动中显示放大镜，手指上方 3× 放大 */
                showMagnifier(e.getX(), e.getY());
                /*
                 * **拖完立即把遮罩比例同步成截取比例**。
                 *
                 * 这是 dcapd2 的属性决定的：
                 *   编码尺寸 = 遮罩尺寸（recompute_enc_size）
                 *   抓取区  = src W×H
                 *   captureScreen 拿到 src 尺寸的位图后，
                 *   会 blit 到画布上的遮罩矩形 —— **拉伸到遮罩大小**。
                 *
                 * 两个比例不一致时就变形。实测：src 2560×544（4.71:1）
                 * 投到遮罩 1920×532（3.61:1），横向被压扁 24%，
                 * 用户看到画面变形，以为"截取区没生效"。
                 *
                 * 同步策略：保住遮罩的"面积/位置"感觉，只调宽高比例，
                 * 且贴在画布内。这样用户拖框时仪表上的成品永远不变形。
                 */
                /*
                 * **H.264 下不要反过来改遮罩**（2026-11-03 修正）。
                 *
                 * lockCropAspect() 已经让"截取区跟随遮罩比例"，遮罩是主动方。
                 * 这里若再让遮罩跟随截取区，两个方向互相拉扯 ——
                 * 拖一下框，遮罩从 608×416 变 608×610（1.46→1.00），
                 * 整个仪表布局跟着变，用户完全无法控制（实测踩到）。
                 *
                 * 所以 H.264 档拖拽时不碰遮罩，只让截取区适配遮罩。
                 * BGRA 档保持原行为（有 alpha，不变形）。
                 */
                if (MirrorController.getCodec() != MirrorController.CODEC_H264) {
                    syncMaskAspectToCrop();
                }
                invalidate();
                notifyChanged();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dismissMagnifier();   /* 【方案 A】手指抬起即收起放大镜 */
                if (aMode != 0) {
                    aMode = 0;
                    cfg.sanitizeCrop(screenW, screenH);
                    refresh();
                    notifyChanged();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /**
     * 把遮罩（画布坐标）的宽高比例同步成截取区（屏幕坐标）的比例。
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么必须同步
     * ══════════════════════════════════════════════════════════════
     * dcapd2 编码尺寸 = 遮罩尺寸，而抓取区决定"抓屏幕哪块"。
     * captureScreen 拿到 src 大小的位图，再 blit 到画布上的遮罩矩形 ——
     * **必然拉伸到遮罩大小**。比例不一致就变形。
     *
     * 实测：src 2560×544（4.71:1）投到遮罩 1920×532（3.61:1），
     * 横向压扁 24%，用户以为"截取区没生效"。
     *
     * ══════════════════════════════════════════════════════════════
     * 怎么同步才不跳
     * ══════════════════════════════════════════════════════════════
     * 不能简单"保持宽度、按比例算高度" —— 那样遮罩会突然变很高/很扁。
     * 这里用**面积守恒**：先按新比例算出一个面积接近原遮罩的矩形，
     * 再贴到画布内。用户感觉是"框在轻微变形"，而不是"整个跳变"。
     *
     * 同步后如果遮罩顶到画布边界，往里收，绝不出界。
     */
    private void syncMaskAspectToCrop() {
        /*
         * ══════════════════════════════════════════════════════════════
         * **H.264 下必须强制同步**（2026-11-03）
         * ══════════════════════════════════════════════════════════════
         *
         * 用户关掉「同步遮罩比例」时，截取区与遮罩比例可以不一致 ——
         * BGRA 还能靠"等比留黑边"补救（有 alpha 通道填透明）。
         *
         * 但 H.264 **没有 alpha**，dcapd2 里是硬编码：
         *     if (g_codec == H264 || !fit) { dw = boxW; dh = boxH; }
         * 即：无条件把截取画面**拉满遮罩**。比例不一致就是肉眼可见的压扁。
         *
         * 实测：截取 2071×1275 (1.62:1) 投到遮罩 608×416 (1.46:1)
         *       → 横向压缩 11%，用户报"H.264 下拖选的页面跟投屏的不一致，
         *         LZ4 倒是一模一样"。
         *
         * 所以 H.264 档**无视用户开关**，一律同步。
         */
        boolean h264 = MirrorController.getCodec() == MirrorController.CODEC_H264;
        if (!syncAspectEnabled && !h264) return;
        if (cfg.srcW <= 0 || cfg.srcH <= 0) return;

        double want = (double) cfg.srcW / cfg.srcH;      /* 截取区比例 */

        /* 当前遮罩面积 */
        double area = (double) cfg.w * cfg.h;
        if (area <= 0) area = (double) cfg.canvasW * cfg.canvasH;

        /*
         * **按 16 的倍数直接解出 w/h**（不是算完再让 sanitize 去砍）。
         *
         * 为什么：H.264 硬编要求 16 对齐，dcapd2 里是向下取整
         * （`(bw/16)*16`）。如果这里算出 1647×350，
         * sanitize 会砍成 1632×336 → 比例 4.86:1，
         * 而截取区是 4.71:1 —— **又漂了 3%，照样变形**。
         *
         * 所以直接在 16 的网格上找最接近目标比例的那一对：
         * 先把面积开方换成"格数"，再四舍五入，最后乘回 16。
         */
        int nw = (int) Math.round(Math.sqrt(area * want) / 16.0) * 16;
        int nh = (int) Math.round(Math.sqrt(area / want) / 16.0) * 16;

        /* 夹到画布内（按 16 的倍数夹，避免夹完又不对齐） */
        if (nw < 16) nw = 16;
        if (nh < 16) nh = 16;
        int maxW = (cfg.canvasW / 16) * 16;
        int maxH = (cfg.canvasH / 16) * 16;
        if (nw > maxW) {
            nw = maxW;
            nh = Math.max(16, (int) Math.round(nw / want / 16.0) * 16);
        }
        if (nh > maxH) {
            nh = maxH;
            nw = Math.max(16, (int) Math.round(nh * want / 16.0) * 16);
        }
        if (nw > maxW) nw = maxW;
        if (nh > maxH) nh = maxH;

        /* 中心不变（用户拖框时遮罩位置不该乱跑） */
        int cx = cfg.x + cfg.w / 2;
        int cy = cfg.y + cfg.h / 2;

        cfg.w = nw;
        cfg.h = nh;
        cfg.x = cx - nw / 2;
        cfg.y = cy - nh / 2;

        /* 贴边时往里收 */
        if (cfg.x < 0) cfg.x = 0;
        if (cfg.y < 0) cfg.y = 0;
        if (cfg.x + cfg.w > cfg.canvasW) cfg.x = cfg.canvasW - cfg.w;
        if (cfg.y + cfg.h > cfg.canvasH) cfg.y = cfg.canvasH - cfg.h;
        if (cfg.x < 0) cfg.x = 0;
        if (cfg.y < 0) cfg.y = 0;
    }

    /** 供外部强制同步一次遮罩比例（绕过拖拽的路径要调） */
    /**
     * 立刻把遮罩比例对齐到截取区比例（并**落盘**）。
     *
     * **必须落盘**（2026-11-06 修）。
     *
     * 原来这里只改内存里的 cfg：syncMaskAspectToCrop() 改宽高、
     * sanitize() 夹边界、invalidate() 重绘 —— 看起来是生效了，
     * 但 MirrorController 校验比例时读的是 **prefs**：
     *
     *     LayerConfig.loadFromPrefs(ctx, ...) → 比 w/h 与遮罩比例
     *
     * 内存改了、prefs 没改 → 它读到的还是旧值，于是日志一直报
     *   「H.264 截取比例与遮罩不一致 src=1.400 box=1.269 偏差 10.3%」
     * 而 dcapd2 照旧把内容拉伸铺满遮罩 —— 用户看到画面变形。
     *
     * 加上 saveToPrefs 之后，两条读取路径看到的是同一份数据。
     */
    public void syncMaskAspectNow() {
        syncMaskAspectToCrop();
        cfg.sanitize();
        cfg.saveToPrefs(getContext());
        invalidate();
    }

    /** 开关：是否让遮罩比例跟随截取区（关掉就保留用户自己的遮罩） */
    public void setSyncAspectEnabled(boolean on) {
        this.syncAspectEnabled = on;
    }

    /** 开关：拖动过程要不要按网格吸附（关掉 = 像素级精确拖动） */
    public void setSnapEnabled(boolean on) {
        this.snapEnabled = on;
    }

    public boolean isSnapEnabled() {
        return snapEnabled;
    }


    /** 画布尺寸变了（CP 分辨率换过）→ 重新夹一遍遮罩 */
    public void setCanvasSize(int w, int h) {
        if (w < 1 || h < 1) return;
        if (cfg.canvasW == w && cfg.canvasH == h) return;
        /*
         * 画布变大/变小后，原来铺满的遮罩要跟着重新铺满 ——
         * 否则新画布上只在左上角盖一块，"遮罩明明铺满了网格，
         * 仪表却铺不满"就是这么来的。
         */
        boolean wasFull = cfg.x == 0 && cfg.y == 0
                && cfg.w == cfg.canvasW && cfg.h == cfg.canvasH;
        cfg.canvasW = w;
        cfg.canvasH = h;
        if (wasFull) {
            cfg.w = w;
            cfg.h = h;
        }
        cfg.sanitize();
        invalidate();
    }

    public boolean isSyncAspectEnabled() {
        return syncAspectEnabled;
    }

    /** 命中测试：8 个手柄（返回值即 aMode） */
    private int hitTestHandles(float x, float y, RectF cr) {
        /*
         * 命中半径从 dp(22) 放大到 dp(34)（2026-11-03）。
         *
         * 用户反馈"拖动不好拖，无法拖出想要的效果"。原因：
         *   · 手指在 2560×1600 的缩略图上，1px 屏幕 ≈ 0.5px 控件，
         *     手柄视觉半径只有 dp(7)，命中圈 dp(22) 仍偏小
         *   · 4 个角 + 4 条边挤在一个 300dp 高的预览里，容易点错
         *
         * 放大到 dp(34) 后手指能稳稳按到；同时把"边"手柄的判定
         * 收窄一点（只在边的中点附近），避免挤压角手柄。
         */
        float tCorner = dp(34);   /* 角：宽松 */
        float tEdge = dp(26);     /* 边：稍紧，别抢角 */
        float cx = (cr.left + cr.right) / 2f;
        float cy = (cr.top + cr.bottom) / 2f;

        /* 角（优先判定，半径更大） */
        if (near(x, y, cr.left,  cr.top,    tCorner)) return 2;   /* 左上 */
        if (near(x, y, cr.right, cr.top,    tCorner)) return 3;   /* 右上 */
        if (near(x, y, cr.left,  cr.bottom, tCorner)) return 4;   /* 左下 */
        if (near(x, y, cr.right, cr.bottom, tCorner)) return 5;   /* 右下 */
        /* 边（只在中点附近一段内有效，且要求另一轴靠近边线） */
        if (Math.abs(x - cx) < cr.width() / 3f && Math.abs(y - cr.top) < tEdge) return 6;
        if (Math.abs(x - cx) < cr.width() / 3f && Math.abs(y - cr.bottom) < tEdge) return 7;
        if (Math.abs(y - cy) < cr.height() / 3f && Math.abs(x - cr.left) < tEdge) return 8;
        if (Math.abs(y - cy) < cr.height() / 3f && Math.abs(x - cr.right) < tEdge) return 9;
        return 0;
    }

    private boolean near(float x, float y, float tx, float ty, float tol) {
        return Math.hypot(x - tx, y - ty) < tol;
    }

    private int clampScreen(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 按拖拽方向改截取框。
     *
     * ══════════════════════════════════════════════════════════════
     * **用 left/top/right/bottom 四个边算，不要用"对角点"那套**
     * ══════════════════════════════════════════════════════════════
     *
     * 我第一版写的是 setCropFromOpposite(固定对角, 移动角)，
     * 结果把 `idx`（相对按下点的**偏移量**）当成了绝对坐标传进去 ——
     * 于是拖角时框会跳到屏幕左上角附近再被夹成一条，完全不可用。
     * 边拖拽那几条更糟：有的分支两个点 y 相同 → 高度算成 0。
     *
     * 现在改成先算出 l/t/r/b 四条边，谁动谁算，清楚不会错：
     *   · 拖左边 → l 动，r 不动
     *   · 拖上边 → t 动，b 不动
     *   · 拖左上 → l 和 t 都动，r/b 不动
     *   · 拖框内 → 四条边整体平移
     */
    private void applyCropDrag(float dx, float dy) {
        int idx = (int) dx;
        int idy = (int) dy;

        /* 起始的四条边（屏幕坐标） */
        int l0 = aStartX;
        int t0 = aStartY;
        int r0 = aStartX + aStartW;
        int b0 = aStartY + aStartH;

        /* 当前鼠标的绝对位置（屏幕坐标）—— 关键：不是偏移量 */
        int mx = aStartX + idx;
        int my = aStartY + idy;

        int l = l0, t = t0, r = r0, b = b0;

        switch (aMode) {
            case 1:   /* 移动：整体平移 */
                cfg.srcX = clampScreen(l0 + idx, 0, screenW - Math.max(16, aStartW));
                cfg.srcY = clampScreen(t0 + idy, 0, screenH - Math.max(16, aStartH));
                return;

            case 2:   /* 左上角：动 l、t */
                l = mx; t = my; break;
            case 3:   /* 右上角：动 r、t */
                r = mx; t = my; break;
            case 4:   /* 左下角：动 l、b */
                l = mx; b = my; break;
            case 5:   /* 右下角：动 r、b */
                r = mx; b = my; break;

            case 6:   /* 上边：只动 t */
                t = my; break;
            case 7:   /* 下边：只动 b */
                b = my; break;
            case 8:   /* 左边：只动 l */
                l = mx; break;
            case 9:   /* 右边：只动 r */
                r = mx; break;

            case 10:  /* 新建框：起点钉死，拖出矩形 */
                l = Math.min(aStartX, mx);
                t = Math.min(aStartY, my);
                r = Math.max(aStartX, mx);
                b = Math.max(aStartY, my);
                break;

            default:
                return;
        }

        /* 规整（拖动可能把边拖到对面去） */
        if(l > r){ int tmp = l; l = r; r = tmp; }
        if(t > b){ int tmp = t; t = b; b = tmp; }

        /*
         * **边缘吸附**（2026-11-03 新增，解决"拖不出想要的效果"）。
         *
         * 手指很难精确停在 0 或 scrW 这种整数边界上，
         * 差几个像素就会留下一条细黑边 —— 用户觉得"没对准"。
         *
         * 吸附规则：距边缘 24px 以内自动贴齐（约手指 1~2px 的抖动范围）。
         * 同时吸附到目标 App 窗口的边界（如果已知），
         * 这样"刚好框住 App 页面"能一次成功。
         */
        final int SNAP = 24;
        if (Math.abs(l) < SNAP) l = 0;
        if (Math.abs(t) < SNAP) t = 0;
        if (Math.abs(r - screenW) < SNAP) r = screenW;
        if (Math.abs(b - screenH) < SNAP) b = screenH;
        if (winR > winL && winB > winT) {
            if (Math.abs(l - winL) < SNAP) l = winL;
            if (Math.abs(t - winT) < SNAP) t = winT;
            if (Math.abs(r - winR) < SNAP) r = winR;
            if (Math.abs(b - winB) < SNAP) b = winB;
        }

        /* 夹到屏幕内 */
        l = clampScreen(l, 0, screenW);
        r = clampScreen(r, 0, screenW);
        t = clampScreen(t, 0, screenH);
        b = clampScreen(b, 0, screenH);

        /* 最小 16px（太小无法编码，H.264 也要偶数） */
        int w = Math.max(16, r - l);
        int h = Math.max(16, b - t);

        /* 保证不越界：先按右下角限制，再回推起点 */
        if(l + w > screenW) l = Math.max(0, screenW - w);
        if(t + h > screenH) t = Math.max(0, screenH - h);
        if(w > screenW) { w = screenW; l = 0; }
        if(h > screenH) { h = screenH; t = 0; }

        cfg.srcX = l;
        cfg.srcY = t;
        cfg.srcW = w;
        cfg.srcH = h;

        /*
         * ══════════════════════════════════════════════════════════════
         * **拖框时锁定遮罩比例**（2026-11-03 方案 B）
         * ══════════════════════════════════════════════════════════════
         *
         * 问题：用户框 2071×1275（1.624:1），遮罩是 608×416（1.462:1）。
         * H.264 下 dcapd2 硬编码 `dw=boxW, dh=boxH`（必须铺满遮罩），
         * 比例不一致就只能二选一：
         *   · 让画面变形（压扁 11%）
         *   · 或偷偷把框扩大去凑比例（用户发现"多抓了一圈"）
         *
         * 两种都让人困惑。根子在于**框选时就该知道最终比例**。
         *
         * 所以拖拽过程中直接按遮罩比例约束框的宽高：
         *   · 拖角/拖边：先算出用户想改的那一维，另一维按比例跟随
         *   · 拖框内平移：尺寸不变，天然满足
         *   · 新建框：以拖出的宽度为准，高按比例算
         *
         * 这样松手时框的比例就已经等于遮罩比例，投出来分毫不差，
         * 用户看到的就是最终效果 —— 所见即所得。
         *
         * 注意：锁定用**遮罩的原始比例**（cfg.w/cfg.h），不是编码对齐后的，
         * 因为 D 端最终是按遮罩矩形铺满的。
         */
        lockCropAspect();
    }

    /**
     * 把当前截取框的宽高约束成遮罩比例（保持中心与主导维）。
     *
     * 保留哪个维度由"谁变化更大"决定太玄学，这里用简单规则：
     *   · 新建框（aMode==10）→ 以宽度为准
     *   · 拖上/下边（aMode 6,7）→ 以高度为准
     *   · 其余（角、左/右边）→ 以宽度为准
     * 这样手感稳定，不会某一帧突然换成另一维。
     */
    private void lockCropAspect() {
        /*
         * **只有 H.264 档需要锁比例**（2026-11-03）。
         *
         * 为什么只有它：
         *   · H.264 无 alpha，dcapd2 硬编码 `dw=boxW, dh=boxH`
         *     （无条件把画面拉满遮罩）→ 比例不一致就是可见的压扁/拉宽
         *   · BGRA/LZ4 有 alpha，可以等比缩放后留透明黑边，
         *     比例不一致顶多是加边，画面不变形 —— 用户也就不需要被限制
         *
         * 所以在 BGRA 下 `lockCropAspect` 直接返回，拖框完全自由。
         */
        if (MirrorController.getCodec() != MirrorController.CODEC_H264) return;

        if (cfg.w <= 0 || cfg.h <= 0) return;
        if (cfg.srcW <= 0 || cfg.srcH <= 0) return;

        double maskAspect = (double) cfg.w / cfg.h;

        int cx = cfg.srcX + cfg.srcW / 2;
        int cy = cfg.srcY + cfg.srcH / 2;

        int w = cfg.srcW;
        int h = cfg.srcH;

        /* 以高度为准的两种手势：拖上下边 */
        boolean useHeight = (aMode == 6 || aMode == 7);

        if (useHeight) {
            w = (int) Math.round(h * maskAspect);
        } else {
            h = (int) Math.round(w / maskAspect);
        }

        /* 16 对齐（H.264 硬编要求，顺手做了对 BGRA 无害） */
        if (w >= 16) w = (int) Math.round(w / 16.0) * 16;
        if (h >= 16) h = (int) Math.round(h / 16.0) * 16;
        if (w < 16) w = 16;
        if (h < 16) h = 16;

        /* 超出屏幕就往回收 */
        if (w > screenW) {
            w = (screenW / 16) * 16;
            if (!useHeight) h = (int) Math.round(w / maskAspect);
            if (h >= 16) h = (int) Math.round(h / 16.0) * 16;
        }
        if (h > screenH) {
            h = (screenH / 16) * 16;
            if (useHeight) w = (int) Math.round(h * maskAspect);
            if (w >= 16) w = (int) Math.round(w / 16.0) * 16;
        }
        if (w > screenW) w = (screenW / 16) * 16;
        if (h > screenH) h = (screenH / 16) * 16;

        int x = cx - w / 2;
        int y = cy - h / 2;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x + w > screenW) x = Math.max(0, screenW - w);
        if (y + h > screenH) y = Math.max(0, screenH - h);

        cfg.srcX = x;
        cfg.srcY = y;
        cfg.srcW = w;
        cfg.srcH = h;
    }

    private void notifyChanged() {
        if (listener != null) listener.onChanged(cfg);
    }

    /* ══════════════════════════════════════════════════════════════
     * 【方案 E】预设比例一键框选
     * ══════════════════════════════════════════════════════════════
     *
     * 用户反馈"拖框太难用"。其实大多数场景要的只是"框成某个常见比例"，
     * 拖框纯属多余 —— 一键算出该比例能放下的最大区域，居中贴好。
     *
     * 提供 6 档：
     *   16:9      常见视频
     *   4:3       传统比例
     *   3.61:1    仪表原生比例（1920×532）—— 最常用
     *   1:1       正方
     *   21:9      超宽
     *   全屏      整块屏幕（等于不裁）
     *
     * 算法：给定目标比例 want，在 screenW×screenH 里找最大的
     *       want 比例矩形，居中。这样永远不会超出屏幕。
     */
    public static final String[] RATIO_NAMES = {
            "16:9", "4:3", "3.61:1", "1:1", "21:9", "全屏"
    };
    private static final double[] RATIO_VALUES = {
            16.0 / 9.0, 4.0 / 3.0, 1920.0 / 532.0, 1.0, 21.0 / 9.0, 0.0 /* 0=全屏 */
    };

    /**
     * 按预设比例框选。
     *
     * @param idx RATIO_NAMES 的下标
     */
    public void applyRatio(int idx) {
        if (idx < 0 || idx >= RATIO_VALUES.length) return;

        double want = RATIO_VALUES[idx];
        if (want <= 0) {
            /* 「全屏」：整块屏幕，等于不裁 */
            cfg.srcX = 0;
            cfg.srcY = 0;
            cfg.srcW = screenW;
            cfg.srcH = screenH;
        } else {
            /* 在 screenW×screenH 里求最大 want 比例矩形 */
            double availW = screenW;
            double availH = screenH;
            double byW = availW / want;          /* 按宽度算需要的高度 */
            if (byW <= availH) {
                /* 宽度受限（矩形比屏幕"更宽"） */
                cfg.srcW = screenW;
                cfg.srcH = (int) Math.round(byW);
            } else {
                /* 高度受限 */
                cfg.srcH = screenH;
                cfg.srcW = (int) Math.round(screenH * want);
            }
            /*
             * 16 对齐（H.264 硬编要求）。
             *
             * 用**四舍五入**而不是向下取整：向下取整会系统性偏小，
             * 比如 3.61:1 期望高度 709.3，向下取整得 704（误差 5.3），
             * 四舍五入得 704（同上，因为 709 更近 704 而不是 720）。
             * 但对 712 这种值，向下取整 704（误差 8），四舍五入 720（误差 8）。
             * 四舍五入整体误差更均匀。
             */
            cfg.srcW = (int) Math.round(cfg.srcW / 16.0) * 16;
            cfg.srcH = (int) Math.round(cfg.srcH / 16.0) * 16;
            if (cfg.srcW < 16) cfg.srcW = 16;
            if (cfg.srcH < 16) cfg.srcH = 16;

            /* 居中 */
            cfg.srcX = (screenW - cfg.srcW) / 2;
            cfg.srcY = (screenH - cfg.srcH) / 2;
        }

        cfg.sanitizeCrop(screenW, screenH);

        /*
         * **点比例后要同步遮罩比例**（2026-11-03 排查出的问题）。
         *
         * 不同比例的截取区配同一块遮罩会严重变形：
         *   截取 16:9 (1.78:1) 投到遮罩 1440×400 (3.6:1)
         *   → 横向拉伸 2 倍，人脸变胖
         * 同步一次就能避免，成本几乎为零。
         */
        syncMaskAspectToCrop();

        invalidate();
        notifyChanged();
    }

    /* ══════════════════════════════════════════════════════════════
     * 【方案 C】实时截屏当底图
     * ══════════════════════════════════════════════════════════════
     *
     * 从"在空白网格上想象框选"变成"看着真实 App 界面框选"。
     *
     * 底图按整屏尺寸画满预览区，然后在上面叠截取框 ——
     * 用户一眼就能看出"我框的是这一行剧集还是那一块"。
     */
    private android.graphics.Bitmap bgShot;
    private final Paint pShot = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** 设置底图（整屏截图）。传 null 表示清掉。 */
    public void setBackgroundShot(android.graphics.Bitmap bmp) {
        /*
         * **旧图必须回收**（2026-11-03 排查出的 OOM 隐患）。
         *
         * 2560×1600 的 ARGB_8888 位图 = 16 MB。用户每点一次「刷新截图」
         * 都 new 一张，旧的不回收 → 连点 5 次就吃 80 MB，
         * 低内存车机上会直接 OOM 崩掉。
         *
         * 注意：只有当旧图和新的不同对象时才回收，
         * 避免把调用方刚传进来的那张自己回收了。
         */
        if (bgShot != null && bgShot != bmp && !bgShot.isRecycled()) {
            try {
                bgShot.recycle();
            } catch (Throwable ignored) {
            }
        }
        this.bgShot = bmp;
        invalidate();
    }

    public boolean hasBackgroundShot() {
        return bgShot != null && !bgShot.isRecycled();
    }

    /* ══════════════════════════════════════════════════════════════
     * 【方案 A】放大镜
     * ══════════════════════════════════════════════════════════════
     *
     * 手指在 420dp 高的缩略图上拖动，1dp ≈ 6 屏幕像素，
     * 而且手指本身就挡住了要看的地方。
     *
     * 放大镜：拖动时在手指**上方**显示一块 3 倍放大的圆/方区，
     * 实时呈现手指正下方的画面细节 —— 能看清单个像素。
     *
     * 用 Android 原生 Magnifier（API 28+，本车 API 30 可用）。
     * 它自己处理"放大这块 View 的指定区域"，不用我们手写采样。
     */
    private android.widget.Magnifier magnifier;
    private boolean magnifierReady;

    private void ensureMagnifier() {
        if (magnifierReady) return;
        magnifierReady = true;
        if (android.os.Build.VERSION.SDK_INT < 28) return;   /* 不支持就算了 */
        try {
            /*
             * 方法名是 **setInitialZoom**（用 javap 反查 android.jar 确认）。
             * 不存在 setMagnification / setMagnificationFactor ——
             * 网上不少示例是错的，照抄会报"找不到符号"。
             */
            magnifier = new android.widget.Magnifier.Builder(this)
                    .setSize(dp(140), dp(100))
                    .setInitialZoom(3.0f)
                    .build();
        } catch (Throwable t) {
            magnifier = null;   /* 某些 ROM 上 Builder 会抛，静默降级 */
        }
    }

    private void showMagnifier(float x, float y) {
        ensureMagnifier();
        if (magnifier == null) return;
        try {
            /*
             * 显示位置：手指上方 dp(80) —— 这样手指不会挡住放大内容。
             * y 太靠上时改到手指下方，避免超出屏幕顶部。
             */
            float my = y - dp(80);
            if (my < dp(20)) my = y + dp(70);
            magnifier.show(x, my);
        } catch (Throwable ignored) {
        }
    }

    private void dismissMagnifier() {
        if (magnifier == null) return;
        try {
            magnifier.dismiss();
        } catch (Throwable ignored) {
        }
    }

    /** View 从窗口脱离时必须释放放大镜，否则泄漏 */
    @Override
    protected void onDetachedFromWindow() {
        dismissMagnifier();
        if (magnifier != null) {
            try {
                magnifier.dismiss();
            } catch (Throwable ignored) {
            }
            magnifier = null;
            magnifierReady = false;
        }
        /*
         * 底图也要回收 —— 16 MB 的位图留在堆里，
         * 页面重建几次（切前台/后台、转屏）就会把车机内存吃光。
         */
        if (bgShot != null && !bgShot.isRecycled()) {
            try {
                bgShot.recycle();
            } catch (Throwable ignored) {
            }
        }
        bgShot = null;
        super.onDetachedFromWindow();
    }

    /** 统一的几何描述文案（两处显示一致） */
    public static String geometryText(LayerConfig cfg) {
        return String.format(Locale.US, "%d × %d  ·  (%d, %d)  ·  图层 %d × %d",
                cfg.canvasW, cfg.canvasH, cfg.x, cfg.y, cfg.w, cfg.h);
    }
}