package com.hermes.dashcast;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Toast;
import android.widget.TextView;

import java.util.Locale;

/**
 * LayerEditorActivity —— 可视化图层布局编辑器
 *
 * 上半屏是实时预览（拖拽移动、拖角缩放，网格吸附），下半屏是参数面板：
 * 网格 / 形状 / 边框 / 四角圆角 / 梯形上下沿 / 羽化。
 *
 * 调参即时写 /data/local/tmp/dcapd2.layer，dcapd2 每 500ms 查 mtime，
 * 所以仪表上能边调边看 —— 不用重启抓帧进程。
 *
 * 注意：形状依赖 alpha，H.264 档没有 alpha，会在 H.264 档提示用户。
 */
public class LayerEditorActivity extends Activity {

    private static final String TAG = "DashcastLayerUI";

    /* 预览区逻辑尺寸（按画布等比缩放显示） */
    private LayerConfig cfg;
    private PreviewView preview;

    private TextView infoText;

    /* 参数控件 */
    private SeekBar sbGrid, sbBorder, sbTl, sbTr, sbBl, sbBr,
                    sbTrapTop, sbTrapBot, sbFeather;
    private TextView tvGrid, tvBorder, tvTl, tvTr, tvBl, tvBr,
                     tvTrapTop, tvTrapBot, tvFeather;
    private Button btnShape, btnFit, btnFps;
    /** 帧率草稿：改了先存这里，点「保存并应用」才提交 */
    private MirrorController mCtl;   /* 只为调用 setFps，不做投屏控制 */
    private int pendingFps = 30;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int cw = 1920, ch = 532;
        cfg = LayerConfig.loadFromPrefs(this, cw, ch);
        pendingFps = MirrorController.getFps();   /* 草稿初值 = 当前实际值 */
        mCtl = new MirrorController(getApplicationContext(), new CpManager(this));

