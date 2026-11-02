/*
 * MainActivity.java — 授权 + 四个独立操作按钮（美化版）
 *
 * 四个按钮职责分离：
 *   [部署服务]  推送 dashcastd 到 CP（不启动）
 *   [停止服务]  杀进程 + 删除推送的文件，恢复 CP 原样
 *   [开始投屏]  仅启动 dashcastd + 建虚拟屏（不推送文件）
 *   [停止投屏]  仅停 AP 推流服务（CP 断连自动清理图层，不删文件）
 *
 * 核心（协议.md §四.6 方案 4）：不抓主屏，而是自建虚拟屏把目标 App "搬"进去。
 *   - 不弹录屏授权框（DisplayManager.createVirtualDisplay 是公开 API，零权限）
 *   - 支持「投放当前前台 App」：把现成 task 用 moveTaskToDisplay 迁移过去，不重建
 *   - 支持「指定 App」：用 setLaunchDisplayId 把目标 App 启动到虚拟屏
 *
 * 互斥保护：任一操作进行中，四个按钮全部禁用，完成后恢复，防止连点/并发。
 */
package com.hermes.dashcast;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Toast;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "DashcastMain";

    private TextView status;
    private TextView statusDot;
    private TextView targetHint;
    private TextView metrics;
    private boolean running = false;

    private CpManager cpManager;
    /** 单应用镜像（抓屏，不搬 task）—— 中控可操作、仪表实时跟随 */
    private MirrorController mirror;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 四个操作按钮，统一管理禁用/恢复 */
    private final List<View> actionButtons = new ArrayList<>();
    private LinearLayout btnStart, btnStop;    /* 主操作按钮（状态切换时改样式） */
    private TextView statusBadge;              /* 右上角状态徽标 */
    private LinearLayout statusCardView;       /* 状态卡（左侧有状态色条） */
    private TextView linkInfo;                 /* 右侧链路信息（CP 地址 + 分辨率） */
    private TextView logView;                  /* 运行日志显示区 */
    private TextView upstream, tilesView;      /* 状态卡里的上行 / 图块指标 */
    private final java.util.ArrayDeque<String> logLines =
            new java.util.ArrayDeque<>();       /* 最近 12 条事件，环形 */
    private static final int LOG_MAX = 12;
    private LinearLayout btnCodecBgra, btnCodecH264;   /* 编码档二选一 */
    private LinearLayout btnMapMode;                 /* 地图模式开关 */
    private LinearLayout btnMapHud;                  /* 地图 HUD 开关 */
    private LinearLayout viewRow;                    /* 视角三档容器 */
    private LinearLayout[] viewBtns;                 /* 远景/中景/近景 */

    /** 操作互斥标志：任一 CP 操作进行中为 true */
    private boolean busy = false;

    /** 当前选中的投屏目标 App（三个都为 null = 没选，此时不允许开投） */
    private String targetPkg = null;
    private String targetCls = null;
    private String targetLabel = null;

    public static final String PREF = "dashcast";
    private static final String K_PKG = "target_pkg";
    private static final String K_CLS = "target_cls";
    private static final String K_LABEL = "target_label";

    /** 屏幕捕获授权结果（本 ROM 建 PUBLIC 虚拟屏必须带这个 token） */
    private Intent projectionData = null;
    /** 本轮已为"兜底建屏"弹过一次授权框了，别再弹第二次 */
    private boolean projectionTried = false;
    private static final int REQ_PROJECTION = 0x1001;

    /** busy 看门狗超时（推 dashcastd 走 SSH 分块上传，给足 30 秒） */
    private static final long BUSY_TIMEOUT_MS = 30000;

    /** 最近一次「把 App 送到虚拟屏」失败的原因，直接显示到状态栏 */
    private static volatile String sLastLaunchError = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 沉浸式的调用点在 setContentView 之后（见下方），
         * 因为 onCreate 一进来 DecorView 还没建，getInsetsController() 会是 null ——
         * 在那种地方调直接 NPE 闪退。 */

        /* 成员初始化（原样保留，不能在 UI 重构时丢掉） */
        cpManager = new CpManager(this);
        MirrorController prev = MirrorController.getActive();
        mirror = (prev != null) ? prev : new MirrorController(this, cpManager);
        if (MirrorController.isMirroring()) running = true;

        DashcastSenderService.setDisplayReadyListener(displayId ->
                mainHandler.post(() -> onDisplayReady(displayId)));
        DashcastSenderService.setStatsListener((fps, kbps, tiles, connected) ->
                mainHandler.post(() -> {
                    if (metrics != null) metrics.setText(String.valueOf(fps));
                    if (upstream != null) upstream.setText(String.valueOf(kbps));
                    if (tilesView != null) tilesView.setText(String.valueOf(tiles));
                }));

        /* ══════════════════════════════════════════════════════════
         * UI 构建（2026-10-31 结构性重构）
         *   ① 按钮左侧配字符图标，靠形状区分
         *   ② 投屏控制做成"重点卡"（主色描边）
         *   ③ 状态卡做大：大号状态 + 三格指标
         * ══════════════════════════════════════════════════════════ */

        /* ══════════════════════════════════════════════════════════════
         * 两栏首页（2026-10-31 彻底重构）
         *
         * 屏幕 2560×1600 · density 160 → 1dp = 1px，可用 2560 宽。
         * 左栏（固定 620dp）：状态 + 部署 + 投屏控制 + 日志
         * 右栏（剩余）：遮罩编辑（预览 + 参数 + 保存），原来要跳页面
         * ══════════════════════════════════════════════════════════════ */

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setBackgroundColor(getColor(R.color.surface_root));

        /* ══ 左栏 ══ */
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(20), dp(14), dp(12), dp(14));
        page.addView(left, new LinearLayout.LayoutParams(dp(700),
                LinearLayout.LayoutParams.MATCH_PARENT));

        /* ── 标题栏 ── */
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleRow.addView(titleCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("Dashcast");
        title.setTextSize(32);
        title.setTextColor(getColor(android.R.color.white));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.02f);
        title.setIncludeFontPadding(false);
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("车机中控 → 仪表投屏");
        subtitle.setTextSize(15);
        subtitle.setTextColor(getColor(R.color.content_secondary));
        titleCol.addView(subtitle);

        statusBadge = new TextView(this);
        statusBadge.setText("未运行");
        statusBadge.setTextSize(14);
        statusBadge.setTextColor(getColor(R.color.content_secondary));
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(dp(12), dp(5), dp(12), dp(5));
        statusBadge.setBackgroundResource(R.drawable.badge_idle);
        titleRow.addView(statusBadge);
        left.addView(titleRow);

        /* ── 状态卡（左侧色条 + 大状态 + 三格指标）── */
        LinearLayout statusCard = new LinearLayout(this);
        statusCardView = statusCard;
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackgroundResource(R.drawable.status_card);
        statusCard.setPadding(dp(24), dp(14), dp(20), dp(14));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.setMargins(0, dp(12), 0, 0);
        statusCard.setLayoutParams(scLp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = new TextView(this);
        statusDot.setBackgroundResource(R.drawable.status_dot);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotLp.setMargins(0, 0, dp(10), 0);
        statusDot.setLayoutParams(dotLp);
        statusRow.addView(statusDot);

        status = new TextView(this);
        status.setText("状态：未运行");
        status.setTextSize(22);
        status.setTextColor(getColor(R.color.content_primary));
        status.setTypeface(Typeface.DEFAULT_BOLD);
        statusRow.addView(status);
        statusCard.addView(statusRow);

        LinearLayout statGrid = new LinearLayout(this);
        statGrid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        sgLp.setMargins(0, dp(10), 0, 0);
        statGrid.setLayoutParams(sgLp);
        metrics   = statCell(statGrid, "fps",  "—");
        upstream  = statCell(statGrid, "上行 KB/s", "—");
        tilesView = statCell(statGrid, "图块", "—");
        statusCard.addView(statGrid);

        linkInfo = new TextView(this);
        linkInfo.setText("CP 198.18.34.2:8444  ·  SSH 22");
        linkInfo.setTextSize(13);
        linkInfo.setTextColor(getColor(R.color.content_tertiary));
        statusCard.addView(linkInfo);
        left.addView(statusCard);

        /* ── 服务部署 ── */
        left.addView(sectionLabel("服务部署"));
        LinearLayout deployRow = new LinearLayout(this);
        deployRow.setOrientation(LinearLayout.HORIZONTAL);
        deployRow.addView(iconButton("部署", "↓", R.drawable.btn_primary,
                android.R.color.white, v -> deployService()), rowLp(1f));
        deployRow.addView(iconButton("停止", "■", R.drawable.btn_secondary,
                R.color.state_error, v -> stopCpService()), rowLp(1f));
        left.addView(deployRow);

        /* ── 投屏控制（重点卡）── */
        left.addView(sectionLabel("投屏控制"));
        LinearLayout castCard = new LinearLayout(this);
        castCard.setOrientation(LinearLayout.VERTICAL);
        castCard.setBackgroundResource(R.drawable.card_focus);
        castCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        castCard.setLayoutParams(ccLp);

        targetHint = new TextView(this);
        targetHint.setTextSize(14);
        targetHint.setTextColor(getColor(R.color.content_secondary));
        targetHint.setGravity(Gravity.CENTER);
        targetHint.setPadding(dp(10), dp(6), dp(10), dp(6));
        targetHint.setBackgroundResource(R.drawable.chip_bg);
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        thLp.setMargins(dp(4), 0, dp(4), 8);
        targetHint.setLayoutParams(thLp);
        castCard.addView(targetHint);

        castCard.addView(iconButton("选择投屏 App", "◉", R.drawable.btn_secondary,
                R.color.accent_soft, v -> showAppPicker()));

        LinearLayout castMainRow = new LinearLayout(this);
        castMainRow.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = iconButton("开始投屏", "▶", R.drawable.btn_primary,
                android.R.color.white, v -> startCast());
        castMainRow.addView(btnStart, rowLp(1.5f));
        btnStop = iconButton("停止", "■", R.drawable.btn_secondary,
                R.color.content_secondary, v -> stopCastOnly());
        castMainRow.addView(btnStop, rowLp(1f));
        castCard.addView(castMainRow);

        /* 编码档：二选一（默认 BGRA+LZ4）。这两个按钮是 H.264 档唯一的入口，
         * 重构时漏掉过一次，导致完全无法切档 —— 现在固定在主操作下方。 */
        LinearLayout codecRow = new LinearLayout(this);
        codecRow.setOrientation(LinearLayout.HORIZONTAL);
        btnCodecBgra = iconButton("BGRA+LZ4", "◐", R.drawable.btn_secondary,
                R.color.accent_soft, v -> setCodecMode(MirrorController.CODEC_BGRA));
        codecRow.addView(btnCodecBgra, rowLp(1f));
        btnCodecH264 = iconButton("H.264", "▣", R.drawable.btn_secondary,
                R.color.accent_soft, v -> setCodecMode(MirrorController.CODEC_H264));
        codecRow.addView(btnCodecH264, rowLp(1f));
        castCard.addView(codecRow);

        /* 地图模式：中控 2560×1600 → 仪表 1920×532 比例差一倍多，
         *  直接缩放会把地图纵向压扁。开启后只抓窗口的横向中段，
         *  地图在仪表上 1:1 无变形，导航牌/路线都能看清。 */
        btnMapMode = iconButton(mapModeLabel(), "▤", R.drawable.btn_secondary,
                R.color.accent_soft, v -> {
                    boolean on = !mirror.isMapMode();
                    mirror.setMapMode(this, on);
                    updateMapModeBtn();
                    appendLog("地图模式 " + (on ? "开启" : "关闭"));
                    if (MirrorController.isMirroring()) {
                        setStatus("状态：地图模式已切换，重新投屏后生效");
                    }
                });
        castCard.addView(btnMapMode);

        /*
         * 地图视角三档：远 / 中 / 近。
         *
         * 只在地图模式开启时显示 —— 普通 App 投屏用不到视角档位。
         *
         * 为什么做成三档而不是让用户拖四个数字：
         *   抓取区必须同时满足「比例 = 1920/532」和「本车纵向位置合适」两个约束，
         *   手动凑几乎不可能。三档已经把这两个约束算好了。
         */
        viewRow = new LinearLayout(this);
        viewRow.setOrientation(LinearLayout.HORIZONTAL);
        viewRow.setVisibility(mirror.isMapMode() ? View.VISIBLE : View.GONE);

        viewBtns = new LinearLayout[LayerConfig.VIEW_NAMES.length];
        for (int i = 0; i < LayerConfig.VIEW_NAMES.length; i++) {
            final int vi = i;
            LinearLayout b = iconButton(LayerConfig.VIEW_NAMES[i], "◎",
                    R.drawable.btn_secondary, R.color.accent_soft,
                    v -> applyViewMode(vi));
            viewBtns[i] = b;
            viewRow.addView(b, rowLp(1f));
        }
        castCard.addView(viewRow);

        /*
         * ── 地图 HUD 开关 ──
         *
         * 打开后，开始投屏会：
         *   ① 把地图的 HudActivity 用 `am start --display 1` 投到车机的
         *      Display 1（HDMI 800×480，FLAG_PRESENTATION）
         *   ② 让 dcapd2 抓那块屏（argv[13] = 1）
         *
         * 拿到的是**地图自己渲染的原生 HUD 页面** ——
         * 浅底高对比、粗路线、居中车标、无 POI 无地名，
         * 比截主屏上的地图干净得多。
         *
         * 只在地图模式开启时显示（这是地图独有的页面）。
         */
        btnMapHud = iconButton(mapHudLabel(), "◈", R.drawable.btn_secondary,
                R.color.accent_soft, v -> {
                    boolean on = !MirrorController.loadMapHud(this);
                    MirrorController.saveMapHud(this, on);
                    updateMapHudBtn();
                    appendLog("地图 HUD " + (on ? "开启" : "关闭"));
                    if (MirrorController.isMirroring()) {
                        setStatus("状态：地图 HUD 已切换，重新投屏后生效");
                    }
                });
        btnMapHud.setVisibility(View.GONE);
        castCard.addView(btnMapHud);

        left.addView(castCard);

        /* ── 运行日志（占满剩余高度）── */
        left.addView(sectionLabel("运行日志"));
        LinearLayout logCard = card();
        logCard.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logCard.setLayoutParams(lcLp);

        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView logTitle = new TextView(this);
        logTitle.setText("最近事件");
        logTitle.setTextSize(12);
        logTitle.setTextColor(getColor(R.color.content_tertiary));
        logHead.addView(logTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClearLog = new Button(this);
        btnClearLog.setText("清空");
        btnClearLog.setTextSize(12);
        btnClearLog.setTextColor(getColor(R.color.content_tertiary));
        btnClearLog.setBackgroundResource(R.drawable.btn_secondary);
        btnClearLog.setAllCaps(false);
        btnClearLog.setPadding(dp(8), 0, dp(8), 0);
        btnClearLog.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)));
        btnClearLog.setOnClickListener(v -> {
            logLines.clear();
            LocalStore.clearLog(this);
            if (logView != null) logView.setText(LOG_EMPTY_HINT);
        });
        logHead.addView(btnClearLog);
        logCard.addView(logHead);

        logView = new TextView(this);
        logView.setText(LOG_EMPTY_HINT);
        logView.setTextSize(13);
        logView.setTextColor(getColor(R.color.content_tertiary));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(0f, 1.5f);
        logView.setPadding(0, dp(6), 0, 0);
        logView.setGravity(Gravity.TOP);
        logCard.addView(logView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        left.addView(logCard);

        /* ══ 右栏：遮罩编辑 ══ */
        /* ══ 右栏：遮罩编辑（内容较高，套 ScrollView 防挤压）══ */
        ScrollView rightScroll = new ScrollView(this);
        rightScroll.setFillViewport(true);
        page.addView(rightScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), dp(14), dp(20), dp(14));
        rightScroll.addView(right, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        buildMaskPanel(right);

        setContentView(page);

        /* 沉浸式：Flyme Auto 的系统导航栏默认浅色，我们的界面是深色底，
         * 那条白栏很扎眼。themes.xml 里的 navigationBarColor 对它无效
         * （车机用了自己的导航栏窗口），只能整条藏起来。
         *
         * **必须放在 setContentView 之后**：onCreate 一进来 DecorView 还没建，
         * getWindowInsetsController() 会返回 null，在那里调直接 NPE 闪退。 */
        applyImmersive();

        setDotColor(R.color.content_secondary);
        refreshCodecButtons();
        mirror.setMapMode(this, MirrorController.loadMapMode(this));
        updateMapModeBtn();

        /*
         * 恢复上一次选的视角档。
         * 只在 prefs 里记了档位号（0/1/2），不记具体像素 —— 这样以后
         * 调整 viewRect() 的三组数字，用户不需要重新选一次。
         */
        {
            int savedView = getSharedPreferences(PREF, MODE_PRIVATE)
                    .getInt("map_view", LayerConfig.VIEW_MID);
            if (MirrorController.loadMapMode(this) && savedView != LayerConfig.VIEW_MID) {
                maskCfg.applyView(savedView, 1920, 532);
                syncMaskControls();
                refreshMask();
            }
            updateViewBtns();
        }
        updateTargetHint();
        refreshCodecButtons();

        setDotColor(R.color.content_secondary);
        refreshCodecButtons();
        loadTargetPref();
        updateTargetHint();

        /*
         * **loadCodec 必须在这个线程之前同步跑完**。
         *
         * buildMaskPanel()（378 行）里的 maskFps = MirrorController.getFps()
         * 是拿"当前帧率"当作编辑器的初始值；如果 loadCodec 还在后台没跑完，
         * 此时 fps 还是类默认值 30，用户的真实设置（比如 60）就读不到。
         * 后果：用户点「保存并应用」遮罩时，saveMask 拿 30 和"30"比对
         * （见 saveMask 里的 setFps 逻辑），于是**把帧率从 60 打回 30** ——
         * 用户完全没碰帧率，设置却自己变了。
         *
         * loadCodec 只读 SharedPreferences，是微秒级操作，同步跑没有代价。
         */
        MirrorController.loadCodec(this);
        refreshCodecButtons();

        if (MirrorController.isMirroring()) {
            running = true;
            mirror.setListener(makeMirrorListener());
            String label = targetLabel == null ? targetPkg : targetLabel;
            setStatus("状态：投屏运行中 · " + label + " → 仪表（数据实时刷新）");
            setDotColor(R.color.state_ok);
        }
    }

    /**
     * singleTask 复用入口。
     *
     * 从 launcher 再点一次、或 adb am start 再来一次，都走这里而不是新建实例。
     * 必须把状态回调和界面刷新重新挂到当前实例上 —— 否则原来那个 listener
     * 还指着旧实例的 View，状态栏就不刷新了。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (mirror != null) mirror.setListener(makeMirrorListener());
        applyImmersive();
        updateMapModeBtn();
        updateTargetHint();
        refreshCodecButtons();
    }

    /**
     * 界面销毁时把挂在静态/长生命周期对象上的引用全部摘掉。
     *
     * 三个 Listener（DashcastSenderService 的 display/stats、MirrorController 的
     * listener）都是**静态或全局对象持有的 lambda**，隐式捕获 this（Activity）。
     * 不摘的话每次旋转/重进都会泄漏一整个 Activity + 它的整棵 View 树，
     * 而 mirror 是跨 Activity 复用的（getActive 拿旧的），旧 listener 还指着
     * 已销毁的 View，状态回写会抛 BadTokenException。
     */
    @Override
    protected void onDestroy() {
        try {
            DashcastSenderService.setDisplayReadyListener(null);
        } catch (Throwable ignored) {
        }
        try {
            DashcastSenderService.setStatsListener(null);
        } catch (Throwable ignored) {
        }
        try {
            if (mirror != null && !sandboxKeepListenerOnDestroy()) {
                mirror.setListener(null);
            }
        } catch (Throwable ignored) {
        }
        try {
            mainHandler.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    /**
     * mirror 跨 Activity 存活（singleTask + getActive），如果这里直接把 listener
     * 置空，用户在投屏中旋转/重进界面后就再也不刷新状态了。
     * onNewIntent/onCreate 会重新 setListener，所以照理没事；但为了绝对安全，
     * 投屏未结束时保守保留 —— 反正 next onCreate 会覆盖成新实例。
     */
    private boolean sandboxKeepListenerOnDestroy() {
        return running;
    }

    /** 镜像状态/统计回调。绑定到当前 Activity 实例 —— 界面重建后要重新挂 */
    private MirrorController.Listener makeMirrorListener() {
        return new MirrorController.Listener() {
            @Override
            public void onState(String msg, boolean ok2) {
                mainHandler.post(() -> {
                    setStatus("状态：" + msg);
                    setDotColor(ok2 ? R.color.state_ok : R.color.content_secondary);
                });
            }

            @Override
            public void onStats(int fps, int kbps, int tiles) {
                mainHandler.post(() -> {
                    if (metrics == null) return;
                    metrics.setText(String.valueOf(fps));
                    if (upstream != null) upstream.setText(String.valueOf(kbps));
                    if (tilesView != null) tilesView.setText(String.valueOf(tiles));
                });
            }

            @Override
            public void onEnded(String reason) {
                mainHandler.post(() -> {
                    running = false;
                    setStatus("状态：" + reason);
                    setDotColor(R.color.content_secondary);
                    if (metrics != null) metrics.setText("—");
                    if (upstream != null) upstream.setText("—");
                    if (tilesView != null) tilesView.setText("—");
                });
            }
        };
    }

    /** 上次选过的目标（重开 App 不用再选一遍） */
    private void loadTargetPref() {
        android.content.SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        targetPkg = p.getString(K_PKG, null);
        targetCls = p.getString(K_CLS, null);
        targetLabel = p.getString(K_LABEL, null);
        if (targetPkg != null && targetCls != null) {
            Log.i(TAG, "记住的投屏目标: " + targetPkg + "/" + targetCls);
        }
    }

    private void saveTargetPref() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(K_PKG, targetPkg)
                .putString(K_CLS, targetCls)
                .putString(K_LABEL, targetLabel)
                .apply();
    }

    /** 建一个圆角卡片容器 */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, dp(4));
        c.setLayoutParams(lp);
        return c;
    }

    /** 分组小标题 */
    /* ══════════════════════════ 遮罩编辑面板（右栏） ══════════════════════════ */

    private LayerConfig maskCfg;
    private LayerPreviewView maskPreview;
    private TextView maskInfo;
    private SeekBar maskSbGrid, maskSbDpi, maskSbBorder, maskSbTl, maskSbTr,
                    maskSbBl, maskSbBr, maskSbTrapTop, maskSbTrapBot, maskSbFeather;
    private TextView maskTvGrid, maskTvDpi, maskTvBorder, maskTvTl, maskTvTr,
                     maskTvBl, maskTvBr, maskTvTrapTop, maskTvTrapBot, maskTvFeather;
    private LinearLayout maskBtnShape, maskBtnFit;
    private int maskFps = 30;
    private LinearLayout[] maskPresetBtns;      /* 三个预设按钮 */
    private TextView maskLastSliderVal;

    /** 遮罩面板：预览 + 参数滑块 + 保存/取消，全部内嵌在首页右栏 */
    private void buildMaskPanel(LinearLayout col) {
        maskCfg = LayerConfig.loadFromPrefs(this, 1920, 532);
        maskFps = MirrorController.getFps();

        /* 标题 */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText("图层遮罩");
        t.setTextSize(19);
        t.setTextColor(getColor(R.color.content_primary));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnFull = new Button(this);
        btnFull.setText("展开编辑");
        btnFull.setTextSize(13);
        btnFull.setTextColor(getColor(R.color.accent_soft));
        btnFull.setBackgroundResource(R.drawable.btn_secondary);
        btnFull.setAllCaps(false);
        btnFull.setPadding(dp(10), 0, dp(10), 0);
        btnFull.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)));
        btnFull.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, LayerEditorActivity.class)));
        head.addView(btnFull);
        col.addView(head);

        /* 预览区（占 1 份高度，可拖拽） */
        FrameLayout pvBox = new FrameLayout(this);
        pvBox.setBackgroundColor(getColor(R.color.surface_deep));
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(520));
        pvLp.setMargins(0, dp(8), 0, 0);
        pvBox.setLayoutParams(pvLp);

        maskPreview = new LayerPreviewView(this, maskCfg);
        maskPreview.setOnGeometryChanged(c -> refreshMaskInfo());
        /* 地图模式开的时候，预览上叠一块"实际抓取区"虚线框 */
        maskPreview.setMapMode(mirror.isMapMode(), 2560, 1600);
        pvBox.addView(maskPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        col.addView(pvBox);

        maskInfo = new TextView(this);
        maskInfo.setTextSize(13);
        maskInfo.setTextColor(getColor(R.color.content_tertiary));
        maskInfo.setPadding(dp(2), dp(5), 0, dp(8));
        col.addView(maskInfo);

        /* ── 三个预设槽：点=应用，长按=保存当前，再长按=清空 ── */
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.setMargins(0, 0, 0, dp(6));
        presetRow.setLayoutParams(prLp);

        maskPresetBtns = new LinearLayout[LayerConfig.PRESET_COUNT];
        for (int slot = 0; slot < LayerConfig.PRESET_COUNT; slot++) {
            final int s = slot;
            LinearLayout btn = chipButton("", null);
            btn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), 1f));
            ((LinearLayout.LayoutParams) btn.getLayoutParams())
                    .setMargins(dp(3), 0, dp(3), 0);
            maskPresetBtns[slot] = btn;

            /* 短按：应用该预设（空槽提示保存方式） */
            btn.setOnClickListener(v -> {
                if (!LayerConfig.hasPreset(this, s)) {
                    Toast.makeText(this, "预设 " + (s + 1)
                            + " 还是空的 —— 长按保存当前位置", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (maskCfg.loadPreset(this, s)) {
                    syncMaskControls();
                    refreshMask();
                    appendLog("已应用预设 " + (s + 1) + " · "
                            + LayerPreviewView.geometryText(maskCfg));
                    setStatus("状态：已应用预设 " + (s + 1));

                    /*
                     * **必须走完整下发路径**（2026-11-02 bug 修复）。
                     *
                     * 原来这里只做到上面三行就结束了 —— 内存和预览变了，
                     * 但 dcapd2.layer、SharedPreferences、CP 的 destination
                     * 三处持久状态全是旧值。表现就是：
                     *   「预设的遮罩尺寸应用不上，得再点一次保存并应用」
                     * H.264 档最先暴露（它要求编码尺寸=遮罩尺寸=destination
                     * 三者 1:1，不一致就是明显的位置错+拉伸）。
                     *
                     * applyFully() 把这套完整路径收进了 LayerConfig，
                     * 与「保存并应用」走同一份代码，以后不会再漏。
                     * 它会写文件（adb），必须在后台线程。
                     */
                    final LayerConfig snap = maskCfg;
                    final boolean casting = MirrorController.isMirroring();
                    final android.content.Context appCtx = getApplicationContext();
                    new Thread(() -> {
                        try {
                            snap.applyFully(appCtx);
                            Log.i("DashcastMask", "预设 " + (s + 1) + " 已完整下发"
                                    + (casting ? "（含图层矩形）" : "（未投屏，仅落盘）"));
                        } catch (Throwable ex) {
                            Log.w("DashcastMask", "预设下发失败: " + ex);
                        }
                    }, "preset-apply").start();
                }
            });

            /* 长按：已存在 → 询问保存还是清空；不存在 → 直接保存 */
            btn.setOnLongClickListener(v -> {
                if (LayerConfig.hasPreset(this, s)) {
                    new AlertDialog.Builder(this)
                            .setTitle("预设 " + (s + 1))
                            .setItems(new String[]{"保存当前到该槽", "清空该槽"},
                                    (d, which) -> {
                                        if (which == 0) doSavePreset(s);
                                        else {
                                            LayerConfig.clearPreset(this, s);
                                            refreshPresetBtns();
                                            appendLog("已清空预设 " + (s + 1));
                                        }
                                    })
                            .show();
                } else {
                    doSavePreset(s);
                }
                return true;
            });

            presetRow.addView(btn);
        }
        col.addView(presetRow);

        TextView presetHint = new TextView(this);
        presetHint.setText("点预设=应用 · 长按=保存/清空");
        presetHint.setTextSize(12);
        presetHint.setTextColor(getColor(R.color.content_tertiary));
        presetHint.setPadding(dp(4), 0, 0, dp(8));
        col.addView(presetHint);

        /* 形状 / 填充 / 缩放 / 帧率 四格 */
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        maskBtnShape = chipButton(shapeLabel(), v -> {
            maskCfg.shape = (maskCfg.shape + 1) % LayerConfig.SHAPE_NAMES.length;
            ((TextView) maskBtnShape.getChildAt(0)).setText(shapeLabel());
            refreshMask();
        });
        r1.addView(maskBtnShape, rowLp(1f));

        maskBtnFit = chipButton(fitLabel(), v -> {
            maskCfg.fit = maskCfg.fit == 0 ? 1 : 0;
            ((TextView) maskBtnFit.getChildAt(0)).setText(fitLabel());
            refreshMask();
        });
        r1.addView(maskBtnFit, rowLp(1f));

        r1.addView(chipButton("放大", v -> maskPreview.setZoomed(
                !maskPreview.isZoomed())), rowLp(1f));
        r1.addView(chipButton("帧率 " + maskFps, v -> {
            int[] ch = MirrorController.FPS_CHOICES;
            int nx = ch[0];
            for (int i = 0; i < ch.length; i++) {
                if (ch[i] == maskFps) { nx = ch[(i + 1) % ch.length]; break; }
            }
            maskFps = nx;
            ((TextView) ((LinearLayout) v.getParent()).getChildAt(0))
                    .setText("帧率 " + maskFps);
            refreshMask();
        }), rowLp(1f));
        col.addView(r1);

        /* 参数滑块（两列网格） */
        col.addView(maskSection("网格与 DPI"));
        /* 参数滑块：3 列 × 4 行（10 个参数） */
        col.addView(maskSection("网格 · DPI · 边框"));
        col.addView(maskSection("网格 · DPI · 边框 · 羽化"));
        LinearLayout g0 = grid2();
        maskSbGrid = addMaskSlider(g0, "网格", 4, 128, maskCfg.grid, "px",
                v -> { maskCfg.grid = v; refreshMask(); });
        maskTvGrid = maskLastSliderVal;
        maskSbDpi = addMaskSlider(g0, "DPI", 80, 480, maskCfg.dpi, "",
                v -> { maskCfg.dpi = v; refreshMask(); });
        maskTvDpi = maskLastSliderVal;
        col.addView(g0);

        LinearLayout g1 = grid2();
        maskSbBorder = addMaskSlider(g1, "边框", 0, 40, maskCfg.border, "",
                v -> { maskCfg.border = v; refreshMask(); });
        maskTvBorder = maskLastSliderVal;
        maskSbFeather = addMaskSlider(g1, "羽化", 0, 96, maskCfg.feather, "",
                v -> { maskCfg.feather = v; refreshMask(); });
        maskTvFeather = maskLastSliderVal;
        col.addView(g1);

        col.addView(maskSection("四角圆角"));
        LinearLayout g2 = grid2();
        maskSbTl = addMaskSlider(g2, "左上圆角", 0, 120, maskCfg.rTl, "°",
                v -> { maskCfg.rTl = v; refreshMask(); });
        maskTvTl = maskLastSliderVal;
        maskSbTr = addMaskSlider(g2, "右上圆角", 0, 120, maskCfg.rTr, "°",
                v -> { maskCfg.rTr = v; refreshMask(); });
        maskTvTr = maskLastSliderVal;
        col.addView(g2);

        LinearLayout g3 = grid2();
        maskSbBl = addMaskSlider(g3, "左下圆角", 0, 120, maskCfg.rBl, "°",
                v -> { maskCfg.rBl = v; refreshMask(); });
        maskTvBl = maskLastSliderVal;
        maskSbBr = addMaskSlider(g3, "右下圆角", 0, 120, maskCfg.rBr, "°",
                v -> { maskCfg.rBr = v; refreshMask(); });
        maskTvBr = maskLastSliderVal;
        col.addView(g3);

        col.addView(maskSection("梯形"));
        LinearLayout g4 = grid2();
        maskSbTrapTop = addMaskSlider(g4, "梯形上沿", 0, 300, maskCfg.trapTop, "",
                v -> { maskCfg.trapTop = v; refreshMask(); });
        maskTvTrapTop = maskLastSliderVal;
        maskSbTrapBot = addMaskSlider(g4, "梯形下沿", 0, 300, maskCfg.trapBot, "",
                v -> { maskCfg.trapBot = v; refreshMask(); });
        maskTvTrapBot = maskLastSliderVal;
        col.addView(g4);

        /* 保存 / 取消 */
        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2Lp.setMargins(0, dp(10), 0, 0);
        r2.setLayoutParams(r2Lp);

        r2.addView(chipButton("取消（还原）", v -> {
            maskCfg.copyFrom(LayerConfig.loadFromPrefs(this, 1920, 532));
            maskFps = MirrorController.getFps();
            syncMaskControls();
            refreshMask();
        }), rowLp(1f));

        r2.addView(iconButton("保存并应用", "✓", R.drawable.btn_primary,
                android.R.color.white, v -> saveMask()), rowLp(1f));
        col.addView(r2);

        maskPreview.invalidate();
        refreshMaskInfo();
        refreshPresetBtns();
    }

    private String shapeLabel() {
        int s = maskCfg.shape;
        if (s < 0 || s >= LayerConfig.SHAPE_NAMES.length) s = 0;
        return LayerConfig.SHAPE_NAMES[s];
    }

    private String fitLabel() {
        return maskCfg.fit == 0 ? "铺满" : "等比";
    }

    private LinearLayout maskSection(String text) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(getColor(R.color.accent));
        tv.setPadding(dp(4), dp(10), 0, dp(4));
        l.addView(tv);
        return l;
    }

    private LinearLayout grid2() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.HORIZONTAL);
        return g;
    }

    /** 面板里的小胶囊按钮（单文字，无图标） */
    private LinearLayout chipButton(String text, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.btn_secondary);
        b.setClickable(true);
        b.setFocusable(true);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(getColor(R.color.accent_soft));
        tv.setGravity(Gravity.CENTER);
        b.addView(tv);
        b.setOnClickListener(l);
        return b;
    }

    /** 面板里的滑块（两列时各占一半） */
    private SeekBar addMaskSlider(LinearLayout parent, String name, int min, int max,
                                  int cur, String unit, java.util.function.IntConsumer cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(2), dp(4), 0);
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            box.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            box.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(14);
        label.setTextColor(getColor(R.color.content_secondary));
        head.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setTextSize(14);
        val.setTextColor(getColor(R.color.content_primary));
        val.setGravity(Gravity.END);
        head.addView(val);
        box.addView(head);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, cur - min)));
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.accent)));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.accent_soft)));
        sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.surface_border)));
        sb.setPadding(0, dp(2), 0, dp(2));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = min + p;
                val.setText(v + unit);
                if (u) cb.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(sb);
        parent.addView(box);
        val.setText(cur + unit);
        maskLastSliderVal = val;
        return sb;
    }

    private void refreshMask() {
        maskCfg.sanitize();
        maskPreview.invalidate();
        refreshMaskInfo();
    }

    private void refreshMaskInfo() {
        if (maskInfo == null) return;
        maskInfo.setText(LayerPreviewView.geometryText(maskCfg)
                + "  ·  " + LayerConfig.SHAPE_NAMES[maskCfg.shape]);
    }

    private void syncMaskControls() {
        if (maskSbGrid == null) return;
        maskSbGrid.setProgress(maskCfg.grid - 4);
        maskSbDpi.setProgress(maskCfg.dpi - 80);
        maskSbBorder.setProgress(maskCfg.border);
        maskSbTl.setProgress(maskCfg.rTl);
        maskSbTr.setProgress(maskCfg.rTr);
        maskSbBl.setProgress(maskCfg.rBl);
        maskSbBr.setProgress(maskCfg.rBr);
        maskSbTrapTop.setProgress(maskCfg.trapTop);
        maskSbTrapBot.setProgress(maskCfg.trapBot);
        maskSbFeather.setProgress(maskCfg.feather);
        maskTvGrid.setText(maskCfg.grid + "px");
        maskTvDpi.setText("" + maskCfg.dpi);
        maskTvBorder.setText("" + maskCfg.border);
        maskTvTl.setText(maskCfg.rTl + "°");
        maskTvTr.setText(maskCfg.rTr + "°");
        maskTvBl.setText(maskCfg.rBl + "°");
        maskTvBr.setText(maskCfg.rBr + "°");
        maskTvTrapTop.setText("" + maskCfg.trapTop);
        maskTvTrapBot.setText("" + maskCfg.trapBot);
        maskTvFeather.setText("" + maskCfg.feather);
        ((TextView) maskBtnShape.getChildAt(0)).setText(shapeLabel());
        ((TextView) maskBtnFit.getChildAt(0)).setText(fitLabel());
    }

    /** 把当前遮罩存进指定预设槽 */
    private void doSavePreset(int slot) {
        maskCfg.savePreset(this, slot);
        refreshPresetBtns();
        Toast.makeText(this, "已保存到预设 " + (slot + 1) + "（"
                + maskCfg.w + "×" + maskCfg.h + "）", Toast.LENGTH_SHORT).show();
        appendLog("已保存预设 " + (slot + 1) + " · "
                + maskCfg.w + "×" + maskCfg.h);
    }

    /** 刷新三个预设按钮的文案与可用状态 */
    private void refreshPresetBtns() {
        if (maskPresetBtns == null) return;
        for (int slot = 0; slot < maskPresetBtns.length; slot++) {
            LinearLayout btn = maskPresetBtns[slot];
            if (btn == null) continue;
            boolean saved = LayerConfig.hasPreset(this, slot);
            TextView tv = (TextView) btn.getChildAt(0);
            tv.setText("预设 " + (slot + 1) + "\n"
                    + (saved ? LayerConfig.presetLabel(this, slot) : "空"));
            tv.setTextSize(14);
            tv.setTextColor(getColor(saved ? R.color.accent_soft
                                          : R.color.content_tertiary));
            btn.setBackgroundResource(saved
                    ? R.drawable.chip_bg : R.drawable.btn_secondary);
            btn.setAlpha(saved ? 1f : 0.75f);
        }
    }

    private void saveMask() {
        maskCfg.enabled = true;
        maskCfg.saveToPrefs(this);
        final int fps = maskFps;
        final String geo = LayerPreviewView.geometryText(maskCfg);

        new Thread(() -> {
            /* ① 帧率变了就重启 dcapd2（只换进程参数，不碰目标 App） */
            if (fps != MirrorController.getFps()) {
                mirror.setFps(this, fps);
            }

            /* ② 写配置 + 同步图层矩形 —— 这一步就是"刷新仪表"。
             *    投屏中会立刻生效（dcapd2 轮询配置文件 + setLayerRect 重设图层区域），
             *    **不会重启目标 App**，也不会让仪表闪回原厂。
             *    没投屏就只落盘，下次投屏时生效。 */
            boolean ok = maskCfg.push();
            boolean casting = MirrorController.isMirroring();
            if (casting) {
                try {
                    /*
                     * **不能直接用遮罩矩形当 CP 的 destination region**。
                     *
                     * 这一行原来是：
                     *   cpManager.setLayerRect(maskCfg.x, maskCfg.y, maskCfg.w, maskCfg.h);
                     *
                     * 于是"保存遮罩"必然触发重影 —— 这正是用户反馈的
                     * 「LZ4 保存遮罩后基本都会出现重影」：
                     *   · AP 端 dcapd2 把画面画在**画布的遮罩坐标**上（比如 928,192）
                     *   · CP 端把**整块 1920×532 画布压缩**到同样那个矩形里
                     *   → 正常画面被压成一小块 + 缩小副本 → 两个画面叠加
                     *
                     * 正确做法按编码档走（见 LayerGeometry 的详细注释）：
                     *   BGRA 有 alpha：destination = 整屏，靠画布透明挖洞
                     *   H.264 无 alpha：destination = 遮罩矩形，图层本身缩进那块
                     */
                    int[] dest = LayerGeometry.destinationFor(
                            MirrorController.getCodec(),
                            new int[]{maskCfg.x, maskCfg.y, maskCfg.w, maskCfg.h},
                            1920, 532);
                    cpManager.setLayerRect(dest[0], dest[1], dest[2], dest[3]);
                } catch (Throwable ignored) {}
            }

            final boolean wasCasting = casting;
            final boolean pushed = ok;
            mainHandler.post(() -> {
                String msg;
                if (wasCasting) {
                    msg = "遮罩已应用（仪表已刷新）";
                } else if (pushed) {
                    msg = "遮罩已保存（下次投屏生效）";
                } else {
                    msg = "遮罩已保存（仪表未连）";
                }
                setStatus("状态：" + msg);
                appendLog("遮罩已保存 " + geo + (wasCasting ? " · 仪表已刷新" : ""));
            });
        }, "save-mask").start();
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(getColor(R.color.content_accent));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(16), 0, dp(7));
        return t;
    }

    /** 建按钮（带背景 selector 和文字色） */
    /** 建按钮（带权重，用于双列布局） */
    /** 日志空状态文案（清空按钮也复用它） */
    private static final String LOG_EMPTY_HINT =
            "等待操作…\n\n① 点「部署服务」把接收端推到仪表主机\n② 点「选择投屏 App」挑一个目标\n③ 点「开始投屏」即可";

    /**
     * 带左侧图标的按钮。
     *
     * 用 LinearLayout 而不是 Button：Button 无法同时放图标和文字并控制间距。
     * 图标用字符（▶ ■ ↓ 等）而不是图片资源 —— 免增资产，且能跟着文字色一起变。
     */
    private LinearLayout iconButton(String text, String glyph, int bgRes, int colorRes,
                                    View.OnClickListener l) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(bgRes);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setPadding(dp(12), 0, dp(12), 0);

        TextView ic = new TextView(this);
        ic.setText(glyph);
        ic.setTextSize(17);
        ic.setTextColor(getColor(colorRes));
        ic.setAlpha(0.85f);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        icLp.setMargins(0, 0, dp(8), 0);
        ic.setLayoutParams(icLp);
        btn.addView(ic);

        TextView tx = new TextView(this);
        tx.setText(text);
        tx.setTextSize(19);
        tx.setTextColor(getColor(colorRes));
        tx.setTypeface(Typeface.DEFAULT_BOLD);
        tx.setGravity(Gravity.CENTER);
        btn.addView(tx);

        btn.setOnClickListener(l);
        actionButtons.add(btn);
        /* 默认占满一行；放进 row 时由 rowLp 覆盖 */
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)));
        LinearLayout.LayoutParams selfLp = (LinearLayout.LayoutParams) btn.getLayoutParams();
        selfLp.setMargins(dp(4), dp(5), dp(4), dp(5));
        return btn;
    }

    /** 状态卡里的单格指标：上面大数字，下面小标签，各占 1/3 */
    /**
     * 状态卡里的单格指标：上面大数字，下面小标签，各占 1/3。
     *
     * 用外层 FrameLayout 固定高度 + 内部垂直居中的 LinearLayout，
     * 比"嵌套 weight"可靠 —— 后者在 WRAP_CONTENT 父容器里会塌成 0 高。
     */
    private TextView statCell(LinearLayout parent, String label, String initVal) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, dp(56), 1f));

        TextView v = new TextView(this);
        v.setText(initVal);
        v.setTextSize(30);
        v.setTextColor(getColor(R.color.accent_soft));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setIncludeFontPadding(false);
        cell.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(13);
        lb.setTextColor(getColor(R.color.content_tertiary));
        lb.setGravity(Gravity.CENTER);
        lb.setIncludeFontPadding(false);
        lb.setPadding(0, dp(3), 0, 0);
        cell.addView(lb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        parent.addView(cell);
        return v;
    }

    private Button makeButton(String text, int bgRes, int colorRes,
                              View.OnClickListener listener, float weight) {
        Button b = makeButton(text, bgRes, colorRes, listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), weight);
        lp.setMargins(dp(5), dp(6), dp(5), dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private Button makeButton(String text, int bgRes, int colorRes,
                              View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setTextColor(getColor(colorRes));
        b.setBackgroundResource(bgRes);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54));
        lp.setMargins(0, dp(8), 0, dp(8));
        b.setLayoutParams(lp);
        actionButtons.add(b);
        return b;
    }

    /** 切换编码档（二选一）。已在目标档位就什么都不做，避免无谓重启 daemon。 */
    private void setCodecMode(int want) {
        int cur = MirrorController.getCodec();
        if (cur == want) return;
        mirror.setCodec(want);
        refreshCodecButtons();
        setStatus("状态：编码档位已切换 · "
                + (want == MirrorController.CODEC_H264 ? "H.264" : "BGRA+LZ4"));
    }

    /** 刷新二选一按钮的选中态（选中的用青绿实底 + 深字） */
    private void refreshCodecButtons() {
        mainHandler.post(() -> {
            boolean h264 = MirrorController.getCodec() == MirrorController.CODEC_H264;
            applyCodecStyle(btnCodecBgra, !h264);
            applyCodecStyle(btnCodecH264, h264);
        });
    }

    private void applyCodecStyle(LinearLayout b, boolean selected) {
        if (b == null) return;
        int fg = selected ? getColor(R.color.surface_root)
                          : getColor(R.color.content_secondary);
        b.setBackgroundResource(selected
                ? R.drawable.btn_selected : R.drawable.btn_secondary);
        for (int i = 0; i < b.getChildCount(); i++) {
            View c = b.getChildAt(i);
            if (c instanceof TextView) ((TextView) c).setTextColor(fg);
        }
    }

    /** 进入操作：禁用全部按钮，并挂一个看门狗（回调不来也不能把 UI 锁死） */
    private void enterBusy() {
        busy = true;
        setDotColor(R.color.state_warn);
        mainHandler.post(() -> {
            for (View b : actionButtons) b.setEnabled(false);
        });
        mainHandler.removeCallbacks(busyWatchdog);
        mainHandler.postDelayed(busyWatchdog, BUSY_TIMEOUT_MS);
    }

    /** 结束操作：恢复全部按钮 */
    private void leaveBusy() {
        busy = false;
        mainHandler.removeCallbacks(busyWatchdog);
        mainHandler.post(() -> {
            for (View b : actionButtons) b.setEnabled(true);
        });
    }

    /**
     * 看门狗：这些操作全靠服务回调收尾，回调丢了（服务已在跑 / 进程被换掉 /
     * adbd 卡住）就会永远停在"等待虚拟屏就绪…"，四个按钮全灰，只能杀进程。
     */
    private final Runnable busyWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!busy) return;
            Log.w(TAG, "操作超时，强制解锁 UI");
            setStatus("状态：操作超时（没等到虚拟屏回调），已解锁，请重试");
            setDotColor(R.color.text_sub);
            leaveBusy();
        }
    };

    /** 统一入口：busy 期间直接忽略重复点击 */
    private void runOp(String name, String runningMsg,
                       java.util.function.BooleanSupplier op,
                       String okMsg, String failMsg) {
        if (busy) {
            Log.i(TAG, "操作进行中，忽略重复点击: " + name);
            return;
        }
        enterBusy();
        setStatus(runningMsg);
        new Thread(() -> {
            boolean ok;
            try {
                ok = op.getAsBoolean();
            } catch (Exception e) {
                Log.e(TAG, name + " 异常", e);
                ok = false;
            }
            final boolean done = ok;
            setStatus(done ? okMsg : failMsg);
            leaveBusy();
        }, name).start();
    }

    /** [部署服务] 只推送文件，不启动 */
    private void deployService() {
        runOp("cp-deploy", "状态：推送 dashcastd…",
                cpManager::pushDashcastd,
                "状态：部署完成", "状态：部署失败");
    }

    /** [停止服务] 杀进程 + 删除文件，恢复原样 */
    private void stopCpService() {
        // 正在投屏时先停镜像：否则删掉 CP 上的 dashcastd 会把会话打断，
        // 仪表会停在最后一帧且再也连不回来。
        if (mirror != null && mirror.isRunning()) {
            mirror.stop();
            KeepAliveService.stop(this);
            running = false;
            setStatus("状态：已先停止投屏，再删除 CP 服务…");
        }
        runOp("cp-stop", "状态：停止并删除文件…",
                cpManager::stopDashcastd,
                "状态：已停止，文件已删除", "状态：停止失败");
    }

    /** 弹出已安装 App 列表（分组：自己安装 / 系统自带），选择投屏目标 */
    private void showAppPicker() {
        List<AppEntry> userApps = new ArrayList<>();
        List<AppEntry> systemApps = new ArrayList<>();
        loadLaunchableApps(userApps, systemApps);

        if (userApps.isEmpty() && systemApps.isEmpty()) {
            setStatus("状态：未找到可投屏的 App");
            return;
        }

        final AlertDialog[] dialogRef = new AlertDialog[1];

        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(12));

        if (!userApps.isEmpty()) {
            container.addView(groupHeader("自己安装的 App"));
            container.addView(appGrid(userApps, dialogRef));
        }
        if (!systemApps.isEmpty()) {
            container.addView(groupHeader("系统自带 App"));
            container.addView(appGrid(systemApps, dialogRef));
        }

        scroll.addView(container);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择要投屏的 App")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    /** 分组标题 */
    private TextView groupHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(getColor(R.color.accent));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(14), 0, dp(8));
        return t;
    }

    /**
     * 网格容器：每行 COLS 个格子。
     *
     * **用格子方案而不是 GridLayout**：GridLayout 在这个 ROM 上对
     * columnCount 的处理不稳定（不同 API 版本行为不一致），
     * 横排 LinearLayout + 均分权重最可靠 —— 换行由代码控制。
     */
    private static final int APP_GRID_COLS = 4;

    private LinearLayout appGrid(List<AppEntry> list, AlertDialog[] dialogRef) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout curRow = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % APP_GRID_COLS == 0) {
                curRow = new LinearLayout(this);
                curRow.setOrientation(LinearLayout.HORIZONTAL);
                wrap.addView(curRow, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            curRow.addView(appCell(list.get(i), dialogRef), cellLp());
        }
        /* 末行不满时补空位，保证格子宽度一致（否则最后一个会被拉宽） */
        int tail = list.size() % APP_GRID_COLS;
        if (curRow != null && tail != 0) {
            for (int k = tail; k < APP_GRID_COLS; k++) {
                View sp = new View(this);
                curRow.addView(sp, cellLp());
            }
        }
        return wrap;
    }

    private LinearLayout.LayoutParams cellLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                dp(104), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    /** 单个 App 格子：图标 + 名称（可点） */
    private LinearLayout appCell(AppEntry e, AlertDialog[] dialogRef) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackgroundResource(R.drawable.app_cell);
        cell.setPadding(dp(6), dp(10), dp(6), dp(8));
        cell.setClickable(true);
        cell.setFocusable(true);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        icon.setLayoutParams(iconLp);
        try {
            icon.setImageDrawable(e.info.loadIcon(getPackageManager()));
        } catch (Throwable t) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        cell.addView(icon);

        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextSize(14);
        name.setTextColor(getColor(R.color.content_primary));
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLp.setMargins(0, dp(6), 0, 0);
        name.setLayoutParams(nameLp);
        cell.addView(name);

        cell.setOnClickListener(v -> {
            targetPkg = e.info.activityInfo.packageName;
            targetCls = e.info.activityInfo.name;
            targetLabel = e.label;
            saveTargetPref();
            updateTargetHint();
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            if (MirrorController.isMirroring() || running) {
                setStatus("状态：切换投屏目标 → " + e.label + "…");
                startCast();
            } else {
                setStatus("状态：已选择 " + e.label + "（点「开始投屏」生效）");
            }
        });
        return cell;
    }

    /** 已安装可启动 App 的条目 */
    private static class AppEntry {
        final String label;
        final ResolveInfo info;
        final boolean isSystem;
        AppEntry(String label, ResolveInfo info, boolean isSystem) {
            this.label = label;
            this.info = info;
            this.isSystem = isSystem;
        }
    }

    /** 列出所有带 LAUNCHER 入口的 App（分用户/系统两组），排除自身和桌面 */
    private void loadLaunchableApps(List<AppEntry> userOut, List<AppEntry> systemOut) {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
        String selfPkg = getPackageName();
        java.util.Set<String> homes = homePackages();
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            if (selfPkg.equals(ri.activityInfo.packageName)) continue; // 排除自身
            // 桌面/Launcher 不能当投屏目标 —— 那等于把整个中控屏镜像过去
            if (homes.contains(ri.activityInfo.packageName)) continue;
            String label = ri.loadLabel(pm).toString();
            if (label == null || label.isEmpty()) label = ri.activityInfo.packageName;
            boolean isSystem = (ri.activityInfo.applicationInfo.flags
                    & ApplicationInfo.FLAG_SYSTEM) != 0;
            AppEntry e = new AppEntry(label, ri, isSystem);
            if (isSystem) systemOut.add(e); else userOut.add(e);
        }
    }

    /** 桌面（HOME）相关的包名集合：这些永远不是合法的投屏目标 */
    private java.util.Set<String> homePackages() {
        java.util.Set<String> set = new java.util.HashSet<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) set.add(ri.activityInfo.packageName);
            }
        } catch (Exception e) {
            Log.w(TAG, "查询桌面包名失败: " + e);
        }
        set.add("com.flyme.auto.launcher");
        set.add("com.android.launcher3");
        set.add("com.android.systemui");
        return set;
    }

    /** 更新选中目标提示文字 */
    private void updateTargetHint() {
        if (targetHint == null) return;
        if (targetPkg == null) {
            targetHint.setText("投屏目标：未选择 —— 请先选一个 App（只投它，桌面不会被投）");
        } else {
            targetHint.setText("投屏目标：" + (targetLabel == null ? targetPkg : targetLabel)
                    + "　（主屏可以随便切，不影响它）");
        }
    }

    private String label(ResolveInfo ri) {
        try { return ri.loadLabel(getPackageManager()).toString(); }
        catch (Exception e) { return "目标App"; }
    }

    /** [开始投屏] 建虚拟屏 + 推流到 CP：
     *  ① 启动 dashcastd（CP 接收端）
     *  ② 起推流服务（服务内部自建虚拟屏，无需录屏授权）
     *  ③ 虚拟屏就绪 → 把目标 App 启动/迁移到虚拟屏 → 抓帧推流到 CP
     */
    private void startCast() {
        if (busy) {
            Log.i(TAG, "操作进行中，忽略重复点击: 开始投屏");
            return;
        }
        // 必须点名一个 App。不点名就等于"投整个桌面"，那不是我们要的。
        if (targetPkg == null || targetCls == null) {
            setStatus("状态：请先「选择投屏 App」—— 只投这一个，桌面不投");
            showAppPicker();
            return;
        }
        // 上一场还在跑（服务的静态标志为准，Activity 的字段会被重建清零）。
        // 这种情况直接 startForegroundService 会因为 sRunning==true 而跳过建屏，
        // 回调永远不来 → UI 卡在"等待虚拟屏就绪…"。所以先停干净，再重开。
        if (DashcastSenderService.isRunning()) {
            Log.i(TAG, "上一场还在跑，先停掉再重开");
            setStatus("状态：结束上一场投屏…");
            stopServiceNow();
            mainHandler.postDelayed(this::startCastInner, 500);
            return;
        }
        startCastInner();
    }

    private void startCastInner() {
        if (busy) return;
        // 不再预先要授权。建私有虚拟屏（flags=0，不镜像）本来就不需要任何权限，
        // 只有服务确实撞上 CAPTURE_VIDEO_OUTPUT 才会回到 onDisplayReady(-1) 要 token。
        enterBusy();
        setStatus("状态：启动 dashcastd…");

        new Thread(() -> {
            boolean ok;
            int[] size = null;
            try {
                /*
                 * ── 地图 HUD 模式：先把地图的 HUD 页面投到 Display 1 ──
                 *
                 * 必须在启动 dcapd2 **之前**做：dcapd2 一起来就去抓
                 * 物理屏 #1，那块屏得先有内容，否则头几帧全是黑的。
                 *
                 * 失败不阻断投屏 —— HUD 起不来就退回抓主屏（老路径），
                 * 顶多画面差一点，不该让整个投屏开不起来。
                 */
                if (mirror.isMapHudActive()) {
                    setStatus("状态：正在投地图 HUD 到 HUD 屏…");
                    boolean hudOk = mirror.launchMapHud();
                    appendLog(hudOk
                            ? "地图 HUD 已投到 Display " + MirrorController.HUD_DISPLAY_ID
                            : "地图 HUD 未确认到位（继续尝试抓取）");
                }

                // 先确认 CP 上有可执行的 dashcastd（被「停止服务」删过就自动重推），再启动
                ok = cpManager.ensureDashcastd() && cpManager.startDashcastd();
                if (ok) size = cpManager.queryScreenSize();   // 按 CP 真实分辨率建屏
            } catch (Throwable e) {
                // 必须 catch Throwable：这里如果漏出的是 Error（OOM / 链接错误），
                // 线程一死整个进程就闪退 —— 用户看到的就是"点开始投屏直接退出"。
                Log.e(TAG, "启动 dashcastd 异常", e);
                ok = false;
            }
            final boolean done = ok;
            final int[] sz = size;
            mainHandler.post(() -> {
                if (!done) {
                    setStatus("状态：启动 dashcastd 失败（CP 侧接收端没起来）");
                    setDotColor(R.color.text_sub);
                    leaveBusy();
                    return;
                }
                // 换掉原来的「建虚拟屏 + 搬 task」：现在改成抓主屏上目标窗口的像素，
                // App 一直留在中控，所以随时可点。
                setStatus("状态：启动镜像抓帧…");
                KeepAliveService.start(MainActivity.this);
                final String target = targetPkg;
                final String comp = targetPkg + "/" + targetCls;
                mirror.start(target, comp, makeMirrorListener());
                running = true;
                leaveBusy();
            });
        }, "cp-start").start();
    }

    /** 起推流服务。size 为 CP 实测屏幕尺寸，null 则用默认值 */
    private void startCastService(int[] size) {
        Intent svc = new Intent(this, DashcastSenderService.class);
        svc.putExtra(DashcastSenderService.EXTRA_PROJECTION_CODE, Activity.RESULT_OK);
        svc.putExtra(DashcastSenderService.EXTRA_PROJECTION_DATA, projectionData);
        if (size != null) {
            svc.putExtra(DashcastSenderService.EXTRA_WIDTH, size[0]);
            svc.putExtra(DashcastSenderService.EXTRA_HEIGHT, size[1]);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        running = true;
    }

    /** 弹系统的「开始录制或投放」授权框 —— 只有私有屏建不出来时的兜底路径才会到这儿 */
    private void requestProjection() {
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projectionTried = true;
        setStatus("状态：这台 ROM 只肯给带镜像位的屏，需要一次屏幕授权…");
        setDotColor(R.color.state_warn);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Exception e) {
            Log.e(TAG, "拉起授权框失败", e);
            setStatus("状态：拉起屏幕授权失败 " + e.getMessage());
            setDotColor(R.color.text_sub);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                projectionData = data;
                setStatus("状态：已授权，开始投屏…");
                startCast();
            } else {
                setStatus("状态：屏幕授权被拒绝（没有 token 建不了 PUBLIC 虚拟屏）");
                setDotColor(R.color.text_sub);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 虚拟屏就绪回调：把目标 App 搬进虚拟屏（而非物理仪表屏） */
    private void onDisplayReady(int displayId) {
        if (displayId < 0) {
            running = false;
            // 只有"服务明确说缺 CAPTURE_VIDEO_OUTPUT"才去弹授权框。
            // 之前是无条件先弹，结果正常路径也被拦一道。
            if (DashcastSenderService.isProjectionRequired()
                    && projectionData == null && !projectionTried) {
                Log.i(TAG, "私有屏被拒（缺 CAPTURE_VIDEO_OUTPUT），退到 MediaProjection 授权");
                leaveBusy();
                requestProjection();
                return;
            }
            String why = DashcastSenderService.getLastError();
            setStatus("状态：虚拟屏创建失败"
                    + (why == null || why.isEmpty() ? "" : "\n" + why));
            setDotColor(R.color.text_sub);
            leaveBusy();
            return;
        }

        // 送 App 上屏这一步可能要走本机 adbd（socket 阻塞），不能放在主线程
        projectionTried = false;
        setStatus("状态：虚拟屏 " + displayId + " 就绪，正在送 App 上屏…");
        new Thread(() -> {
            String what = targetLabel == null ? targetPkg : targetLabel;
            boolean ok = launchToDisplay(targetPkg, targetCls, displayId);

            // 落地验证：确认那一帧真的在这块屏上，不是"以为投了其实是桌面"
            boolean onDisplay = ok && isOnDisplay(displayId, targetPkg);
            final boolean done = ok;
            final boolean verified = onDisplay;

            mainHandler.post(() -> {
                if (done) {
                    setStatus("状态：投屏运行中 · 虚拟屏 " + displayId + "（" + what + " → CP）"
                            + (verified ? "　✓ 已在那块屏上" : ""));
                    setDotColor(R.color.state_ok);
                } else {
                    setStatus("状态：" + what + " 上屏失败"
                            + (sLastLaunchError.isEmpty() ? "" : "\n" + sLastLaunchError));
                    setDotColor(R.color.text_sub);
                }
                leaveBusy();
            });
        }, "launch-to-display").start();
    }

    /** 目标包是不是真的跑在 displayId 这块屏上（dumpsys activity 里那张 task 表） */
    private boolean isOnDisplay(int displayId, String pkg) {
        try {
            String dump = AdbShell.exec("dumpsys activity activities", 8000);
            String marker = "Display #" + displayId;
            int at = dump.indexOf(marker);
            if (at < 0) return false;

            // 本 display 的段落一直切到下一个 Display #
            int end = dump.indexOf("Display #", at + marker.length());
            String block = end > at ? dump.substring(at, end) : dump.substring(at);
            boolean hit = block.contains(pkg);
            Log.i(TAG, "落地验证 display " + displayId + " ⊃ " + pkg + " = " + hit);
            return hit;
        } catch (Throwable t) {
            Log.w(TAG, "落地验证失败: " + t);
            return true;   // 验不了就不吓唬人
        }
    }

    /**
     * 把目标 App 启动到指定 display（虚拟屏）。
     *
     * 这里必须带重试：DisplayManager 建好屏的那一刻，WindowManager 侧的 DisplayContent
     * 还没建出来，此时 launchDisplayId 会命中 ActivityStackSupervisor 的
     * "displayContent == null → false" 分支，抛 Permission Denial。等 200ms 再发就好。
     * （AOSP 11：非 private 屏本来就允许任何人 launch，权限不是真因。）
     */
    private boolean launchToDisplay(String pkg, String cls, int displayId) {
        String component = pkg + "/" + cls;

        // ① 目标已经在跑 → 直接把那个 task 搬过去（协议.md §四.6 第 3 步）
        //    task 不重建，App 不闪、状态不丢，比再开一个实例干净得多
        if (moveExistingTask(pkg, displayId)) {
            return true;
        }

        // ② 公开 API：ROM 不卡权限时直接过（不经过任何外部进程）
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(new ComponentName(pkg, cls));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(displayId);
                startActivity(i, opts.toBundle());
                Log.i(TAG, "公开 API 把 " + component + " 送到 display " + displayId);
                return true;
            } catch (SecurityException e) {
                Log.w(TAG, "launchDisplayId=" + displayId + " 被 SafeActivityOptions 拒（第 "
                        + attempt + " 次）");
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            } catch (Exception e) {
                Log.e(TAG, "启动到虚拟屏失败: " + e);
                return false;
            }
        }

        // ② 本机 adbd（root，免认证）：am start --display，uid 0 不受那个 signature 限制
        return rootAmStart(component, displayId);
    }

    /**
     * 把目标 App 已经在跑的那个 task 搬到虚拟屏上（不重建）。
     *
     * 这台 ROM 的每个全屏 task 各自占一个 root task，且 stackId == taskId，
     * 所以 `am display move-stack <taskId> <displayId>` 正好等价于 Android 12+ 的
     * moveTaskToDisplay。协议.md §四.6 第 3 步要的就是这个效果：App 不闪、状态不丢。
     */
    private boolean moveExistingTask(String pkg, int displayId) {
        int taskId = findTaskId(pkg, displayId);
        if (taskId <= 0) return false;

        try {
            String out = AdbShell.exec("am display move-stack " + taskId + " " + displayId, 8000).trim();
            Log.i(TAG, "am display move-stack " + taskId + " " + displayId + " → " + out);
            boolean ok = !out.contains("Exception") && !out.contains("Error");
            if (ok) sLastLaunchError = "";
            else sLastLaunchError = out.replace('\n', ' ');
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "move-stack 失败: " + t);
            return false;
        }
    }

    /** 在 `am stack list` 里找目标包现有的 task；已经在目标屏上的不算 */
    private int findTaskId(String pkg, int displayId) {
        try {
            String dump = AdbShell.exec("am stack list", 8000);
            int curDisplay = -1;
            int curStack = -1;

            for (String raw : dump.split("\n")) {
                String line = raw.trim();

                if (line.startsWith("Stack id=")) {
                    curStack = leadingInt(line.substring("Stack id=".length()));
                    int di = line.indexOf("displayId=");
                    curDisplay = di >= 0 ? leadingInt(line.substring(di + "displayId=".length())) : -1;
                    continue;
                }

                if (line.startsWith("taskId=")) {
                    int id = leadingInt(line.substring("taskId=".length()));
                    int colon = line.indexOf(": ");
                    if (colon < 0) continue;
                    String rest = line.substring(colon + 2);
                    int slash = rest.indexOf('/');
                    String tpkg = slash > 0 ? rest.substring(0, slash) : "";
                    if (pkg.equals(tpkg) && curDisplay != displayId) {
                        Log.i(TAG, "找到 " + pkg + " 的 task=" + id
                                + "（stack=" + curStack + "，在 display " + curDisplay + "）");
                        return id;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读 am stack list 失败: " + t);
        }
        return -1;
    }

    /** 取字符串开头的连续数字 */
    private static int leadingInt(String s) {
        int v = 0, i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            v = v * 10 + (s.charAt(i) - '0');
            i++;
        }
        return i == 0 ? -1 : v;
    }

    /** 用本机 adbd 的 root 通道执行 am start（无需 su、不碰 /system） */
    private boolean rootAmStart(String component, int displayId) {
        // --activity-multiple-task：目标 App 已经有 task 时，强制在新屏上再开一个，
        // 否则 am 只会把原来那个 task 拉到前台（它还在主屏上），画面根本不会进虚拟屏。
        String cmd = "am start --display " + displayId
                + " --activity-multiple-task -n " + component;
        try {
            String out = AdbShell.exec(cmd, 8000).trim();
            Log.i(TAG, "adbd: " + cmd + "\n" + out);

            if (out.contains("not started")) {
                // 备用手段：先杀掉目标进程，让它没有旧 task 可复用
                String pkg = component.substring(0, component.indexOf('/'));
                Log.i(TAG, "已有旧 task，force-stop " + pkg + " 后重开");
                AdbShell.exec("am force-stop " + pkg, 5000);
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                out = AdbShell.exec(cmd, 8000).trim();
                Log.i(TAG, "adbd 重试:\n" + out);
            }

            boolean ok = out.contains("Starting:") && !out.contains("Error")
                    && !out.contains("not started");
            if (!ok) {
                sLastLaunchError = out.replace('\n', ' ');
                Log.e(TAG, "adbd 启动失败: " + sLastLaunchError);
            }
            return ok;
        } catch (Throwable t) {
            sLastLaunchError = t.toString();
            Log.e(TAG, "adbd 通道失败: " + t);
            return false;
        }
    }

    /** [停止投屏] 停镜像抓帧进程；CP 端图层随之还原 */
    private void stopCastOnly() {
        if (!running && (mirror == null || !mirror.isRunning())) {
            setStatus("状态：未在投屏中");
            return;
        }

        /*
         * **顺序很关键**：先改界面状态，再做实际的停止动作。
         *
         * 原来的写法是先 mirror.stop() 再 setStatus —— 而 mirror.stop() 内部
         * 会碰 adb / SSH / 线程 join，一旦任何一步抛异常，后面的 setStatus
         * 就永远执行不到，界面卡在"运行中"。用户只能再点一次（第二次因为
         * running 已经是 false 了，走的是另一个分支，反而"正常"了）。
         *
         * 现在把状态置位放最前面，stop 的实活全部 catch 包裹。
         */
        final String killed = targetPkg;
        running = false;
        setDotColor(R.color.content_secondary);
        setStatus("状态：投屏已停止，正在还原仪表…");
        appendLog("停止投屏" + (targetLabel != null ? " · " + targetLabel : ""));

        /* 停控制器（内部已改成异步，这里不会阻塞 UI） */
        try {
            if (mirror != null) mirror.stop();
        } catch (Throwable t) {
            Log.w(TAG, "mirror.stop 异常: " + t.getMessage());
        }

        try {
            KeepAliveService.stop(this);
        } catch (Throwable ignored) {
        }

        new Thread(() -> {
            /*
             * ⓪ **先把地图 HUD 从 HUD 屏撤下来**。
             *
             * 必须放最前面：HUD 页面还留在 Display 1 上，那块屏就一直
             * 显示导航画面。用户点「停止」的期望是"仪表回到原厂"，
             * Display 1 上残留的 HUD 虽然不影响仪表（那是另一块屏），
             * 但下次再投屏时 dcapd2 抓到的还是旧画面 —— 会出现
             * "刚点开始就看到上一场的画面"这种错觉。
             *
             * 不 force-stop：地图是系统应用，杀了会被立刻拉起。
             * 只需把 HUD 页面挪回主屏。
             */
            if (mirror != null && MirrorController.loadMapHud(MainActivity.this)
                    && false) {   /* HUD 是系统应用，停止时无需撤离 */
                try {
                    mirror.dismissMapHud();
                    Log.i(TAG, "地图 HUD 已从 Display "
                            + MirrorController.HUD_DISPLAY_ID + " 撤回");
                } catch (Throwable t) {
                    Log.w(TAG, "撤回地图 HUD 失败: " + t);
                }
            }

            /* ① 杀投屏的目标 App。
             *
             * **系统预装应用杀不掉**：地图（com.geely.map）、音乐这类由
             * ecarx.intent.action.MAP_SERVICE 之类的系统服务托管，force-stop
             * 之后 Android 会立刻把它们拉起来（实测 PID 秒级就变新的）。
             * 所以这里不再是"一定要杀掉"，做完就完，日志如实记结果。 */
            boolean killedOk = true;
            if (killed != null && !killed.isEmpty()) {
                try {
                    AdbShell.exec("am force-stop " + killed, 5000);
                    sleepQuiet(1200);
                    String still = AdbShell.exec("pidof " + killed, 3000);
                    killedOk = still == null || still.trim().isEmpty();
                    Log.i(TAG, killedOk
                            ? "已结束投屏 App: " + killed
                            : "投屏 App 仍在运行（系统应用会被自动拉起）: " + killed);
                } catch (Throwable t) {
                    killedOk = false;
                    Log.w(TAG, "结束投屏 App 失败: " + t.getMessage());
                }
            }

            /* ② 还原仪表 */
            boolean ok = false;
            try {
                ok = cpManager.restoreDashboard();
            } catch (Throwable t) {
                Log.w(TAG, "还原仪表异常: " + t.getMessage());
            }
            final boolean done = ok;
            final boolean appGone = killedOk;
            mainHandler.post(() -> {
                if (!done) {
                    setStatus("状态：投屏已停止（仪表还原未确认）");
                } else if (!appGone) {
                    setStatus("状态：投屏已停止（仪表已还原 · 目标为系统应用，它自己会留在后台）");
                } else {
                    setStatus("状态：投屏已停止（仪表已还原）");
                }
            });
        }, "stop-cast").start();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 真正干活的停止动作：投递 ACTION_STOP（服务同步 release）+ stopService */
    private void stopServiceNow() {
        Intent stop = new Intent(this, DashcastSenderService.class);
        stop.setAction(DashcastSenderService.ACTION_STOP);
        try {
            startService(stop);          // 服务在跑，这条只是投递指令
        } catch (Throwable t) {
            Log.w(TAG, "投递停止指令失败: " + t);
        }
        stopService(new Intent(this, DashcastSenderService.class));
        running = false;
    }

    private void setStatus(final String text) {
        mainHandler.post(() -> status.setText(text));
        Log.i(TAG, text);
        mainHandler.post(() -> {
            updateBadge(text);
            appendLog(text);
        });
    }

    /** 往运行日志面板追加一条（环形 12 条，带时间戳） */
    /**
     * 隐藏系统导航栏 + 让内容顶到状态栏下面。
     *
     * 为什么不用 themes.xml 的 navigationBarColor：Flyme Auto 车机的导航栏
     * 是独立窗口（带 Home/音乐/音量那排），App 里设 navigationBarColor 对它
     * 完全无效 —— 实测设了还是白的。只能整条藏起来。
     *
     * 用 SYSTEM_UI_FLAG_IMMERSIVE_STICKY：划一下能临时叫出来，几秒后自动藏，
     * 用户不会因为找不到返回键而卡住。
     */
    private void applyImmersive() {
        try {
            Window w = getWindow();
            if (w == null) return;
            View decor = w.getDecorView();
            if (decor == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                w.setDecorFitsSystemWindows(false);
                /* 立刻试一次；如果 DecorView 还没 attach 就排到消息队列尾部再试。 */
                if (decor.isAttachedToWindow()) {
                    hideNavBars(w);
                } else {
                    decor.post(() -> {
                        try {
                            hideNavBars(w);
                        } catch (Throwable t) {
                            Log.w(TAG, "applyImmersive(post) 失败（忽略）: " + t);
                        }
                    });
                }
                return;
            }

            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable t) {
            /* 任何机型差异都不该让 App 起不来 —— 藏导航栏只是锦上添花 */
            Log.w(TAG, "applyImmersive 失败（忽略）: " + t);
        }
    }

    /** 真正执行隐藏导航栏。调用前必须确认 DecorView 已 attach。 */
    private void hideNavBars(Window w) {
        WindowInsetsController ctl = w.getInsetsController();
        if (ctl == null) return;
        ctl.hide(WindowInsets.Type.navigationBars());
        ctl.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void appendLog(String raw) {
        if (logView == null || raw == null || raw.isEmpty()) return;
        String msg = raw;
        /* 去掉开头的"状态："前缀，日志里不必重复 */
        if (msg.startsWith("状态：")) msg = msg.substring(3);

        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        logLines.addLast(ts + "  " + msg);
        while (logLines.size() > LOG_MAX) logLines.removeFirst();
        /* 同时落盘到 Download/Dashcast/dashcast.log（UI 只留最近 12 条） */
        LocalStore.log(this, msg);
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        logView.setText(sb.length() > 0 ? sb.toString() : "（暂无日志）");
    }

    /** 按状态文字更新右上角徽标（运行中/进行中/失败/未运行） */
    /** 地图模式按钮文案 */
    private String mapModeLabel() {
        return mirror.isMapMode() ? "地图模式 · 开" : "地图模式 · 关";
    }

    /** 地图 HUD 按钮文案 */
    private String mapHudLabel() {
        return MirrorController.loadMapHud(this) ? "地图 HUD · 开" : "地图 HUD · 关";
    }

    /** 刷新地图 HUD 按钮的高亮与可见性 */
    private void updateMapHudBtn() {
        if (btnMapHud == null) return;
        ((TextView) btnMapHud.getChildAt(1)).setText(mapHudLabel());
        btnMapHud.setBackgroundResource(MirrorController.loadMapHud(this)
                ? R.drawable.btn_selected : R.drawable.btn_secondary);
        /*
         * 只在地图模式 + 目标就是地图时才显示 ——
         * HUD 是地图独有的页面，投别的 App 无从谈起。
         */
        boolean show = mirror.isMapMode()
                && true;   /* HUD 是独立系统应用，不限定目标 */
        btnMapHud.setVisibility(show ? View.VISIBLE : View.GONE);
        /* HUD 模式下视角三档无意义（抓的是 HUD 全屏，不是主屏某块） */
        if (viewRow != null && show && MirrorController.loadMapHud(this)) {
            viewRow.setVisibility(View.GONE);
        } else if (viewRow != null && mirror.isMapMode()) {
            viewRow.setVisibility(View.VISIBLE);
        }
    }

    private void updateMapModeBtn() {
        if (btnMapMode == null) return;
        ((TextView) btnMapMode.getChildAt(1)).setText(mapModeLabel());
        btnMapMode.setBackgroundResource(mirror.isMapMode()
                ? R.drawable.btn_selected : R.drawable.btn_secondary);
        /* 预览上的"抓取区"参考框跟着开关走 */
        if (maskPreview != null) {
            maskPreview.setMapMode(mirror.isMapMode(), 2560, 1600);
        }
        /* 视角三档只在地图模式下有意义 */
        if (viewRow != null) {
            viewRow.setVisibility(mirror.isMapMode() ? View.VISIBLE : View.GONE);
        }
        /* 地图 HUD 开关跟着地图模式 + 目标 App 走 */
        updateMapHudBtn();
    }

    /**
     * 应用地图视角档位（远/中/近）。
     *
     * 做的事就三步，全部走 LayerConfig 的既有路径：
     *   ① applyView() —— 把该档的抓取区和遮罩尺寸算进内存
     *   ② syncMaskControls() + refreshMask() —— 界面跟着更新
     *   ③ applyFully() —— 后台线程完整下发（push + prefs + CP 图层）
     *
     * 第③步是关键：只改内存不落盘的话，dcapd2 那边还是旧参数，
     * 仪表纹丝不动（和"点预设不生效"是同一个坑）。
     */
    private void applyViewMode(int view) {
        int prefsView = view;

        maskCfg.applyView(prefsView, 1920, 532);

        /* 记住选择，下次进来还是这档 */
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putInt("map_view", prefsView).apply();

        syncMaskControls();
        refreshMask();
        updateViewBtns();

        appendLog("视角 → " + LayerConfig.VIEW_NAMES[prefsView]
                + " · " + LayerPreviewView.geometryText(maskCfg));

        final LayerConfig snap = maskCfg;
        final android.content.Context appCtx = getApplicationContext();
        final boolean casting = MirrorController.isMirroring();
        new Thread(() -> {
            try {
                snap.applyFully(appCtx);
                Log.i("DashcastView", "视角档已下发"
                        + (casting ? "（含图层矩形）" : "（未投屏，仅落盘）"));
            } catch (Throwable ex) {
                Log.w("DashcastView", "视角档下发失败: " + ex);
            }
        }, "view-apply").start();

        if (casting) {
            setStatus("状态：视角已切换为 " + LayerConfig.VIEW_NAMES[prefsView]);
        }
    }

    /** 高亮当前视角档 */
    private void updateViewBtns() {
        if (viewBtns == null) return;
        int cur = getSharedPreferences(PREF, MODE_PRIVATE).getInt("map_view", LayerConfig.VIEW_MID);
        for (int i = 0; i < viewBtns.length; i++) {
            if (viewBtns[i] == null) continue;
            viewBtns[i].setBackgroundResource(i == cur
                    ? R.drawable.btn_selected : R.drawable.btn_secondary);
        }
    }

    /** 按状态文字更新右上角徽标（运行中/进行中/失败/未运行） */
    private void updateBadge(String text) {
        if (statusBadge == null || text == null) return;
        String shortText;
        int bgRes, fgRes;
        if (text.contains("运行中") || text.contains("跟随中")) {
            shortText = "运行中"; bgRes = R.drawable.badge_active; fgRes = R.color.accent;
        } else if (text.contains("失败") || text.contains("异常")) {
            shortText = "异常"; bgRes = R.drawable.badge_error; fgRes = R.color.danger;
        } else if (text.contains("…") || text.contains("正在") || text.contains("等待")) {
            shortText = "进行中"; bgRes = R.drawable.badge_warn; fgRes = R.color.warn;
        } else {
            shortText = "未运行"; bgRes = R.drawable.badge_idle; fgRes = R.color.text_sub;
        }
        statusBadge.setText(shortText);
        statusBadge.setTextColor(getColor(fgRes));
        statusBadge.setBackgroundResource(bgRes);
    }

    /** 设置状态灯颜色 */
    private void setDotColor(int colorRes) {
        final int color = getColor(colorRes);
        mainHandler.post(() -> {
            android.graphics.drawable.GradientDrawable d =
                    (android.graphics.drawable.GradientDrawable) statusDot.getBackground();
            if (d != null) d.setColor(color);
            /* 状态卡左侧色条：改 layer-list 第 2 层（index 1）的颜色 */
            android.graphics.drawable.Drawable bg = statusCardView == null
                    ? null : statusCardView.getBackground();
            if (bg instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld =
                        (android.graphics.drawable.LayerDrawable) bg;
                android.graphics.drawable.Drawable bar = ld.getDrawable(1);
                if (bar != null) bar.setColorFilter(color,
                        android.graphics.PorterDuff.Mode.SRC_IN);
            }
        });
    }
    private LinearLayout.LayoutParams rowLp(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), weight);
        lp.setMargins(dp(5), dp(6), dp(5), dp(6));
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
