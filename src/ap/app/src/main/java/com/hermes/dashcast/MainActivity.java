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
    private TextView logView;                  /* 运行日志显示区 */
    private TextView upstream;                 /* 状态卡里的上行指标（图块已删） */
    private final java.util.ArrayDeque<String> logLines =
            new java.util.ArrayDeque<>();       /* 最近 12 条事件，环形 */
    private static final int LOG_MAX = 12;
    private LinearLayout btnCodecBgra, btnCodecH264;   /* 编码档二选一 */
    private LinearLayout btnMapMode;                 /* 地图模式开关 */

    /*
     * 地图投屏（方案 ①：注入地图进程，2026-11-08）
     *
     * **已并入「投屏模式」选择（2026-11-08 晚）**：
     * 原来是一行独立的「地图投屏 / 停地图」按钮，但它和普通投屏在底下做的是
     * 同一件重活 —— 部署 CP 服务、起 dcapd2、占 layer 99 —— 两套同时跑会
     * 互相踩（dashcastd 被重启、dcapd2 被杀、图层争抢）。
     *
     * 现在合并成「模式：普通投屏 / 地图投屏」+ 一组开始/停止按钮。
     * 这两个字段保留声明（别处 `if (btnMapCast != null)` 还在用），但恒为 null。
     */
    private LinearLayout btnMapCast, btnMapStop;
    private TextView mapCastState;

    /* 投屏模式：0=普通投屏（抓主屏/指定 App），1=地图投屏（注入地图进程） */
    private int castMode = 0;
    private LinearLayout btnModeNormal, btnModeMap;
    /** 「选择投屏 App」按钮 —— 地图模式下隐藏（那条链路不需要选 App） */
    private LinearLayout btnPickApp;
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
    /** 本轮已为"兜底建屏"弹过一次授权框了，别再弹第二次 */

    /** busy 看门狗超时（推 dashcastd 走 SSH 分块上传，给足 30 秒） */
    private static final long BUSY_TIMEOUT_MS = 30000;

    /** 最近一次「把 App 送到虚拟屏」失败的原因，直接显示到状态栏 */
    private static volatile String sLastLaunchError = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        /*
         * ══════════════════════════════════════════════════════════════
         * **DashLog 必须先初始化**（2026-11-05）
         * ══════════════════════════════════════════════════════════════
         *
         * DashLog 把技术日志双写到 logcat + Download/Dashcast/dashcast.log。
         * 它需要 Context 才能定位文件 —— 没 init 之前写文件全部静默跳过，
         * 那样 dashcast.log 就是空的，等于白改。
         *
         * 放在 onCreate 最前面（super.onCreate 之后），
         * 保证后面所有 DashLog.xxx 都能落盘。
         */
        DashLog.init(this);
        /* 沉浸式的调用点在 setContentView 之后（见下方），
         * 因为 onCreate 一进来 DecorView 还没建，getInsetsController() 会是 null ——
         * 在那种地方调直接 NPE 闪退。 */

        /* 成员初始化（原样保留，不能在 UI 重构时丢掉） */
        cpManager = new CpManager(this);
        MirrorController prev = MirrorController.getActive();
        mirror = (prev != null) ? prev : new MirrorController(this, cpManager);
        if (MirrorController.isMirroring()) running = true;

        /*
         * **先读回 CP 的真实分辨率**（2026-11-03）。
         *
         * 画布尺寸不再写死 1920×532 —— 那是"遮罩铺满网格，仪表却铺不满"
         * 的根因：CP 屏如果不是这个尺寸，图层只盖住上面一截。
         * 这里在读 maskCfg 之前把上次记住的分辨率装进静态字段，
         * 后面 buildMaskPanel 才能按正确画布尺寸算遮罩。
         */
        MirrorController.loadOutputSize(this);


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

        /*
         * ══════════════════════════════════════════════════════════════
         * **整个页面要避开状态栏**（2026-11-04 修复"截取 App 页面看不见"）
         * ══════════════════════════════════════════════════════════════
         *
         * applyImmersive() 里调了 `setDecorFitsSystemWindows(false)`：
         * 让内容延伸到状态栏下方（沉浸式）。但**没有任何地方补顶部留白**，
         * 于是所有内容从 y=0 开始画 —— 车机顶部那条状态栏（时间/4G/天气）
         * 高约 70px，正好把「▸ 截取 App 页面」这一行（y=46..70）整个盖住。
         *
         * 用户反馈"截取 app 页面在车机查看消失"，就是这个原因 ——
         * 不是控件没创建，是被状态栏遮住了。
         *
         * 修法：读系统状态栏高度（WindowInsets），作为 page 的顶部 padding。
         * 用 insets 而不是写死 70dp，是为了兼容不同车机的状态栏高度差异。
         */
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setBackgroundColor(getColor(R.color.surface_root));
        /* 状态栏高度延迟到 attach 之后才读得到，所以挂在 post 里 */
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getInsets(WindowInsets.Type.statusBars()).top;
            v.setPadding(0, top, 0, insets.getInsets(WindowInsets.Type.navigationBars()).bottom);
            return WindowInsets.CONSUMED;
        });
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setBackgroundColor(getColor(R.color.surface_root));

        /* ══ 左栏 ══ */
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(20), dp(12), dp(12), dp(12));
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
        title.setTextSize(28);   /* 36 → 28：这是控制面板，不是启动页 */
        /*
         * 标题文字用 content_primary（浅色 UI 是深色字）
         * 原来是写死的 android.R.color.white —— 深色底上没问题，
         * 换成 #F5F5F5 页面后白字完全看不见。
         */
        title.setTextColor(getColor(R.color.content_primary));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.02f);
        title.setIncludeFontPadding(false);
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("车机中控 → 仪表投屏");
        subtitle.setTextSize(16);
        subtitle.setTextColor(getColor(R.color.content_secondary));
        titleCol.addView(subtitle);

        statusBadge = new TextView(this);
        statusBadge.setText("未运行");
        statusBadge.setTextSize(16);
        statusBadge.setTextColor(getColor(R.color.content_secondary));
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(dp(12), dp(4), dp(12), dp(4));
        statusBadge.setBackgroundResource(R.drawable.badge_idle);
        titleRow.addView(statusBadge);
        left.addView(titleRow);

        /* ── 状态卡（左侧色条 + 大状态 + 三格指标）── */
        LinearLayout statusCard = new LinearLayout(this);
        statusCardView = statusCard;
        statusCard.setOrientation(LinearLayout.VERTICAL);
        /*
         * 状态卡改用 StatusCardDrawable（2026-11-04）。
         *
         * 原来是 status_card.xml（layer-list：squircle 卡片 + 5dp 竖条）。
         * 但 XML 的竖条用 topLeftRadius/bottomLeftRadius 是**标准圆弧**，
         * 现在卡片主体是超椭圆曲线，两者曲率对不上 —— 放大能看到豁口。
         *
         * StatusCardDrawable 一次画完：squircle 路径 + clipPath 裁出色条，
         * 曲率完全一致，且支持 setBarColor 动态改色。
         */
        statusCard.setBackground(new StatusCardDrawable(
                getColor(R.color.surface_card),
                getColor(R.color.surface_border),
                getColor(R.color.state_idle),
                dpFloat(Squircle.R_CARD), dpFloat(5)));
        statusCard.setPadding(dp(24), dp(12), dp(20), dp(12));
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
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotLp.setMargins(0, 0, dp(12), 0);
        statusRow.addView(statusDot);

        status = new TextView(this);
        status.setText("状态：未运行");
        /*
         * ══════════════════════════════════════════════════════════
         * **状态文字必须限行**（2026-11-05）
         * ══════════════════════════════════════════════════════════
         *
         * 实测：投屏失败时状态文案变成三行（含包名 + 建议），
         * 状态卡从 84dp 撑到 200dp+，把下面「服务部署」「投屏控制」
         * 整块往下推了 160px —— 按钮位置全变，用户点空。
         *
         * 修法：限 2 行 + 尾部省略号。
         *   2 行足够显示「状态：投屏失败 —— com.xxx 起不到前台」这一半，
         *   完整原因在下方「运行日志」里能看到（日志不截断）。
         */
        status.setTextSize(22);   /* 28 → 22：两行 28sp 太占地方 */
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setLineSpacing(0f, 1.05f);
        status.setTextColor(getColor(R.color.content_primary));
        status.setTypeface(Typeface.DEFAULT_BOLD);
        statusRow.addView(status);
        statusCard.addView(statusRow);

        /*
         * 指标区重新布局（2026-11-04）。
         *
         * 删掉「图块」后只剩 fps 和上行两格 —— 原来三格均分时
         * 每格占 1/3 宽度刚好；现在两格各占 1/2，数字会离得很开、
         * 中间大片空白，看着散。
         *
         * 改成：两格各自固定宽度（200dp），整体左对齐，
         * 数字紧凑排在一起，右侧留空 —— 视觉上是一组数据而不是散落的。
         */
        LinearLayout statGrid = new LinearLayout(this);
        statGrid.setOrientation(LinearLayout.HORIZONTAL);
        statGrid.setGravity(Gravity.START);
        /*
         * 高度 64 → 84dp（2026-11-04 修复标签被裁）。
         *
         * 内部实际需要：
         *   数值 36sp ≈ 44px
         *   标签 15sp ≈ 20px + 上边距 3px
         *   = 67px > 64px  → 标签底部被裁掉半行，
         *   用户看到的就是"上行 KB/s"那几个字被切了一半。
         *
         * 给到 84dp：留出 17px 余量，任何字体缩放都不会裁。
         */
        LinearLayout.LayoutParams sgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(84));
        sgLp.setMargins(0, dp(8), 0, 0);
        statGrid.setLayoutParams(sgLp);
        metrics   = statCellFixed(statGrid, "fps",  "—");
        upstream  = statCellFixed(statGrid, "上行 KB/s", "—");
        statusCard.addView(statGrid);

        left.addView(statusCard);

        /* ── 服务部署 ── */
        left.addView(sectionLabel("服务部署"));
        LinearLayout deployRow = new LinearLayout(this);
        deployRow.setOrientation(LinearLayout.HORIZONTAL);
        /*
         * **「部署」降级为次要样式**（2026-11-07，unslop：one bright element）。
         *
         * 原来它和「开始投屏」都是蓝实底 —— 左栏同时出现两个亮元素，
         * 主次不分。而「部署」是**一次性前置动作**（把接收端推到仪表主机），
         * 日常使用根本不会点它；真正的主操作是「开始投屏」。
         *
         * 改成 secondaryButton（抬升底 + 描边），图标色用 accent_soft
         * （蓝，但比主按钮的白色低调），既保留辨识度又不抢焦点。
         *
         * 注意：「停服务」本来就是 btn_danger（红描边），这一行两个按钮
         * 现在都是次要样式，只有左边的「开始投屏」是蓝实底。
         */
        deployRow.addView(iconButton("部署", R.drawable.ic_download, R.drawable.btn_secondary,
                R.color.accent_soft, v -> deployService()), rowLp(1f));
        deployRow.addView(iconButton("停服务", R.drawable.ic_stop, R.drawable.btn_danger,
                R.color.state_error, v -> stopCpService()), rowLp(1f));
        left.addView(deployRow);

        /* ── 投屏控制（重点卡）── */
        left.addView(sectionLabel("投屏控制"));
        LinearLayout castCard = new LinearLayout(this);
        castCard.setOrientation(LinearLayout.VERTICAL);
        /*
         * 投屏控制卡改用 squircle（2026-11-04）。
         *
         * 原来用 card_focus.xml（XML 方角 + 描边，无阴影）。
         * 在浅色 UI 上，白卡片浮在灰底上**唯一的分层线索是阴影** ——
         * 没有阴影时卡片和背景几乎融在一起，整片看起来"一片白"。
         *
         * 改用 Squircle.of(this).card()：squircle 圆角 + 1dp 描边 + 阴影。
         */
        castCard.setBackground(Squircle.of(this).card(R.color.surface_card));
        castCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        castCard.setLayoutParams(ccLp);

        targetHint = new TextView(this);
        targetHint.setTextSize(16);
        targetHint.setTextColor(getColor(R.color.content_secondary));
        targetHint.setGravity(Gravity.CENTER);
        targetHint.setPadding(dp(12), dp(8), dp(12), dp(8));
        targetHint.setBackgroundResource(R.drawable.chip_bg);
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        thLp.setMargins(dp(4), 0, dp(4), 8);
        targetHint.setLayoutParams(thLp);
        castCard.addView(targetHint);

        btnPickApp = iconButton("选择投屏 App", R.drawable.ic_target, R.drawable.btn_secondary,
                R.color.accent_soft, v -> showAppPicker());
        castCard.addView(btnPickApp);

        /*
         * ══════════════════════════════════════════════════════════════
         * **投屏模式切换（2026-11-08 合并普通投屏 / 地图投屏）**
         * ══════════════════════════════════════════════════════════════
         *
         * 之前是两套独立按钮：
         *   [开始投屏][停投屏]   普通 App 投屏
         *   [地图投屏][停地图]   地图专用投屏
         *
         * 但底下重复了三件重活，两套同时跑会互相踩：
         *   · 部署 CP 服务（dashcastd）—— 会重启对方的会话，画面中断
         *   · 启动 dcapd2 —— 同一时间只能有一个，起来就把对方杀了
         *   · 占用 CP layer 99 —— 同一个图层两边抢
         *
         * 现在合并成一个模式选择 + 一组开始/停止按钮：
         *   普通投屏 —— 抓主屏/指定 App
         *   地图投屏 —— 注入地图进程，抓它的 VirtualDisplay
         *
         * 两条路径共享同一套 CP 部署、dcapd2 管理、图层管理、遮罩下发，
         * 只有「画面源」不同。
         */
        castMode = 0;
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        btnModeNormal = chipButton("", v -> switchCastMode(0));
        modeRow.addView(btnModeNormal, rowLp(1f));
        btnModeMap = chipButton("", v -> switchCastMode(1));
        modeRow.addView(btnModeMap, rowLp(1f));
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        modeLp.setMargins(0, 0, 0, dp(6));
        modeRow.setLayoutParams(modeLp);
        castCard.addView(modeRow);
        refreshModeButtons();

        LinearLayout castMainRow = new LinearLayout(this);
        castMainRow.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = iconButton("开始投屏", R.drawable.ic_play, R.drawable.btn_primary,
                android.R.color.white, v -> startCastByMode());
        castMainRow.addView(btnStart, rowLp(1.5f));
        btnStop = iconButton("停投屏", R.drawable.ic_stop, R.drawable.btn_danger,
                R.color.state_error, v -> stopCastByMode());
        castMainRow.addView(btnStop, rowLp(1f));
        castCard.addView(castMainRow);

        /*
         * 原来这里还有一行独立的「地图投屏 / 停地图」按钮（2026-11-08 已并进
         * 上方的模式选择）。保留两个字段的声明（btnMapCast / btnMapStop），
         * 因为其它地方（enabled 联动、状态刷新）还在引用，但按 null 处理，
         * 所有 `if (btnMapCast != null)` 都会安全跳过。
         */
        btnMapCast = null;
        btnMapStop = null;

        mapCastState = new TextView(this);
        mapCastState.setTextSize(14);
        mapCastState.setTextColor(getColor(R.color.content_secondary));
        mapCastState.setGravity(Gravity.CENTER);
        mapCastState.setPadding(dp(8), dp(4), dp(8), dp(4));
        mapCastState.setText("地图投屏：未启动");
        castCard.addView(mapCastState);

        /* 编码档：二选一（默认 BGRA+LZ4）。这两个按钮是 H.264 档唯一的入口，
         * 重构时漏掉过一次，导致完全无法切档 —— 现在固定在主操作下方。 */
        LinearLayout codecRow = new LinearLayout(this);
        codecRow.setOrientation(LinearLayout.HORIZONTAL);
        btnCodecBgra = iconButton("BGRA+LZ4", R.drawable.ic_half, R.drawable.btn_secondary,
                R.color.accent_soft, v -> setCodecMode(MirrorController.CODEC_BGRA));
        codecRow.addView(btnCodecBgra, rowLp(1f));
        btnCodecH264 = iconButton("H.264", R.drawable.ic_square_in, R.drawable.btn_secondary,
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
        /*
         * ── 地图 HUD：一键开投 / 一键停 ──
         *
         * 两条投屏路径是**互斥**的：
         *   · App 投屏  → 抓主屏某窗口
         *   · 地图 HUD → 抓 Display 1 上的 com.ecarx.hud
         * 同一时刻只能有一个，否则两路都往 CP 的 layer 99 写，画面会打架。
         *
         * 所以点这个按钮要能"抢占"：正在 App 投屏 → 先停它 → 再开 HUD。
         * 反过来点「开始投屏」时也一样（见 startCast 里调 stopMapHudOnly）。
         *
         * 前提：CP 侧服务已部署（点过「部署」）。没部署时
         *   startCastInner 会自己 ensureDashcastd 重推，只是慢一点。
         */
        btnMapHud = iconButton(mapHudLabel(), "◈", R.drawable.btn_secondary,
                R.color.accent_soft, v -> {
                    /*
                     * **这是个纯开关**（需求：点一下开，再点一下关）。
                     *
                     *   · 没在投屏 → 开 HUD 投屏
                     *   · 正在投 HUD → 停（仪表回原厂）
                     *   · 正在投 App → 停 App，再开 HUD（抢占）
                     *
                     * 三种情况都不跳转别的 App、不弹选 App 面板 ——
                     * HUD 的取景源是固定的 com.ecarx.hud，跟"投哪个 App"无关。
                     */
                    if (MirrorController.isMirroring()) {
                        if (mirror.isMapHudActive()) {
                            /* 正在投 HUD → 关 */
                            appendLog("地图 HUD · 关闭投屏");
                            stopCastOnly();
                        } else {
                            /* 正在投 App → 抢占切到 HUD */
                            startHudCast();
                        }
                    } else {
                        /* 空闲 → 开 */
                        startHudCast();
                    }
                });
        btnMapHud.setVisibility(View.GONE);
        castCard.addView(btnMapHud);

        left.addView(castCard);

        /* ── 运行日志（占满剩余高度）── */
        left.addView(sectionLabel("运行日志"));
        LinearLayout logCard = card();
        logCard.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logCard.setLayoutParams(lcLp);

        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView logTitle = new TextView(this);
        logTitle.setText("最近事件");
        logTitle.setTextSize(13);
        logTitle.setTextColor(getColor(R.color.content_tertiary));
        logHead.addView(logTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClearLog = new Button(this);
        btnClearLog.setText("清空");
        btnClearLog.setTextSize(13);
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

        /*
         * 启动时**读回历史事件**（2026-11-05 方案 B）。
         *
         * 事件现在存 prefs（不落 events.log），所以 App 重启后
         * 还能看到上次做了什么 —— 之前是纯内存，重启就空了。
         */
        for (String ev : LocalStore.readEvents(this)) {
            logLines.addLast(ev);
            while (logLines.size() > LOG_MAX) logLines.removeFirst();
        }
        logView = new TextView(this);
        if (logLines.isEmpty()) {
            logView.setText(LOG_EMPTY_HINT);
        } else {
            StringBuilder sb0 = new StringBuilder();
            for (String l0 : logLines) {
                if (sb0.length() > 0) sb0.append((char) 10);
                sb0.append(l0);
            }
            logView.setText(sb0.toString());
        }
        logView.setTextSize(15);
        logView.setTextColor(getColor(R.color.content_tertiary));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(0f, 1.5f);
        logView.setPadding(0, dp(8), 0, 0);
        logView.setGravity(Gravity.TOP);
        logCard.addView(logView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        left.addView(logCard);

        /* ══ 右栏：遮罩编辑 ══ */
        /* ══ 右栏：遮罩编辑（内容较高，套 ScrollView 防挤压）══ */
        ScrollView rightScroll = new ScrollView(this);
        rightScroll.setFillViewport(true);
        rightScrollRef = rightScroll;
        page.addView(rightScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        /*
         * 顶部留白 14 → 32dp（2026-11-04）。
         *
         * 「截取 App 页面」移到右栏最顶部后，uiautomator 实测标题落在
         * y=32..53 —— 车机顶部有状态栏（时间/4G/温度），32px 正好压在
         * 状态栏底下，视觉上像"这个区块不存在"。
         *
         * 原来的 14dp 是按"最上面是「图层遮罩」标题"设计的
         * （标题自带 12dp padding，合计 26dp 够用）；
         * 现在最上面是折叠卡片（card() 只有 10dp 上边距），不够。
         * 加到 32dp，和左栏视觉起线对齐，也不压状态栏。
         */
        /*
         * 顶部留白 32 → 14dp（2026-11-04）。
         *
         * 32dp 是按"最上面是卡片"设计的，但实测叠加后：
         *   right padding 32 + card margin 10 + card padding 8 = 50dp
         * 车机屏上就是标题上方一条明显空白带（用户反馈"顶部有什么东西"）。
         *
         * 回到 14dp：heading 文字本身有行高，14dp 足够不贴状态栏。
         */
        right.setPadding(dp(12), dp(12), dp(20), dp(12));
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
                maskCfg.applyView(savedView, MirrorController.getOutputW(), MirrorController.getOutputH());
                syncMaskControls();
                refreshMask();
            }
            updateViewBtns();
        }
        updateTargetHint();


        setDotColor(R.color.content_secondary);

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
        refreshCodecLabels();


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
        refreshCodecLabels();

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
            public void onStats(int fps, int kbps, int tiles, int capture) {
                mainHandler.post(() -> {
                    if (metrics == null) return;
                    /*
                     * **fps=0 但 capture>0 是「画面静止」，不是「链路死了」**
                     * （2026-11-06 修）。
                     *
                     * dcapd2 的 fps 语义是"这一秒发出去了多少有变化的帧"：
                     *   · 画面静止 → tiles=0 → frames=0 → fps=0
                     *   · 但 capture 仍是 20（每秒确实抓了 20 帧）
                     *
                     * 原来直接把 0 打上去，用户看到「fps 0 / 上行 0」，
                     * 第一反应就是"投屏断了"。实测日志证明链路是通的：
                     *   大帧 270 图块 src=SCREEN   ← 第一帧全量送达
                     *   STAT capture=20 tiles=0    ← 之后画面没变，无需发送
                     *
                     * 所以这里区分三种状态：
                     *   ① capture==0            → 真的没在抓（进程死了/暂停）
                     *   ② capture>0 && tiles==0 → 画面静止，链路正常
                     *   ③ tiles>0               → 正常刷新
                     */
                    /*
                     * **H.264 档的「静止」判据必须用 kbps，不能用 tiles**
                     * （2026-11-06 修本轮的失误）。
                     *
                     * H.264 走的是 h264_enc_drain() → RTP/UDP，
                     * 完全不走 enc_push() —— 而 tiles 只在 enc_push 里累加。
                     * 所以 H.264 档下 tiles **恒为 0**，哪怕画面满速在推。
                     *
                     * 实测（01:22）：
                     *   STAT fps=19 capture=20 tiles=0 kbps=216
                     *   上行显示 56 KB/s（数据确实在发）
                     * 而 fps 格却显示「静止」—— 这就是判据用错了。
                     *
                     * 正确判据：
                     *   · BGRA 档：tiles>0 才算在推（tiles 是图块数）
                     *   · H.264 档：kbps>0 就算在推（tiles 永远 0，没有意义）
                     *
                     * 用 kbps 做统一判据更稳：两种档位都只在真正发数据时非 0。
                     */
                    boolean alive = capture > 0;
                    boolean pushing = (kbps > 0) || (tiles > 0);

                    if (!alive) {
                        metrics.setText("0");
                        metrics.setTextColor(getColor(R.color.state_warn));
                    } else if (!pushing) {
                        metrics.setText("静止");
                        metrics.setTextColor(getColor(R.color.content_secondary));
                    } else {
                        metrics.setText(String.valueOf(fps));
                        metrics.setTextColor(getColor(R.color.accent_soft));
                    }
                    if (upstream != null) upstream.setText(String.valueOf(kbps));
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
            DashLog.i(TAG, "记住的投屏目标: " + targetPkg + "/" + targetCls);
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
    /**
     * 卡片容器（MiUIX Card 规范 + squircle 圆角）。
     *
     * 圆角从 XML 的 card_bg（标准 16dp 圆弧）换成 SquircleDrawable：
     *   MiUIX Card.kt 用的是 squircleSurface，不是标准圆角。
     *   差别在于转角"拉长"到 1.1 倍半径 + 贝塞尔过渡，
     *   曲率连续，视觉上和 HyperOS 卡片一致。
     *
     * 填充色 = MiUIX surfaceContainer (#242424)，和页面底同色；
     * 只用 1dp outline (#404040) 描边来划分卡片边界 —— 这是 MiUIX 的分层方式。
     */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Squircle.of(this).card(R.color.surface_card));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(4));
        c.setLayoutParams(lp);
        return c;
    }

    /** 分组小标题 */
    /* ══════════════════════════ 遮罩编辑面板（右栏） ══════════════════════════ */

    private LayerConfig maskCfg;
    private LayerPreviewView maskPreview;
    private TextView maskInfo;
    private SeekBar maskSbGrid, maskSbBorder, maskSbTl, maskSbTr,
                    maskSbBl, maskSbBr, maskSbTrapTop, maskSbTrapBot, maskSbFeather;
    private TextView maskTvGrid, maskTvBorder, maskTvTl, maskTvTr,
                     maskTvBl, maskTvBr, maskTvTrapTop, maskTvTrapBot, maskTvFeather;
    private LinearLayout maskBtnShape, maskBtnFit;
    /** 四角圆角"同步"勾选框（勾上 = 四角联动） */
    private android.widget.CheckBox cornerLockBox;
    /** 防重入标志：批量同步四角时不要递归回调 */
    private boolean cornerSyncing = false;
    private int maskFps = 30;
    private LinearLayout[] maskPresetBtns;      /* 三个预设按钮 */
    private TextView maskLastSliderVal;

    /* ── App 页面截取区控件（方案 D：预览区直接拖框）── */
    private LayerPreviewView appCropPreview;
    private LinearLayout appCropFollow, appCropManual;
    private LinearLayout appCropFitCenter, appCropFitFill;
    private TextView appCropHint, appCropHint2, appCropRatio;
    private android.widget.CheckBox appCropSyncBox;
    private android.widget.CheckBox appCropEnableBox;
    private boolean appCropFollowMode = true;

    /* 编码档能力提示（H.264 档下列出哪些功能不生效） */
    private TextView maskAlphaWarn;

    /**
     * 把画布尺寸对齐到 CP 真实分辨率（并保持"原来铺满的仍然铺满"）。
     *
     * 「遮罩铺满网格、仪表却铺不满」的根因就是画布写死 1920×532。
     * 一切"铺满"相关的动作都先走这里。
     */
    private void syncCanvasToOutput() {
        if (maskCfg == null) return;
        int w = MirrorController.getOutputW();
        int h = MirrorController.getOutputH();
        if (maskCfg.canvasW == w && maskCfg.canvasH == h) return;
        boolean wasFull = maskCfg.x == 0 && maskCfg.y == 0
                && maskCfg.w == maskCfg.canvasW && maskCfg.h == maskCfg.canvasH;
        maskCfg.canvasW = w;
        maskCfg.canvasH = h;
        if (wasFull) {
            maskCfg.w = w;
            maskCfg.h = h;
        }
        maskCfg.sanitize();
        if (maskPreview != null) maskPreview.invalidate();
        appendLog("画布对齐仪表分辨率 → " + w + "×" + h);
    }

    /** 遮罩面板：预览 + 参数滑块 + 保存/取消，全部内嵌在首页右栏 */
    private void buildMaskPanel(LinearLayout col) {
        maskCfg = LayerConfig.loadFromPrefs(this, MirrorController.getOutputW(), MirrorController.getOutputH());
        maskFps = MirrorController.getFps();
        /* 载入时先把画布尺寸对齐一次（CP 分辨率可能变过） */
        syncCanvasToOutput();

        /*
         * 「截取 App 页面」放在最顶部（2026-11-04 布局调整）。
         *
         * 两套坐标的语义关系：
         *   截取区 srcX/Y/W/H = 抓屏幕哪一块   ← 输入
         *   遮罩   x/y/w/h    = 投到仪表哪一块 ← 输出
         *
         * 输入逻辑上先于输出，所以放最前。原来夹在「预览区」和
         * 「预设槽」中间 —— 用户调遮罩调到一半碰到它，操作流被打断。
         *
         * 折叠时只占 44dp；展开时正是用户在操作它的时候。
         */
        buildAppCropSection(col);

        /* 标题 */
        final boolean maskPanelOpen = getSharedPreferences(PREF, MODE_PRIVATE)
                .getBoolean("mask_panel_open", true);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        /*
         * **「图层遮罩」标题变成折叠条**（2026-11-07）。
         *
         * 之前它下面永远全部展开：预览 + 预设 + 形状 + 帧率 + 警告 +
         * 11 个滑块 + 保存按钮，整条要滚动两三屏。
         *
         * 现在拆两层：
         *   始终可见：预览画布 + 几何信息（核心视觉反馈，不该藏）
         *   折叠内容：预设 / 形状 / 帧率 / 参数滑块 / 保存按钮
         *
         * 折叠后右栏只剩一块预览 —— 用户切到这个页面时先看到画面，
         * 要改参数再展开，符合「先看结果、再调参数」的操作顺序。
         */
        /*
         * **折叠符号挪到右侧，单独标「参数」**（2026-11-07 二次修正）。
         *
         * 原来标题写成「▾ 图层遮罩」，但 ▸/▾ 语义是"整块内容的开合"——
         * 而这里的预览画布**始终显示**，只有参数区折叠。
         * 用户看到 ▸ 却马上看到预览，会以为"折叠没生效"。
         *
         * 现在拆成两段：
         *   左：图层遮罩（固定标题，不带符号）
         *   右：参数 ▸ / 参数 ▾（明确指向"参数已收起/已展开"）
         */
        TextView t = new TextView(this);
        t.setText("图层遮罩");
        t.setTextSize(20);
        t.setTextColor(getColor(R.color.content_primary));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.setClickable(true);
        head.setPadding(dp(0), dp(4), dp(8), dp(4));
        head.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        /*
         * 「参数 ▸/▾」做成小胶囊 —— 纯文字容易被当成说明文字，
         * 加个浅底让它一眼看出"这里可以点"。
         */
        TextView paramsToggle = new TextView(this);
        paramsToggle.setText(maskPanelOpen ? "参数 ▾" : "参数 ▸");
        paramsToggle.setTextSize(15);   /* 14 → 15：与其它次要文字同档 */
        paramsToggle.setTextColor(getColor(R.color.content_secondary));
        paramsToggle.setBackground(squircleFor(R.drawable.btn_secondary));
        paramsToggle.setGravity(Gravity.CENTER);
        paramsToggle.setPadding(dp(12), dp(4), dp(12), dp(4));
        head.addView(paramsToggle);

        /*
         * 「展开编辑」按钮已删除（2026-11-04）。
         *
         * 它跳转到 LayerEditorActivity —— 一个独立的可视化布局编辑器，
         * 内容（形状/网格/边框圆角/梯形羽化/恢复全屏/取消保存）
         * 和首页右栏**完全重复**，只是排布更松散。
         *
         * 两套编辑入口容易让人困惑"改哪个才生效"，而且独立页面的
         * 实时预览不跟随投屏状态。既然首页已经够用，直接去掉。
         *
         * （LayerEditorActivity.java 本身保留 —— 万一以后要恢复入口，
         *   文件还在。它不再被任何代码引用。）
         */
        col.addView(head);

        /* 预览区（可拖拽；高度 360 —— 新增「截取 App 页面」区块后要给它腾地方） */
        buildPreviewArea(col);

        /* ── 三个预设槽：点=应用，长按=保存当前，再长按=清空 ── */
        /*
         * ↑ 以上（预览画布 + 几何信息）始终可见。
         * ↓ 以下全部装进 maskBody —— 折叠时一起隐藏。
         */
        LinearLayout maskBody = new LinearLayout(this);
        maskBody.setOrientation(LinearLayout.VERTICAL);
        maskBody.setVisibility(maskPanelOpen ? android.view.View.VISIBLE : android.view.View.GONE);
        col.addView(maskBody);

        head.setOnClickListener(v -> {
            boolean now = maskBody.getVisibility() != android.view.View.VISIBLE;
            maskBody.setVisibility(now ? android.view.View.VISIBLE : android.view.View.GONE);
            paramsToggle.setText(now ? "参数 ▾" : "参数 ▸");
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putBoolean("mask_panel_open", now).apply();
        });

        buildPresetRow(maskBody);

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
            /* 三档循环：铺满(0) → 等比(1) → cover裁切(2) → 铺满(0)
             *
             * cover 是 2026-11-08 为地图投屏加的：
             * 地图源比例 3.61:1，遮罩可能是 1.27:1，
             *   fit=0 → 横向压扁变形
             *   fit=1 → 缩成细条留黑边
             *   fit=2 → 保持比例放大填满，超出裁掉  ← 车模不变形
             */
            maskCfg.fit = (maskCfg.fit + 1) % 3;
            ((TextView) maskBtnFit.getChildAt(0)).setText(fitLabel());
            refreshMask();
        });
        r1.addView(maskBtnFit, rowLp(1f));

        /*
         * ══════════════════════════════════════════════════════════════
         * **「铺满仪表」按钮**（2026-11-03 新增）
         * ══════════════════════════════════════════════════════════════
         *
         * 用户反馈：遮罩已经在网格里铺满，仪表上却还是不满。
         *
         * 根因有两层：
         *   ① 「等比」模式（fit=1）会让画面缩在遮罩中间留黑边 ——
         *      用户看到"遮罩占满了，画面却只占中间一块"
         *   ② H.264 档 16 对齐：遮罩 1920×532 → 编码 1920×528，
         *      差 4px（肉眼几乎看不出，但确实不是严丝合缝）
         *
         * 这个按钮一键把两件事都做对：
         *   · 遮罩 = 整个画布（0,0 1920×532）
         *   · fit = 0（铺满，不留黑边）
         *   · 顺手同步到配置文件与 CP 图层
         */
        r1.addView(chipButton("铺满仪表", v -> {
            /*
             * **先把画布尺寸对齐到 CP 真实分辨率，再铺满**（2026-11-03）。
             *
             * 用户的原话「遮罩尺寸无法铺满仪表盘（明明遮罩尺寸已经铺满网格了）」：
             * 网格画的是画布，画布原来是写死的 1920×532；CP 屏不是这个尺寸时
             * 图层就只盖住一截。现在按 CP 实测尺寸重算画布，再一键铺满，
             * 才是真的"铺满仪表"。
             */
            syncCanvasToOutput();
            maskCfg.x = 0;
            maskCfg.y = 0;
            maskCfg.w = maskCfg.canvasW;
            maskCfg.h = maskCfg.canvasH;
            maskCfg.fit = 0;
            maskCfg.enabled = true;
            maskCfg.sanitize();
            /* sanitize 按 x/y/w/h 重算 canvasFill；这里确实是铺满，明确置位 */
            maskCfg.canvasFill = true;
            ((TextView) maskBtnFit.getChildAt(0)).setText(fitLabel());
            refreshMask();
            updateAppCropTexts();
            appendLog("遮罩 → 铺满仪表 " + maskCfg.canvasW + "×" + maskCfg.canvasH);
            /* 后台落盘 + 同步 CP 图层，投屏中会立刻生效 */
            final LayerConfig snap = maskCfg;
            final android.content.Context appCtx = getApplicationContext();
            new Thread(() -> {
                try {
                    snap.applyFully(appCtx);
                } catch (Throwable ex) {
                    DashLog.w(TAG, "铺满仪表下发失败: " + ex);
                }
            }, "fill-dash").start();
        }), rowLp(1f));

        /*
         * 「放大」按钮已删除（2026-11-04）。
         *
         * 它原本是「预览画布 2 倍放大」，号称给精确拖框用。
         * 但实现有缺陷：computeScale() 里 ox/oy 按放大后的尺寸算，
         * 2 倍后画布必然超出预览区，offset 变成负值 ——
         * 画布中心可见，四周被裁且无法平移查看，反而更难操作。
         *
         * 功能不完整，直接去掉。精确拖框靠下面的「拖拽吸附」开关
         * （关掉后就是像素级）已经够用。
         */
        maskBody.addView(r1);

        /*
         * 帧率单独一行（2026-11-03）。
         *
         * r1 原来塞了 5 个按钮（矩形/铺满/铺满仪表/放大/帧率），
         * 706dp 宽均分后每个只剩 141dp —— 中文标签开始截断。
         * 把帧率拿出来独立成窄条，r1 回到 4 个按钮的舒适宽度。
         */
        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        fpsRow.addView(chipButton("帧率 " + maskFps, v -> {
            int[] ch = MirrorController.FPS_CHOICES;
            int nx = ch[0];
            for (int i = 0; i < ch.length; i++) {
                if (ch[i] == maskFps) { nx = ch[(i + 1) % ch.length]; break; }
            }
            maskFps = nx;
            /*
             * **不能 v.getParent()**（2026-11-04 修复闪退）。
             *
             * chipButton 返回的 LinearLayout 是**按钮本身**，
             * 它的第 0 个子 View 才是文字 TextView。
             *
             * 原代码多绕了一层 getParent()：v 已经是按钮，
             * v.getParent() 拿到的是 fpsRow（LinearLayout），
             * getChildAt(0) 取到的还是那个按钮 container，
             * 强转 TextView 直接 ClassCastException → 崩溃。
             */
            ((TextView) ((LinearLayout) v).getChildAt(0))
                    .setText("帧率 " + maskFps);
            refreshMask();
            MirrorController mc = MirrorController.getActive();
            if (mc != null) { mc.setFps(this, maskFps); }
        }), rowLp(1f));
        maskBody.addView(fpsRow);

        /*
         * ══════════════════════════════════════════════════════════════
         * **依赖 alpha 的功能在 H.264 档不生效，如实告知**（2026-11-03）
         * ══════════════════════════════════════════════════════════════
         *
         * 排查结论（读 layer_mask.c 确认）：
         *   形状（圆角/梯形/椭圆）、四角圆角、梯形上下、羽化、边框，
         *   全部靠**写 alpha 通道**实现（dc_layer_apply 里改 p[3]）。
         *   而 H.264 是 YUV420，**没有 alpha**，编码时直接丢弃。
         *
         *   所以 H.264 档下这些控件拖了完全没反应 —— 用户容易以为是 bug。
         *   另外 fit=1（等比居中）也被 dcapd2 硬编码忽略（见 c 文件第 372 行）。
         *
         * 方案：置灰 + 醒目提示。诚实说明比让人白试好。
         */
        maskAlphaWarn = new TextView(this);
        maskAlphaWarn.setTextSize(13);
        maskAlphaWarn.setTextColor(getColor(R.color.state_warn));
        maskAlphaWarn.setPadding(dp(4), dp(8), dp(4), dp(2));
        maskAlphaWarn.setLineSpacing(0, 1.15f);
        maskBody.addView(maskAlphaWarn);

        /*
         * 参数滑块区（两列网格）。
         *
         * 原来这里堆了三个重复的小标题：
         *   "网格与 DPI" / "网格 · DPI · 边框" / "网格 · DPI · 边框 · 羽化"
         * 是历史迭代留下的残骸，占 78dp 纯浪费。
         * 现在只留一个「遮罩参数」，把 4 个滑块合在一组。
         */
        maskBody.addView(maskSection("遮罩参数"));

        /*
         * **低频参数收进「高级」折叠区**（2026-11-07，unslop）。
         * 原来 15 个滑块平铺，调「羽化」要先滚过网格/边框/四角/梯形。
         * 现在分两层：默认可见 = 羽化 + 四角圆角；高级内 = 网格/边框/梯形。
         */
        LinearLayout advancedBox = new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout advancedHead = new LinearLayout(this);
        advancedHead.setOrientation(LinearLayout.HORIZONTAL);
        advancedHead.setGravity(Gravity.CENTER_VERTICAL);
        advancedHead.setBackground(squircleFor(R.drawable.btn_secondary));
        advancedHead.setClickable(true);
        advancedHead.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams ahLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        ahLp.setMargins(dp(4), dp(8), dp(4), dp(4));
        advancedHead.setLayoutParams(ahLp);

        TextView advIcon = new TextView(this);
        advIcon.setTextSize(13);
        advIcon.setTextColor(getColor(R.color.content_secondary));
        advIcon.setWidth(dp(18));
        advancedHead.addView(advIcon);

        TextView advLabel = new TextView(this);
        advLabel.setText("高级参数（网格 / 边框 / 梯形）");
        advLabel.setTextSize(15);
        advLabel.setTextColor(getColor(R.color.content_secondary));
        advancedHead.addView(advLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final boolean advOpen = getSharedPreferences(PREF, MODE_PRIVATE)
                .getBoolean("advanced_open", false);
        advancedBox.setVisibility(advOpen ? android.view.View.VISIBLE : android.view.View.GONE);
        advIcon.setText(advOpen ? "▾" : "▸");

        advancedHead.setOnClickListener(v -> {
            boolean now = advancedBox.getVisibility() != android.view.View.VISIBLE;
            advancedBox.setVisibility(now ? android.view.View.VISIBLE : android.view.View.GONE);
            advIcon.setText(now ? "▾" : "▸");
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putBoolean("advanced_open", now).apply();
        });

        LinearLayout g0 = grid2();        maskSbGrid = addMaskSlider(g0, "网格", 4, 128, maskCfg.grid, "px",
                v -> { maskCfg.grid = v; refreshMask(); });
        maskTvGrid = maskLastSliderVal;
        /*
         * **DPI 滑块已删除**（2026-11-04）。
         *
         * 排查确认：这个参数被解析进 LayerConfig、写进 dcapd2.layer，
         * 但 **dcapd2 和 layer_mask.c 里没有任何地方读它**
         * （grep 全项目只有 parse 那一行）。
         * 真正决定画面尺寸的是：抓取区 srcW×srcH + 遮罩 w×h。
         *
         * 之前保留它并置灰，想着"协议预留字段，以后可能用"。
         * 但它拖不动、也不生效，在界面上只是个坏的滑块 ——
         * 用户会反复尝试拖动它。
         *
         * 直接去掉。LayerConfig.dpi 字段和 dcapd2.layer 的 dpi= 键
         * 都保留（协议兼容），只是不再有 UI 入口。
         */
        /*
         * ══════════════════════════════════════════════════════════
         * **遮罩参数改成 3 列**（2026-11-04）
         * ══════════════════════════════════════════════════════════
         *
         * 原来 4 个滑块排成 2×2 —— 每行两个各占一半宽度，
         * 在右栏约 1300dp 的可用宽度下，单个滑块有 650dp，
         * 但右侧还留着一大片空白（数值文字只占最右 60dp）。
         *
         * 改成 3 列后：
         *   网格 | DPI | 边框      ← 第 1 行三个
         *   羽化 |                 ← 第 2 行一个
         *
         * 滑块缩短到约 430dp —— 对 4~128 的整数区间，
         * 430px 足够拖出每个整数（约 3.4px/单位），手感没有损失。
         */
        maskSbBorder = addMaskSlider(g0, "边框", 0, 40, maskCfg.border, "",
                v -> { maskCfg.border = v; refreshMask(); });
        maskTvBorder = maskLastSliderVal;
        advancedBox.addView(g0);

        LinearLayout g1 = grid2();
        maskSbFeather = addMaskSlider(g1, "羽化", 0, 96, maskCfg.feather, "",
                v -> { maskCfg.feather = v; refreshMask(); });
        maskTvFeather = maskLastSliderVal;
        maskBody.addView(g1);
        maskBody.addView(advancedHead);
        maskBody.addView(advancedBox);


        /* ══════════════════════════════════════════════════════════════
         * **四角圆角锁定**（2026-11-05 用户要求）
         * ══════════════════════════════════════════════════════════════
         *
         * "四角圆角添加个同步打勾按钮，左上 右上 左下 右下 捆绑在一起一起拖动"
         *
         * 交互：
         *   · 勾上 → 拖任意一个角，另外三个跟着变（四个值始终相等）
         *   · 取消 → 各角独立（可以只圆一个角，做出不对称形状）
         *
         * 为什么默认勾上：绝大多数场景就是"四角统一圆角"，
         * 分别调四个值是少数派需求。默认联动少拖三次。
         *
         * 状态存 prefs（"corner_locked"），重启后保持。
         */
        buildCornerControls(maskBody);

        advancedBox.addView(maskSection("梯形"));
        LinearLayout g4 = grid2();
        maskSbTrapTop = addMaskSlider(g4, "梯形上沿", 0, 300, maskCfg.trapTop, "",
                v -> { maskCfg.trapTop = v; refreshMask(); });
        maskTvTrapTop = maskLastSliderVal;
        maskSbTrapBot = addMaskSlider(g4, "梯形下沿", 0, 300, maskCfg.trapBot, "",
                v -> { maskCfg.trapBot = v; refreshMask(); });
        maskTvTrapBot = maskLastSliderVal;
        advancedBox.addView(g4);

        buildMaskActionRow(maskBody);

        maskPreview.invalidate();
        refreshMaskInfo();
        refreshPresetBtns();

        /*
         * 面板搭好了，按当前编码档刷新一次可用性 ——
         * 否则用户在 H.264 档下进来，控件是亮的但拖了没反应。
         */
        applyAlphaDependentAvailability(
                MirrorController.getCodec() == MirrorController.CODEC_H264);
    }

    /** 预设槽 1/2/3 + 「点预设=应用 · 长按=保存/清空」提示 */
    private void buildPresetRow(LinearLayout col) {
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.setMargins(0, 0, 0, dp(8));
        presetRow.setLayoutParams(prLp);

        maskPresetBtns = new LinearLayout[LayerConfig.PRESET_COUNT];
        for (int slot = 0; slot < LayerConfig.PRESET_COUNT; slot++) {
            final int s = slot;
            LinearLayout btn = chipButton("", null);
            btn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), 1f));
            ((LinearLayout.LayoutParams) btn.getLayoutParams())
                    .setMargins(dp(4), 0, dp(4), 0);
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
                            DashLog.i("DashcastMask", "预设 " + (s + 1) + " 已完整下发"
                                    + (casting ? "（含图层矩形）" : "（未投屏，仅落盘）"));
                        } catch (Throwable ex) {
                            DashLog.w("DashcastMask", "预设下发失败: " + ex);
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
        presetHint.setTextSize(13);
        presetHint.setTextColor(getColor(R.color.content_tertiary));
        presetHint.setPadding(dp(4), 0, 0, dp(8));
        col.addView(presetHint);
    }

    /** 预览画布（黑底 squircle）+ 下方几何信息行 */
    private void buildPreviewArea(LinearLayout col) {
        FrameLayout pvBox = new FrameLayout(this);
        /*
         * 预览容器改成 squircle（2026-11-04）。
         *
         * 原来直接 setBackgroundColor 纯色 → 直角矩形，
         * 和周围 16dp squircle 卡片放在一起显得"方"。
         *
         * 现在用 SquircleDrawable：填充 #000000（MiUIX surface），
         * 16dp 超椭圆圆角，和卡片一致的曲率。
         * 描边 1dp outline，让预览区和页面底分开。
         */
        pvBox.setBackground(Squircle.of(this).border(
                R.color.surface_deep, R.color.surface_border, 1f, Squircle.R_TAB));
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        pvLp.setMargins(0, dp(8), 0, 0);
        pvBox.setLayoutParams(pvLp);

        maskPreview = new LayerPreviewView(this, maskCfg);
        maskPreview.setOnGeometryChanged(c -> refreshMaskInfo());
        /* 地图模式开的时候，预览上叠一块"实际抓取区"虚线框 */
        maskPreview.setMapMode(mirror.isMapMode(), 2560, 1600);
        /*
         * 补应用「吸附网格」偏好。
         * buildAppCropSection 在 maskPreview 创建之前就跑了（它在右栏顶部），
         * 那次调用被 null 判断跳过。这里 maskPreview 已就绪，补一次。
         */
        maskPreview.setSnapEnabled(getSharedPreferences(PREF, MODE_PRIVATE)
                .getBoolean("layer_snap_grid", true));

        pvBox.addView(maskPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        col.addView(pvBox);

        maskInfo = new TextView(this);
        maskInfo.setTextSize(15);
        maskInfo.setTextColor(getColor(R.color.content_tertiary));
        maskInfo.setPadding(dp(2), dp(4), 0, dp(8));
        col.addView(maskInfo);
    }

    /** 四角圆角：标题 + 同步勾选 + 4 个滑块 */
    private void buildCornerControls(LinearLayout col) {
        LinearLayout cornerHead = new LinearLayout(this);
        cornerHead.setOrientation(LinearLayout.HORIZONTAL);
        cornerHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView cornerTitle = new TextView(this);
        cornerTitle.setText("四角圆角");
        cornerTitle.setTextSize(15);
        cornerTitle.setTextColor(getColor(R.color.accent));
        cornerTitle.setPadding(dp(4), dp(12), 0, dp(4));
        cornerHead.addView(cornerTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cornerLockBox = new android.widget.CheckBox(this);
        tintCheckBox(cornerLockBox);
        cornerLockBox.setText("同步");
        cornerLockBox.setTextSize(15);
        cornerLockBox.setTextColor(getColor(R.color.content_secondary));
        cornerLockBox.setPadding(dp(8), dp(4), 0, dp(4));
        cornerLockBox.setMinHeight(dp(40));
        cornerLockBox.setChecked(getSharedPreferences(PREF, MODE_PRIVATE)
                .getBoolean("corner_locked", true));
        cornerLockBox.setOnCheckedChangeListener((b, checked) -> {
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putBoolean("corner_locked", checked).apply();
            applyCornerLockStyle();
            appendLog("四角圆角 " + (checked ? "已同步" : "已独立"));
        });
        cornerHead.addView(cornerLockBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(cornerHead);

        LinearLayout g2 = grid2();
        maskSbTl = addMaskSlider(g2, "左上圆角", 0, 120, maskCfg.rTl, "°",
                v -> setCorner(0, v));
        maskTvTl = maskLastSliderVal;
        maskSbTr = addMaskSlider(g2, "右上圆角", 0, 120, maskCfg.rTr, "°",
                v -> setCorner(1, v));
        maskTvTr = maskLastSliderVal;
        col.addView(g2);

        LinearLayout g3 = grid2();
        maskSbBl = addMaskSlider(g3, "左下圆角", 0, 120, maskCfg.rBl, "°",
                v -> setCorner(2, v));
        maskTvBl = maskLastSliderVal;
        maskSbBr = addMaskSlider(g3, "右下圆角", 0, 120, maskCfg.rBr, "°",
                v -> setCorner(3, v));
        maskTvBr = maskLastSliderVal;
        col.addView(g3);

        applyCornerLockStyle();
    }

    /** 遮罩面板底部：取消（还原）/ 保存并应用 */
    private void buildMaskActionRow(LinearLayout col) {
        /* 保存 / 取消 */
        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2Lp.setMargins(0, dp(12), 0, 0);
        r2.setLayoutParams(r2Lp);

        r2.addView(chipButton("取消（还原）", v -> {
            maskCfg.copyFrom(LayerConfig.loadFromPrefs(this, MirrorController.getOutputW(), MirrorController.getOutputH()));
            maskFps = MirrorController.getFps();
            syncMaskControls();
            refreshMask();
        }), rowLp(1f));

        r2.addView(iconButton("保存并应用", R.drawable.ic_check, R.drawable.btn_primary,
                android.R.color.white, v -> saveMask()), rowLp(1f));
        col.addView(r2);
    }

    /**
     * 形状按钮的文案。
     *
     * H.264 档下按钮是置灰的（dcapd2.c 第 391 行硬编码 dw=boxW，
     * 只认矩形），但原来的文案仍显示「矩形」—— 用户看到灰按钮写着
     * 「矩形」，会以为"这个功能坏了"，而不是"当前档位锁死在这个值"。
     *
     * 所以 H.264 时加个后缀说明原因，灰得明明白白。
     */
    private String shapeLabel() {
        int sg = maskCfg.shape;
        if (sg < 0 || sg >= LayerConfig.SHAPE_NAMES.length) sg = 0;
        /* H.264 档：形状固定矩形，文案标注避免误解 */
        if (MirrorController.getCodec() == MirrorController.CODEC_H264) {
            return "矩形(固定)";
        }
        return LayerConfig.SHAPE_NAMES[sg];
    }
    /**
     * 适配方式按钮的文案。
     *
     * 原本文案「铺满 ✓ / 等比（留边）」直接说明后果 —— 这很好，
     * 保留。但 H.264 档下按钮是置灰的（dcapd2.c 第 391 行
     * `g_codec == H264` 短路前面那个条件，fit 被完全忽略），
     * 灰按钮写着「等比（留边）」同样让人以为功能坏了。
     *
     * H.264 时统一显示「铺满(固定)」—— 文案要短，
     * 因为按钮宽度由 rowLp 均分（约 200dp），「铺满（固定）」
     * 带全角括号会被挤成两行。
     */
    private String fitLabel() {
        if (MirrorController.getCodec() == MirrorController.CODEC_H264) {
            /*
             * H.264 无 alpha，只能靠图层矩形挖洞，所以一直铺满。
             * 但 fit=2（cover）在 H.264 下同样需要 —— 它是「保持比例 + 裁掉超出」，
             * 也是铺满图层，只是源裁得不一样。所以 H.264 也允许切到 cover。
             */
            return maskCfg.fit == 2 ? "裁切填满 ✓" : "铺满(固定)";
        }
        if (maskCfg.fit == 2) return "裁切填满 ✓";
        return maskCfg.fit == 0 ? "铺满 ✓" : "等比（留边）";
    }

    private LinearLayout maskSection(String text) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(getColor(R.color.accent));
        tv.setPadding(dp(4), dp(12), 0, dp(4));
        l.addView(tv);
        return l;
    }

    private LinearLayout grid2() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.HORIZONTAL);
        return g;
    }

    /** 面板里的小胶囊按钮（单文字，无图标） */
    /* ══════════════════════════════════════════════════════════════
     * App 页面截取区（2026-11-03 新增）
     * ══════════════════════════════════════════════════════════════
     *
     * 和「遮罩」完全独立的两套坐标：
     *   遮罩 (x,y,w,h)             = 画布 1920×532 → 内容投到仪表哪一块
     *   截取 (srcX,srcY,srcW,srcH) = 屏幕 2560×1600 → 抓 App 的哪一块像素
     *
     * 设计要点：
     *   · **默认折叠**，只占一行标题 —— 右栏已经很满，
     *     展开后把下面的控件推下去，靠 ScrollView 滚动可达，
     *     "保存并应用" 不会被永久挤出屏幕。
     *   · **按 App 记忆**：每个 App 的截取区尺寸单独存
     *     （prefs 里 app_rect_<pkg>.x/.y/.w/.h），
     *     换 App 时自动套用；没存过就用默认值。
     *   · **写入同一个配置文件**：src_x/src_y/src_w/src_h/src_app_pkg
     *     和遮罩的 x/y/w/h 一起写进 /data/local/tmp/dcapd2.layer，
     *     dcapd2 的解析器按 key 逐个查，多出未知键不影响它。
     */
    /** 右栏外层 ScrollView（展开截取区后要滚到它顶部） */
    private ScrollView rightScrollRef;

    /**
     * 截取区展开高度上限。
     *
     * 取屏幕可用高度的 52%：车机屏约 800dp → 约 416dp，
     * 够放「跟随/手动」+ 380dp 预览 + 比例提示 + 几个按钮，
     * 又不会把下面的「保存并应用」顶出屏幕。
     */
    /**
     * 可折叠正文的最大高度。
     *
     * **从 520dp 提到 78% 屏高（2026-11-04）**。
     *
     * 原因：正文内容实际需要 ~900dp
     *   modeRow 56 + hint 22 + cropBox 380 + hint2 22 + ratio 22
     *   + syncRow 56 + snapRow 56 + fitRow 56 + actRow 56
     *   + ratioRow×2 112 + shotRow 56  ≈ 894dp
     *
     * 现在按屏高 78% 算（1600 × 0.78 ≈ 1248px），最多 1200dp，
     * 比内容高度略小 —— 留一点滚动余量，同时保证"保存并应用"始终可见。
     */
    private int bodyScrollHeight() {
        int h = getResources().getDisplayMetrics().heightPixels;
        int px = (int) (h * 0.78f);
        int min = dp(600);
        int max = dp(1200);
        if (px < min) px = min;
        if (px > max) px = max;
        return px;
    }

    private void buildAppCropSection(LinearLayout parent) {
        /*
         * ══════════════════════════════════════════════════════════════
         * **整块卡片都能点**（2026-11-04 用户要求）
         * ══════════════════════════════════════════════════════════════
         *
         * 之前只有标题行和那个小按钮可点，用户反映"点不准"。
         * 现在：
         *   · card 本身 clickable + 一个 OnClickListener
         *   · 标题行、展开按钮、折叠状态下的整块卡片 —— 全都走同一个 toggle
         *   · 展开后，卡片内的**正文**要吃掉触摸（否则点滑块、
         *     拖截取框都会被卡片抢走）—— 见 body.setOnClickListener(null)
         *     配合 setClickable(true)，只截不断冒泡。
         */
        /*
         * **不再用浅灰卡片，改成和「图层遮罩」同一套视觉语言**（2026-11-07）。
         *
         * 原来这里是 card()（带 surface_card 底 + 阴影），而下面的
         * 「图层遮罩」标题是纯文字、无背景。结果截取区看起来像一条
         * 格格不入的浮条贴在页面顶部 —— 它其实是与图层遮罩并列的
         * 两大功能之一，视觉上却像次级工具。
         *
         * 现在去掉背景，只留一条可点的标题行，靠 ▸/▾ 表达折叠状态，
         * 和「高级参数」折叠条、以及「图层遮罩」标题保持一致的观感。
         */
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 0, 0, 0);
        card.setClickable(true);
        card.setFocusable(true);

        /* ── 标题行：整行可点，右侧带明确的「展开 / 收起」按钮 ── */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        /*
         * **和「图层遮罩」标题完全一致**（2026-11-07）。
         *
         * 原来这里套了 chip_bg（surface_raised 底 + 1dp 描边 + 12dp 圆角），
         * 而下面是裸文字 —— 两条本该平级的分组标题，一条像按钮、一条像标题。
         *
         * 现在都去掉背景，只留 ▸/▾ + 加粗文字，靠前缀和点击区表达可折叠。
         */
        head.setPadding(dp(0), dp(4), dp(8), dp(4));

        TextView title = new TextView(this);
        title.setText("▸ 截取 App 页面");   /* 默认折叠，toggle 里会切换 */
        /*
         * **字号从 14 提到 18**（2026-11-04）。
         *
         * 用户原话「app 页面截取那一行再大点，太小了」——
         * 这行是整块功能的入口，字号跟正文一样大就等于看不见。
         * 18sp + 加粗，和「图层遮罩」标题同级，一眼能找到。
         */
        title.setTextSize(20);
        title.setTextColor(getColor(R.color.content_primary));   /* 与「图层遮罩」一致 */
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        /*
         * 右侧**自己的**展开按钮。
         *
         * 为什么必须有：原来这里只有一行小字「点标题展开」，
         * 而紧挨着的「图层遮罩」标题右边有个「展开编辑」按钮 ——
         * 用户点那个，跳去了图层编辑器的全屏页面，
         * 于是反馈"截取 app 页面无法展开，展开编辑跑到了图层遮罩那"。
         *
         * 现在这个区块自带一个高对比按钮，文案是「展开 / 收起」，
         * 和「展开编辑」视觉上区分开，不会点错。
         */


        /*
         * **高度改回 WRAP_CONTENT**（2026-11-07）。
         *
         * 历史：2026-11-04 时这里给 dp(56)，因为那时 head 里有个 44dp 的
         * 「展开/收起」Button，需要给按钮留上下余量；而且当时 title 是
         * width=0 + weight=1，若 head 用 WRAP_CONTENT 会量成 0 高度塌掉。
         *
         * 现在按钮已删、padding 改成 4dp 上下（和「图层遮罩」一致），
         * title 自身的行高就撑得起 head，不需要固定高度：
         * 固定 56dp 反而让它比下面那条「图层遮罩」（约 28dp）高出一倍，
         * 两条平级标题的高矮差很明显。
         */
        card.addView(head, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        /*
         * ══════════════════════════════════════════════════════════════
         * **总开关：是否启用 App 页面截取**（默认关闭）
         * ══════════════════════════════════════════════════════════════
         *
         * 关（默认）= 铺满遮罩尺寸，抓整个窗口，行为和以前一致。
         * 开 = 按 srcX/Y/W/H 只抓框选的那一块。
         *
         * 为什么默认关：大部分用户只想"把 App 投满仪表"，
         * 默认开会让新用户困惑"为什么只投了一小块"。
         *
         * 放在标题下方独立一行（不折叠），一眼看到当前状态。
         */
        appCropEnableBox = new android.widget.CheckBox(this);
        /*
         * 勾选框着色（2026-11-04 浅色 UI）。
         *
         * 浅色主题下系统默认的 CheckBox tint 是 systemGray（#C7C7CC），
         * 放在纯白卡片上只有 1.7:1 对比度 —— 勾没勾几乎看不出。
         *
         * 显式给 buttonTint：选中态用 accent 蓝（iOS 惯例），
         * 未选中态用 surface_border（#D1D1D6，在白底上轮廓清晰）。
         */
        tintCheckBox(appCropEnableBox);
        appCropEnableBox.setText("启用页面截取（关闭 = 铺满遮罩）");
        /* 13 → 16sp：这行是功能总开关，字大一点才好找、好点（2026-11-04） */
        appCropEnableBox.setTextSize(16);
        appCropEnableBox.setTextColor(getColor(R.color.content_primary));
        appCropEnableBox.setPadding(dp(8), dp(8), 0, dp(8));
        appCropEnableBox.setMinHeight(dp(44));
        boolean enablePref = getSharedPreferences(PREF, MODE_PRIVATE)
                .getBoolean("app_crop_enabled", false);
        appCropEnableBox.setChecked(enablePref);
        appCropEnableBox.setOnCheckedChangeListener((b, checked) -> {
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putBoolean("app_crop_enabled", checked).apply();
            if (!checked) {
                /* 关掉时恢复整窗口，投屏立刻回到铺满 */
                resetAppCropToFull();
            }
            appendLog("页面截取 " + (checked ? "开启" : "关闭（铺满遮罩）"));
            updateAppCropEnableState();

            /*
             * **开关一变就要重新下发**（2026-11-03 修复）。
             *
             * 原来这里只写了 prefs —— 而 prefs 只在「应用并投屏」或
             * 「保存并应用」时才被写进 dcapd2.layer / CP 图层。
             * 后果：用户勾上开关、拖好框、以为生效了，
             * 实际 dcapd2 还在按旧参数抓全屏。
             *
             * 这正是"截取 app 页面尺寸无法生效"的直接原因：
             * 开关只是改了个布尔值，没有任何一方真正用上它。
             *
             * 现在：凡是正在投屏，就立刻 applyFully + 重拉 dcapd2，
             * 和点「应用并投屏」走同一条路径。
             */
            if (MirrorController.isMirroring()) {
                final LayerConfig snap = maskCfg;
                final android.content.Context appCtx = getApplicationContext();
                new Thread(() -> {
                    try {
                        snap.applyFully(appCtx);
                        mirror.restartDaemonNow();
                        DashLog.i(TAG, "截取开关变化已下发（enabled=" + checked + "）");
                    } catch (Throwable ex) {
                        DashLog.w(TAG, "截取开关下发失败: " + ex);
                    }
                }, "crop-toggle-apply").start();
                setStatus("状态：截取开关已" + (checked ? "开启" : "关闭") + "，正在刷新…");
            }
        });
        /*
         * **总开关移进可折叠正文**（2026-11-04）。
         *
         * 原来它挂在 card 直属位置：折叠时仍然显示，
         * 于是 head(56dp) + 复选框(44dp) 挤在卡片顶部，
         * 用户在车机小屏上看不清那一块是什么。
         *
         * 现在把它作为 body 的第一个子控件 —— 折叠时一起隐藏，
         * 展开后第一眼就能看到这个开关。
         */

        /*
         * ── 可折叠的正文：**自带 ScrollView，高度封顶** ──
         *
         * ══════════════════════════════════════════════════════════════
         * 2026-11-04 修「截取页面超出界面 / 无法展开」
         * ══════════════════════════════════════════════════════════════
         *
         * 原来 body 直接挂在 card 里、高度 WRAP_CONTENT：
         * 展开后 380dp 预览 + 一堆按钮加起来七八百 dp，
         * 整块 card 比右栏的可视区还高 ——
         *   · 下面的「应用并投屏」被推到屏幕外，够不着
         *   · 外层 rightScroll 已经滚到底了也看不到
         *   · 用户看到的就是"截取页面超出界面"
         *
         * 而"无法展开"多半是同一个隐患的另一面：卡片被撑得太高，
         * 点击区域的实际位置和视觉位置对不上（车机屏滚动位置固定时尤甚）。
         *
         * 修法：body 套进一个**高度受限的 ScrollView**，
         * 展开时最多占屏幕高度的 52%，超出部分在块内自己滚。
         * 这样：
         *   · 整块 card 高度有上限，下面的控件永远不会被顶出去
         *   · 里面的内容再长也能滚到
         *   · 点整块卡片折叠的行为不受影响（view 始终在可视范围内）
         */
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        /*
         * **body 自己不要设 GONE**（2026-11-04 修「展开后内容空白」）。
         *
         * 上一版把 body 设成 GONE，折叠靠它、展开只切了外层 bodyScroll ——
         * 于是展开后 ScrollView 可见、高度也设上了，
         * 但里面的 body 永远 GONE → **整块内容空白**，
         * 用户还以为"跑到顶部去了"。
         *
         * 显隐只由 bodyScroll 一个开关负责，body 始终 VISIBLE。
         */
        body.setPadding(0, dp(8), 0, 0);
        /*
         * **正文必须吃掉触摸**：整块 card 现在可点（toggle），
         * 如果 body 不拦截，点里面的滑块/截取框会冒泡到 card，
         * 一碰就把整块收起来 —— 那才是真的"没法用"。
         * clickable + 空监听 = 只截住、不处理。
         */
        /*
         * **总开关作为正文第一项**（2026-11-04）。
         *
         * 原来它挂在 card 直属位置：折叠时仍然显示，
         * 于是 head(56dp) + 复选框(44dp) 挤在卡片顶部，
         * 车机小屏上根本看不清那一块是什么。
         *
         * 现在作为 body 的第一个子控件 —— 折叠时一起隐藏，
         * 展开后第一眼就能看到这个开关。
         */
        body.addView(appCropEnableBox);

        body.setClickable(true);
        body.setOnClickListener(v -> { });

        /*
         * ══════════════════════════════════════════════════════════
         * 用 MaxHeightScrollView 替代标准 ScrollView（2026-11-04）
         * ══════════════════════════════════════════════════════════
         *
         * 用户反馈"截取区展开后下面一大片空白"。
         *
         * 根因：标准 ScrollView 必须给固定高度。原来展开时设成
         * bodyScrollHeight()（屏幕高 78%，约 1248px），而内容实测只有
         * 约 500px —— ���出 748px 全是空白。
         *
         * MaxHeightScrollView 的行为是"高度 = min(内容高, 上限)"：
         *   内容短 → 容器跟着缩短，无空白
         *   内容长 → 封顶后可滚动，不会把按钮挤出屏幕
         */
        MaxHeightScrollView bodyScroll = new MaxHeightScrollView(this);
        bodyScroll.setMaxHeight(bodyScrollHeight());
        /*
         * **高度用 WRAP_CONTENT，不要固定值**（2026-11-04）。
         *
         * 原来的 LinearLayout.LayoutParams(MATCH_PARENT, 0) 是
         * "折叠状态下高度 0"的写法 —— 但它会和 MaxHeightScrollView
         * 内部的 onMeasure 打架：外层给 0，测量结果永远是 0。
         *
         * 改成 WRAP_CONTENT，让 MaxHeightScrollView 自己决定高度
         * （内容高 or 上限，取小）。折叠时靠 setVisibility(GONE) 隐藏。
         */
        bodyScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        bodyScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        bodyScroll.setVisibility(View.GONE);
        bodyScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0));
        bodyScroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        /* 模式切换：跟随窗口 / 手动 */
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        appCropFollow = chipButton("跟随窗口", v -> setAppCropMode(true));
        appCropManual = chipButton("手动", v -> setAppCropMode(false));

        /* 模式切换：跟随窗口 / 手动 */
        /*
         * **两个按钮都要加**（2026-11-04 修复丢失）。
         *
         * 之前一次编辑把手误删了 appCropFollow 的 addView ——
         * 界面上只剩一个占满整行的「手动」按钮，"跟随窗口"整个消失。
         * 用户在截图里看到的那个巨大蓝色「手动」就是它。
         */
        modeRow.addView(appCropFollow, rowLp(1f));
        modeRow.addView(appCropManual, rowLp(1f));

        body.addView(modeRow);

        /* 两个 App 历史提示 */
        appCropHint = new TextView(this);
        appCropHint.setTextSize(13);
        appCropHint.setTextColor(getColor(R.color.content_tertiary));
        appCropHint.setPadding(dp(4), dp(8), 0, 0);
        body.addView(appCropHint);

        /*
         * ── 方案 D：直接在预览区拖框（不再用四个滑块）──
         *
         * 为什么换成拖框：
         *   滑块要用户心算"屏幕上哪个坐标对应 App 的哪一块"，
         *   而且四个数之间互相牵制（X+W 不能超屏幕）。
         *   拖框是所见即所得：框住哪块就是抓哪块。
         *
         * 这里复用一个独立的 LayerPreviewView（appCropPreview），
         * 切到它的 appCropMode —— 那个模式画的是**屏幕坐标系**
         * （2560×1600 缩略 + 橙色截取框 + 灰色 App 窗口参考框），
         * 而上方的 maskPreview 画的是**画布坐标系**（1920×532 遮罩）。
         * 两个 View 各管一套坐标，互不干扰。
         */
        appCropPreview = new LayerPreviewView(this, maskCfg);
        appCropPreview.setAppCropMode(true, mirror.getScreenPxSafe());
        appCropPreview.setOnGeometryChanged(c -> {
            /* 拖动中实时刷新只读文案与比例提示 */
            updateAppCropTexts();
        });
        FrameLayout cropBox = new FrameLayout(this);
        /* 截取预览容器：同上，squircle 化 */
        cropBox.setBackground(Squircle.of(this).border(
                R.color.surface_deep, R.color.surface_border, 1f, Squircle.R_TAB));
        LinearLayout.LayoutParams cropLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(380));   /* 420→380，给下方控件留空间 */
        cropLp.setMargins(0, dp(8), 0, dp(4));
        cropBox.setLayoutParams(cropLp);
        cropBox.addView(appCropPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        body.addView(cropBox);

        /* 只读的坐标文案（替代四个滑块，仍要让用户看到精确值） */
        appCropHint2 = new TextView(this);
        appCropHint2.setTextSize(13);
        appCropHint2.setTextColor(getColor(R.color.content_secondary));
        appCropHint2.setPadding(dp(4), dp(2), 0, dp(2));
        body.addView(appCropHint2);

        /* 比例提示 */
        appCropRatio = new TextView(this);
        appCropRatio.setTextSize(13);
        appCropRatio.setTextColor(getColor(R.color.content_secondary));
        appCropRatio.setPadding(dp(4), dp(8), 0, dp(4));
        body.addView(appCropRatio);

        /*
         * ── 「同步遮罩比例」勾选框 ──
         *
         * 为什么需要这个开关：
         *   dcapd2 的编码尺寸 = 遮罩尺寸，抓取区只决定"抓屏幕哪块"，
         *   两者比例不一致时画面会被拉伸（实测 24% 横压）。
         *
         *   但自动同步会把用户精心调好的遮罩改掉（面积守恒重算宽高），
         *   而且没有恢复入口 —— 所以做成可关。
         *
         *   开：画面永不变形，代价是遮罩跟着变
         *   关：保留用户的遮罩，但比例不匹配会变形（界面会警告）
         */
        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);
        syncRow.setPadding(dp(4), dp(2), 0, dp(2));

        appCropSyncBox = new android.widget.CheckBox(this);
        tintCheckBox(appCropSyncBox);
        appCropSyncBox.setText("同步遮罩比例（防变形）");
        appCropSyncBox.setTextSize(13);
        appCropSyncBox.setTextColor(getColor(R.color.content_secondary));
        boolean syncPref = getSharedPreferences(PREF, MODE_PRIVATE)
                .getBoolean("layer_sync_aspect", true);
        appCropSyncBox.setChecked(syncPref);
        appCropPreview.setSyncAspectEnabled(syncPref);
        appCropSyncBox.setOnCheckedChangeListener((b, checked) -> {
            if (appCropPreview != null) appCropPreview.setSyncAspectEnabled(checked);
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putBoolean("layer_sync_aspect", checked).apply();
            appendLog("遮罩比例同步 " + (checked ? "开" : "关"));
            /* 勾上时立刻同步一次，让用户马上看到效果 */
            if (checked && appCropPreview != null) {
                appCropPreview.syncMaskAspectNow();
                updateAppCropTexts();
            }
        });
        syncRow.addView(appCropSyncBox, rowLp(1f));
        body.addView(syncRow);

        /*
         * ── 「吸附网格」勾选框（2026-11-03 新增）──
         *
         * 用户原话：「拖动不好拖，拖不出自己想要的效果」。
         *
         * 根因：拖动**每一帧**都按 grid（默认 32px）取整 ——
         * 手指移几十像素画面不动，然后突然跳一格；想微调 10px 做不到。
         *
         * 现在默认只在松手时吸附一次，拖动过程跟手。
         * 想彻底像素级精确就把这个勾去掉。
         */
        LinearLayout snapRow = new LinearLayout(this);
        snapRow.setOrientation(LinearLayout.HORIZONTAL);
        snapRow.setGravity(Gravity.CENTER_VERTICAL);
        snapRow.setPadding(dp(4), 0, 0, dp(2));

        android.widget.CheckBox snapBox = new android.widget.CheckBox(this);
        tintCheckBox(snapBox);
        snapBox.setText("吸附网格（关掉 = 像素级精确）");
        snapBox.setTextSize(13);
        snapBox.setTextColor(getColor(R.color.content_secondary));
        boolean snapPref = getSharedPreferences(PREF, MODE_PRIVATE)
                .getBoolean("layer_snap_grid", true);
        snapBox.setChecked(snapPref);
        /*
         * **maskPreview 此时还是 null**（2026-11-04 崩溃修复）。
         *
         * buildAppCropSection 被移到右栏最顶部，执行时
         * maskPreview 还没创建（它在下面「预览区」才 new）。
         * 原来直接调 maskPreview.setSnapEnabled() → NPE → App 启动即死。
         *
         * 赋初值延后：先记下偏好，等 maskPreview 建好后在
         * buildMaskPanel 末尾统一应用（见下方的 applySnapPref）。
         */
        if (maskPreview != null) maskPreview.setSnapEnabled(snapPref);
        appCropPreview.setSnapEnabled(snapPref);
        snapBox.setOnCheckedChangeListener((b, checked) -> {
            if (maskPreview != null) maskPreview.setSnapEnabled(checked);
            if (appCropPreview != null) appCropPreview.setSnapEnabled(checked);
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putBoolean("layer_snap_grid", checked).apply();
            appendLog("网格吸附 " + (checked ? "开" : "关（像素级精确）"));
        });
        snapRow.addView(snapBox, rowLp(1f));
        body.addView(snapRow);

        /* 适配方式：等比居中 / 裁满铺 */
        LinearLayout fitRow = new LinearLayout(this);
        fitRow.setOrientation(LinearLayout.HORIZONTAL);
        appCropFitCenter = chipButton("等比居中", v -> setAppCropFit(0));
        appCropFitFill = chipButton("裁满铺", v -> setAppCropFit(1));
        fitRow.addView(appCropFitCenter, rowLp(1f));
        fitRow.addView(appCropFitFill, rowLp(1f));
        body.addView(fitRow);

        /* 操作：读取窗口 / 应用并投屏 */
        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout btnRead = iconButton("读取窗口", R.drawable.ic_download,
                R.drawable.btn_secondary, R.color.content_primary,
                v -> readTargetWindow());
        LinearLayout btnApply = iconButton("应用并投屏", R.drawable.ic_check,
                R.drawable.btn_selected, R.color.accent_on,
                v -> applyAppCropAndCast());
        actRow.addView(btnRead, rowLp(1f));
        actRow.addView(btnApply, rowLp(1f));
        body.addView(actRow);

        /*
         * ══════════════════════════════════════════════════════════════
         * 【方案 E】预设比例一键框选
         * ══════════════════════════════════════════════════════════════
         *
         * 拖框最难的地方是"不知道该框多大"。大多数场景其实只要
         * "框成某个常见比例"，点一下自动算出该比例能放下的最大区域。
         *
         * 3.61:1 是仪表原生比例（1920/532），最常用 —— 点了就是
         * "整屏宽度、高度按仪表比例裁"，一步到位。
         */
        /*
         * 比例按钮：每行 3 个，按需换行。
         *
         * 原来那段写法很绕（用 ri>2 / ri==3 / ri==length-1 三个条件
         * 交叉判断何时新建、何时入树），虽然能跑但极易改坏。
         * 改成直白的"每 3 个开新行"，一眼能看懂。
         */
        LinearLayout ratioRow = null;
        for (int ri = 0; ri < LayerPreviewView.RATIO_NAMES.length; ri++) {
            if (ri % 3 == 0) {
                ratioRow = new LinearLayout(this);
                ratioRow.setOrientation(LinearLayout.HORIZONTAL);
                body.addView(ratioRow);
            }
            final int idx = ri;
            LinearLayout b = chipButton(LayerPreviewView.RATIO_NAMES[ri], v -> {
                if (appCropPreview == null) return;
                appCropPreview.applyRatio(idx);
                updateAppCropTexts();
                appendLog("按比例框选 → " + LayerPreviewView.RATIO_NAMES[idx]
                        + "  " + maskCfg.srcW + "×" + maskCfg.srcH);

                /*
                 * **点比例后立刻落盘 + 重启抓帧**（2026-11-03 修复）。
                 *
                 * 原来只改了内存（cfg.src*）和预览显示 ——
                 * 界面文案变了，但 prefs / dcapd2.layer 全是旧值，
                 * 用户以为设置成功，实际抓的还是旧区域。
                 *
                 * 这和「勾开关」「切编码档」是同一类错误：
                 * 改状态却没触发下发。统一处理：走 applyFully + 重拉。
                 */
                if (MirrorController.isMirroring()) {
                    maskCfg.enabled = true;
                    if (appCropPreview != null) appCropPreview.syncMaskAspectNow();
                    final LayerConfig snap = maskCfg;
                    final android.content.Context appCtx = getApplicationContext();
                    new Thread(() -> {
                        try {
                            snap.applyFully(appCtx);
                            mirror.restartDaemonNow();
                            DashLog.i(TAG, "按比例框选已下发");
                        } catch (Throwable ex) {
                            DashLog.w(TAG, "按比例框选下发失败: " + ex);
                        }
                    }, "ratio-apply").start();
                }
            });
            if (ratioRow != null) ratioRow.addView(b, rowLp(1f));
        }

        /*
         * ══════════════════════════════════════════════════════════════
         * 【方案 C】实时截屏当底图
         * ══════════════════════════════════════════════════════════════
         *
         * 点一下截当前屏幕，铺在预览区当底图 —— 用户直接"看着真实
         * App 界面框选"，而不是对着空白网格猜坐标。
         *
         * 为什么不自动刷：截屏走 adb，每秒一次会拖慢界面；
         * 用户调整 App 界面后主动点一次更可控。
         */
        LinearLayout shotRow = new LinearLayout(this);
        shotRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout btnShot = iconButton("刷新截图（当底图）", R.drawable.ic_crop,
                R.drawable.btn_secondary, R.color.content_primary,
                v -> refreshBackgroundShot());
        LinearLayout btnShotClear = iconButton("清除底图", R.drawable.ic_x,
                R.drawable.btn_secondary, R.color.content_tertiary,
                v -> {
                    if (appCropPreview != null) appCropPreview.setBackgroundShot(null);
                    appendLog("底图已清除");
                });
        shotRow.addView(btnShot, rowLp(2f));
        shotRow.addView(btnShotClear, rowLp(1f));
        body.addView(shotRow);

        /* 正文装进受限高度的 bodyScroll，再挂到 card —— 卡片高度才有上限 */
        card.addView(bodyScroll);

        /*
         * **整块卡片 / 标题行 / 按钮都能点**（三个入口，同一个 toggle）。
         *
         * 用户要求"整块卡片都能点" —— 车机上手指粗、命中率低，
         * 只让标题行可点经常按空。现在 card 本体也绑了。
         *
         * 展开状态下点正文不会被折叠：body 已经 clickable 吃掉触摸
         * （见上面 body.setClickable(true) 的注释）。
         */
        View.OnClickListener toggle = v -> {
            boolean opening = bodyScroll.getVisibility() != View.VISIBLE;

            /*
             * ══════════════════════════════════════════════════════
             * **顺序很关键：先设高度，再切可见性**（2026-11-04 修复空白）
             * ══════════════════════════════════════════════════════
            /*
             * MaxHeightScrollView 自己会算高度 = min(内容, 上限)，
             * 这里只需要切可见性，不用再手动设置 LayoutParams.height。
             *
             * 这也是"展开后一大片空白"的根治办法 —— 原来硬设 1248px，
             * 内容只有 500px，多出来的 748px 就是那片空白。
             */
            if (opening) {
                bodyScroll.setVisibility(View.VISIBLE);
                bodyScroll.requestLayout();
                bodyScroll.post(() -> bodyScroll.scrollTo(0, 0));
            } else {
                bodyScroll.setVisibility(View.GONE);
            }
            title.setText((opening ? "▾ " : "▸ ") + "截取 App 页面");

            if (opening) {
                syncAppCropFromConfig();
                /*
                 * 展开后把外层右栏滚到这一块顶部 ——
                 * 否则用户还在原位置，看不到刚展开的内容。
                 */
                final ScrollView outer = rightScrollRef;
                if (outer != null) {
                    outer.post(() -> outer.smoothScrollTo(0, card.getTop()));
                }
            }
        };
        card.setOnClickListener(toggle);
        head.setOnClickListener(toggle);

        /*
         * **区块之间留出间距**（2026-11-07）。
         *
         * 原来 card 没有任何 margin，而下面的「图层遮罩」head 只有 4dp padding ——
         * 两条平级标题只差 32px，看起来就是两行文字挨在一起，不像两个独立模块。
         *
         * 加 16dp 底部间距（= 4 个网格），和右栏各区块之间的呼吸感一致。
         */
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(16));
        parent.addView(card, cardLp);
        /*
         * 初始化：只载入配置，**不自动读目标窗口**。
         *
         * 原来这里调 setAppCropMode(true)，它内部会 readTargetWindow()
         * → 每次打开 App 就起线程走 adb 读窗口，哪怕用户没展开这个区块。
         * 浪费几百毫秒，还会和投屏主循环抢 adb socket。
         *
         * 用户真要用时自己点「跟随窗口」或「读取窗口」即可。
         */
        syncAppCropFromConfig();
        setAppCropModeVisual(true);
        mirror.warmScreenPxAsync();
    }

    /** 从配置里把截取区读进滑块（并按当前目标 App 套用历史值） */
    private void syncAppCropFromConfig() {
        if (appCropPreview == null) return;

        /* 先看当前目标 App 有没有历史尺寸 */
        boolean applied = false;
        if (targetPkg != null) {
            applied = maskCfg.applyAppRect(this, targetPkg);
        }

        /* 没历史值：用配置里的，再不行给个合理的默认（整屏上部 3.6:1 横条） */
        if (!applied) {
            if (maskCfg.srcW <= 0 || maskCfg.srcH <= 0) {
                int[] scr = mirror.getScreenPxSafe();
                int sw = scr[0] > 0 ? scr[0] : 2560;
                int sh = scr[1] > 0 ? scr[1] : 1600;
                maskCfg.srcW = sw;
                maskCfg.srcH = (int) (sw / ((double) MirrorController.getOutputW()
                        / MirrorController.getOutputH()));
                if (maskCfg.srcH > sh) maskCfg.srcH = sh;
                maskCfg.srcX = 0;
                maskCfg.srcY = (sh - maskCfg.srcH) / 2;
            }
        }
        maskCfg.sanitizeCrop(mirror.getScreenPxSafe()[0], mirror.getScreenPxSafe()[1]);

        if (appCropHint != null) {
            String pkg = targetPkg == null ? "（未选 App）" : targetPkg;
            appCropHint.setText(applied
                    ? "✓ 已套用该 App 的历史尺寸：" + pkg
                    : "该 App 无历史尺寸，使用当前值 · " + pkg);
        }
        if (MirrorController.getCodec() == MirrorController.CODEC_H264) { appCropPreview.syncMaskAspectNow(); }
        appCropPreview.setAppCropMode(true, mirror.getScreenPxSafe());
        appCropPreview.refresh();
        updateAppCropTexts();
    }

    /**
     * 切换投屏 App 时，套用该 App 的历史截取尺寸并立刻落盘。
     *
     * 用户的诉求：截取某个 App 的页面后保存，下次投同一个 App 时
     * 要自动按存好的框去截，不用重新框一遍。
     *
     * 三层保障（缺一层就会"看起来没记住"）：
     *   ① applyAppRect  → 把 app_rect_<pkg> 读进内存
     *   ② applyFully    → 写回 prefs 的全局 layer_src_*
     *   ③ restartDaemon → 让 dcapd2 按新框重新抓
     *
     * 注意：没有历史记录时**什么都不做** —— 保留当前框，
     * 不要用一个"默认值"覆盖用户正在用的设置。
     */
    private void applyAppRectOnSwitch() {
        if (maskCfg == null || targetPkg == null) return;
        if (!LayerConfig.hasAppRect(this, targetPkg)) {
            appendLog("该 App 无历史截取尺寸（保留当前框）：" + targetPkg);
            return;
        }
        boolean applied = maskCfg.applyAppRect(this, targetPkg);
        if (!applied) return;

        /* ① 打开总开关 —— 有记忆就说明用户想按框截 */
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean("app_crop_enabled", true).apply();
        if (appCropEnableBox != null && !appCropEnableBox.isChecked()) {
            appCropEnableBox.setChecked(true);
        }
        updateAppCropEnableState();

        /* ② 同步到界面 */
        if (appCropPreview != null) {
            appCropPreview.setAppCropMode(true, mirror.getScreenPxSafe());
            appCropPreview.refresh();
        }
        updateAppCropTexts();
        if (appCropHint != null) {
            appCropHint.setText("✓ 已套用该 App 的历史尺寸：" + targetPkg);
        }

        /* ③ 落盘 + 重拉抓帧（投屏中才需要；没在投屏时下次启动自然读 prefs） */
        maskCfg.enabled = true;
        final LayerConfig snap = maskCfg;
        final android.content.Context appCtx = getApplicationContext();
        new Thread(() -> {
            try {
                snap.applyFully(appCtx);
                if (MirrorController.isMirroring()) {
                    mirror.restartDaemonNow();
                }
                appendLog("已套用 " + targetPkg + " 的截取尺寸 "
                        + snap.srcW + "×" + snap.srcH
                        + " @(" + snap.srcX + "," + snap.srcY + ")");
            } catch (Throwable ex) {
                DashLog.w(TAG, "套用 App 截取尺寸失败: " + ex);
            }
        }, "apprect-apply").start();
    }

    /** 刷新只读文案 + 比例提示 + 重绘预览（拖动和载入时都调） */
    private void updateAppCropTexts() {
        if (appCropHint2 != null) {
            appCropHint2.setText("截取 X=" + maskCfg.srcX + "  Y=" + maskCfg.srcY
                    + "  W=" + maskCfg.srcW + "  H=" + maskCfg.srcH);
        }
        if (appCropPreview != null) appCropPreview.invalidate();
        updateAppCropRatio();
    }


    /** 刷新比例提示（对比仪表比例） */
    private void updateAppCropRatio() {
        if (appCropRatio == null) return;
        int w = maskCfg.srcW > 0 ? maskCfg.srcW : 1;
        int h = maskCfg.srcH > 0 ? maskCfg.srcH : 1;
        double r = (double) w / h;

        /*
         * 按编码档给出不同的判据（2026-11-03）。
         *
         * H.264：必须等于**遮罩**比例（dcapd2 会铺满遮罩，不一致就变形）
         *         —— 拖框时已自动锁定，这里显示"已锁定"给用户信心
         * BGRA ：不要求等于遮罩比例，只要不极端就行（有 alpha 可留边）
         *         这里改成和**仪表**比例对照，仅供参考
         */
        boolean h264 = MirrorController.getCodec() == MirrorController.CODEC_H264;

        if (h264) {
            /*
             * 用**画布坐标**的遮罩宽高算比例（2026-11-03 修正）。
             *
             * maskCfg.w/h 就是画布系（1920×532）里的遮罩矩形，
             * 也就是 CP 的 destination region —— dcapd2 会把这个比例的内容
             * 铺满这块，所以比例必须和它一致。
             *
             * 之前看不出问题是因为 "遮罩" 和 "仪表" 两个数容易混：
             *   遮罩 592×432 = 1.37     ← 用户设的那块
             *   仪表 1920×532 = 3.61    ← 整块屏
             * 如果误用后者就会给出误导性的"已匹配"提示。
             */
            double boxAspect = (maskCfg.h > 0)
                    ? (double) maskCfg.w / maskCfg.h
                    : (double) MirrorController.getOutputW() / MirrorController.getOutputH();
            boolean ok = Math.abs(r - boxAspect) / boxAspect < 0.02;
            appCropRatio.setText(String.format(
                    "截取 %.2f : 1  %s  遮罩 %d×%d (%.2f : 1)  H.264 已锁定",
                    r, ok ? "✓" : "⚠", maskCfg.w, maskCfg.h, boxAspect));
            appCropRatio.setTextColor(getColor(ok
                    ? R.color.content_secondary : R.color.state_warn));
        } else {
            double dash = (double) MirrorController.getOutputW() / MirrorController.getOutputH();
            appCropRatio.setText(String.format(
                    "比例 %.2f : 1  仪表 %.2f : 1  （BGRA 可自由框选，留黑边不变形）",
                    r, dash));
            appCropRatio.setTextColor(getColor(R.color.content_secondary));
        }
    }

    /** 跟随窗口 / 手动 切换 */
    /**
     * 跟随窗口 / 手动 切换（用户主动点击时用）。
     * 跟随模式会读目标 App 窗口 —— 这是用户明确要求的动作，
     * 允许触发（内部在后台线程跑）。
     */
    private void setAppCropMode(boolean follow) {
        setAppCropModeVisual(follow);
        if (follow) readTargetWindow();
    }

    /** 只改按钮外观与模式标志，不触发任何 adb 动作（初始化时用） */
    private void setAppCropModeVisual(boolean follow) {
        appCropFollowMode = follow;
        if (appCropFollow != null) {
        styleToggle(appCropFollow, follow);
        }
        if (appCropManual != null) {
        styleToggle(appCropManual, !follow);
        }
    }

    /** 适配方式：0=等比居中 1=裁满铺（影响 fit 字段，进而在 recompute_dst 生效） */
    private void setAppCropFit(int mode) {
        maskCfg.fit = mode;
        if (appCropFitCenter != null) {
        styleToggle(appCropFitCenter, mode == 0);
        }
        if (appCropFitFill != null) {
        styleToggle(appCropFitFill, mode == 1);
        }
        appendLog("App 适配方式 → " + (mode == 0 ? "等比居中" : "裁满铺"));
    }

    /** 读目标 App 的窗口矩形，填进四个滑块 */
    private void readTargetWindow() {
        if (targetPkg == null) {
            setStatus("状态：请先「选择投屏 App」");
            return;
        }
        setStatus("状态：正在读取 " + (targetLabel == null ? targetPkg : targetLabel) + " 的窗口…");
        new Thread(() -> {
            int[] r = null;
            try {
                r = mirror.readWindowRectPublic(targetPkg);
            } catch (Throwable t) {
                DashLog.w(TAG, "读窗口失败: " + t);
            }
            final int[] rr = r;
            mainHandler.post(() -> {
                if (rr == null || rr[2] <= 0 || rr[3] <= 0) {
                    setStatus("状态：读不到窗口（App 可能没在前台）");
                    return;
                }
                appCropPreview.setTargetWindow(rr);
                maskCfg.srcX = rr[0];
                maskCfg.srcY = rr[1];
                maskCfg.srcW = rr[2];
                maskCfg.srcH = rr[3];
                maskCfg.sanitizeCrop(mirror.getScreenPxSafe()[0], mirror.getScreenPxSafe()[1]);
                setAppCropMode(false);
                updateAppCropTexts();
                appendLog("读取窗口 → " + rr[2] + "×" + rr[3] + " @(" + rr[0] + "," + rr[1] + ")");
                setStatus("状态：已读取窗口 " + rr[2] + "×" + rr[3]);
            });
        }, "read-window").start();
    }

    /**
     * 是否启用「App 页面截取」。
     *
     * 关（默认）= 不裁，抓整个窗口 → 铺满遮罩。
     * 开 = 按 srcX/Y/W/H 只抓框选的那块。
     *
     * 这个开关最终影响 MirrorController.applyMapCrop 里的判定 ——
     * 那边会读同一个 prefs key，避免两处状态不一致。
     */
    static boolean isAppCropEnabled(android.content.Context c) {
        return c.getSharedPreferences(PREF, MODE_PRIVATE)
                .getBoolean("app_crop_enabled", false);
    }

    /** 把截取区恢复成「整屏」= 不裁（等同于关闭截取时的行为） */
    private void resetAppCropToFull() {
        int[] scr = mirror.getScreenPxSafe();
        maskCfg.srcX = 0;
        maskCfg.srcY = 0;
        maskCfg.srcW = scr[0] > 0 ? scr[0] : 2560;
        maskCfg.srcH = scr[1] > 0 ? scr[1] : 1600;
        maskCfg.sanitizeCrop(scr[0], scr[1]);
        updateAppCropTexts();
        appendLog("截取区 → 铺满整个屏幕 " + maskCfg.srcW + "×" + maskCfg.srcH);
    }

    /** 截取开关变化后，刷新正文区的可用性（关了就把拖框置灰） */
    private void updateAppCropEnableState() {
        boolean on = appCropEnableBox != null && appCropEnableBox.isChecked();
        float alpha = on ? 1f : 0.4f;
        if (appCropPreview != null) {
            appCropPreview.setEnabled(on);
            appCropPreview.setAlpha(alpha);
        }
        /* 关的时候顺手把正文折叠起来，界面更干净 */
        if (!on) {
            setStatus("状态：页面截取已关闭（投屏将铺满遮罩）");
        }
    }

    /**
     * 【方案 C】截当前屏幕当预览底图。
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么不能直接 `screencap -p | base64 -w0`
     * ══════════════════════════════════════════════════════════════
     * 2560×1600 的 PNG 约 2~4 MB，base64 后 3~6 MB 的字符串。
     * AdbShell.exec 是给短命令设计的（一次性读完、有 soTimeout），
     * 拿它读几兆的字符串有三个坑：
     *   · 超时（socket soTimeout 15s，大文件传输可能不够）
     *   · ByteArrayOutputStream 反复扩容，内存峰值到 25 MB+
     *   · busybox 的 base64 不一定支持 -w0（不同版本行为不一致）
     *
     * 改成**分块读取**：先把 PNG 落到 /sdcard，再按 64KB 一块
     * `dd | base64` 拉回来，每块只有几十 KB，稳。
     *
     * 同时**降采样**：预览区只有 420dp 高，不需要原分辨率。
     * inSampleSize=2 → 1280×800，位图从 16 MB 降到 4 MB。
     */
    private void refreshBackgroundShot() {
        /*
         * ══════════════════════════════════════════════════════════════
         * **先把目标 App 拉到前台再截屏**（2026-11-03 修复）
         * ══════════════════════════════════════════════════════════════
         *
         * 原来直接 `screencap` —— 但用户点这个按钮时，
         * **前台是 dashcast 自己**（不然他看不到按钮）。
         * 于是截回来的是 dashcast 的界面，不是目标 App 的画面。
         * 用户反馈"刷新底图不是对应投屏 App 的内容"就是这个。
         *
         * 正确顺序：
         *   ① 记下自己是不是前台
         *   ② am start 把目标 App 拉到前台（display 0）
         *   ③ 等 1.5 秒让它渲染完
         *   ④ screencap
         *   ⑤ 再把 dashcast 拉回前台（否则用户回不到设置界面）
         *
         * 注意：如果用户没选目标 App，就跳过拉起，直接截当前屏幕。
         */
        if (targetPkg == null) {
            setStatus("状态：请先「选择投屏 App」再刷新底图");
            return;
        }
        setStatus("状态：正在切到目标 App 并截屏…");
        final String pkg = targetPkg;
        final String cls = targetCls;
        new Thread(() -> {
            android.graphics.Bitmap bmp = null;
            String tmp = "/sdcard/dc_shot.png";
            boolean pulled = false;
            try {
                /* ① 把目标 App 拉到前台，**并确认真的到位了**再截 */
                String comp = (cls != null && !cls.isEmpty()) ? (pkg + "/" + cls) : pkg;
                boolean fgOk = false;
                for (int attempt = 0; attempt < 3 && !fgOk; attempt++) {
                    try {
                        AdbShell.exec("am start --display 0 -n " + comp, 6000);
                        pulled = true;
                        /*
                         * 光 am start 不够：车机负载高时首帧要一两秒，
                         * 有时 Activity 起得来但窗口还没画完 ——
                         * 那时截到的还是旧界面（用户看到"底图不是目标 App"）。
                         * 所以**回读前台包名**确认，没到位就再等再试。
                         */
                        for (int wait = 0; wait < 6; wait++) {
                            Thread.sleep(500);
                            String fg = mirror.foregroundPkgPublic();
                            if (pkg.equals(fg)) {
                                fgOk = true;
                                /* 前台对了再多等一拍，等它把内容画出来 */
                                Thread.sleep(700);
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        DashLog.w(TAG, "拉目标 App 前台失败（第 " + (attempt + 1) + " 次）: " + t);
                    }
                }
                if (!fgOk) {
                    DashLog.w(TAG, "没能把 " + pkg + " 拉到前台，底图可能不是它的画面");
                }

                /*
                 * ══════════════════════════════════════════════════════════════
                 * **截屏要重试**（2026-11-05 修复"截图大小异常: 0"）
                 * ══════════════════════════════════════════════════════════════
                 *
                 * 日志实录：
                 *   12:49:05  已载入底图 1280×800（抖音短视频）      ← 成功
                 *   12:49:06  截屏失败: 截图大小异常: 0            ← 紧接着失败
                 *
                 * 原因：screencap -p 偶尔写出 0 字节 —— 车机 GPU 忙
                 *      或者 SurfaceFlinger 正在重排时就是会这样。
                 *      一次失败就放弃太脆，重试 3 次基本都能成。
                 */
                long size = 0;
                for (int shot = 1; shot <= 3; shot++) {
                    /* ② 截屏落盘 */
                    AdbShell.exec("screencap -p " + tmp, 20000);

                    /* ③ 问大小 */
                    String sz = AdbShell.exec("stat -c %s " + tmp, 4000);
                    size = 0;
                    if (sz != null) {
                        try {
                            size = Long.parseLong(sz.trim().replaceAll("[^0-9]", ""));
                        } catch (Throwable ignored) {
                        }
                    }
                    if (size <= 0) {
                        String ls = AdbShell.exec("ls -l " + tmp, 4000);
                        if (ls != null) {
                            String[] parts = ls.trim().split("\\s+");
                            if (parts.length >= 5) {
                                try {
                                    size = Long.parseLong(parts[4]);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                    if (size > 0 && size <= 12L * 1024 * 1024) break;
                    DashLog.w(TAG, "截屏第 " + shot + " 次得到 " + size
                            + " 字节，重试");
                    Thread.sleep(600);
                }
                if (size <= 0 || size > 12L * 1024 * 1024) {
                    throw new java.io.IOException("截图大小异常: " + size + "（已重试 3 次）");
                }

                /* ④ 分块拉取（每块 64KB） */
                final int CHUNK_BYTES = 65536;
                int chunks = (int) ((size + CHUNK_BYTES - 1) / CHUNK_BYTES);
                java.io.ByteArrayOutputStream all =
                        new java.io.ByteArrayOutputStream((int) size);
                for (int i = 0; i < chunks; i++) {
                    String b64 = AdbShell.exec(
                            "dd if=" + tmp + " bs=" + CHUNK_BYTES
                                    + " skip=" + i + " count=1 2>/dev/null | base64",
                            8000);
                    if (b64 == null) continue;
                    b64 = b64.replaceAll("\\s", "");
                    if (b64.isEmpty()) continue;
                    try {
                        byte[] dec = android.util.Base64.decode(
                                b64, android.util.Base64.DEFAULT);
                        all.write(dec, 0, dec.length);
                    } catch (Throwable ignored) {
                    }
                }

                /* ⑤ 解码（降采样一半） */
                byte[] png = all.toByteArray();
                android.graphics.BitmapFactory.Options opt =
                        new android.graphics.BitmapFactory.Options();
                opt.inSampleSize = 2;
                bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length, opt);
                all.close();

                try {
                    AdbShell.exec("rm -f " + tmp, 3000);
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                DashLog.w(TAG, "截屏失败: " + t);
                try {
                    AdbShell.exec("rm -f " + tmp, 3000);
                } catch (Throwable ignored) {
                }
            } finally {
                /*
                 * ⑥ **无论如何都把 dashcast 拉回前台**。
                 * 否则用户截完图就停在目标 App 里，回不到设置界面 ——
                 * 那比"底图不对"更让人困惑。
                 */
                if (pulled) {
                    try {
                        AdbShell.exec("am start -n com.hermes.dashcast/.MainActivity", 6000);
                    } catch (Throwable ignored) {
                    }
                }
            }

            final android.graphics.Bitmap fb = bmp;
            mainHandler.post(() -> {
                if (appCropPreview == null) return;
                if (fb == null) {
                    setStatus("状态：截屏失败（可继续用网格框选）");
                    appendLog("截屏失败");
                    return;
                }
                appCropPreview.setBackgroundShot(fb);
                setStatus("状态：已载入底图 " + fb.getWidth() + "×" + fb.getHeight()
                        + "（" + (targetLabel == null ? targetPkg : targetLabel) + "）");
                appendLog("底图已更新 " + fb.getWidth() + "×" + fb.getHeight()
                        + " · " + (targetLabel == null ? targetPkg : targetLabel));
            });
        }, "shot-bg").start();
    }

    /** 应用截取区并开始投屏（会先按 App 存历史值） */
    private void applyAppCropAndCast() {
        if (targetPkg == null) {
            setStatus("状态：请先「选择投屏 App」");
            return;
        }
        maskCfg.saveAppRect(this, targetPkg);   /* 按 App 记住 */
        /* 画布先对齐仪表真实分辨率（"铺满网格却铺不满仪表"的根因在这里） */
        syncCanvasToOutput();

        /*
         * **「应用并投屏」必须顺手把总开关打开**（2026-11-03 第 3 个根因）。
         *
         * 「启用页面截取」默认关闭，而关着时 applyMapCrop **完全不裁**，
         * 抓整个窗口铺满遮罩 —— 用户框好了、点了「应用并投屏」，
         * 画面却还是整窗口，看着就是"截取尺寸无法生效"。
         *
         * 用户点这个按钮的意图很明确："按我框的那块投"，所以这里替他打开，
         * 并把勾选框的 UI 同步过来（否则界面显示未勾选、实际已生效，更困惑）。
         */
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean("app_crop_enabled", true).apply();
        if (appCropEnableBox != null && !appCropEnableBox.isChecked()) {
            appCropEnableBox.setChecked(true);   /* 触发器里会再下发一次，无妨 */
        }
        updateAppCropEnableState();

        /*
         * **必须置 enabled=true**（2026-11-03 排查出的第 9 个 bug）。
         *
         * applyFully() 里同步 CP 图层矩形的条件是：
         *     if (cp != null && sCasting && enabled && w > 0 && h > 0)
         *                                         ^^^^^^^
         * enabled 只在「保存并应用」（saveMask）里被设 true，
         * 而「应用并投屏」这条路从来没设 —— 于是：
         *   · dcapd2.layer 写了（dcapd2 按新参数抓帧）
         *   · 但 CP 的 layer 99 destination **没变**
         *   · 结果图层还是旧尺寸，画面被拉伸/裁掉
         * 用户看到的就是"截取区不生效"。
         */
        maskCfg.enabled = true;

        /*
         * **落盘前强制同步一次遮罩比例**。
         *
         * 拖拽时虽然每帧都在同步（LayerPreviewView.syncMaskAspectToCrop），
         * 但「读取窗口」这条路是直接改 srcX/Y/W/H 再刷新的 ——
         * 如果那条路径没同步，这里落盘的遮罩比例就和截取区不一致，
         * dcapd2 会把画面拉伸变形（实测 24% 横压）。
         * 多调一次成本几乎为零，但能彻底堵死这个漏洞。
         */
        if (appCropPreview != null) appCropPreview.syncMaskAspectNow();

        setStatus("状态：正在应用截取区并投屏…");
        appendLog("应用截取区 " + maskCfg.srcW + "×" + maskCfg.srcH
                + " @(" + maskCfg.srcX + "," + maskCfg.srcY + ")");

        final LayerConfig snap = maskCfg;
        final android.content.Context appCtx = getApplicationContext();
        final boolean casting = MirrorController.isMirroring();
        new Thread(() -> {
            try {
                /* ① 写配置文件（含 src_app_pkg / src_x / src_y / src_w / src_h）
                   ② 写 prefs（launchDaemon 从 prefs 读 src_*） */
                snap.applyFully(appCtx);

                /*
                 * ③ 分两种情况：
                 *
                 *  A. 正在投屏 → **必须重拉 dcapd2**。
                 *     loop() 拉起它的条件是 `fgIsTarget && !daemonAlive()`，
                 *     进程活着就不会重起 —— 参数永远是旧的，
                 *     用户看到"截取页面不生效"（实测就是这个根因）。
                 *
                 *  B. 没在投屏 → 交给 startCast() 起全新进程，参数自然是新的。
                 */
                if (casting) {
                    /*
                     * 重拉之后 CP 是新会话（layer 99 被重建），
                     * destination region 也跟着消失了 —— 必须立刻补一次，
                     * 否则仪表上图层回到默认位置/尺寸，
                     * 表现又是"截取区不生效"（只是换了个原因）。
                     */
                    boolean restarted = mirror.restartDaemonNow();
                    if (restarted) {
                        LayerConfig.setCasting(true);
                        int[] dest = LayerGeometry.destinationFor(
                                MirrorController.getCodec(),
                                new int[]{snap.x, snap.y, snap.w, snap.h},
                                MirrorController.getOutputW(),
                                MirrorController.getOutputH());
                        cpManager.setLayerRect(dest[0], dest[1], dest[2], dest[3]);
                        /* 新进程没有旧像素，请求一次整屏全量 */
                        AdbShell.exec("touch /data/local/tmp/dc_force", 2000);
                        DashLog.i(TAG, "截取区已生效并重建图层：" + dest[0] + ","
                                + dest[1] + " " + dest[2] + "x" + dest[3]);
                    } else {
                        DashLog.w(TAG, "重拉 dcapd2 失败（截取区可能仍按旧参数）");
                    }
                }
            } catch (Throwable t) {
                DashLog.w(TAG, "写截取配置失败: " + t);
            }
            mainHandler.post(() -> {
                if (!casting) {
                    startCast();   /* 没在投 → 起一场新投屏 */
                } else {
                    setStatus("状态：截取区已应用（" + maskCfg.srcW + "×"
                            + maskCfg.srcH + " @(" + maskCfg.srcX + ","
                            + maskCfg.srcY + ")）");
                    setDotColor(R.color.state_ok);
                    appendLog("截取区已生效");
                }
            });
        }, "app-crop-apply").start();
    }

    /* ══════════════════════════ 通用控件 ══════════════════════════ */

    private LinearLayout chipButton(String text, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Squircle.of(this).secondaryButton());
        b.setClickable(true);
        b.setFocusable(true);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(getColor(R.color.accent_soft));
        tv.setGravity(Gravity.CENTER);
        /*
         * **强制单行 + 不换行 + 缩放字宽**（2026-11-04）。
         *
         * 按钮宽度由 rowLp 按权重均分（右栏四个按钮各约 200dp）。
         * 「矩形(固定)」「铺满(固定)」这类 7-8 个字符的文案，
         * 在 16sp 下会顶到换行 —— 一换行按钮高度撑破、整行错位。
         *
         * 这里锁死单行，宽度不够时自动缩小字距而不换行。
         */
        tv.setSingleLine(true);
        tv.setMaxLines(1);
        tv.setEllipsize(null);
        tv.setHorizontallyScrolling(false);
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
        label.setTextSize(16);
        label.setTextColor(getColor(R.color.content_secondary));
        head.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setTextSize(16);
        val.setTextColor(getColor(R.color.content_primary));
        val.setGravity(Gravity.END);
        head.addView(val);
        /*
         * 滑块换成 MiUIX 样式（2026-11-04）。
         *
         * 系统 SeekBar 是「4dp 细线 + 小圆点」，和 MiUIX 的
         * 「28dp 粗轨道 + 0.72× 圆点」观感差很远。
         * 而且 tint 只能改颜色，改不了粗细和圆点比例，
         * 所以直接用自绘的 MiuixSeekBar。
         *
         * 颜色取自 MiUIX sliderColors()：
         *   已选段 = primary (#277AF7)
         *   未选段 = sliderBackground (26% 白)
         *   圆点   = primary
         */
        box.addView(head);

        MiuixSeekBar sb = new MiuixSeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, cur - min)));
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
        /*
         * 显示遮罩尺寸时标注当前编码档下的**实际下发尺寸**。
         *
         * 因为两种档位对尺寸的处理不同（2026-11-03 方案 B）：
         *   · BGRA  → 用户原始尺寸，1:1 零损失
         *   · H.264 → 对齐到 16 倍数（硬编要求），会矮/窄几个像素
         *
         * 用户拖滑块时看到的数字是「自己设的值」，但实际生效的可能是
         * 对齐后的值 —— 不标出来会让人困惑"为什么画面比设的小一点"。
         */
        int codec = MirrorController.getCodec();
        int[] rr = maskCfg.rectForCodec(codec);
        boolean aligned = (rr[2] != maskCfg.w) || (rr[3] != maskCfg.h);

        String txt = LayerPreviewView.geometryText(maskCfg)
                + "  ·  " + LayerConfig.SHAPE_NAMES[maskCfg.shape];

        if (codec == 2) {
            txt += aligned
                    ? "  ·  H.264 实际 " + rr[2] + "×" + rr[3] + "（16对齐）"
                    : "  ·  ✓16对齐";
        }
        maskInfo.setText(txt);
    }

    private void syncMaskControls() {
        if (maskSbGrid == null) return;
        maskSbGrid.setProgress(maskCfg.grid - 4);
        maskSbBorder.setProgress(maskCfg.border);
        maskSbTl.setProgress(maskCfg.rTl);
        maskSbTr.setProgress(maskCfg.rTr);
        maskSbBl.setProgress(maskCfg.rBl);
        maskSbBr.setProgress(maskCfg.rBr);
        maskSbTrapTop.setProgress(maskCfg.trapTop);
        maskSbTrapBot.setProgress(maskCfg.trapBot);
        maskSbFeather.setProgress(maskCfg.feather);
        maskTvGrid.setText(maskCfg.grid + "px");
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
            tv.setTextSize(16);
            tv.setTextColor(getColor(saved ? R.color.accent_soft
                                          : R.color.content_tertiary));
            btn.setBackgroundResource(saved
                    ? R.drawable.chip_bg : R.drawable.btn_secondary);
            btn.setAlpha(saved ? 1f : 0.75f);
        }
    }

    private void saveMask() {
        /* 落盘前把画布对齐到仪表真实分辨率，否则"铺满"只是铺满了一块错的画布 */
        syncCanvasToOutput();
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
                            MirrorController.getOutputW(), MirrorController.getOutputH());
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

    /**
     * 分组小标题。
     *
     * ══════════════════════════════════════════════════════════════
     * 排版调整（2026-11-04 美化）
     * ══════════════════════════════════════════════════════════════
     * · 字号 15 → 14sp：和按钮 15sp 拉开差距，形成"标题小而正、按钮大而重"
     *   的层次。原来标题和按钮同大小，靠颜色区分，视觉噪音大。
     * · letterSpacing 0.08 → 0.06：中文不像西文那样需要大字距，
     *   0.08 会把"遮罩参数"拉得很散。
     * · 上间距 16 → 12dp、下间距 7 → 5dp：整体紧凑，少滚动一屏。
     * · 左侧加一条 3dp 强调色竖线（用 CompoundDrawable 模拟）——
     *   比纯色文字更有"分组感"，也不用额外嵌套布局。
     */
    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(getColor(R.color.content_accent));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(4), dp(12), 0, dp(4));
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
    /**
     * 把旧的 XML 背景资源映射成对应的 squircle（2026-11-04）。
     *
     * iconButton 的签名带 bgRes 参数，19 处调用传了 4 种资源。
     * 与其改 19 处调用，不如在这里做一次映射 —— 调用方一行都不用动。
     *
     * 注意 btn_selected 保持 XML 版本：它是胶囊（999dp），
     * 用户明确要求保留胶囊样式，不该被 squircle 覆盖。
     */
    private android.graphics.drawable.Drawable squircleFor(int bgRes) {
        Squircle sq = Squircle.of(this);
        if (bgRes == R.drawable.btn_primary)   return sq.primaryButton();
        if (bgRes == R.drawable.btn_secondary) return sq.secondaryButton();
        if (bgRes == R.drawable.btn_danger)    return sq.dangerButton();
        /* btn_selected 等其它（胶囊）保持原样 */
        return getDrawable(bgRes);
    }

    /*
     * **图标按钮：优先 vector drawable**（2026-11-07）。
     *
     * 原来传 "↓" "■" "▶" 这类字符，靠字体渲染：字形宽度/基线不一致、
     * 缺字时字体回退可能变豆腐块、颜色只能整体 0.85 压暗。
     * vector 是纯路径，任何 DPI 都清晰；颜色用 setImageTintList 精确控制。
     */
    private LinearLayout iconButton(String text, int iconRes, int bgRes, int colorRes,
                                    View.OnClickListener l) {
        return makeIconButton(text, iconRes, null, bgRes, colorRes, l);
    }

    private LinearLayout makeIconButton(String text, int iconRes, String glyph,
                                        int bgRes, int colorRes, View.OnClickListener l) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        /*
         * 背景从 XML drawable 换成 squircle（2026-11-04）。
         *
         * iconButton 的 bgRes 参数有 4 种取值，按它选对应的 squircle：
         *   btn_primary   → primaryButton()   （蓝实底，16dp squircle）
         *   btn_secondary → secondaryButton() （抬升底+描边，16dp）
         *   btn_selected  → 保持 btn_selected（胶囊 999dp，用户要求保留）
         *   btn_danger    → dangerButton()    （透明底+红描边，16dp）
         */
        btn.setBackground(squircleFor(bgRes));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setPadding(dp(12), 0, dp(12), 0);

        /* 图标：有 iconRes 用 ImageView+vector（精确着色），否则退回字符字形 */
        if (iconRes != 0) {
            ImageView ic = new ImageView(this);
            ic.setImageResource(iconRes);
            ic.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int sz = dp(18);
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(sz, sz);
            icLp.setMargins(0, 0, dp(8), 0);
            ic.setLayoutParams(icLp);
            ic.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(colorRes)));
            btn.addView(ic);
        } else {
            TextView ic = new TextView(this);
            ic.setText(glyph);
            ic.setTextSize(16);
            ic.setTextColor(getColor(colorRes));
            ic.setAlpha(0.85f);
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            icLp.setMargins(0, 0, dp(8), 0);
            ic.setLayoutParams(icLp);
            btn.addView(ic);
        }
        TextView tx = new TextView(this);
        tx.setText(text);
        /*
         * 文字 19 → 15sp（2026-11-04 美化）。
         *
         * 19sp 在左栏（700dp 宽）的单行按钮上没问题，但这个 iconButton
         * 也用在右栏两张并排的按钮上（每张只有 340dp）——
         * 「刷新截图（当底图）」这种 9 字标签在 19sp 下会被截断成
         * 「刷新截图（当底...」。15sp 能完整放下 9 个字。
         */
        tx.setTextSize(16);
        tx.setTextColor(getColor(colorRes));
        tx.setTypeface(Typeface.DEFAULT_BOLD);
        tx.setGravity(Gravity.CENTER);
        btn.addView(tx);

        btn.setOnClickListener(l);
        actionButtons.add(btn);
        /* 默认占满一行；放进 row 时由 rowLp 覆盖 */
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));
        LinearLayout.LayoutParams selfLp = (LinearLayout.LayoutParams) btn.getLayoutParams();
        selfLp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return btn;
    }

    /** 旧重载：仍接受字符字形（过渡用） */
    private LinearLayout iconButton(String text, String glyph, int bgRes, int colorRes,
                                    View.OnClickListener l) {
        return makeIconButton(text, 0, glyph, bgRes, colorRes, l);
    }

    /** 状态卡里的单格指标：上面大数字，下面小标签，各占 1/3 */

    /**
     * 固定宽度的指标格（删掉「图块」后只剩两格，不能再用 1/3 权重均分）。
     *
     * 原来三格用 weight=1 均分 —— 那是为"三个指标铺满一行"设计的。
     * 现在只有 fps 和上行两个，若继续均分，每格占一半宽度，
     * 数字会离得老远、中间空一大片，看着像两组无关的数据。
     *
     * 改成每格固定 200dp，整体左对齐 —— 两个数字紧凑挨在一起，
     * 右侧留白，视觉上是一组。
     */
    private TextView statCellFixed(LinearLayout parent, String label, String initVal) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        /* 高度 56 → 76dp，跟上外层的 84dp（原 56 会裁掉标签） */
        cell.setLayoutParams(new LinearLayout.LayoutParams(dp(200),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView v = new TextView(this);
        v.setText(initVal);
        v.setTextSize(28);   /* 36 → 28：状态卡格子只 200dp 宽，36 会撑满 */
        v.setTextColor(getColor(R.color.accent_soft));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setIncludeFontPadding(false);
        cell.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(15);
        lb.setTextColor(getColor(R.color.content_tertiary));
        lb.setGravity(Gravity.CENTER);
        lb.setIncludeFontPadding(false);
        lb.setPadding(0, dp(4), 0, 0);
        cell.addView(lb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        parent.addView(cell);
        return v;
    }

    private Button makeButton(String text, int bgRes, int colorRes,
                              View.OnClickListener listener, float weight) {
        Button b = makeButton(text, bgRes, colorRes, listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), weight);
        lp.setMargins(dp(4), dp(8), dp(4), dp(8));
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
                dp(56));
        lp.setMargins(0, dp(8), 0, dp(8));
        b.setLayoutParams(lp);
        actionButtons.add(b);
        return b;
    }

    /** 切换编码档（二选一）。已在目标档位就什么都不做，避免无谓重启 daemon。 */
    private void setCodecMode(int want) {
        /*
         * ★ Java 渲染模式只支持 BGRA+LZ4（2026-11-11 加）
         *
         * dcapd2 当前构建里 H.264 编码器是桩实现
         * （h264enc.c 依赖 NDK media 头，交叉编译链没有，
         *  改用 h264stub.c 提供空函数）。
         *
         * 一旦切到 H.264：
         *   dcapd2 打 "H.264 编码器启动失败，回退到 BGRA 档"
         *   → tiles=0、totalKB=0 → **一帧都发不出去**
         *   而 App 侧日志还显示 29fps 在发，极具迷惑性。
         *
         * 实测踩过一次，排查半小时。这里直接挡住。
         */
        if (want == MirrorController.CODEC_H264) {
            setStatus("状态：当前构建仅支持 BGRA+LZ4（H.264 编码器未编进来）");
            refreshCodecButtons();
            return;
        }
        int cur = MirrorController.getCodec();
        if (cur == want) return;
        mirror.setCodec(want);
        refreshCodecButtons();
        refreshCodecLabels();

        setStatus("状态：编码档位已切换 · "
                + (want == MirrorController.CODEC_H264 ? "H.264" : "BGRA+LZ4"));

        /*
         * **切档后必须立刻重新下发配置**（2026-11-03 修复）。
         *
         * 两种档位对「图层矩形」的要求完全相反：
         *   BGRA  → destination = 整屏 (0,0,1920,532)，画布遮罩外靠 alpha 透明
         *   H.264 → destination = 遮罩矩形 (x,y,w,h)，图层本身缩进那块
         *
         * 原来切档后只写了 dc_codec 让 dcapd2 自行换编码器，
         * **没有重写 dcapd2.layer、也没有重设 CP 图层** ——
         * CP 还留着上一个档位的 destination：
         *   BGRA → H.264 时，图层仍是"整屏"，
         *   H.264 无 alpha，就把原厂仪表整块盖住了。
         *
         * 用户必须手动点一次「保存并应用」才会走 applyFully()
         * 把 destinationFor(新档位) 下发下去 —— 正是反馈的现象。
         *
         * 这里直接补上：切完档就 applyFully()，一步到位。
         * 顺便把 maskCfg 按新档位重新对齐（H.264 要 16 对齐）。
         */
        final LayerConfig snap = maskCfg;
        final android.content.Context appCtx = getApplicationContext();
        final int newCodec = want;
        new Thread(() -> {
            try {
                /*
                 * **先等 dcapd2 真正切完编码档，再下发图层矩形**（2026-11-03）。
                 *
                 * 原来 setCodec 只写了个数字文件，dcapd2 500ms 后才轮询到；
                 * 这边立刻按新档位算 destination 下发，两条路赛跑 ——
                 * CP 已把图层缩到遮罩矩形，dcapd2 还在吐整块画布，
                 * 结果位置和大小全错。用户看到"切 H.264 后不按遮罩位置投屏，
                 * 要点一次保存并应用才刷新"。
                 *
                 * 现在等切换落地再下发，一次就对。
                 */
                MirrorController.awaitCodecSwitched(3000);
                snap.applyFully(appCtx);
                DashLog.i(TAG, "编码档切换后已重新下发配置（codec=" + newCodec + "）");
            } catch (Throwable ex) {
                DashLog.w(TAG, "切档后下发失败: " + ex);
            }
        }, "codec-reapply").start();

        /* 遮罩容量提示、形状可用性也跟着档位变 */
        refreshMaskInfo();
        applyAlphaDependentAvailability(want == MirrorController.CODEC_H264);
    }

    /** 刷新二选一按钮的选中态（选中的用青绿实底 + 深字） */
    /** 刷新「形状」「适配」按钮文案。原来这段在 6 处复制，统一抽到这里（2026-11-06）。 */
    private void refreshCodecLabels() {
        if (maskBtnShape != null) {
            ((TextView) maskBtnShape.getChildAt(0)).setText(shapeLabel());
        }
        if (maskBtnFit != null) {
            ((TextView) maskBtnFit.getChildAt(0)).setText(fitLabel());
        }
    }
    private void refreshCodecButtons() {
        mainHandler.post(() -> {
            boolean h264 = MirrorController.getCodec() == MirrorController.CODEC_H264;
            applyCodecStyle(btnCodecBgra, !h264);
            applyCodecStyle(btnCodecH264, h264);
            /* 编码档一变，依赖 alpha 的控件可用性也跟着变 */
            applyAlphaDependentAvailability(h264);
        });
    }

    /**
     * **按编码档启用/禁用「依赖 alpha」的控件**（2026-11-03）。
     *
     * 哪些功能依赖 alpha（读 layer_mask.c 确认）：
     *   · 形状 圆角矩形 / 梯形 / 椭圆（shape=1/2/3）
     *   · 四角圆角 r_tl / r_tr / r_bl / r_br
     *   · 梯形上沿 / 下沿
     *   · 羽化 feather
     *   · 边框 border（a==254 标记）
     *
     * 它们都靠 dc_layer_apply() 写画布的 **alpha 通道**实现，
     * 而 H.264 是 YUV420 —— 编码时 alpha 直接被丢掉，
     * 拖了完全没反应。置灰 + 提示，别让用户白试。
     *
     * 注意：**矩形（shape=0）不需要 alpha**，永远可用。
     * 铺满模式也不需要 alpha，所以 fit 按钮保留。
     */
    private void applyAlphaDependentAvailability(boolean h264) {
        if (maskAlphaWarn == null) return;

        if (h264) {
            /*
             * **措辞：说"不做"，不说"不能"**（2026-11-06）。
             *
             * 原来写「不支持下列功能」—— 读起来像"这个 App 能力不够"，
             * 实际上是**格式限制**：H.264 是 YUV420，本身没有 alpha 通道，
             * 圆角/羽化这类"靠 alpha 挖洞"的效果没有可写入的地方。
             *
             * 换成"这套链路不做"：把限制说成设计选择，而不是能力缺陷。
             * 用户知道"想要这些就切 LZ4"比知道"不支持"有用得多。
             */
            maskAlphaWarn.setText(
                    "H.264 档这套链路不做形状类效果（编码是 YUV420，不带 alpha）：\n"
                            + "    圆角矩形 / 梯形 / 椭圆 / 四角圆角 / 梯形上下 / 羽化 / 边框\n"
                            + "  要用这些，切到「BGRA+LZ4」档即可（带 alpha，全部可用）。\n"
                            + "  矩形遮罩 + 铺满 在 H.264 下照常工作。");
            maskAlphaWarn.setVisibility(View.VISIBLE);
        } else {
            maskAlphaWarn.setText("✓ BGRA+LZ4 档：形状/圆角/羽化/边框 全部可用");
            maskAlphaWarn.setTextColor(getColor(R.color.content_tertiary));
            maskAlphaWarn.setVisibility(View.VISIBLE);
        }
        if (h264) maskAlphaWarn.setTextColor(getColor(R.color.state_warn));

        float alpha = h264 ? 0.42f : 1f;

        /* 形状按钮：H.264 下只能停在「矩形」，直接不让切 */
        if (maskBtnShape != null) {
            maskBtnShape.setEnabled(!h264);
            maskBtnShape.setAlpha(alpha);
        }

        /*
         * 「等比 / 铺满」在 H.264 下也无效 —— dcapd2.c 第 372 行硬编码：
         *     if (g_codec == DASHCAST_CODEC_H264 || (g_mask_ready && !fit)) {
         *         dw = boxW; dh = boxH;      // 永远铺满
         *     }
         * 等比会留黑边，而 H.264 无 alpha 填不了透明，只能铺满。
         * 所以这个按钮在 H.264 档下点了也没反应，一并置灰。
         */
        if (maskBtnFit != null) {
            maskBtnFit.setEnabled(!h264);
            maskBtnFit.setAlpha(alpha);
        }
        /* 圆角 / 梯形 / 羽化 / 边框 滑块 */
        disable(maskSbBorder, alpha, h264);
        disable(maskSbFeather, alpha, h264);
        disable(maskSbTl, alpha, h264);
        disable(maskSbTr, alpha, h264);
        disable(maskSbBl, alpha, h264);
        disable(maskSbBr, alpha, h264);
        disable(maskSbTrapTop, alpha, h264);
        disable(maskSbTrapBot, alpha, h264);

        /*
         * ══════════════════════════════════════════════════════════════
         * H.264 下形状强制回矩形，切回 BGRA 时**还原用户原来的形状**
         * ══════════════════════════════════════════════════════════════
         *
         * H.264 没有 alpha，圆角/梯形/椭圆都无效，所以进 H.264 时
         * 把 shape 压成矩形是对的（否则用户看到"直角黑块"更困惑）。
         *
         * 但原来的写法只压不还 —— 切回 BGRA 后 shape 仍是 0，
         * 而圆角参数 65/65/65/65 还好好地在 prefs 里，白白浪费：
         *   用户反馈"切回 LZ4 圆角不见了，得重新设一遍"（Bug D）。
         *
         * 修法：进 H.264 前把用户形状记到 sShapeBeforeH264，
         *      切回 BGRA 时还原它。用一个字段跨越切换周期。
         */
        if (h264) {
            if (maskCfg.shape != LayerConfig.SHAPE_RECT) {
                sShapeBeforeH264 = maskCfg.shape;   /* 记住用户的形状 */
                maskCfg.shape = LayerConfig.SHAPE_RECT;
                if (maskBtnShape != null) {
                    ((TextView) maskBtnShape.getChildAt(0)).setText(shapeLabel());
                }
                refreshMask();
                DashLog.i(TAG, "H.264：形状压成矩形，已记住原形状=" + sShapeBeforeH264);
            }
        } else {
            /*
             * 切回 BGRA：如果之前被压成矩形、但记着别的形状，就还原。
             * 只在"当前是矩形 + 有记忆 + 记忆非矩形"时才动，
             * 避免把用户手动选的矩形又改回去。
             */
            if (maskCfg.shape == LayerConfig.SHAPE_RECT
                    && sShapeBeforeH264 != LayerConfig.SHAPE_RECT) {
                maskCfg.shape = sShapeBeforeH264;
                sShapeBeforeH264 = LayerConfig.SHAPE_RECT;   /* 用完清掉，只还原一次 */
                if (maskBtnShape != null) {
                    ((TextView) maskBtnShape.getChildAt(0)).setText(shapeLabel());
                }
                refreshMask();
                DashLog.i(TAG, "BGRA：已还原形状=" + maskCfg.shape);
            }
        }
    }

    /** 进 H.264 时被压掉的用户形状（切回 BGRA 时还原，只还原一次） */
    private int sShapeBeforeH264 = LayerConfig.SHAPE_RECT;

    private void disable(android.widget.SeekBar sb, float alpha, boolean off) {
        if (sb == null) return;
        sb.setEnabled(!off);
        sb.setAlpha(alpha);
    }

    /** 编码档按钮的选中态（委托给统一的 styleToggle，保持全界面一致） */
    private void applyCodecStyle(LinearLayout b, boolean selected) {
        styleToggle(b, selected);
    }

    /**
     * **统一的"选中态"样式切换**（2026-11-04）。
     *
     * 背景换胶囊 + **同时改文字颜色** —— 两者必须同步，否则
     * 选中档的淡青字压在蓝底上对比度只有 1.3:1，比未选中还难读。
     *
     * 原来只有 applyCodecStyle 做对了（编码档按钮），
     * 其余 6 处（跟随窗口/手动、等比居中/裁满铺、远景中景近景、
     * 地图模式、地图 HUD）都只换背景不改字色 —— 全是同一个 bug。
     *
     * @param b   按钮容器（chipButton / iconButton 返回的 LinearLayout）
     * @param on  true=选中
     */
    private void styleToggle(LinearLayout b, boolean on) {
        if (b == null) return;
        b.setBackgroundResource(on
                ? R.drawable.btn_selected : R.drawable.btn_secondary);
        /*
         * 选中态文字必须是**白色**（2026-11-04 修复）。
         *
         * 原来用 surface_root —— 那时它还是深色（#17161A），深字压蓝底勉强能看。
         * 现在 surface_root 改成浅灰 #F5F5F5 后，浅灰字压在蓝底上直接糊成一片，
         * 选中项反而看不清。
         *
         * 正确做法：选中态一律白字（accent_on = #FFFFFF），
         * 这是 iOS 蓝底按钮的标准配法，对比度 3.6:1。
         *
         * 「所有文字变黑」那条要求针对的是未选中态和普通文字；
         * 选中态是「白字蓝底」的特殊组合，不属于该规则。
         */
        int fg = getColor(on ? R.color.accent_on : R.color.content_secondary);
        applyTextColorRecursive(b, fg);
    }

    /** 递归给容器内所有 TextView 设字色（兼容图标+文字的复合按钮） */
    private void applyTextColorRecursive(View v, int color) {
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(color);
            return;
        }
        if (!(v instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            applyTextColorRecursive(g.getChildAt(i), color);
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
            DashLog.w(TAG, "操作超时，强制解锁 UI");
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
            DashLog.i(TAG, "操作进行中，忽略重复点击: " + name);
            return;
        }
        enterBusy();
        setStatus(runningMsg);
        new Thread(() -> {
            boolean ok;
            try {
                ok = op.getAsBoolean();
            } catch (Exception e) {
                DashLog.e(TAG, name + " 异常", e);
                ok = false;
            }
            final boolean done = ok;
            setStatus(done ? okMsg : failMsg);
            leaveBusy();
        }, name).start();
    }

    /** [部署服务] 只推送文件，不启动 */
    private void deployService() {
        /*
         * ══════════════════════════════════════════════════════════════
         * **部署前先清空日志**（2026-11-05）
         * ══════════════════════════════════════════════════════════════
         *
         * 用户要求："下次部署把这个路径的 dashcast.log 清空，再把日志写入"。
         *
         * 理由：日志是纯追加（FileWriter 的 append=true），用久了会累积到
         * 2MB 才轮转。每次部署是一个"新会话"的起点 —— 从这里清空，
         * 之后看到的都是本次部署之后的日志，排查问题不会被历史噪音干扰。
         *
         * 清空放在 runOp 之前：保证「推送 dashcastd…」这条
         * 就是新日志的第一行。
         */
        LocalStore.clearLog(this);
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
        /*
         * ★ **停服务必须连带还原仪表**（2026-11-09 修「图层不下来」）
         *
         * 原来只调 stopDashcastd() —— 它只 killall 进程。
         * 而 CP 的 layer 99 是 dashcastd 建的，进程被杀后**图层不会自动消失**：
         * 仪表上就留着一块投屏画面，怎么点都下不来。
         *
         * 日志实证（dashcast(1).log 22:40:24）：
         *   停止并删除文件…
         *   停止结果: DONE（二进制保留，下次启动免重推）
         *   已停止，文件已删除
         *   ← 全程没有出现「还原仪表」那行
         *
         * 而地图投屏那条路径（MapCastService.stopSequence）是有的：
         *   cpm.stopDashcastd();
         *   cpm.restoreDashboard();     ← 这一句普通投屏的停服务漏了
         *   日志：「还原仪表（第 1 次）: 原表含99=false → 设为 [10] 验证=干净」
         *
         * 现在补上，并且**先停进程再还原**（顺序不能反：dashcastd 活着会
         * 自己重建 layer 99，还原完又被它建回去）。
         */
        runOp("cp-stop", "状态：停止并还原仪表…",
                () -> {
                    boolean ok = cpManager.stopDashcastd();
                    /* 无论 stop 成功与否都试一次还原 —— 半死状态下更需要兜住 */
                    cpManager.restoreDashboard();
                    return ok;
                },
                "状态：已停止，仪表已还原", "状态：停止失败");
    }

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
        t.setTextSize(16);
        t.setTextColor(getColor(R.color.accent));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(12), 0, dp(8));
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
        cell.setPadding(dp(8), dp(12), dp(8), dp(8));
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
        name.setTextSize(16);
        name.setTextColor(getColor(R.color.content_primary));
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLp.setMargins(0, dp(8), 0, 0);
        name.setLayoutParams(nameLp);
        cell.addView(name);

        cell.setOnClickListener(v -> {
            targetPkg = e.info.activityInfo.packageName;
            targetCls = e.info.activityInfo.name;
            targetLabel = e.label;
            saveTargetPref();
            /*
             * **切换 App 立刻套用该 App 的历史截取尺寸并落盘**（2026-11-04）。
             *
             * 用户要求："截取某 App 页面保存后，下次启动该 App 要默认
             * 从配置文件读取并按配置截取，不想重新截。"
             *
             * 这里补上：切 App 就套用 + 立刻 applyFully，
             * 让"选 App → 开始投屏"这条最短路径也正确。
             */
            applyAppRectOnSwitch();
            updateTargetHint();
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            if (MirrorController.isMirroring() || running) {
                setStatus("状态：切换投屏目标 → " + e.label + "…");
                /*
                 * **用 startCastByMode() 而不是 startCast()**（2026-11-08）
                 *
                 * 选 App 面板在地图模式下是隐藏的，正常走不到这里；
                 * 但代码路径不排除被其它地方触发（比如以后加了快捷键），
                 * 那时 castMode=1 却调 startCast() 会走普通投屏链路，
                 * 和地图投屏抢 dcapd2 / layer 99 —— 正是这次合并要消除的冲突。
                 */
                startCastByMode();
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
            DashLog.w(TAG, "查询桌面包名失败: " + e);
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


    /** [开始投屏] 建虚拟屏 + 推流到 CP：
     *  ① 启动 dashcastd（CP 接收端）
     *  ② 起推流服务（服务内部自建虚拟屏，无需录屏授权）
     *  ③ 虚拟屏就绪 → 把目标 App 启动/迁移到虚拟屏 → 抓帧推流到 CP
     */
    /**
     * 「地图 HUD」一键投屏 —— 不需要选目标 App，且会**抢占**当前正在跑的
     * App 投屏。
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么单独开一个入口
     * ══════════════════════════════════════════════════════════════
     * HUD 的取景源是固定的：com.ecarx.hud 一直在 Display 1 上跑，
     * dcapd2 抓的就是那块屏。**没有任何"投哪个 App"的概念**。
     * 走普通 startCast() 会被 targetPkg==null 拦下来弹选 App 面板。
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么要先停 App 投屏
     * ══════════════════════════════════════════════════════════════
     * 两条路都写 CP 的 layer 99，同时跑画面会打架。
     * 用户点「地图 HUD」的意图是"我要看 HUD"——那就应该把 App 投屏让出来，
     * 而不是弹个"请先停止投屏"让他自己去点。
     */
    private void startHudCast() {
        if (busy) {
            DashLog.i(TAG, "操作进行中，忽略重复点击: 地图 HUD");
            return;
        }

        /*
         * ① 如果正在投 App，先停干净再开 HUD。
         *
         * 停完后必须**等一下**：stopCastOnly() 内部是异步的
         * （mirror.stop() 会走 SSH/线程 join），立刻启 dcapd2 会撞车 ——
         * 旧进程还没退、CP 的 layer 99 还没清。
         */
        final boolean wasAppCasting = MirrorController.isMirroring() && !mirror.isMapHudActive();
        if (wasAppCasting) {
            appendLog("地图 HUD · 先停止 App 投屏");
        }

        /*
         * ② 开 HUD 会话。
         *
         * **不动 mapMode** —— 「地图模式」和「地图 HUD」是两条独立的路径：
         *   · 地图模式  = 抓主屏上地图 App 的窗口，按遮罩裁剪
         *   · 地图 HUD  = 抓 Display 1 上的 com.ecarx.hud
         * 早先这里顺手把 mapMode 也置 true，导致用户在 HUD 模式下
         * 还能看到视角三档、遮罩也在生效，两个概念被搅在一起。
         */
        MirrorController.setHudSession(true);
        updateMapHudBtn();

        if (wasAppCasting) {
            setStatus("状态：正在切换到地图 HUD…");
            stopCastOnly();
            /* 给停止流程留时间收尾，再启 HUD */
            mainHandler.postDelayed(this::startHudCastInner, 2500);
        } else {
            startHudCastInner();
        }
    }

    /** startHudCast 的第二段：真正起投（抽取出来便于延迟调用） */
    private void startHudCastInner() {
        if (busy) return;

        /*
         * **必须重新确认 HUD 会话还在**。
         *
         * 抢占流程是：停 App 投屏 → 等 2.5 秒 → 再起 HUD。
         * 这 2.5 秒里 stopCastOnly() 的收尾会把会话清掉（它本来就要清，
         * 见"停止投屏把所有的投屏都关掉"那条需求）。
         *
         * 所以这里再置一次 —— 注意只碰 HUD 会话，不碰 mapMode，
         * 两者独立（地图模式属于 App 投屏路径）。
         */
        MirrorController.setHudSession(true);

        appendLog("地图 HUD · 一键开投");


        /* 直接进投屏流程（不校验 targetPkg） */
        startCastInner();
    }

    /*
     * ═══════════════════════════════════════════════════════════════════
     * **投屏模式切换（2026-11-08 合并两套按钮）**
     * ═══════════════════════════════════════════════════════════════════
     *
     * 合并理由：普通投屏和地图投屏底下做的是同一批重活 ——
     *   · 部署 CP 服务（dashcastd）→ 会重启对方会话，画面中断
     *   · 启动 dcapd2 → 同一时间只能有一个，起来就把对方杀了
     *   · 占用 CP layer 99 → 同一个图层两边抢
     *
     * 两套同时点必然互相踩。合并成一个模式选择后，同一时间只有一条链路在跑。
     */

    /** 切换投屏模式并刷新按钮 */
    private void switchCastMode(int mode) {
        if (busy) {
            DashLog.i(TAG, "操作进行中，忽略模式切换");
            return;
        }
        if (castMode == mode) return;

        /* 正在投的时候不允许换模式 —— 换了等于让底下那套重活互相踩
         *
         * 注意用的是静态的 isMirroring()，不是 isRunning()：
         * MirrorController.isRunning() 是实例方法，静态调用编不过。
         * 地图投屏那边 MapCastService.get() 是单例，可以直接调 isRunning()。
         */
        boolean running = MirrorController.isMirroring() || MapCastService.get().isRunning();
        if (running) {
            setStatus("状态：请先「停投屏」再切换模式");
            return;
        }

        castMode = mode;
        refreshModeButtons();
        setStatus("状态：" + (mode == 0 ? "普通投屏" : "地图投屏") + "（未启动）");
        appendLog("投屏模式 · " + (mode == 0 ? "普通投屏" : "地图投屏"));
    }

    /** 两个模式按钮的选中态 + 文案，并按模式隐藏不需要的控件 */
    private void refreshModeButtons() {
        if (btnModeNormal == null || btnModeMap == null) return;
        setChipSelected(btnModeNormal, castMode == 0, "普通投屏");
        setChipSelected(btnModeMap, castMode == 1, "地图投屏");

        /*
         * 地图模式不需要「选择投屏 App」—— 那条链路是注入地图进程，
         * 目标固定是 com.geely.map，用户选了别的 App 也没用。
         * 留着按钮只会让人以为要选、选了又不生效。
         */
        if (btnPickApp != null) {
            btnPickApp.setVisibility(castMode == 0 ? View.VISIBLE : View.GONE);
        }
        if (targetHint != null) {
            targetHint.setVisibility(castMode == 0 ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * chip 按钮的选中态。
     *
     * **直接用项目里的 styleToggle**（2026-11-08 修）。
     *
     * 一开始我自己写了套 setBackgroundResource(btn_primary/btn_secondary) +
     * setTextColor(white/accent_soft)，和全项目其它 chip（编码档、遮罩形状、
     * 视角三档）的选中态不一致 —— 别处用的是 btn_selected + accent_on。
     * 同一个界面里两套选中样式，用户看到的是"有的按钮点了变蓝、有的变深灰"。
     *
     * styleToggle 还额外处理了「所有子 View 文字变色」（applyTextColorRecursive），
     * 而我的版本只改了第一个 TextView —— chipButton 目前只有一个 TextView 所以
     * 看不出问题，但带图标的 chip 就会漏。
     */
    private void setChipSelected(LinearLayout chip, boolean on, String label) {
        if (chip == null) return;
        TextView tv = (TextView) chip.getChildAt(0);
        if (tv != null) tv.setText(on ? label + " ✓" : label);
        styleToggle(chip, on);
    }

    /** 按当前模式开投 */
    private void startCastByMode() {
        if (castMode == 0) {
            startCast();       /* 普通投屏：抓主屏/指定 App */
        } else {
            startMapCast();    /* 地图投屏：注入地图进程 */
        }
    }

    /**
     * 按当前模式停投（两条链路都要清，防止上一次残留）
     *
     * **必须串行**（2026-11-08 修「刚启动就被杀」）
     *
     * stopCastOnly() 和 stopMapCast() 内部各自往 worker 线程丢任务，
     * 两个 worker 并发跑时，pkill 的执行顺序不确定 ——
     * 实测踩到过：
     *   23:48:15  同时发起两个 stop
     *   23:48:20  用户已经点了「地图投屏」，新 dcapd2 起来了
     *   23:48:29  普通投屏那边的清理才执行 pkill -9 -f dcapd2
     *   23:48:30  新的 dcapd2 被误杀 → "抓帧服务未启动"
     *
     * 现在：先同步停普通投屏（它是后台线程但 stop 本身很快返回），
     * 隔 1.5 秒让它的清理走完，再停地图投屏。用户点停止时看到的
     * 只是"慢了一点点"，换来的是后续启动绝不被旧清理干扰。
     */
    private void stopCastByMode() {
        /*
         * ★ **整段必须在主线程**（2026-11-09 修闪退）
         *
         * 上一版把这两步丢进了 `new Thread(...)`，理由是里面有 sleep(1500)
         * 怕卡 UI。但 stopCastOnly() 内部第 4642 行会调 updateMapHudBtn()，
         * 那里面是 `((TextView) btn.getChildAt(0)).setText(...)` —— 动的是
         * View，只能在创建它的主线程执行。
         *
         * 崩溃日志（crash buffer，22:40:10）：
         *   android.view.ViewRootImpl$CalledFromWrongThreadException:
         *     Only the original thread that created a view hierarchy can touch its views.
         *       at MainActivity.updateMapHudBtn(MainActivity.java:4875)
         *       at MainActivity.stopCastOnly(MainActivity.java:4642)
         *       at MainActivity.lambda$stopCastByMode$0(MainActivity.java:4153)
         *       at java.lang.Thread.run(Thread.java:923)     ← 后台线程
         *
         * 后果是闪退 + 图层没还原（stop 中途被打断，CP 上 layer 99 残留）。
         *
         * 现在改用 **postDelayed**：主线程立刻停普通投屏，1.5 秒后再停地图投屏。
         * 既不阻塞 UI（不是 sleep），又保证两步都在主线程、顺序也确定。
         */
        stopCastOnly();        /* 停普通投屏（主线程，内部异步） */

        /*
         * 给普通投屏的异步清理留时间 —— 它内部那条
         * `pkill -9 -x dcapd2` 是丢在线程池里跑的，
         * 不等就会和地图投屏的启动撞车（实测日志见方法注释）。
         */
        mainHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            stopMapCast();     /* 停地图投屏（内部有 isRunning 保护，没跑就跳过） */
        }, 1500);
    }

    private void startCast() {
        if (busy) {
            DashLog.i(TAG, "操作进行中，忽略重复点击: 开始投屏");
            return;
        }
        // 必须点名一个 App。不点名就等于"投整个桌面"，那不是我们要的。
        if (targetPkg == null || targetCls == null) {
            setStatus("状态：请先「选择投屏 App」—— 只投这一个，桌面不投");
            showAppPicker();
            return;
        }

        /*
         * **抢占：正在投地图 HUD 就先把它停掉。**
         *
         * 两条投屏路径互斥 —— 都写 CP 的 layer 99，同时跑画面会打架。
         * 用户选了 App 又点「开始投屏」，意图很明确："我要投这个 App"，
         * 那就该把 HUD 让出来，而不是弹"请先停止 HUD"让他自己点两次。
         */
        if (MirrorController.isMirroring() && mirror.isMapHudActive()) {
            appendLog("开始投屏 · 先停止地图 HUD");
            setStatus("状态：正在从地图 HUD 切到 App 投屏…");
            MirrorController.setHudSession(false);   /* 关掉 HUD 会话，走 App 分支 */
            updateMapHudBtn();
            stopCastOnly();
            mainHandler.postDelayed(this::startCastAfterHudStop, 2500);
            return;
        }


        startCastInner();
    }

    /** startCast 在停止 HUD 之后的续接（抽取出来便于延迟调用） */
    private void startCastAfterHudStop() {
        if (busy) return;

        /*
         * 同理，进这里必须确认 HUD 标志是**关**的 ——
         * 否则 startCastInner 里 isMapHudActive() 还是 true，
         * 会继续走 HUD 分支（抓 Display 1），而用户要的是投 App。
         */
        MirrorController.setHudSession(false);


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

                /*
                 * 先确认 CP 上有可执行的 dashcastd（被「停止服务」删过就自动重推），再启动
                 *
                 * ★ 2026-11-12 加强：分步检查 + 明确失败原因
                 *
                 * 原来是 `ensureDashcastd() && startDashcastd()` 一行，
                 * 短路求值让「哪一步失败」完全看不出来 —— 用户只看到
                 * 「启动 dashcastd 失败（CP 侧接收端没起来）」。
                 *
                 * 实测踩到：/run 是 tmpfs，车机重启后二进制直接消失，
                 * ensureDashcastd() 返回 false，startDashcastd() 根本没跑，
                 * 而日志里没有任何线索指向「文件没了」。
                 */
                boolean cDeployed = cpManager.ensureDashcastd();
                if (!cDeployed) {
                    appendLog("CP 二进制部署失败（/run 是 tmpfs，重启会丢，需重新推送）");
                    appendLog(cpManager.tailLog(8));
                    ok = false;
                } else {
                    boolean cStarted = cpManager.startDashcastd();
                    if (!cStarted) {
                        appendLog("CP 侧 dashcastd 启动失败，CP 日志：");
                        appendLog(cpManager.tailLog(8));
                        ok = false;
                    } else {
                        ok = true;
                    }
                }
                if (ok) {
                    size = cpManager.queryScreenSize();   // 按 CP 真实分辨率建屏
                    /*
                     * **把真实分辨率定下来**（2026-11-03）。
                     *
                     * 画布/抓帧/图层/遮罩全按这个尺寸算。原来写死 1920×532，
                     * 只要 CP 屏不是这个数，图层就只盖住一截 ——
                     * 用户看到的就是"遮罩铺满网格，仪表上却铺不满"。
                     */
                    if (size != null && size[0] >= 640 && size[1] >= 320) {
                        MirrorController.setOutputSize(getApplicationContext(),
                                size[0], size[1]);
                    } else {
                        int[] cached = {MirrorController.getOutputW(),
                                        MirrorController.getOutputH()};
                        size = cached;
                    }
                }
            } catch (Throwable e) {
                // 必须 catch Throwable：这里如果漏出的是 Error（OOM / 链接错误），
                // 线程一死整个进程就闪退 —— 用户看到的就是"点开始投屏直接退出"。
                DashLog.e(TAG, "启动 dashcastd 异常", e);
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
                /*
                 * HUD 模式下 **target 和 comp 都指向 HUD 自己** ——
                 *
                 * 不能沿用 targetPkg：那可能是用户上次选的 B 站/地图，
                 * loop() 会拿它去"确保目标在运行"，于是去 am start 那个 App，
                 * 把焦点抢走 —— 用户看到"点地图 HUD 就跳到别的 App"。
                 *
                 * 而 HUD 是系统应用、常年运行，用它最稳：
                 *   · targetAlive() 永远为 true，不会误判"目标已退出"
                 *   · loop() 的 HUD 分支根本不看它，只是兜底
                 */
                final String target = mirror.isMapHudActive()
                        ? MirrorController.HUD_PKG
                        : targetPkg;
                final String comp = mirror.isMapHudActive()
                        ? MirrorController.HUD_PKG + "/" + MirrorController.HUD_CLS
                        : targetPkg + "/" + targetCls;
                mirror.start(target, comp, makeMirrorListener());
                running = true;
                leaveBusy();
            });
        }, "cp-start").start();
    }


    /** 虚拟屏就绪回调：把目标 App 搬进虚拟屏（而非物理仪表屏） */
    private void onDisplayReady(int displayId) {
        if (displayId < 0) {
            /*
             * **这个分支已经走不到了**（2026-11-07）。
             *
             * displayId 只由 DashcastSenderService 的 displayReadyListener 传进来，
             * 而那个 Service 从未被 startService 启动过（isRunning() 恒 false），
             * 所以 onDisplayReady 永远不会被调用。
             *
             * 保留这段只为兜住"万一以后有人恢复了那条链路"：
             * 直接报错，不再尝试 MediaProjection 授权（那条链路依赖同一个 Service）。
             */
            running = false;
            DashLog.e(TAG, "onDisplayReady(" + displayId + ") —— 私有屏建立失败");
            setStatus("状态：虚拟屏创建失败（displayId=" + displayId + "）");
            setDotColor(R.color.text_sub);
            leaveBusy();
            return;
        }


        /*
         * **HUD 模式不需要"送 App 上屏"**（2026-11-02）。
         *
         * 上面那个虚拟屏（displayId）是给普通 App 投屏用的：把目标 App
         * 搬到那块私有屏，再截那块屏的像素。
         *
         * 但 HUD 模式的取景源是 **Display 1 上的 com.ecarx.hud** ——
         * 跟这块虚拟屏毫无关系。如果还往虚拟屏送 App：
         *   · 会把 HUD 挤到后台（日志实测「回桌面 —— App 转后台」）
         *   · dcapd2 抓 Display 1 时画面反而空了
         *   · 日志「后台跟随不可用 —— 仪表停在最后一帧」
         *
         * 所以 HUD 模式直接跳过这段，只把状态标为运行中。
         */
        if (mirror.isMapHudActive()) {
            setStatus("状态：地图 HUD 投屏运行中（Display "
                    + MirrorController.HUD_DISPLAY_ID + " → CP）");
            setDotColor(R.color.state_ok);
            leaveBusy();
            return;
        }

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
            DashLog.i(TAG, "落地验证 display " + displayId + " ⊃ " + pkg + " = " + hit);
            return hit;
        } catch (Throwable t) {
            DashLog.w(TAG, "落地验证失败: " + t);
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
                DashLog.i(TAG, "公开 API 把 " + component + " 送到 display " + displayId);
                return true;
            } catch (SecurityException e) {
                DashLog.w(TAG, "launchDisplayId=" + displayId + " 被 SafeActivityOptions 拒（第 "
                        + attempt + " 次）");
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            } catch (Exception e) {
                DashLog.e(TAG, "启动到虚拟屏失败: " + e);
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
            DashLog.i(TAG, "am display move-stack " + taskId + " " + displayId + " → " + out);
            boolean ok = !out.contains("Exception") && !out.contains("Error");
            if (ok) sLastLaunchError = "";
            else sLastLaunchError = out.replace('\n', ' ');
            return ok;
        } catch (Throwable t) {
            DashLog.w(TAG, "move-stack 失败: " + t);
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
                        DashLog.i(TAG, "找到 " + pkg + " 的 task=" + id
                                + "（stack=" + curStack + "，在 display " + curDisplay + "）");
                        return id;
                    }
                }
            }
        } catch (Throwable t) {
            DashLog.w(TAG, "读 am stack list 失败: " + t);
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
            DashLog.i(TAG, "adbd: " + cmd + "\n" + out);

            if (out.contains("not started")) {
                // 备用手段：先杀掉目标进程，让它没有旧 task 可复用
                String pkg = component.substring(0, component.indexOf('/'));
                DashLog.i(TAG, "已有旧 task，force-stop " + pkg + " 后重开");
                AdbShell.exec("am force-stop " + pkg, 5000);
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                out = AdbShell.exec(cmd, 8000).trim();
                DashLog.i(TAG, "adbd 重试:\n" + out);
            }

            boolean ok = out.contains("Starting:") && !out.contains("Error")
                    && !out.contains("not started");
            if (!ok) {
                sLastLaunchError = out.replace('\n', ' ');
                DashLog.e(TAG, "adbd 启动失败: " + sLastLaunchError);
            }
            return ok;
        } catch (Throwable t) {
            sLastLaunchError = t.toString();
            DashLog.e(TAG, "adbd 通道失败: " + t);
            return false;
        }
    }

    /* ═══════════════════════════════════════════════════════════
     * 地图投屏（方案 ①，2026-11-08）
     *
     * 链路：
     *   MapCastService
     *     → 解压 assets/hudcast-inject.gz 到 /data/local/tmp/
     *     → hudcast-inject -p <地图pid> -s mapcast.js
     *     → 地图进程内：VirtualDisplay(1920x532, 0x3) + MapView + HUDControl
     *        .initSurfaceView（引擎注册 slot 5）→ PixelCopy(Surface) → VDF1
     *     → dcapd2 (1920x532) → CP:8444 → dashcastd → layer 99 → 仪表盘
     *
     * 为什么不能纯 APK 实现：
     *   Dashcast 没有 CAPTURE_VIDEO_OUTPUT，建不了大尺寸 VirtualDisplay；
     *   地图进程有 —— 所以必须注入。
     * ═══════════════════════════════════════════════════════════ */
    private void startMapCast() {
        /*
         * **不要在 UI 线程调 AdbShell.available()**（2026-11-08 修）。
         *
         * 它内部走 Socket 连 adbd:5555 → 在 UI 线程会抛
         * NetworkOnMainThreadException → 异常被 catch 掉 → available() 返回 false
         * → 这里直接 return，按钮看起来"没反应"。
         *
         * 所以整段都交给 MapCastService 的 worker 线程，UI 线程只改按钮状态。
         */
        setStatus("状态：正在启动地图投屏…");
        appendLog("地图投屏 · 启动");
        if (mapCastState != null) mapCastState.setText("地图投屏：启动中…");

        MapCastService.get().start(this, msg -> mainHandler.post(() -> {
            /*
             * **进度也要反映到顶部大状态**（2026-11-08 修）。
             *
             * 原来只更新 mapCastState 那行小字，顶部 status 永远停在
             * "正在启动地图投屏…"，用户看到的就是"卡住了"——
             * 其实后台早就跑完了（实测 bootSequence 11 秒就完成）。
             */
            if (mapCastState != null) mapCastState.setText("地图投屏：" + msg);
            setStatus("状态：" + msg);
            if (msg.contains("失败") || msg.contains("未启动")) {
                if (btnMapCast != null) btnMapCast.setEnabled(true);
                if (btnMapStop != null) btnMapStop.setEnabled(false);
            }
            /* 真正就绪的信号：抓到帧了。这时把按钮状态定下来 */
            if (msg.contains("注入成功")) {
                appendLog("地图投屏 · 已就绪");
            }
        }));

        if (btnMapCast != null) btnMapCast.setEnabled(false);
        if (btnMapStop != null) btnMapStop.setEnabled(true);
    }

    private void stopMapCast() {
        /* 没在跑就静默跳过 —— stopCastByMode() 会同时调 stopCastOnly()
         * 和 stopMapCast()，两个都无条件执行的话，永远会多打一条
         * "地图投屏已停止，仪表已还原" 的状态，用户以为出问题了。 */
        if (!MapCastService.get().isRunning()) {
            if (mapCastState != null) mapCastState.setText("地图投屏：未启动");
            return;
        }
        setStatus("状态：正在停止地图投屏…");
        appendLog("地图投屏 · 停止");
        MapCastService.get().stop();
        if (mapCastState != null) mapCastState.setText("地图投屏：未启动");
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

        /*
         * **停止 = 关掉所有投屏，仪表还原**（需求）。
         *
         * HUD 会话标志必须在这里清 —— 它是一次投屏会话的状态，
         * 会话结束了就该没了。不清的话：
         *   · 下次点「开始投屏」投 App，isMapHudActive() 还是 true
         *     → 又把 HUD 推上仪表
         *   · UI 上「地图 HUD · 开」一直亮着，看起来像还在投
         */
        MirrorController.setHudSession(false);
        updateMapHudBtn();

        setDotColor(R.color.content_secondary);
        setStatus("状态：投屏已停止，正在还原仪表…");
        appendLog("停止投屏" + (targetLabel != null ? " · " + targetLabel : ""));

        /* 停控制器（内部已改成异步，这里不会阻塞 UI） */
        try {
            if (mirror != null) mirror.stop();
        } catch (Throwable t) {
            DashLog.w(TAG, "mirror.stop 异常: " + t.getMessage());
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
            if (mirror != null && MirrorController.isHudSession()
                    && false) {   /* HUD 是系统应用，停止时无需撤离 */
                try {
                    mirror.dismissMapHud();
                    DashLog.i(TAG, "地图 HUD 已从 Display "
                            + MirrorController.HUD_DISPLAY_ID + " 撤回");
                } catch (Throwable t) {
                    DashLog.w(TAG, "撤回地图 HUD 失败: " + t);
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
                    DashLog.i(TAG, killedOk
                            ? "已结束投屏 App: " + killed
                            : "投屏 App 仍在运行（系统应用会被自动拉起）: " + killed);
                } catch (Throwable t) {
                    killedOk = false;
                    DashLog.w(TAG, "结束投屏 App 失败: " + t.getMessage());
                }
            }

            /* ② 还原仪表 */
            boolean ok = false;
            try {
                ok = cpManager.restoreDashboard();
            } catch (Throwable t) {
                DashLog.w(TAG, "还原仪表异常: " + t.getMessage());
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


    private void setStatus(final String text) {
        mainHandler.post(() -> status.setText(text));
        DashLog.i(TAG, text);
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

            /*
             * **不隐藏 dock 栏/导航栏**（2026-11-07 用户要求）。
             *
             * 原来的行为是"沉浸式"：把导航栏藏起来，让画面铺满整屏。
             * 但车机的 dock 栏（底部「回到桌面 / 音量 / 空调」那排）
             * 是用户日常操作入口 —— 藏掉之后想回桌面还得从边缘上滑，
             * 在颠簸的车里很难点准。
             *
             * 现在：
             *   · 保留 setDecorFitsSystemWindows(false) —— 内容仍然延伸到
             *     状态栏下方（否则顶部会多出一条白边）
             *   · 不再调 hideNavBars() —— 导航栏保持可见
             *
             * 注意：page 的 OnApplyWindowInsetsListener 仍然会读
             * navigationBars 的 bottom inset 加 padding，所以内容不会被
             * dock 栏压住 —— 它会自动让出那块高度。
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                w.setDecorFitsSystemWindows(false);
                /*
                 * 系统栏样式：只让它"半透明覆盖"，不做隐藏。
                 * BEHAVIOR_DEFAULT = 从边缘滑动时临时显示（系统默认行为），
                 * 这里显式设成它，避免继承到某个自定义值。
                 */
                if (decor.isAttachedToWindow()) {
                    applySystemBarStyle(w);
                } else {
                    decor.post(() -> {
                        try {
                            applySystemBarStyle(w);
                        } catch (Throwable t) {
                            DashLog.w(TAG, "applyImmersive(post) 失败（忽略）: " + t);
                        }
                    });
                }
                return;
            }

            /*
             * 老 API：只保留 LAYOUT_STABLE + LAYOUT_HIDE_NAVIGATION 让内容延伸，
             * 去掉 SYSTEM_UI_FLAG_HIDE_NAVIGATION（那才是"藏起来"）。
             */
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable t) {
            /* 任何机型差异都不该让 App 起不来 —— 藏导航栏只是锦上添花 */
            DashLog.w(TAG, "applyImmersive 失败（忽略）: " + t);
        }
    }

    /*
     * **只设系统栏样式，不隐藏**（2026-11-07 改）。
     *
     * 原来这个方法叫 hideNavBars，里面 `ctl.hide(navigationBars())` 把导航栏
     * 彻底藏了。现在保留 dock 栏，只做两件事：
     *
     *   · show(navigationBars()) —— 确保它是可见的（有些 ROM 会记住上次的隐藏态）
     *   · 图标深浅 —— 我们的页面是浅灰底（#F3F3F2），系统栏图标必须是深色，
     *     否则白图标压在浅底上完全看不见。LIGHT_APPEARANCE 就是干这个的。
     *
     * 同时给状态栏也设 LIGHT_APPEARANCE —— 顶部同样的问题。
     */
    private void applySystemBarStyle(Window w) {
        WindowInsetsController ctl = w.getInsetsController();
        if (ctl == null) return;
        /*
         * 显式 show：车机的 framework 有时会把窗口不显示的 flag 记住，
         * 上一版隐藏过的窗口重新进来可能仍是隐藏态。
         */
        ctl.show(WindowInsets.Type.navigationBars());
        ctl.show(WindowInsets.Type.statusBars());
        ctl.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
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
        return MirrorController.isHudSession() ? "地图 HUD · 开" : "地图 HUD · 关";
    }

    /** 刷新地图 HUD 按钮的高亮与可见性 */
    private void updateMapHudBtn() {
        if (btnMapHud == null) return;
        ((TextView) btnMapHud.getChildAt(1)).setText(mapHudLabel());
        styleToggle(btnMapHud, MirrorController.isHudSession());

        /*
         * **HUD 按钮永远可见**（2026-11-03 修复）。
         *
         * 原来写作 `boolean show = mirror.isMapMode() && true;` ——
         * 挂在「地图模式」上。后果：
         *   · 用户在投 HUD 时关掉地图模式 → 按钮消失
         *   · HUD 会话还在跑，仪表还显示 HUD，但用户找不到关闭入口
         *   · 用户报的就是："点地图模式关就会收回地图 HUD"
         *
         * 现在 HUD 和地图模式是**完全独立**的两条路：
         *   地图模式 = 抓主屏地图窗口 + 遮罩裁剪（影响 App 投屏）
         *   地图 HUD = 抓 Display 1 的 com.ecarx.hud
         * 所以 HUD 按钮不依赖任何别的开关，始终可点。
         */
        btnMapHud.setVisibility(View.VISIBLE);

        /* 视角三档只属于「地图模式」（App 投屏路径），HUD 会话时不显示 */
        if (viewRow != null) {
            boolean showView = mirror.isMapMode() && !MirrorController.isHudSession();
            viewRow.setVisibility(showView ? View.VISIBLE : View.GONE);
        }
    }

    private void updateMapModeBtn() {
        if (btnMapMode == null) return;
        ((TextView) btnMapMode.getChildAt(1)).setText(mapModeLabel());
        styleToggle(btnMapMode, mirror.isMapMode());
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

        maskCfg.applyView(prefsView, MirrorController.getOutputW(), MirrorController.getOutputH());

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
                DashLog.i("DashcastView", "视角档已下发"
                        + (casting ? "（含图层矩形）" : "（未投屏，仅落盘）"));
            } catch (Throwable ex) {
                DashLog.w("DashcastView", "视角档下发失败: " + ex);
            }
        }, "view-apply").start();

        if (casting) {
            setStatus("状态：视角已切换为 " + LayerConfig.VIEW_NAMES[prefsView]);
        }
    }

    /**
     * 高亮当前视角档。
     *
     * **还要同步文字颜色**（2026-11-04 补充）。
     *
     * 原来只换背景，文字一直保持创建时的颜色（accent_soft 淡青）。
     * 背景从"深色描边"变成"强调色实底"后，淡青字压在蓝底上
     * 对比度只有 1.3:1，几乎看不见 —— 选中档的字反而比未选中的更难读。
     *
     * 现在按状态给字色：
     *   选中 → surface_root（近黑），压在蓝底上 7:1
     *   未选 → content_secondary（灰）
     */
    private void updateViewBtns() {
        if (viewBtns == null) return;
        int cur = getSharedPreferences(PREF, MODE_PRIVATE).getInt("map_view", LayerConfig.VIEW_MID);
        for (int i = 0; i < viewBtns.length; i++) {
            if (viewBtns[i] == null) continue;
            styleToggle(viewBtns[i], i == cur);
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
    /**
     * 设置状态灯颜色 —— 同时更新状态卡左侧色条。
     *
     * 状态卡背景已从 XML layer-list 换成 StatusCardDrawable（squircle），
     * 所以这里不能再走 LayerDrawable.getDrawable(1) 的老路，
     * 要直接调它的 setBarColor。
     */
    private void setDotColor(int colorRes) {
        final int color = getColor(colorRes);
        mainHandler.post(() -> {
            /* 左上角那个小圆点（GradientDrawable） */
            if (statusDot != null) {
                android.graphics.drawable.Drawable d = statusDot.getBackground();
                if (d instanceof android.graphics.drawable.GradientDrawable) {
                    ((android.graphics.drawable.GradientDrawable) d).setColor(color);
                }
            }
            /* 状态卡左侧 5dp 色条 */
            if (statusCardView != null) {
                android.graphics.drawable.Drawable bg = statusCardView.getBackground();
                if (bg instanceof StatusCardDrawable) {
                    ((StatusCardDrawable) bg).setBarColor(color);
                    bg.invalidateSelf();
                } else if (bg instanceof android.graphics.drawable.LayerDrawable) {
                    /* 兼容：万一还是旧的 layer-list */
                    android.graphics.drawable.LayerDrawable ld =
                            (android.graphics.drawable.LayerDrawable) bg;
                    android.graphics.drawable.Drawable bar = ld.getDrawable(1);
                    if (bar != null) bar.setColorFilter(color,
                            android.graphics.PorterDuff.Mode.SRC_IN);
                }
            }
        });
    }
    /**
     * 行内按钮的布局参数（等分宽度 + 固定高度）。
     *
     * ══════════════════════════════════════════════════════════════
     * 尺寸收紧（2026-11-04 布局重构）
     * ══════════════════════════════════════════════════════════════
     * 原来是 62dp 高 + 上下各 6dp 边距 = 每行占 74dp。
     *
     * 右栏有 6 行按钮 + 若干滑块行，光按钮区就吃掉 444dp，
     * 加上遮罩参数区 300dp、截取区展开 560dp，总高超过 1600dp，
     * 用户必须滚动才能看到「保存并应用」—— 最重要的按钮被推到屏幕外。
     *
     * 改成 48dp 高 + 上下 4dp = 每行 56dp，6 行省下 108dp。
     * 48dp 也符合 Android 触摸目标最小尺寸建议（48dp），不算小。
     */
    private LinearLayout.LayoutParams rowLp(float weight) {
        /*
         * 按钮尺寸按 MiUIX ButtonDefaults 调整（2026-11-04）。
         *
         *   MiUIX: MinHeight = 40dp, InsideMargin = 16dp横 / 13dp纵
         *
         * 车机上纯 40dp 偏小（手指粗、路面颠簸），取 44dp ——
         * 比 MiUIX 高 4dp，触摸目标更准，视觉上仍接近。
         * 边距 4dp 保留（MiUIX 卡片内元素间距是 4-8dp 栅格）。
         */
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), weight);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }


    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 给 CheckBox 上色（浅色 UI 必需，否则勾选框在白底上几乎不可见）。
     *
     * buttonTint 是三态 ColorStateList：
     *   选中   → accent (#007AFF)
     *   未选中 → surface_border (#D1D1D6)
     *   禁用   → 半透明
     */
    private void tintCheckBox(android.widget.CheckBox cb) {
        if (cb == null) return;
        int[][] states = new int[][]{
                {android.R.attr.state_checked},
                {-android.R.attr.state_enabled},
                {}
        };
        int[] colors = new int[]{
                getColor(R.color.accent),
                getColor(R.color.content_tertiary),
                getColor(R.color.surface_border)
        };
        cb.setButtonTintList(new android.content.res.ColorStateList(states, colors));
    }
    /**
     * 设置某一个角的圆角（0=左上 1=右上 2=左下 3=右下）。
     *
     * **如果"同步"勾上了，四个角一起改**（2026-11-05）。
     *
     * 为什么要专门一个方法而不是直接 maskCfg.rTl = v：
     *   · 同步模式下改一个滑块，另外三个滑块的**位置和数字也要跟着动**，
     *     否则用户看到"左上 65°、右上 20°"但实际渲染是四个 65° —— 界面说谎。
     *   · 三个滑块的 setProgress 又会回调 setCorner，需要防重入。
     */
    private void setCorner(int which, int v) {
        if (cornerSyncing) {
            /* 正在批量同步，只改值不改 UI，避免无限递归 */
            applyCornerValue(which, v);
            refreshMask();
            return;
        }
        boolean locked = cornerLockBox != null && cornerLockBox.isChecked();
        if (locked) {
            cornerSyncing = true;
            try {
                applyCornerValue(0, v);
                applyCornerValue(1, v);
                applyCornerValue(2, v);
                applyCornerValue(3, v);
                /* 三个同伴滑块的 UI 也要跟上（正在拖的那个已经是 v 了） */
                setCornerSlider(maskSbTl, maskTvTl, v);
                setCornerSlider(maskSbTr, maskTvTr, v);
                setCornerSlider(maskSbBl, maskTvBl, v);
                setCornerSlider(maskSbBr, maskTvBr, v);
            } finally {
                cornerSyncing = false;
            }
        } else {
            applyCornerValue(which, v);
        }
        refreshMask();
    }

    /** 只改 maskCfg 的字段，不碰 UI */
    private void applyCornerValue(int which, int v) {
        switch (which) {
            case 0: maskCfg.rTl = v; break;
            case 1: maskCfg.rTr = v; break;
            case 2: maskCfg.rBl = v; break;
            case 3: maskCfg.rBr = v; break;
        }
    }

    /** 同步一个圆角滑块的进度 + 右侧数值文字 */
    private void setCornerSlider(SeekBar sb, TextView tv, int v) {
        if (sb != null && sb.getProgress() != v) sb.setProgress(v);
        if (tv != null) tv.setText(v + "°");
    }

    /** "同步"勾上时把四个角的当前值统一（取左上的），并调整样式 */
    private void applyCornerLockStyle() {
        boolean locked = cornerLockBox != null && cornerLockBox.isChecked();
        if (locked) {
            int v = maskCfg.rTl;
            cornerSyncing = true;
            try {
                applyCornerValue(1, v);
                applyCornerValue(2, v);
                applyCornerValue(3, v);
                setCornerSlider(maskSbTl, maskTvTl, v);
                setCornerSlider(maskSbTr, maskTvTr, v);
                setCornerSlider(maskSbBl, maskTvBl, v);
                setCornerSlider(maskSbBr, maskTvBr, v);
            } finally {
                cornerSyncing = false;
            }
            refreshMask();
        }
        /*
         * 锁定态下把三个"同伴"滑块调暗一点 —— 暗示"改它们没用"。
         * 但不能真 disable：用户可能想改锁定值，四个都拖得动，
         * 只是效果等价。所以只降一点 alpha 做视觉提示。
         */
        float alpha = locked ? 0.72f : 1.0f;
        if (maskSbTr != null) maskSbTr.setAlpha(alpha);
        if (maskSbBl != null) maskSbBl.setAlpha(alpha);
        if (maskSbBr != null) maskSbBr.setAlpha(alpha);
        if (maskTvTr != null) maskTvTr.setAlpha(alpha);
        if (maskTvBl != null) maskTvBl.setAlpha(alpha);
        if (maskTvBr != null) maskTvBr.setAlpha(alpha);
    }

    /** dp → px 的 float 版本（squircle 圆角要 float 精度） */
    private float dpFloat(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}