        /*
         * **不要在这里再 new 一个 CpManager**（2026-11-07 去冗余）。
         *
         * 上面第 60 行 `new MirrorController(getApplicationContext(), new CpManager(this))`
         * 的构造里已经调过 LayerConfig.attachCp(cp)（MirrorController.java:396），
         * CpManager 也被 mCtl 持有着。这里再 new 一个是白建：
         *   · 多算一次 assets 的 MD5（computeLocalMd5 在构造里跑，几十毫秒）
         *   · 多占一份 JSch 状态
         *   · 而且是同一个静态字段 sCp 被覆盖两次
         *
         * 注释里原本想要的"进编辑器时同步一次图层矩形"，
         * 由 mCtl 构造里那次 attachCp + 后续 pushAsync 覆盖，不需要额外动作。
         */

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        /*
         * 居中限宽（2026-10-31）。车机屏 2560×1600，全宽铺满会让滑块拉成
         * 2500px、按钮横跨全场，视觉上"散"。限制最大 900dp 并水平居中，
         * 控件比例接近平板，看着自然。
         */
        FrameLayout center = new FrameLayout(this);
        center.setBackgroundColor(getColor(R.color.surface_root));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(12), pad, dp(24));

        int maxW = dp(900);
        FrameLayout.LayoutParams lpRoot = new FrameLayout.LayoutParams(
                maxW, FrameLayout.LayoutParams.WRAP_CONTENT);
        lpRoot.gravity = Gravity.CENTER_HORIZONTAL;
        root.setLayoutParams(lpRoot);
        center.addView(root);

        /* ── 标题 ── */
        TextView title = new TextView(this);
        title.setText("可视化布局编辑器（拖拽 / 网格 / 形状 / 圆角）");
        title.setTextSize(17);
        title.setTextColor(getColor(R.color.content_primary));
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title);

        /* ── 预览区 ── */
        FrameLayout previewBox = new FrameLayout(this);
        previewBox.setBackgroundColor(getColor(R.color.surface_raised));
        int ph = dp(180);
        LinearLayout.LayoutParams lpBox = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ph);
        previewBox.setLayoutParams(lpBox);

        preview = new PreviewView(this);
        previewBox.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(previewBox);

        infoText = new TextView(this);
        infoText.setTextSize(12);
        infoText.setTextColor(getColor(R.color.content_secondary));
        infoText.setPadding(dp(4), dp(6), 0, dp(10));
        root.addView(infoText);

        /* ── 形状 + 网格 ── */
        root.addView(section("形状与网格"));

        LinearLayout row1 = row();
        btnShape = mkButton(shapeLabel(), v -> cycleShape());
        row1.addView(btnShape, weightLp());
        btnFit = mkButton(fitLabel(), v -> toggleFit());
        row1.addView(btnFit, weightLp());
        row1.addView(mkButton("放大编辑", v -> zoomPreview()), weightLp());

        /* 帧率档位：点一下切到下一个（60→30→25→20→15→60）
         *
         * **只在内存里暂存，点「保存并应用」才真正生效**（2026-10-31 改）。
         * 原先点一下就 setFps，会立刻重启 dcapd2 —— 投屏中切档会闪一下黑屏，
         * 而且和"编辑器里所有改动都要显式保存"的交互不一致。
         * 现在 pendingFps 存草稿，保存时统一提交。 */
        btnFps = mkButton(fpsLabel(), v -> {
            int[] fpsChoices = MirrorController.FPS_CHOICES;
            int cur = pendingFps;
            int next = fpsChoices[0];
            for (int i = 0; i < fpsChoices.length; i++) {
                if (fpsChoices[i] == cur) { next = fpsChoices[(i + 1) % fpsChoices.length]; break; }
            }
            pendingFps = next;          /* 先存草稿，不提交 */
            btnFps.setText(fpsLabel());
            Toast.makeText(this, "帧率改为 " + next + " fps，点「保存并应用」生效",
                    Toast.LENGTH_SHORT).show();
        });
        row1.addView(btnFps, weightLp());
        root.addView(row1);

        sbGrid = addSlider(root, "网格", 4, 128, cfg.grid, "px",
                v -> { cfg.grid = v; refresh(); });
        tvGrid = lastSliderValue;

        /* ── 边框与圆角（两列网格，省纵向空间） ── */
        root.addView(section("边框与圆角"));

        LinearLayout gr1 = grid2();
        sbBorder = addSlider(gr1, "边框", 0, 40, cfg.border, "", v -> { cfg.border = v; refresh(); });
        tvBorder = lastSliderValue;
        sbTl = addSlider(gr1, "左上圆角", 0, 120, cfg.rTl, "°", v -> { cfg.rTl = v; refresh(); });
        tvTl = lastSliderValue;
        root.addView(gr1);

        LinearLayout gr2 = grid2();
        sbTr = addSlider(gr2, "右上圆角", 0, 120, cfg.rTr, "°", v -> { cfg.rTr = v; refresh(); });
        tvTr = lastSliderValue;
        sbBl = addSlider(gr2, "左下圆角", 0, 120, cfg.rBl, "°", v -> { cfg.rBl = v; refresh(); });
        tvBl = lastSliderValue;
        root.addView(gr2);

        LinearLayout gr3 = grid2();
        sbBr = addSlider(gr3, "右下圆角", 0, 120, cfg.rBr, "°", v -> { cfg.rBr = v; refresh(); });
        tvBr = lastSliderValue;
        sbTrapTop = addSlider(gr3, "梯形上沿", 0, 300, cfg.trapTop, "", v -> { cfg.trapTop = v; refresh(); });
        tvTrapTop = lastSliderValue;
        root.addView(gr3);

        /* ── 梯形与羽化 ── */
        root.addView(section("梯形与羽化"));

        LinearLayout gr4 = grid2();
        sbTrapBot = addSlider(gr4, "梯形下沿", 0, 300, cfg.trapBot, "", v -> { cfg.trapBot = v; refresh(); });
        tvTrapBot = lastSliderValue;
        sbFeather = addSlider(gr4, "羽化", 0, 96, cfg.feather, "", v -> { cfg.feather = v; refresh(); });
        tvFeather = lastSliderValue;
        root.addView(gr4);

        /* ── 底部操作 ── */
        root.addView(section("应用到仪表"));

        LinearLayout row2 = row();

        /* 注意：「遮罩启用/关闭」按钮已移除（2026-10-31）。
        /*
         * 「恢复全屏」= 把遮罩**尺寸**拉回整屏，但**保持遮罩启用**。
         *
         * 早先这里写的是 cfg.enabled = false（顺手把遮罩关掉），有问题：
         *   · 关掉之后遮罩不生效，图层退回"整屏不透明铺满"，
         *     这正是"遮罩外要露原厂"的反面；
         *   · 而且「遮罩启用/关闭」开关已经按需求删掉了，
         *     关掉后用户**没有任何界面入口**能再打开 —— 只能删配置文件。
         *
         * 正确语义：enabled 恒为 true，恢复全屏只是把矩形改成 0,0 画布宽高。
         * 想真正关闭遮罩，删 /data/local/tmp/dcapd2.layer 即可（文档里写了）。
         */
        row2.addView(mkButton("恢复全屏", v -> {
            cfg.enabled = true;
            cfg.x = 0; cfg.y = 0;
            cfg.w = cfg.canvasW; cfg.h = cfg.canvasH;
            cfg.rTl = cfg.rTr = cfg.rBl = cfg.rBr = 0;
            cfg.trapTop = cfg.trapBot = cfg.feather = cfg.border = 0;
            cfg.shape = LayerConfig.SHAPE_RECT;
            cfg.fit = 0;
            syncControls();
            refresh();
            /* 只改内存，点「保存」才落盘 */
        }), weightLp());
        root.addView(row2);

        /* ── 保存 / 取消 ── */
        root.addView(section("应用更改"));
        LinearLayout rowSave = row();

        rowSave.addView(mkButton("取消（还原）", v -> {
            /* 丢弃内存里的改动，重新从 prefs 载入 */
            LayerConfig fresh = LayerConfig.loadFromPrefs(this, cfg.canvasW, cfg.canvasH);
            if (fresh != null) cfg.copyFrom(fresh);
            pendingFps = MirrorController.getFps();   /* 帧率草稿也回滚 */
            btnFps.setText(fpsLabel());
            syncControls();
            refresh();
            Toast.makeText(this, "已取消，回到上次保存的配置", Toast.LENGTH_SHORT).show();
        }), weightLp());

        rowSave.addView(mkButton("保存并应用", v -> {
            cfg.sanitize();
            cfg.pushAsync();          /* 写文件 + 存 prefs + 同步图层矩形 */
            cfg.saveToPrefs(this);
            /* 帧率草稿在这里才真正提交（setFps 会重启 dcapd2） */
            boolean fpsChanged = pendingFps != MirrorController.getFps();
            if (fpsChanged) mCtl.setFps(this, pendingFps);
            btnFps.setText(fpsLabel());
            Toast.makeText(this,
                    fpsChanged ? ("已保存（帧率 " + pendingFps + " fps）+ 图层已应用")
                               : "已保存并应用到仪表",
                    Toast.LENGTH_SHORT).show();
        }), weightLp());
        root.addView(rowSave);

        TextView hintSave = new TextView(this);
        hintSave.setText("拖动和滑块只改预览，点「保存并应用」才会真正写到仪表；"
                + "点「取消」回到上次保存的状态。");
        hintSave.setTextSize(11);
        hintSave.setTextColor(getColor(R.color.content_secondary));
        hintSave.setPadding(dp(4), dp(8), dp(4), 0);
        root.addView(hintSave);
        TextView hint = new TextView(this);
        hint.setText("提示：形状依赖透明通道，只在「BGRA+LZ4」档生效；"
                + "H.264 视频档没有 alpha，形状会变成黑块。");
        hint.setTextColor(getColor(R.color.state_warn));
        hint.setTextSize(11);
        hint.setPadding(dp(4), dp(10), dp(4), 0);
        root.addView(hint);

        scroll.addView(center);
        setContentView(scroll);
        syncControls();
        refresh();
    }

    /* ============================== 控件构建 ============================== */

    private TextView lastSliderValue;

    private interface OnInt {
        void on(int v);
    }

    private LinearLayout section(String text) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(getColor(R.color.accent));
        t.setPadding(dp(4), dp(16), 0, dp(6));
        l.addView(t);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private Button mkButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(getColor(R.color.content_primary));
        b.setBackgroundColor(getColor(R.color.surface_raised));
        b.setOnClickListener(l);
        b.setLayoutParams(weightLp());
        return b;
    }

    /** 加一个带标签+数值+滑块的组合 */
    /** 两列网格容器（滑块成对排列，省纵向空间） */
    private LinearLayout grid2() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.HORIZONTAL);
        g.setPadding(0, dp(2), 0, 0);
        return g;
    }
    private LinearLayout.LayoutParams colLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private SeekBar addSlider(LinearLayout parent, String name, int min, int max,
                              int cur, String unit, OnInt cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), 0);
        /* 父容器方向决定参数：横向两列时 width=0+weight，纵向单列时 MATCH_PARENT。
         * 之前一律用 colLp()（width=0），在纵向 root 里宽度变 0 → 滑块整块消失。 */
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            box.setLayoutParams(colLp());
        } else {
            box.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout head = row();
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(13);
        label.setTextColor(getColor(R.color.content_secondary));
        head.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(this);
        val.setTextSize(13);
        val.setTextColor(getColor(R.color.content_primary));
        val.setGravity(Gravity.END);
        head.addView(val);
        box.addView(head);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, cur - min)));
        /* 滑块配色跟主题走（种子色 accent，次要轨道用 surface_border） */
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.accent)));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.accent_soft)));
        sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.surface_border)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = min + p;
                val.setText(v + unit);
                if (fromUser) cb.on(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                /* 只改内存，点「保存」才落盘并同步仪表 */
                /* prefs 也在保存时统一写 */
            }
        });
        sb.setPadding(0, dp(2), 0, dp(6));
        box.addView(sb);
        parent.addView(box);

        val.setText(cur + unit);
        lastSliderValue = val;
        return sb;
    }

    /* ============================== 状态刷新 ============================== */

    private String shapeLabel() {
        int s = cfg.shape;
        if (s < 0 || s >= LayerConfig.SHAPE_NAMES.length) s = 0;
        return "形状：" + LayerConfig.SHAPE_NAMES[s];
    }

    private String fitLabel() {
        return cfg.fit == 0 ? "填充：铺满" : "填充：等比";
    }
    /** 帧率按钮文案：显示当前档位 */
    private String fpsLabel() {
        boolean changed = pendingFps != MirrorController.getFps();
        return "帧率：" + pendingFps + " fps" + (changed ? " *" : "");
    }

    /** 切换填充方式：铺满（无黑边）↔ 等比（可能留透明边） */
    private void toggleFit() {
        cfg.fit = cfg.fit == 0 ? 1 : 0;
        btnFit.setText(fitLabel());
        /* 只改内存，点「保存」才落盘并同步仪表 */
        /* prefs 也在保存时统一写 */
        refresh();
    }
    private void cycleShape() {
        cfg.shape = (cfg.shape + 1) % LayerConfig.SHAPE_NAMES.length;
        btnShape.setText(shapeLabel());
        /* 只改内存，点「保存」才落盘并同步仪表 */
        /* prefs 也在保存时统一写 */
        refresh();
    }

    private void zoomPreview() {
        /* 放大编辑：预览区在 1x/2x 之间切换 */
        preview.zoomed = !preview.zoomed;
        preview.invalidate();
    }

    /** 把 cfg 数值回写到控件（"恢复全屏"等批量改动后调用） */
    private void syncControls() {
        sbGrid.setProgress(cfg.grid - 4);
        sbBorder.setProgress(cfg.border);
        sbTl.setProgress(cfg.rTl);
        sbTr.setProgress(cfg.rTr);
        sbBl.setProgress(cfg.rBl);
        sbBr.setProgress(cfg.rBr);
        sbTrapTop.setProgress(cfg.trapTop);
        sbTrapBot.setProgress(cfg.trapBot);
        sbFeather.setProgress(cfg.feather);
        tvGrid.setText(cfg.grid + "px");
        tvBorder.setText("" + cfg.border);
        tvTl.setText(cfg.rTl + "°");
        tvTr.setText(cfg.rTr + "°");
        tvBl.setText(cfg.rBl + "°");
        tvBr.setText(cfg.rBr + "°");
        tvTrapTop.setText("" + cfg.trapTop);
        tvTrapBot.setText("" + cfg.trapBot);
        tvFeather.setText("" + cfg.feather);
    }

    private void refresh() {
        cfg.sanitize();
        preview.invalidate();
        infoText.setText(String.format(Locale.US,
                "%d × %d · (%d, %d)　图层 %d × %d　%s",
                cfg.canvasW, cfg.canvasH, cfg.x, cfg.y, cfg.w, cfg.h,
                cfg.enabled ? "已生效" : "未启用"));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /* ============================== 预览视图 ============================== */

    /**
     * 预览：把画布等比缩到控件里，画出图层矩形（按形状路径），
     * 支持拖动整个图层、拖角改尺寸，都按网格吸附。
     */
    private class PreviewView extends View {

        private final Paint pBg    = new Paint();
        private final Paint pLayer = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pEdge  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pHandle= new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pGrid  = new Paint();

        boolean zoomed;
        private float scale = 1f, ox, oy;

        /* 拖拽状态 */
        private int mode = 0;       /* 0=无 1=移动 2=右下缩放 */
        private float downX, downY;
        private int startX, startY, startW, startH;

        PreviewView(android.content.Context ctx) {
            super(ctx);
            pBg.setColor(getColor(R.color.surface_root));
            pLayer.setColor(getColor(R.color.accent));
            pLayer.setAlpha(200);
            pEdge.setColor(getColor(R.color.accent_soft));
            pEdge.setStyle(Paint.Style.STROKE);
            pEdge.setStrokeWidth(2f);
            pHandle.setColor(getColor(R.color.accent));
            pGrid.setColor(0x22FFFFFF);
            pGrid.setStrokeWidth(1f);
        }

        private void computeScale() {
            float availW = getWidth() - 16f;
            float availH = getHeight() - 16f;
            if (availW <= 0 || availH <= 0) return;
            scale = Math.min(availW / cfg.canvasW, availH / cfg.canvasH);
            if (zoomed) scale *= 2f;
            ox = (getWidth() - cfg.canvasW * scale) / 2f;
            oy = (getHeight() - cfg.canvasH * scale) / 2f;
        }

        @Override
        protected void onDraw(Canvas c) {
            computeScale();
            c.drawRect(0, 0, getWidth(), getHeight(), pBg);

            /* 画布边界 */
            pEdge.setColor(0x44FFFFFF);
            c.drawRect(ox, oy, ox + cfg.canvasW * scale, oy + cfg.canvasH * scale, pEdge);

            /* 网格 */
            if (cfg.grid > 0) {
                pEdge.setColor(0x22FFFFFF);
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
            pLayer.setColor(cfg.enabled ? getColor(R.color.accent) : getColor(R.color.content_tertiary));
            pLayer.setAlpha(cfg.enabled ? 200 : 120);
            c.drawPath(path, pLayer);

            pEdge.setColor(cfg.enabled ? getColor(R.color.accent) : getColor(R.color.content_secondary));
            pEdge.setStrokeWidth(2f);
            c.drawPath(path, pEdge);

            /* 右下角拖拽柄 */
            c.drawCircle(r.right, r.bottom, dp(9), pHandle);
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
            computeScale();
            float cx = (e.getX() - ox) / scale;
            float cy = (e.getY() - oy) / scale;

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    /* 靠近右下角 = 缩放，否则 = 移动 */
                    downX = e.getX(); downY = e.getY();
                    startX = cfg.x; startY = cfg.y;
                    startW = cfg.w; startH = cfg.h;

                    float hx = ox + (cfg.x + cfg.w) * scale;
                    float hy = oy + (cfg.y + cfg.h) * scale;
                    float dist = (float) Math.hypot(e.getX() - hx, e.getY() - hy);
                    mode = (dist < dp(24)) ? 2 : 1;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = (e.getX() - downX) / scale;
                    float dy = (e.getY() - downY) / scale;

                    if (mode == 1) {
                        cfg.x = cfg.snap(startX + (int) dx);
                        cfg.y = cfg.snap(startY + (int) dy);
                    } else if (mode == 2) {
                        cfg.w = cfg.snap(startW + (int) dx);
                        cfg.h = cfg.snap(startH + (int) dy);
                    }
                    cfg.sanitize();
                    invalidate();
                    infoText.setText(String.format(Locale.US,
                            "%d × %d · (%d, %d)　图层 %d × %d　%s",
                            cfg.canvasW, cfg.canvasH, cfg.x, cfg.y,
                            cfg.w, cfg.h, cfg.enabled ? "已生效" : "未启用"));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (mode != 0) {
                        mode = 0;
                        cfg.sanitize();
                        /* 只改内存，点「保存」才落盘并同步仪表 */
                        /* prefs 也在保存时统一写 */
                        refresh();
                    }
                    return true;
                }
            }
            return super.onTouchEvent(e);
        }
    }
}