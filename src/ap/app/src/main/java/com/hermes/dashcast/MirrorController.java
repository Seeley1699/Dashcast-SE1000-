package com.hermes.dashcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 投屏控制器：把目标 App 的画面推到仪表，管理 dcapd2 与 CP 侧 dashcastd 的生命周期。
 *
 * 链路：
 *   MainActivity ──(adbd root)──▶ /data/local/tmp/dcapd2 ──TCP:8444──▶ CP dashcastd
 *
 * 两种工作模式：
 *   主屏镜像 —— dcapd2 直接抓目标 App 在主屏上的那块矩形。中控可以随便切，
 *               只要目标还在前台就一直在抓。
 *   后台跟随 —— 目标 App 被搬到虚拟屏，画面由 VdMirror 抓 ImageReader，
 *               经 127.0.0.1:8450 喂给 dcapd2。这样按 HOME 之后还能继续投。
 *
 * 代次（gen）：每次 start() 递增。循环里所有耗时操作之后都要检查，
 * 代次变了立刻退场 —— 否则旧线程会把新目标的 dcapd2 杀掉。
 */
public class MirrorController {

    private static final String TAG = "DashcastMirror";

    private static final String BIN = "/data/local/tmp/dcapd2";
    private static final String BIN_MD5 = "f0ea833ddabc1bdfe071db9c404af51f";
    private static final String PAUSE = "/data/local/tmp/dcapd2.pause";
    private static final String LOGF = "/data/local/tmp/dcapd2.log";
    /** 编码档位文件：App 写数字，dcapd2 每 500ms 轮询，运行时切档不重启进程 */
    private static final String CODEC_FILE = "/data/local/tmp/dc_codec";

    private static final String PREF_CODEC = "codec";
    private static final String PREF_FPS = "fps";
    private static final String PREF_MAP_MODE = "map_mode";

    /*
     * 仪表画布尺寸 —— **跟着 CP 真实分辨率走**（2026-11-03 修复「遮罩铺满网格，
     * 仪表上却铺不满」）。
     *
     * 原来写死 1920×532：画布、dcapd2 输出、ivi 图层、destination region
     * 全是这个数。但只要 CP 屏不是正好 1920×532（实测有的车机是 1920×720），
     * 图层就只盖住屏幕上面一截，下面露出原厂仪表 ——
     * 用户看到的就是"遮罩明明铺满了网格，仪表却铺不满"。
     *
     * 现在：App 起投屏时把 cpManager.queryScreenSize() 的结果写进 prefs，
     * 这边静态读出来用。拿不到就退回 1920×532（老行为）。
     */
    private static final int DEF_OUT_W = 1920;
    private static final int DEF_OUT_H = 532;
    private static volatile int sOutW = DEF_OUT_W;
    private static volatile int sOutH = DEF_OUT_H;

    private static final String PREF_OUT_W = "cp_scr_w";
    private static final String PREF_OUT_H = "cp_scr_h";

    public static int getOutputW() { return sOutW; }
    public static int getOutputH() { return sOutH; }

    /** App 启动时读一次缓存的 CP 分辨率 */
    public static void loadOutputSize(Context c) {
        try {
            SharedPreferences p = prefs(c);
            int w = p.getInt(PREF_OUT_W, 0);
            int h = p.getInt(PREF_OUT_H, 0);
            if (w >= 640 && h >= 320) {
                sOutW = w;
                sOutH = h;
                DashLog.i(TAG, "仪表画布尺寸（缓存）= " + w + "×" + h);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 起投屏时拿到 CP 实测分辨率 → 记住并生效 */
    public static void setOutputSize(Context c, int w, int h) {
        if (w < 640 || h < 320) return;
        sOutW = w;
        sOutH = h;
        try {
            prefs(c).edit().putInt(PREF_OUT_W, w).putInt(PREF_OUT_H, h).apply();
        } catch (Throwable ignored) {
        }
        DashLog.i(TAG, "仪表画布尺寸 → " + w + "×" + h);
    }

    /** 分片推送时每片的 base64 字符数（adbd 单条命令别太长） */
    private static final int CHUNK = 3000;

    public static final int CODEC_BGRA = 0;
    public static final int CODEC_H264 = 2;

    // ==================== 地图 HUD（原生渲染）====================

    /*
     * 地图的 HUD 页面 —— **地图自己为小屏导航设计的渲染**。
     *
     * 和主界面地图（MainActivity）比，它干净得多：
     *   浅底高对比 / 粗路线 / 居中车标 / 无 POI / 无地名 / 无 UI 按钮
     * 正是开车时该看的东西。
     *
     * 实测（2026-11-02）：
     *   am start --display 1 -n <这个 Activity>
     *   → 车机 Display 1（HDMI 800×480, FLAG_PRESENTATION）上出现原生 HUD 画面
     *   → dcapd2 抓物理屏 #1 就能拿到，投到仪表
     *
     * 关键：这是个**普通 Activity**，不需要任何系统权限 ——
     * 不像 DIM 通道（要 signature|privileged）或 VirtualDisplay（要 CAPTURE_VIDEO_OUTPUT）。
     */
    /*
     * ══════════════════════════════════════════════════════════════
     * 两个不同的"HUD"，别搞混（2026-11-02 实测区分）
     * ══════════════════════════════════════════════════════════════
     *
     * ① com.geely.map 的 HudActivity —— 地图给自己做的小屏渲染
     *    浅底、只有路线和车标、无文字信息。**不是我们要的。**
     *
     * ② com.ecarx.hud —— 车机真正的 HUD 应用（系统预装，开机自启）
     *    黑底、档位 + 车标 + 转向提示 + 剩余距离/时间。
     *    **这才是 HUD 该有的样子**，也正是开车要的信息。
     *
     * 它跑在 Display 1（HDMI 800×480）。用户这台车没有 HUD 硬件，
     * 那块屏平时是"空跑"的 —— 正好拿来抓取投到仪表。
     */
    public static final String HUD_PKG = "com.ecarx.hud";
    public static final String HUD_CLS = "com.ecarx.hud.view.MainActivity";

    /** 地图：HUD 的导航数据来源。地图在导航态时 HUD 才有转向提示 */
    public static final String MAP_PKG  = "com.geely.map";
    public static final String MAP_MAIN_CLS = "com.ecarx.navi.MainActivity";

    /** HUD 那块屏的 DisplayManager displayId */
    public static final int HUD_DISPLAY_ID = 1;
    /** dcapd2 的物理屏序号（getPhysicalDisplayIds 下标） */
    public static final int HUD_PHYSICAL_INDEX = 1;
    /** HUD 屏分辨率 */
    public static final int HUD_W = 800;
    public static final int HUD_H = 480;

    /*
     * HUD 画面的有效区域（实测标定，2026-11-02）。
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么不能直接照抓整块 800×480
     * ══════════════════════════════════════════════════════════════
     * HUD 是 800×480（比例 1.667），仪表是 1920×532（比例 3.609）。
     * 整块照抓等比缩放后 HUD 只占中间 886×532 —— 两侧大片黑边，
     * 内容又小又远，等于没用。
     *
     * ══════════════════════════════════════════════════════════════
     * 实测的内容边界（逐像素扫描 HUD 截图得出的真实值）
     * ══════════════════════════════════════════════════════════════
     *   x 126..735（宽 610）
     *   y 221..338（高 118）
     *   内容中心 (430, 279)
     *
     * 内容是：档位「P」（左）+ 车辆图标（中）
     *        + 转向箭头「↶ 164米进入 无名道路」+「剩24公里 22:36到」（右）
     *
     * ══════════════════════════════════════════════════════════════
     * 裁剪参数怎么来的
     * ══════════════════════════════════════════════════════════════
     * 目标：比例必须 ≈ 3.609（仪表），且内容四周留 padding 不被切。
     *
     *   取 h = 200  → 放大倍率 532/200 = 2.66 倍
     *   对应 w = 200 × 3.609 = 722
     *   以内容中心 430 为锚 → x = 430 - 722/2 = 69  →  70
     *   y = 279 - 200/2 = 179  →  180
     *
     * 校验：
     *   x 70..792 覆盖内容 126..735   ✅ 左右各留 56/57 px
     *   y 180..380 覆盖内容 221..338  ✅ 上下各留 41/42 px
     *   722/200 = 3.610 ≈ 3.609      ✅ 误差 0.03%
     *
     * 早先试过 x=0,w=800（全宽）：右侧文字（「入」「到」）会顶到画面
     * 右边界被切掉 —— 因为文字本来就在 HUD 右侧（x≈735），
     * 放大 2.66 倍后正好超出。所以必须右移并留 padding。
     *
     * 换车型 / 换 HUD 版本时这几个数要重新量。
     */
    public static final int HUD_SRC_X = 70;
    public static final int HUD_SRC_Y = 180;
    public static final int HUD_SRC_W = 722;
    public static final int HUD_SRC_H = 200;

    /*
     * ══════════════════════════════════════════════════════════════
     * HUD 会话标志 —— **瞬态，不持久化**
     * ══════════════════════════════════════════════════════════════
     *
     * 这是整个 HUD 设计里最容易搞错的一点：
     *
     *   早先把它存在 prefs（`map_hud`），结果一旦开过就永久生效 ——
     *   之后**任何**一次投屏都走 HUD 分支，选 App 投屏也把 HUD 推上仪表。
     *   用户报的就是这个：选定 app 开始投屏，不管怎样都会把 hud 投到仪表。
     *
     * 正确语义：HUD 是**一次投屏会话**的取景方式，不是全局开关。
     *   · 点「地图 HUD」        → 开会话 → 投 HUD
     *   · 再点「地图 HUD」      → 关会话（同时也停投屏）
     *   · 选 App 点「开始投屏」 → 关会话 → 投 App
     *   · 点「停止」            → 关会话 → 仪表还原
     *
     * 所以放**静态内存字段**：进程活着就有效，App 重启自然归零，
     * 也不会污染下一次投屏。用 static 是为了跨 MainActivity 重建 /
     * 多个 MirrorController 实例都能读到同一个值 ——
     * 实例字段实测会不同步（见 isMapHudActive 的注释）。
     */
    private static volatile boolean sHudSession = false;

    /** 当前是不是 HUD 投屏会话 */
    public static boolean isHudSession() {
        return sHudSession;
    }

    /** 设置 / 清除 HUD 会话 */
    public static void setHudSession(boolean on) {
        sHudSession = on;
        DashLog.i(TAG, "HUD 会话 " + (on ? "开启" : "清除"));
    }

    private static final int DEFAULT_FPS = 30;
    public static final int[] FPS_CHOICES = {60, DEFAULT_FPS, 25, 20, 15};

    private static volatile int fps = DEFAULT_FPS;
    private static volatile int codec = CODEC_BGRA;
    private static volatile MirrorController sActive;

    /** 上次已打过日志的矩形键，避免 8 秒保活每次刷两行 */
    private static volatile String sLastRectKey = "";

    private final Context ctx;
    private final CpManager cp;
    private final VdMirror vd;
    private final AtomicInteger gen = new AtomicInteger(0);
    private final Object cleanupLock = new Object();

    /** 图层矩形同步是否正在进行（防止 8 秒保活重复起线程） */
    private final AtomicBoolean rectInFlight = new AtomicBoolean(false);
    private volatile long rectNextAllowedMs = 0;
    private volatile int rectFailCount = 0;

    private volatile String pkg;
    private volatile String component;
    private volatile boolean running;
    private volatile boolean bgMode;
    private long lastCpLog;
    /*
     * 这几个字段是**跨线程**读写的，必须 volatile：
     *   lastRect  —— loop() 写（windowRect 结果）、UI 线程经 setFps() 读
     *   listener  —— UI 线程 setListener、loop 线程 state() 读
     *   mapMode   —— UI 线程 setMapMode、loop 线程 applyMapCrop 读
     *   worker    —— start/stop 在主线程、onDestroy 也会碰
     * 少了 volatile 就没有 happens-before，JIT 可以把读提到循环外，
     * 表现为"改了设置但不生效"或"停止后状态还在刷"。
     * （running / bgMode / pkg / component 原本就是 volatile，这几个漏了。）
     */
    private volatile int[] lastRect;
    private volatile Thread worker;
    private volatile Listener listener;
    private volatile boolean mapMode = false;

    public interface Listener {
        /** 状态行文字；ok=true 表示正常 */
        void onState(String msg, boolean ok);

        /** 每秒一次：fps / 上行 KB/s / 图块数 */
        void onStats(int fps, int kbps, int tiles);

        /** 会话被系统结束（目标 App 退出等）：界面要把"运行中"复位 */
        default void onEnded(String reason) {}
    }

    /** HOME / 熄屏监听：按下即暂停，不等轮询 —— 桌面一帧都不会漏上仪表。 */
    private final BroadcastReceiver leavingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null) return;
            String act = i.getAction();
            String reason = i.getStringExtra("reason");
            final boolean screenOff = Intent.ACTION_SCREEN_OFF.equals(act);
            boolean home = Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(act)
                    && ("homekey".equals(reason) || "recentapps".equals(reason));
            if (!screenOff && !home) return;

            final BroadcastReceiver.PendingResult pr = goAsync();
            final int myGen = gen.get();
            new Thread(() -> {
                try {
                    if (gen.get() != myGen) return;
                    if (screenOff) {
                        setPause();
                        state("熄屏 —— 仪表停在最后一帧", true);
                    } else if (isMapHudActive()) {
                        /*
                         * HUD 模式：dashcast 活动退到后台跟 HUD 无关 ——
                         * HUD 画面一直在 Display 1 上，不需要建虚拟屏搬运。
                         * 这里直接什么都不做，保持投屏继续。
                         */
                        DashLog.i(TAG, "HUD 模式：忽略后台事件（无需虚拟屏跟随）");
                    } else {
                        enterBg(myGen);
                    }
                } finally {
                    pr.finish();
                }
            }, "mirror-leave").start();
        }
    };

    public MirrorController(Context ctx, CpManager cp) {
        this.ctx = ctx.getApplicationContext();
        this.cp = cp;
        LayerConfig.attachCp(cp);
        this.vd = new VdMirror(this.ctx);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(MainActivity.PREF, Context.MODE_PRIVATE);
    }

    public static MirrorController getActive() {
        return sActive;
    }

    public static boolean isMirroring() {
        MirrorController c = sActive;
        return c != null && c.isRunning();
    }

    public boolean isRunning() {
        return running;
    }

    /** 界面重建后把新 Activity 的回调挂回来（状态栏/统计的实时刷新靠它） */
    public void setListener(Listener l) {
        this.listener = l;
    }

    // ============================== 生命周期 ==============================

    public void start(String pkg, String component, Listener l) {
        stop();
        final int myGen = gen.incrementAndGet();
        this.pkg = pkg;
        this.component = component;
        this.listener = l;
        this.running = true;
        DashLog.i(TAG, "start 目标=" + pkg + " gen=" + myGen);

        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            ctx.registerReceiver(leavingReceiver, f);
            DashLog.i(TAG, "已注册 HOME/熄屏 监听（按下即暂停，不等轮询）");
        } catch (Throwable t) {
            DashLog.w(TAG, "注册离开广播失败（不影响推流，仅降级为轮询）", t);
        }

        worker = new Thread(() -> loop(myGen), "mirror-loop");
        worker.start();
        sActive = this;
        setCastingFlag(true);
        /*
         * ══════════════════════════════════════════════════════════════
         * **必须同步把 LayerConfig 的 casting 标志也置 true**（2026-11-05）
         * ══════════════════════════════════════════════════════════════
         *
         * setCastingFlag() 只写 prefs（给进程重建后恢复用），
         * 而 applyFully() 判断的 `sCasting` 是 LayerConfig 的**静态字段**，
         * 只有 LayerConfig.setCasting() 才会变。
         *
         * 原来 start() 只调了 setCastingFlag(true)，漏了 setCasting(true)：
         *   · 第一次投屏：loop() 拉起 dcapd2 后会调 setCasting(true)，没事
         *   · 用户点「停止投屏」→ stop() 里 setCasting(false)
         *   · 再点「开始投屏」→ start() **没有把它置回 true**
         *     → 之后所有 applyFully() 都打印
         *        「图层矩形**跳过**：cp=true casting=false」
         *     → CP 永远收不到新的 destination region
         *
         * 日志实证（dashcast(2).log）：
         *   17:47:52.924 图层矩形**跳过**：cp=true casting=false w=560 h=400
         *   17:51:00.055 图层矩形**跳过**：cp=true casting=false w=560 h=400
         * 这就是「切 H.264 不按遮罩位置投屏、要点一次保存并应用才刷新」
         * 的真因 —— 点保存时这里其实也跳过了，只是 8 秒保活偶尔补上，
         * 表现得像"再点一次就好了"，实际一直在漏。
         */
        LayerConfig.setCasting(true);
    }

    /**
     * 停止投屏。
     *
     * **状态先落地，重活挪后台**：原来同步 join(1500) 等 worker 退出，
     * 而且是从主线程（按钮）调进来的 —— 用户点了停止，界面卡 1.5 秒才响应，
     * 看上去"没反应"，于是再点一次。
     */
    public void stop() {
        final int myGen = gen.incrementAndGet();   // 立刻作废旧代次，旧循环下次判断就 return
        setCastingFlag(false);
        LayerConfig.setCasting(false);
        if (sActive == this) sActive = null;
        running = false;
        bgMode = false;

        try {
            ctx.unregisterReceiver(leavingReceiver);
        } catch (Throwable ignored) {
        }

        final Thread t = worker;
        worker = null;

        new Thread(() -> {
            if (t != null) {
                try {
                    t.join(2000);
                } catch (InterruptedException ignored) {
                }
            }
            /*
             * **清理前必须复核代次**。
             *
             * start() 顶部就是无条件的 stop()。如果在这 2 秒 join 期间用户
             * 切了目标（或用持久化恢复又 start 了一次），新的 loop 已经
             * launchDaemon 起来了 —— 旧清理线程一路跑下去 pkill -9 dcapd2，
             * 就把**新会话**的抓帧进程杀掉：dashcastd 收 EOF 自己退出、
             * layer 99 被拆，仪表黑屏/回原厂。
             *
             * 判据：gen 还是我们那次递增的值 = 期间没人再 start/stop。
             */
            if (gen.get() != myGen) {
                DashLog.i(TAG, "停止流程已被新会话取代（gen " + myGen + " → "
                        + gen.get() + "），跳过清理");
                return;
            }
            try {
                vd.stop(true);
            } catch (Throwable ignored) {
            }
            VdMirror.setFeedFrames(false);
            forceCleanup(myGen);
        }, "mirror-stop").start();
    }

    /**
     * 强制清理：摘后台标志 + 杀 dcapd2，每一步都验证结果，不行就重试。
     *
     * @param myGen 发起清理时的那一代。每轮 pkill 前后都要复核 ——
     *              若期间有新会话 start 了，立刻收手，不能再杀。
     */
    private void forceCleanup(int myGen) {
        Thread t = new Thread(() -> {
            synchronized (cleanupLock) {
                if (gen.get() != myGen) {
                    DashLog.i(TAG, "清理开始时代次已变，放弃");
                    return;
                }
                boolean hadDaemon = daemonAlive();
                int logLines = (hadDaemon && cp != null) ? cp.dashcastdLogLines() : -1;

                for (int i = 0; i < 4; i++) {
                    if (gen.get() != myGen) {
                        DashLog.i(TAG, "清理中途代次变化，停止 pkill（新会话已在跑）");
                        return;
                    }
                    try {
                        String out = AdbShell.exec(
                                "rm -f /data/local/tmp/dcapd2.bg /data/local/tmp/dcapd2.pause; "
                                + "pkill -9 -x dcapd2; sleep 0.4; "
                                + "pidof dcapd2 > /dev/null 2>&1 || echo KILLED; "
                                + "ls /data/local/tmp/dcapd2.bg >/dev/null 2>&1 || echo BGCLEAN",
                                6000);
                        if (out != null && out.contains("KILLED") && out.contains("BGCLEAN")) {
                            DashLog.i(TAG, "清理确认完成（第 " + (i + 1) + " 次）");
                            if (hadDaemon && logLines >= 0) {
                                if (cp.verifySessionEnded(logLines, 3000)) {
                                    DashLog.i(TAG, "dashcastd 已确认会话结束，图层已还原");
                                } else {
                                    DashLog.e(TAG, "dashcastd 未报告会话结束 —— 图层可能仍在，"
                                            + "但不再阻塞（restoreDashboard 会再兜一次）");
                                }
                            }
                            return;
                        }
                        DashLog.w(TAG, "清理未确认（第 " + (i + 1) + " 次）: "
                                + (out == null ? "null" : out.trim().replace('\n', ' ')));
                    } catch (Throwable te) {
                        DashLog.w(TAG, "清理命令失败（第 " + (i + 1) + " 次）: " + te);
                    }
                    sleep(400);
                }
                DashLog.e(TAG, "清理重试 4 次仍未确认，dcapd2 可能还在按旧目标推流");
            }
        }, "mirror-cleanup");
        t.start();
        try {
            t.join(1500);
        } catch (InterruptedException ignored) {
        }
    }

    // ============================== 后台跟随 ==============================

    /**
     * 目标 App 离开前台 → 起虚拟屏，把它搬进去继续跟随。
     *
     * 虚拟屏的 dp 尺寸必须和主屏窗口一致（见 VdMirror.start 的注释），
     * 否则 App 进后台会按小屏重新排版，画面整体放大一倍。
     */
    public synchronized void enterBg(int myGen) {
        if (gen.get() != myGen) return;
        if (bgMode || !running || component == null) return;

        bgMode = true;
        final String comp = component;
        /*
         * **清零虚拟屏无输出计时**（2026-11-05）。
         *
         * 上一轮判死时已清零，但"上一轮是被别的路径退出的"
         * （比如目标回前台 exitBg、或 stop）时不会清 ——
         * 那样新一轮的起始时刻还是旧的，一进来就可能立刻判死。
         * 每次进后台跟随都是全新的观察窗口，必须从这里重新计时。
         */
        vdDeadSince = 0;
        state("回桌面 —— App 转后台，仪表继续实时跟随", true);
        DashLog.i(TAG, "进入后台跟随模式 gen=" + myGen);

        /*
         * ══════════════════════════════════════════════════════════════
         * **第一件事：立刻停抓主屏**（2026-11-05 修复"回 HOME 漏桌面"）
         * ══════════════════════════════════════════════════════════════
         *
         * 原来 DCBG 是在 vd.start() 里面才发的，而 enterBg 前面还有
         * 一堆准备工作（读 prefs、算屏幕尺寸、设截取框）。
         * 日志实证：
         *   12:48:42.282  enterBg 开始
         *   12:48:42.758  第一帧 VD 画面（476ms 后）
         *
         * **那 476ms 里 dcapd2 还在抓主屏 = 抓的是桌面** ——
         * 用户看到的就是"按 HOME 仪表闪一下桌面"。
         *
         * 修法：进门就发，建连 + 4 字节 write 是微秒级，
         * 之后再做那堆准备工作，dcapd2 全程不碰主屏。
         */
        boolean dcbgOk = vd.sendDcbgNow();
        if (!dcbgOk) {
            DashLog.w(TAG, "DCBG 未发出 —— 仪表可能多闪几帧桌面");
        }

        if (gen.get() != myGen) return;

        /*
         * ══════════════════════════════════════════════════════════════
         * **虚拟屏尺寸必须用「整屏」，不能用遮罩尺寸**（2026-11-03 修复）
         * ══════════════════════════════════════════════════════════════
         *
         * 原来取 lastRect[2]/[3]，而 lastRect 现在可能是：
         *   · 用户框选的 App 截取区（比如 2560×544）
         *   · 地图模式的遮罩换算区（比如 1440×400）
         * 都不是整屏。
         *
         * 后果（用户反馈"回桌面后截取失效"）：
         *   虚拟屏按 2560×544 建 → App 被塞进一块很矮的屏里
         *   → App 按 544 的高度重新排版（列表压缩、按钮挤在一起）
         *   → 抓出来的画面和"前台时的样子"完全不同
         *   → 用户以为"截取失效了"
         *
         * 必须用真实屏幕尺寸（2560×1600），App 才会保持原有布局。
         * 我们只是换个地方渲染它，不是把它压扁。
         */
        int[] scr = getScreenPx();
        int srcW = scr[0] > 0 ? scr[0] : 2560;
        int srcH = scr[1] > 0 ? scr[1] : 1600;
        int mainDpi = ctx.getResources().getDisplayMetrics().densityDpi;

        VdMirror.setProxyWanted(true);   /* 挂控制通道（按 HOME 停抓用） */
        VdMirror.setFeedFrames(true);    /* 虚拟屏模式：允许喂帧 */

        /*
         * **把「截取 App 页面」的框也带进后台**（2026-11-03 修复）。
         *
         * 用户报：设好截取框，App 在前台时正常，一按 HOME 回桌面就失效。
         * 根因是 VD 分支整块 blit，完全不认截取框。
         * 这里拿到截取框 + 目标窗口矩形交给 VdMirror，它换算成虚拟屏坐标
         * 后用 'DCVP' 命令下发给 dcapd2。
         */
        try {
            LayerConfig lc = LayerConfig.loadFromPrefs(ctx, getOutputW(), getOutputH());
            if (lc != null && lc.srcW > 0 && lc.srcH > 0) {
                int[] crop = {lc.srcX, lc.srcY, lc.srcW, lc.srcH};
                vd.setCropRect(crop, lastRect);
            } else {
                vd.setCropRect(null, null);
            }
        } catch (Throwable t) {
            DashLog.w(TAG, "读取截取框失败（后台跟随将取整块）: " + t);
        }

        /*
         * vd.start() 里是 am display move-stack / am start --display，
         * 单次 6~8 秒。这期间用户完全可能点「停止投屏」：
         * stop() 已经 vd.stop(true) 拆了屏、setFeedFrames(false) 断了帧源，
         * 而我们这里还在把目标 App 往一块没人喂帧的虚拟屏上搬 ——
         * 结果就是中控上 App 直接消失、仪表停在最后一帧。
         * 所以搬完必须复核，代次变了立刻把刚搬出去的收回来。
         */
        boolean started = vd.start(comp, srcW, srcH, mainDpi, this::state);

        if (gen.get() != myGen) {
            DashLog.i(TAG, "enterBg 期间代次变化（已停止），收回虚拟屏");
            try {
                vd.stop(true);
            } catch (Throwable ignored) {
            }
            VdMirror.setFeedFrames(false);
            try {
                AdbShell.exec("rm -f /data/local/tmp/dcapd2.bg", CHUNK);
            } catch (Throwable ignored) {
            }
            return;
        }
        /*
         * **还要复核 bgMode**（2026-11-05）。
         *
         * vd.start() 里是 am display move-stack，单次 6~8 秒。
         * 这期间目标 App 完全可能自己回到前台 → loop 的另一处调 exitBg()
         * 把 bgMode 置 false、把虚拟屏拆了、断了帧源。
         * 如果这里不检查，紧接着就会去 touch dcapd2.bg（进入"跟随模式"
         * 但屏已经没了），dcapd2 从此既不抓主屏、也没帧可收
         * → 表现为"后台跟随失败（虚拟屏无输出）"。
         *
         * gen 只能捕捉 start/stop（新会话），捕捉不到"同会话内切回前台"，
         * 所以必须单独看 bgMode。
         */
        if (!bgMode) {
            DashLog.i(TAG, "enterBg 期间已退出后台跟随（目标回前台），放弃本次搬屏结果");
            try {
                vd.stop(true);
            } catch (Throwable ignored) {
            }
            VdMirror.setFeedFrames(false);
            try {
                AdbShell.exec("rm -f /data/local/tmp/dcapd2.bg", CHUNK);
            } catch (Throwable ignored) {
            }
            return;
        }

        if (!started) {
            setPause();                 // 先冻住，再断帧源，中间不漏桌面
            vd.stop(false);
            try {
                AdbShell.exec("rm -f /data/local/tmp/dcapd2.bg", CHUNK);
            } catch (Throwable ignored) {
            }
            /*
             * ══════════════════════════════════════════════════════════
             * **失败提示要说清原因**（2026-11-05）
             * ══════════════════════════════════════════════════════════
             *
             * 原来的文案「后台跟随不可用 —— 仪表停在最后一帧」
             * 只说了"结果"，没说"为什么"和"怎么办"。
             *
             * 实测最常见的原因：用户选了一个**没有可投屏界面**的系统服务
             * （比如「智能泊车辅助」com.ecarx.apa），am start 起不来，
             * 虚拟屏自然也建不成。
             *
             * 这类情况用户完全无从判断 —— 界面上什么都没错，
             * 就是投不出来。所以要明确告诉他"换一个 App 试试"。
             */
            String tgt = (pkg == null) ? "该 App" : pkg;
            state("状态：投屏失败 —— " + tgt + " 起不到前台"
                    + "（可能是系统服务类 App，没有可投屏的界面）"
                    + "。请换一个 App 重试", true);
        } else {
            /*
             * ══════════════════════════════════════════════════════════
             * **虚拟屏起来了，再补发一次截取框**（2026-11-05 修）
             * ══════════════════════════════════════════════════════════
             *
             * 上面 setCropRect() 那次调用时虚拟屏还没建、vdW/vdH 还是
             * 上一次的旧值（甚至是初值 1280×720），换算出来的裁剪框必然是错的
             * —— 它内部会退化成"只记下来、先不发"（sPendingCrop）。
             *
             * vd.start() 里 armed=true 之后确实会自动补发一次，
             * 但那是**另一条线程**、且紧接着就要开始喂帧，
             * 时序上不保证 dcapd2 在收到第一帧之前就拿到正确的裁剪框。
             *
             * 这里显式再发一次，确保「裁剪框先到、帧后到」——
             * 否则头几帧会按默认整块渲染，用户看到画面"先大后小"跳一下。
             */
            republishVdCrop();
            try {
                AdbShell.exec("touch /data/local/tmp/dcapd2.bg", CHUNK);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 目标回前台 → 断帧源（dcapd2 自动恢复主屏抓帧），摘掉后台标志。
     *
     * **必须和 enterBg 用同一把锁**（2026-11-05）。
     *
     * enterBg 是 synchronized，原来 exitBg 不是 —— 于是两者可以交错：
     *   · 线程 A（loop）正在 enterBg 里建虚拟屏、setFeedFrames(true)
     *   · 线程 B（readStats 判死路径）调 exitBg：bgMode=false、
     *     vd.stop(true) 把刚建好的屏拆了、setFeedFrames(false)
     *   · A 接着往下跑：把 App 搬进一块已经被拆掉的虚拟屏
     * → 日志里「App 已转后台（虚拟屏 N）」紧跟「退出后台跟随模式」，
     *   N 已经不存在了，后续当然"无帧"。
     *
     * 加 synchronized 后两者互斥；锁的是 this，与 enterBg 一致，
     * 且两个方法内部都没有会再拿这把锁的调用（vd.* 是另一个对象），
     * 不会自锁。
     */
    private void exitBg() {
        if (!bgMode) return;
        bgMode = false;
        /* 退出跟随 = 上一轮观察窗口作废，下次 enterBg 会重新计时 */
        vdDeadSince = 0;
        DashLog.i(TAG, "退出后台跟随模式");
        vd.stop(true);
        VdMirror.setFeedFrames(false);
        try {
            AdbShell.exec("rm -f /data/local/tmp/dcapd2.bg", CHUNK);
        } catch (Throwable ignored) {
        }
        /*
         * ══════════════════════════════════════════════════════════════
         * **回主屏模式必须把控制通道重新挂上**（2026-11-05 修）
         * ══════════════════════════════════════════════════════════════
         *
         * setFeedFrames(false) 内部会把 sProxyWanted 一起置 false，
         * 于是紧接着这行 `vd.hold()` 的 ensureConnected() 直接 return false ——
         * **控制通道根本没连上**，用户以为挂好了，其实没有。
         *
         * 后果：回到前台之后改「截取 App 页面」或「按比例框选」，
         * 'DCSR' 下发失败（"控制通道未建立"）→ 只能 pkill 重启 dcapd2，
         * 也就是日志里那些"配置已热更新"时有时无的情况。
         *
         * 修：先显式允许建连，再 hold()。
         */
        VdMirror.setProxyWanted(true);
        boolean held = vd.hold();
        DashLog.i(TAG, "回主屏模式，控制通道" + (held ? "已重新挂上" : "挂接失败（热更新不可用）"));
    }

    private void setCastingFlag(boolean on) {
        try {
            prefs(ctx).edit().putBoolean("casting_active", on).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 会话结束：清理 + 通知界面复位（目标 App 退出时走这里）。 */
    private void endSession(final String reason) {
        final Listener l = listener;
        DashLog.i(TAG, "结束投屏会话：" + reason);
        new Thread(() -> {
            try {
                stop();
            } catch (Throwable t) {
                DashLog.w(TAG, "结束会话时清理异常", t);
            }
            /*
             * **必须停 KeepAliveService**。
             *
             * 它只在「开始投屏」时启动（前台服务 + 通知 + START_STICKY）。
             * 目标 App 自己退出时走的是 endSession 这条路，而不是界面上的
             * 「停止投屏」按钮 —— 原来只 stop() 镜像，前台服务和那条常驻
             * 通知永远留着，而且进程被系统杀掉后 START_STICKY 还会再拉起来，
             * 在用户看来就是"已经没在投屏了，通知却还在，App 也杀不干净"。
             */
            try {
                KeepAliveService.stop(ctx);
            } catch (Throwable ignored) {
            }
            if (l != null) {
                try {
                    l.onEnded(reason);
                } catch (Throwable ignored) {
                }
            }
        }, "mirror-end").start();
    }

    // ============================== 主循环 ==============================

    /**
     * 投屏主循环。由 start() 起一个线程跑，每次目标切换都递增 gen ——
     * 循环里所有耗时操作之后都要检查 gen，代次变了立刻退场，
     * 否则旧线程会把新目标的 dcapd2 杀掉、或者把新状态覆盖掉。
     */
    private void loop(int myGen) {
        long lastStats = 0, lastFgCheck = 0, lastAliveCheck = 0;
        /*
         * **启动宽限期**（2026-11-05 修复"投屏启动即失败"）。
         *
         * 现象：点「开始投屏」→ 日志「正在打开目标 App」→ 1 秒后
         *      「回桌面 —— App 转后台」→「后台跟随不可用」。
         *
         * 根因：am start 发出后 App 需要 2~3 秒才真正到前台，
         *      而 loop 每 800ms 检查一次前台包名，第一次读到的是桌面，
         *      立刻判"目标离开前台"→ enterBg 建虚拟屏 → 失败。
         *
         * 修法：start() 记下启动时刻，loop 在前 6 秒内**不做离开判定**，
         *      只等 App 上来。如果 6 秒后还没上来，才认为是真的失败。
         */
        /*
         * ══════════════════════════════════════════════════════════════
         * **宽限期不能是"循环开始后 6 秒"**（2026-11-05 修复）
         * ══════════════════════════════════════════════════════════════
         *
         * 原来写成 `long startGraceAt = 循环开始时刻`，配 `LAUNCH_GRACE_MS=6000`。
         * 循环是长期运行的（一次投屏几分钟到几小时），
         * 所以**一旦跑过 6 秒，inGrace 就永久为 false**，
         * 之后每 800ms 的前台检查只要读到"不是目标 App"就立刻 enterBg。
         *
         * 实际后果：App 起不来 / 用户切到别的 App / 系统弹窗抢焦点时，
         * 会疯狂 enterBg → 新建虚拟屏 → 无帧 → exitBg 的循环。
         * 日志里 displayId 从 11 一路涨到 16、`启动 dashcastd` 七次，
         * 就是这个机制在反复触发。
         *
         * 修法：宽限期只在**「刚把目标拉到前台」那一刻**有意义，
         * 所以改成可变字段，每次 am start 之后都刷新成"现在"。
         * 循环空闲时它会自然变旧（>6 秒），这才是正确语义：
         * 「刚拉起 → 给它 6 秒上台；跑久了 → 该检查就检查」。
         */
        long[] graceAt = { SystemClock.elapsedRealtime() };
        final long LAUNCH_GRACE_MS = 6000;
        long lastCpAliveCheck = 0, lastRectSync = 0, lastHold = 0;
        int failCount = 0, deadChecks = 0;
        boolean seenTargetAlive = false, broughtForward = false;

        try {
            while (true) {
                if (gen.get() != myGen) return;
                if (!running) return;
                long now = SystemClock.elapsedRealtime();

                /*
                 * ① 首次进入：目标 App 必然在后台（用户是在我们界面点的按钮），
                 *    主动拉到前台，否则会一直停在"等目标 App 到前台"。
                 *
                 * **HUD 模式绝不能做这一步**（2026-11-03 实测踩到）。
                 *
                 * 第 588 行 `am start --display 0 -n <component>` 会：
                 *   · 把 component 拉到**主屏（Display 0）前台**
                 *   · HUD 模式下 component 是 com.ecarx.hud/...，
                 *     所以每次点「地图 HUD」都把 HUD 拽到主屏、抢走焦点 ——
                 *     用户看到的就是"点几次地图 HUD 就跳转到别的 App"
                 *   · 更糟的是 HUD 会从 Display 1 移到 Display 0/2，
                 *     dcapd2 抓 Display 1 就抓空了
                 *
                 * HUD 的画面本来就在 Display 1 上由系统维持，
                 * 我们抓的是那块屏的像素，完全不需要它在前台。
                 */
                boolean hudMode = isMapHudActive();
                if (!broughtForward && !hudMode) {
                    broughtForward = true;
                    state("正在打开目标 App…", false);
                    try {
                        AdbShell.exec("am start --display 0 -n " + component, 6000);
                    } catch (Throwable ignored) {
                    }
                    graceAt[0] = SystemClock.elapsedRealtime();   /* 刚拉起，重新计时 */
                    sleep(1000);
                    if (gen.get() != myGen) return;
                } else if (hudMode) {
                    broughtForward = true;   /* 标记已完成，避免重复判断 */
                }

                /*
                 * ② 前台判定：800ms 一次。dumpsys window 约 60ms，占不到单核 10%。
                 *
                 * **HUD 模式整段跳过**（2026-11-02 实测踩到）。
                 *
                 * 这套逻辑是给"App 投屏"设计的：目标 App 被切到后台时，
                 * 把它搬进虚拟屏继续跟随，免得仪表停在最后一帧。
                 *
                 * 但 HUD 模式的取景源是 Display 1 上的 com.ecarx.hud ——
                 * 它**永远不在主屏前台**（主屏前台是桌面或别的 App）。
                 * 于是 foregroundPkg() 读到的永远是桌面，
                 * fgIsTarget 永远 false → 每次进来都触发 enterBg()：
                 *   · 日志「回桌面 —— App 转后台」
                 *   · 去建虚拟屏、搬一个不存在的 App 上去 → 失败
                 *   · 日志「后台跟随不可用 —— 仪表停在最后一帧」
                 *   · 连带把 HUD 的画面挤掉
                 *
                 * HUD 画面本来就在固定的 Display 1 上，不需要"跟随前台"。
                 */
                boolean hud = isMapHudActive();

                /*
                 * **HUD 模式必须在这里自己拉 dcapd2**（2026-11-02 关键修复）。
                 *
                 * 下面第 ⑧ 段（`fgIsTarget && !daemonAlive()`）是唯一的
                 * 正常起 dcapd2 的入口 —— 但它要求 **目标 App 在前台**。
                 * HUD 模式下我们把 fgIsTarget 强制设成 false（HUD 永远不在
                 * 主屏前台），于是那段永远不会执行 → dcapd2 要么根本没起，
                 * 要么是上一次会话残留的旧进程（参数还是 App 模式的
                 * 2560x1600 / 740,924）。
                 *
                 * 实测现象：点「地图 HUD」日志全对（一键开投、正在投 HUD），
                 * 但 dcapd2 抓的还是主屏 2560x1600 —— 因为压根没重启过它。
                 *
                 * 所以 HUD 模式在这里直接拉起：只要 dcapd2 不在、或它在跑
                 * 但参数不是 HUD 的，就重起一次。
                 */
                if (hud) {
                    if (!daemonAlive()) {
                        if (!ensureBinary()) {
                            state("抓帧进程部署失败（检查 assets/dcapd2.b64）", false);
                            sleep(2000);
                            continue;
                        }
                        /*
                         * HUD 模式没有"窗口矩形"（那是 App 投屏才有的概念），
                         * lastRect 可能是 null。但 launchDaemon 的签名要一个
                         * int[]，而且它在 HUD 分支里会**整体覆盖** s 和 cap ——
                         * 传进去的值完全不参与计算。
                         *
                         * 传一个整屏矩形当占位，避免 null 被解引用
                         * （实测踩到 "Attempt to read from null array"）。
                         */
                        int[] rect = lastRect != null
                                ? lastRect
                                : new int[]{0, 0, HUD_W, HUD_H};
                        if (launchDaemon(rect)) {
                            if (gen.get() != myGen) return;
                            failCount = 0;
                            vd.hold();
                            LayerConfig.setCasting(true);
                            syncLayerRectAsync();
                            state("地图 HUD 投屏运行中（Display "
                                    + HUD_DISPLAY_ID + " → CP）", true);
                        } else if (++failCount >= 2) {
                            state("拉起 dcapd2 失败，稍后重试", false);
                            sleep(1500);
                        }
                    }
                    /* HUD 模式到此为止：不做前台判定、不进后台跟随 */
                }

                boolean fgIsTarget = false;
                /*
                 * 启动宽限期：前 6 秒不做"离开前台"判定。
                 *
                 * am start 发出后 App 要 2~3 秒才到前台，
                 * 这期间 foregroundPkg() 读到的是桌面 ——
                 * 不能据此判定"目标已转后台"，否则会立刻进虚拟屏跟随并失败。
                 */
                boolean inGrace = (now - graceAt[0]) < LAUNCH_GRACE_MS;
                /*
                 * **800 → 1500ms**（2026-11-05 降频）。
                 *
                 * 这里每次都要发起一次 ADB 连接跑 `dumpsys window displays`，
                 * 而 AdbShell.exec 是「一次调用一条 TCP」：connect → CNXN 协商
                 * → OPEN → 收数据 → close。命令本身几毫秒，付的是协议开销。
                 *
                 * 前后台切换的判定不需要 800ms 精度 —— App 切走之后
                 * 晚 700ms 才发现，用户完全感知不到；但 ADB 连接数
                 * 从 1.25 次/秒降到 0.67 次/秒，省了近一半。
                 *
                 * 和 readStats 一起，把稳态连接数从约 4.25 次/秒
                 * 压到约 2 次/秒（见 readStats 里对应的注释）。
                 */
                if (!hud && now - lastFgCheck >= 1500) {
                    lastFgCheck = now;
                    String fg = foregroundPkg();
                    fgIsTarget = pkg != null && pkg.equals(fg);
                    if (fgIsTarget) {
                        if (bgMode) exitBg();      /* 目标回前台 → 退出虚拟屏跟随 */
                        seenTargetAlive = true;
                    } else if (!bgMode && !inGrace) {
                        /*
                         * **冷却期内不重试 enterBg**（2026-11-05 修复死循环）。
                         *
                         * 否则会形成：
                         *   enterBg → VD 无帧 → exitBg → enterBg → …
                         * 日志里「虚拟屏 15/16/17/18...」不断新建。
                         */
                        if (SystemClock.elapsedRealtime() >= vdFailCooldownUntil) {
                            enterBg(myGen);
                            if (gen.get() != myGen) return;
                        }
                    }
                }

                /*
                 * ③ **2 秒一次，一次 ADB 连接搞定两件事**（2026-11-05）
                 *
                 * 原来是 `if (daemonAlive()) readStats();` —— 两次独立连接
                 * （`pidof dcapd2` + `tail -3 dcapd2.log`）。
                 * 现在合并成 readStatsAndAlive()，只付一次握手，
                 * 而且两个结果天然同一时刻，不会读错行。
                 *
                 * 降频到 2 秒的依据：dcapd2 自己每秒才打一行 STAT，
                 * 1 秒采样本来就在读同一行（甚至上一行）。2 秒一定拿到新行。
                 */
                if (now - lastStats >= 2000) {
                    lastStats = now;
                    readStatsAndAlive();
                }

                /* ④ CP 侧 dashcastd 探活（5 秒一次，或矩形连败 3 次立即触发）。
                 *    原来 30 秒一次 —— 切编码档后 dashcastd 会自己退出，
                 *    30 秒意味着仪表要黑几十秒。 */
                if (now - lastCpAliveCheck >= 5000 || rectFailCount >= 3) {
                    lastCpAliveCheck = now;
                    if (gen.get() != myGen) return;
                    if (!cp.isDashcastdRunning()) {
                        DashLog.w(TAG, "CP 侧 dashcastd 不在运行，尝试恢复…");
                        if (cp.ensureDashcastd() && cp.startDashcastd()) {
                            DashLog.i(TAG, "CP 侧 dashcastd 已恢复，重置图层同步退避");
                            resetRectBackoff();
                            lastRectSync = 0;
                            forceFullFrame();    /* CP 是新会话，整屏补一帧 */
                        } else {
                            DashLog.e(TAG, "CP 侧 dashcastd 恢复失败（CP 不可达或文件推不上）");
                        }
                    } else if (rectFailCount >= 3) {
                        /* 进程活着但矩形一直设不上：多半是 layer 99 被重建了，
                         * 强制重下一次即可，不重启进程。 */
                        resetRectBackoff();
                        lastRectSync = 0;
                    }
                }

                /* ⑤ 图层矩形保活：dashcastd 每次会话都重建 layer 99，
                 *    destination region 随层消失，每 8 秒重下发一次。 */
                if (now - lastRectSync >= 8000) {
                    lastRectSync = now;
                    if (gen.get() != myGen) return;
                    syncLayerRectAsync();
                }

                /* ⑥ 主屏模式预挂控制通道（按 HOME 时零延迟停抓） */
                if (fgIsTarget && !bgMode && now - lastHold >= 5000) {
                    lastHold = now;
                    if (!vd.isHeld()) {
                        if (gen.get() != myGen) return;
                        vd.hold();
                    }
                }

                /* ⑦ 目标 App 存活检查：连查两次才判死（搬屏瞬间查不到是正常的） */
                if (now - lastAliveCheck >= 2000) {
                    lastAliveCheck = now;
                    if (targetAlive()) {
                        seenTargetAlive = true;
                        deadChecks = 0;
                    } else if (seenTargetAlive && ++deadChecks >= 2) {
                        DashLog.i(TAG, "目标 App 已退出，结束投屏会话");
                        endSession("目标 App 已退出 —— 投屏已结束，仪表已还原");
                        return;
                    }
                }

                /* ⑧ dcapd2 不在就拉起来 */
                if (bgMode) {
                    if (!daemonAlive() && lastRect != null) {
                        /* 第一次运行、或刷过 APK 后 dcapd2 的 MD5 变了：
                         * 这里必须先确保二进制在位并把新的推上去，
                         * 否则 launchDaemon 会一直起不来（文件不存在）。 */
                        if (!ensureBinary()) {
                            state("抓帧进程部署失败（检查 assets/dcapd2.b64）", false);
                            sleep(2000);
                            continue;
                        }
                        if (launchDaemon(lastRect)) {
                            if (gen.get() != myGen) return;
                            failCount = 0;
                            state("后台跟随中 · " + pkg + " → 仪表", true);
                        } else if (++failCount >= 2) {
                            state("拉起 dcapd2 失败，稍后重试", false);
                            sleep(1500);
                        }
                    }
                } else if (fgIsTarget && !daemonAlive()) {
                    int[] r = windowRect(pkg);
                    if (r != null) {
                        lastRect = r;
                        if (!ensureBinary()) {
                            state("抓帧进程部署失败（检查 assets/dcapd2.b64）", false);
                            sleep(2000);
                            continue;
                        }
                        if (launchDaemon(r)) {
                            if (gen.get() != myGen) return;   // launchDaemon 里等了好几秒
                            clearPause();
                            failCount = 0;
                            vd.hold();
                            LayerConfig.setCasting(true);
                            syncLayerRectAsync();
                            state("投屏运行中 · " + pkg + " → 仪表（中控可正常操作）", true);
                        } else if (++failCount >= 2) {
                            state("拉起 dcapd2 失败，稍后重试", false);
                            sleep(1500);
                        }
                    } else {
                        state("读不到 " + pkg + " 的窗口矩形", false);
                        sleep(1000);
                    }
                }

                sleep(300);
            }
        } catch (Throwable t) {
            DashLog.e(TAG, "mirror 循环异常", t);
            if (gen.get() == myGen) {
                state("投屏异常结束: " + t.getMessage(), false);
            }
        }
    }

    // ============================== 部署与启动 ==============================

    /** 确认 /data/local/tmp/dcapd2 是最新版本，不是就推一遍。 */
    private boolean ensureBinary() {
        try {
            String out = AdbShell.exec("md5sum " + BIN + " 2>/dev/null", CHUNK);
            if (out != null && out.contains(BIN_MD5)) {
                DashLog.i(TAG, "dcapd2 已是最新");
                return true;
            }

            String b64 = readAsset("dcapd2.b64");
            if (b64 == null || b64.isEmpty()) {
                DashLog.e(TAG, "assets 里没有 dcapd2.b64");
                return false;
            }

            DashLog.i(TAG, "推送 dcapd2（" + b64.length() + " 字符）");
            AdbShell.exec("rm -f " + BIN + ".b64", CHUNK);

            int chunks = (b64.length() + CHUNK - 1) / CHUNK;
            int done = 0;
            for (int i = 0; i < b64.length(); i += CHUNK) {
                String part = b64.substring(i, Math.min(b64.length(), i + CHUNK));
                AdbShell.exec("printf '%s' '" + part + "' >> " + BIN + ".b64", 8000);
                done++;
                if (done == 1 || done % 5 == 0 || done == chunks) {
                    state("首次使用：正在推送抓帧进程 " + done + "/" + chunks
                            + "（只需一次，之后直接跳过）", false);
                }
            }

            AdbShell.exec("base64 -d " + BIN + ".b64 > " + BIN
                    + " && chmod 755 " + BIN + " && rm -f " + BIN + ".b64", 8000);

            String chk = AdbShell.exec("md5sum " + BIN, CHUNK);
            boolean ok = chk != null && chk.contains(BIN_MD5);
            DashLog.i(TAG, ok ? "dcapd2 落盘校验通过" : "dcapd2 落盘校验失败: " + chk);
            return ok;
        } catch (Throwable t) {
            DashLog.e(TAG, "部署失败: " + t);
            return false;
        }
    }

    private String readAsset(String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "US-ASCII");
        } catch (Throwable t) {
            DashLog.e(TAG, "读 asset 失败: " + name, t);
            return null;
        }
    }

    /**
     * 拉起 dcapd2。
     *
     * 地图模式下先按遮罩把窗口矩形裁成"用户框的那块"，
     * 其余情况原样传窗口矩形。
     */
    private boolean launchDaemon(int[] r) {
        synchronized (cleanupLock) {
            int[] s = screenSize();

            /*
             * r 可能是 null —— HUD 模式没有"窗口矩形"可言。
             * 下面 HUD 分支会整体覆盖 s/cap，所以这里给个占位即可；
             * 但 applyMapCrop(r) 会解引用 r，必须先兜住。
             */
            int[] safeR = r != null ? r : new int[]{0, 0, getOutputW(), getOutputH()};
            int[] cap = applyMapCrop(safeR);

            /*
             * **地图 HUD 模式**（2026-11-02 新增）。
             *
             * 不走主屏抓取，改抓车机的 Display 1（HDMI 800×480）——
             * 那里是地图自己渲染的原生 HUD 画面（浅底、粗路线、居中车标）。
             *
             * 三个参数整体换掉：
             *   屏幕尺寸 → HUD 屏的 800×480
             *   抓取区   → 整块 HUD 屏（HUD 的关键信息就在中间一竖条，
             *              两侧空白由遮罩裁掉，这里先照全抓，靠遮罩定位）
             *   display  → 物理屏序号 1
             */
            boolean hud = isMapHudActive();
            /*
             * 诊断日志：HUD 模式判定失败时，一定要能看出是哪一条不满足。
             * 实测踩过：prefs 里 map_mode/map_hud 都是 true，但 dcapd2
             * 拿到的还是 App 模式参数 —— 因为判定发生在那两个字段
             * 生效之前的时机。这条日志能立刻定位。
             */
            DashLog.i(TAG, "launchDaemon 判定: isMapHudActive=" + hud
                    + " mapMode=" + mapMode
                    + " hudSession=" + sHudSession
                    + " ctx=" + (ctx != null));
            if (hud) {
                s    = new int[]{HUD_W, HUD_H};
                /*
                 * **裁 HUD 的中间信息条**（2026-11-02 实测标定）。
                 *
                 * HUD 是 800×480（比例 1.667），仪表是 1920×532（比例 3.609）。
                 * 整块照抓的话，等比缩放后 HUD 只能占中间 886×532，
                 * 两侧大片黑边，内容显得又小又远。
                 *
                 * 实测 HUD 画面里真正的信息都在中间那条黑色横条上：
                 *   档位「P」+ 车辆图标 + 转向箭头 +「剩24公里 22:36到」
                 *   横条边界：y=160 开始黑，y=348 结束（高 188，全宽 800）
                 *
                 * 取 y=140, h=220（信息条 + 上下各留一点绿线背景）：
                 *   比例 800/220 = 3.64 ≈ 仪表 3.609，误差 0.9%，肉眼无感
                 *   → 内容几乎铺满整屏，字和图标都放大了 2.4 倍
                 *
                 * 这两个数（HUD_SRC_Y / HUD_SRC_H）是量出来的，不是猜的。
                 * 换车型/换 HUD 版本时可能要重标。
                 */
                cap  = new int[]{HUD_SRC_X, HUD_SRC_Y, HUD_SRC_W, HUD_SRC_H};
                DashLog.i(TAG, "HUD 投屏：抓 Display " + HUD_DISPLAY_ID
                        + "（物理屏 #" + HUD_PHYSICAL_INDEX + "）"
                        + "源 " + HUD_W + "×" + HUD_H
                        + " → 裁 x=" + HUD_SRC_X + " y=" + HUD_SRC_Y
                        + " " + HUD_SRC_W + "×" + HUD_SRC_H);
            } else if (mapMode) {
                DashLog.i(TAG, "地图模式：源 " + safeR[2] + "×" + safeR[3]
                        + " → 抓 " + cap[2] + "×" + cap[3]
                        + " @ (" + cap[0] + "," + cap[1] + ")");
            }

            String cmd = "rm -f " + PAUSE + "; pkill -x dcapd2; "
                    + "cd /data/local/tmp && nohup ./dcapd2 "
                    + s[0] + " " + s[1] + " "
                    + cap[0] + " " + cap[1] + " " + cap[2] + " " + cap[3] + " "
                    + getOutputW() + " " + getOutputH() + " "
                    + DashcastSenderService.CP_HOST + " " + DashcastSenderService.CP_PORT
                    + " " + fps + " " + codec
                    + " " + (hud ? HUD_PHYSICAL_INDEX : -1)   /* argv[13]：抓哪块屏 */
                    + " > " + LOGF + " 2>&1 &";
            try {
                // 命令以 & 结尾，adbd 那边不会立刻关流，这里超时是正常现象
                AdbShell.exec(cmd, 2500);
            } catch (Throwable ignored) {
            }

            // 轮询到 3 秒：车机负载高时 dcapd2 起来要一两秒，单次判定会误报失败
            for (int i = 0; i < 8; i++) {
                sleep(400);
                if (daemonAlive()) {
                    DashLog.i(TAG, "dcapd2 已启动");
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 让 dcapd2 下一帧整屏全量发送。
     *
     * 用途：CP 重建了会话（layer 99 被重画），旧像素没了，增量永远补不齐。
     * 实现：写一个标记文件，dcapd2 每 500ms 检查一次就置 force_full。
     */
    private void forceFullFrame() {
        new Thread(() -> {
            try {
                AdbShell.exec("touch /data/local/tmp/dc_force", 2000);
                DashLog.i(TAG, "已请求整屏全量重画");
            } catch (Throwable ignored) {
            }
        }, "force-full").start();
    }

    // ============================== 图层矩形 ==============================

    private void resetRectBackoff() {
        rectFailCount = 0;
        rectNextAllowedMs = 0;
    }

    /** 把 ivi 图层同步到当前遮罩矩形。投屏中才做（未投屏 layer 99 不存在）。 */
    private void syncLayerRectAsync() {
        if (cp == null) return;
        if (!LayerConfig.isCasting()) return;
        if (SystemClock.elapsedRealtime() < rectNextAllowedMs) return;
        if (!rectInFlight.compareAndSet(false, true)) return;

        new Thread(() -> {
            boolean done = false;
            try {
                /*
                 * **关键：destination region 不能无脑用遮罩矩形**。
                 *
                 * 之前这里直接 currentMaskRect() → setLayerRect(遮罩 x/y/w/h)，
                 * 结果合成器把**整块 1920×532 画布压缩**到遮罩矩形里 ——
                 * 而 AP 端已经把画面画在画布的遮罩坐标上了，
                 * 于是仪表上出现"正常画面 + 缩小副本"叠在一起 = 重影。
                 *
                 * 正确的换算按编码档走（详见 LayerGeometry 的注释）：
                 *   BGRA：画布遮罩外已透明 → destination = 整屏，1:1 零缩放
                 *   H.264：无 alpha → destination = 遮罩矩形，图层本身缩进那块
                 */
                int[] r = LayerGeometry.destinationFor(codec, currentMaskRect(), getOutputW(), getOutputH());

                /* 保活每 8 秒一次，矩形没变就不必每次打两条日志。
                 * 原来每轮都打（Mirror 一条 + CpManager 一条），
                 * 十分钟能刷上百行，真信息全被淹了。 */
                String key = r[0] + "," + r[1] + " " + r[2] + "x" + r[3];
                boolean same = key.equals(sLastRectKey);

                for (int i = 0; i < 15; i++) {
                    if (!LayerConfig.isCasting()) return;
                    try {
                        if (cp.setLayerRect(r[0], r[1], r[2], r[3])) {
                            if (!same) {
                                sLastRectKey = key;
                                DashLog.i(TAG, "图层矩形已应用 → " + key);
                            }
                            /* 保活成功：完全静默。任何级别都会淹掉真信息 */
                            done = true;
                            return;
                        }
                    } catch (Throwable t) {
                        DashLog.w(TAG, "图层矩形同步异常（第 " + (i + 1) + " 次）: " + t);
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            } finally {
                rectInFlight.set(false);
                if (done) {
                    rectFailCount = 0;
                    rectNextAllowedMs = 0;
                } else {
                    /* 失败退避：1 次等 10 秒，2 次 20 秒，3 次 40 秒…上限 120 秒。
                     * 避免 8 秒保活反复触发无效重试。 */
                    if (rectFailCount < 10) rectFailCount++;
                    long back = Math.min(120000L, 10000L * (1L << Math.min(3, rectFailCount - 1)));
                    rectNextAllowedMs = SystemClock.elapsedRealtime() + back;
                    DashLog.w(TAG, "图层矩形同步失败，退避 " + (back / 1000) + " 秒后再试");
                }
            }
        }, "layer-rect").start();
    }

    /** 读当前遮罩矩形（从 prefs）；没有遮罩就返回整屏 */
    private int[] currentMaskRect() {
        try {
            LayerConfig lc = LayerConfig.loadFromPrefs(ctx, getOutputW(), getOutputH());
            if (lc != null && lc.enabled && lc.w > 0 && lc.h > 0) {
                return new int[]{lc.x, lc.y, lc.w, lc.h};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{0, 0, getOutputW(), getOutputH()};
    }

    // ============================== 地图模式 ==============================

    /**
     * 地图模式：**抓取区域 = 遮罩矩形**（不是按比例裁中段）。
     *
     * 场景：用户在地图上框一块（比如车道图），希望只抓这块、铺满仪表。
     * 遮罩是画布（1920×532）坐标，窗口是 2560×1600，所以要按比例换算。
     *
     * 换算：先把遮罩按原比例放进窗口（居中），再取遮罩百分比那一段。
     * 这样同一遮罩在任意窗口分辨率下都对到同一片区域。
     *
     * mapMode 关闭时原样返回 —— 普通 App 投屏行为不变。
     */
    private int[] applyMapCrop(int[] win) {
        /*
         * ⚠ 先处理「App 页面截取区」，**它不受 mapMode 约束**。
         *
         * 原来第一行就是 `if (!mapMode) return win;` —— 于是用户在
         * 「截取 App 页面」里设了 X/Y/W/H 却完全不生效：
         * 配置写进 dcapd2.layer 了（src_x=320 src_y=854 src_w=2560 src_h=533），
         * 但 dcapd2 拿到的还是旧的全屏 0,0 2560×1440。
         * 实测就是这个现象。
         *
         * 两套坐标的定位不同：
         *   地图模式的显式区（srcH 反推宽度）→ 只在地图模式有意义
         *   App 截取区（srcW 显式给全）      → 任何 App 投屏都该生效
         * 所以把 App 截取区提到 mapMode 判断之前。
         */
        LayerConfig lc0 = LayerConfig.loadFromPrefs(ctx, getOutputW(), getOutputH());
        /*
         * **先看总开关**（2026-11-03）。
         *
         * 「启用页面截取」默认关闭 —— 关着就完全不做裁切，抓整个窗口
         * 铺满遮罩，行为和没有这个功能时一致。
         *
         * 这样才满足"默认关闭（就是铺满遮罩尺寸）"：
         * 用户不主动开，就不会被框选参数干扰。
         */
        /*
         * ══════════════════════════════════════════════════════════════
         * **按「当前目标 App」取截取框，不是全局 layer_src_***
         * ══════════════════════════════════════════════════════════════
         *
         * 用户报："我截取某 App 页面保存后，下次启动该 App 会不会默认从
         * 配置文件读取、按配置截取？不想还得重新截。"
         *
         * 原来只读全局 layer_src_* —— 那是"最后一次操作的那个 App"的值。
         * 场景：先框抖音（存 app_rect_抖音 + 全局=抖音框），
         *       再框 B 站（存 app_rect_B站 + 全局=B站框），
         *       切回抖音投屏 → 读全局 = **B 站的框** → 抓错位置。
         *
         * 而 MainActivity 里 applyAppRect 早就按 App 记了
         * （app_rect_<pkg>.x/.y/.w/.h），只是这里没用上。
         *
         * 现在：有该 App 的专属记录就优先用它，没有才退回全局值。
         */
        LayerConfig lcApp = null;
        if (pkg != null && !pkg.isEmpty() && LayerConfig.hasAppRect(ctx, pkg)) {
            lcApp = LayerConfig.loadFromPrefs(ctx, getOutputW(), getOutputH());
            int[] ar = LayerConfig.loadAppRect(ctx, pkg);
            if (ar != null && ar[2] > 0 && ar[3] > 0) {
                lcApp.srcX = ar[0];
                lcApp.srcY = ar[1];
                lcApp.srcW = ar[2];
                lcApp.srcH = ar[3];
                DashLog.i(TAG, "按 App 历史尺寸抓取 → " + pkg + " "
                        + ar[2] + "×" + ar[3] + " @(" + ar[0] + "," + ar[1] + ")");
            } else {
                lcApp = null;
            }
        }
        if (lcApp != null) {
            lc0 = lcApp;   /* 有专属记录就覆盖全局值 */
        } else if (pkg != null && !pkg.isEmpty()) {
            /*
             * **无历史尺寸 → 沿用全局 layer_src_*，但要说清楚**。
             *
             * 这是有意的设计（不是漏写）：用户在这台车机上通常只会
             * 设一次框，之后所有 App 都按这个区域截 —— 沿用全局值
             * 正好符合这个习惯。
             *
             * 如果用户想「每个 App 各截一块」，那就对它点一次
             * 「应用并投屏」（会存下 app_rect_<pkg>），之后自动套用。
             *
             * 这条日志是给排查用的：万一画面截错位置，能一眼看出
             * "是用了全局框，不是这个 App 的框"。
             */
            DashLog.i(TAG, "该 App 无历史尺寸，沿用全局截取框 → " + pkg
                    + "（想为它单独设框：点「应用并投屏」）");
        }
        boolean cropOn = MainActivity.isAppCropEnabled(ctx);
        if (cropOn && lc0 != null && lc0.srcW > 0 && lc0.srcH > 0) {
            int sw = ((lc0.srcW + 15) / 16) * 16;   /* 16 对齐（H.264 硬编要求） */
            int sh = ((lc0.srcH + 15) / 16) * 16;
            int sx = lc0.srcX;
            int sy = lc0.srcY;

            /*
             * **夹住时用「整屏尺寸」，不是「窗口矩形」**（2026-11-03 修复）。
             *
             * 用户在右侧预览区框选时，看到的是**整块屏幕**（2560×1600 缩略图），
             * 所以 X/Y/W/H 是屏幕坐标。
             *
             * 原来这里用 win[0]+win[2] 当边界 —— 而 win 是目标 App 的窗口矩形，
             * 它可能比屏幕小（App 不是全屏时），也可能因为读不到窗口而降级成
             * 一个默认值。结果用户设 X=320 W=2560 会被夹成 X=0
             * （320+2560 > 2560），看起来就是"截取区不生效"。
             *
             * 改成按屏幕尺寸夹：只要框选本身没超出屏幕，就原样保留。
             */
            int[] scr = screenSize();
            int scrW = scr[0] > 0 ? scr[0] : 2560;
            int scrH = scr[1] > 0 ? scr[1] : 1600;

            /*
             * **比例不一致时只记日志，不再擅自改框**（2026-11-03 方案 B）。
             *
             * 上一版在这里按遮罩比例反算截取区宽高 —— 结果用户的
             * 2071×1275 被悄悄扩成 2080×1424（上下多抓 149px），
             * 用户发现"投出来的比框选的大一圈"，反而更困惑。
             *
             * 方案 B 把约束提前到**拖拽时**（LayerPreviewView.lockCropAspect），
             * 松手时框的比例就已经等于遮罩比例。
             * 所以这里不该再改，只在比例真的对不上时留一条日志便于排查
             * （对不上通常意味着用户手改过 prefs 或换了遮罩）。
             */
            if (MirrorController.getCodec() == CODEC_H264) {
                LayerConfig lcs = LayerConfig.loadFromPrefs(ctx, getOutputW(), getOutputH());
                if (lcs != null && lcs.w > 0 && lcs.h > 0) {
                    double boxAspect = (double) lcs.w / lcs.h;
                    double srcAspect = (double) sw / sh;
                    double drift = Math.abs(srcAspect - boxAspect) / boxAspect;
                    if (drift > 0.01) {
                        DashLog.w(TAG, "H.264 截取比例与遮罩不一致 "
                                + String.format(java.util.Locale.US,
                                        "src=%.3f box=%.3f 偏差 %.1f%%", srcAspect, boxAspect, drift * 100)
                                + " —— 画面会被拉伸（拖框时应已锁定比例，可能是旧配置）");
                    }
                }
            }

            /*
             * ⚠ **顺序很关键**：先夹到屏幕 → 再 16 对齐 → 对齐后可能
             * 又超出去，所以要**再夹一次**。
             *
             * 原来的写法是先对齐、再夹 X/Y，但漏了"对齐让尺寸变大"这种
             * 情况 —— 比如用户 H=1590，对齐成 1600（正好）；
             * H=1595 也对齐成 1600。但如果某次读到的旧值是
             * W=2560 H=1610（上次在更大屏幕上设的），
             * 对齐后 sh=1616 > 屏幕 1600，**sh 完全没被夹** ——
             * dcapd2 会去抓屏幕外的像素，画面下半截是黑的。
             */
            sw = ((sw + 15) / 16) * 16;
            sh = ((sh + 15) / 16) * 16;
            if (sw > scrW) sw = ((scrW + 15) / 16) * 16;
            if (sh > scrH) sh = (scrH / 16) * 16;   /* 向下取，避免超界 */
            if (sw <= 0) sw = (scrW / 16) * 16;
            if (sh <= 0) sh = 16;

            /* 再夹起点（此时尺寸已定） */
            if (sx < 0) sx = 0;
            if (sy < 0) sy = 0;
            if (sx + sw > scrW) sx = Math.max(0, scrW - sw);
            if (sy + sh > scrH) sy = Math.max(0, scrH - sh);

            DashLog.i(TAG, "App 截取区生效：源 " + sw + "×" + sh + " @(" + sx + "," + sy
                    + ")  屏幕 " + scrW + "×" + scrH + "  窗口 "
                    + win[0] + "," + win[1] + " " + win[2] + "×" + win[3]);
            return new int[]{sx, sy, sw, sh};
        }

        if (!mapMode) return win;

        LayerConfig lc = LayerConfig.loadFromPrefs(ctx, getOutputW(), getOutputH());
        if (lc == null || lc.w <= 0 || lc.h <= 0
                || lc.w >= getOutputW() || lc.h >= getOutputH()) {
            return win;   /* 无遮罩或遮罩是整屏 → 不裁 */
        }

        /*
         * ② 地图模式的显式抓取区（layer_src_x / _y / _h）。
         *
         * 为什么需要：下面那套"按遮罩比例居中适配"只能居中，
         * 没法指定纵向起点。实测遮罩 1440×400 时居中算出抓 y=364–1075，
         * 本车箭头（窗口 y≈1030）落在 94% 位置 —— 几乎贴底，前方全看不到。
         * 显式给 srcY=900 后本车落在 33%，才是开车该有的构图。
         *
         * 宽度按遮罩比例反推，保证编码时零缩放：
         *   srcW = srcH * (遮罩宽/遮罩高)
         */
        if (lc.srcH > 0) {
            double maskAspect0 = (double) lc.w / lc.h;
            int sh = lc.srcH;
            int sw = (int) Math.round(sh * maskAspect0);
            /* 16 对齐（H.264 硬编要求） */
            sw = ((sw + 15) / 16) * 16;
            sh = ((sh + 15) / 16) * 16;
            int sx = lc.srcX;
            int sy = lc.srcY;
            /* 夹住，别出窗口 */
            if (sx + sw > win[0] + win[2]) sx = win[0] + win[2] - sw;
            if (sy + sh > win[1] + win[3]) sy = win[1] + win[3] - sh;
            if (sx < win[0]) sx = win[0];
            if (sy < win[1]) sy = win[1];
            return new int[]{sx, sy, sw, sh};
        }

        double px0 = (double) lc.x / getOutputW();
        double py0 = (double) lc.y / getOutputH();
        double px1 = (double) (lc.x + lc.w) / getOutputW();
        double py1 = (double) (lc.y + lc.h) / getOutputH();

        double maskAspect = (double) lc.w / lc.h;
        double winAspect = (double) win[2] / win[3];

        int mx, my, mw, mh;
        if (winAspect >= maskAspect) {
            /* 窗口更宽：高度对齐，水平居中 */
            mh = win[3];
            mw = (int) Math.round(mh * maskAspect);
            mx = win[0] + (win[2] - mw) / 2;
            my = win[1];
        } else {
            /* 窗口更高：宽度对齐，垂直居中 */
            mw = win[2];
            mh = (int) Math.round(mw / maskAspect);
            mx = win[0];
            my = win[1] + (win[3] - mh) / 2;
        }

        int cx0 = mx + (int) Math.round(mw * px0);
        int cy0 = my + (int) Math.round(mh * py0);
        int cx1 = mx + (int) Math.round(mw * px1);
        int cy1 = my + (int) Math.round(mh * py1);

        int cw = cx1 - cx0;
        int ch = cy1 - cy0;
        if (cw < 16) cw = 16;
        if (ch < 16) ch = 16;

        return new int[]{cx0, cy0, cw, ch};
    }

    /** 地图模式开关（持久化，重启记得住） */
    public void setMapMode(Context c, boolean on) {
        mapMode = on;
        prefs(c).edit().putBoolean(PREF_MAP_MODE, on).apply();
        DashLog.i(TAG, "地图模式 " + (on ? "开启（按遮罩裁剪）" : "关闭（整窗口缩放）"));
    }

    public boolean isMapMode() {
        return mapMode;
    }

    public static boolean loadMapMode(Context c) {
        return prefs(c).getBoolean(PREF_MAP_MODE, false);
    }

    // ==================== HUD 取景 ====================

    /**
     * HUD 模式是否生效：地图模式 + HUD 开关。
     *
     * HUD（com.ecarx.hud）是独立的系统应用，只作为"取景源"，
     * 不要求投屏目标是哪个 App —— 开了开关就走它。
     */
    public boolean isMapHudActive() {
        /*
         * **只认会话标志，不读 prefs、不看 mapMode**。
         *
         * 演化过程（三个版本的教训都在这）：
         *
         * v1：`if (!mapMode) return false;`
         *     mapMode 是实例字段，MainActivity 重建后新 mirror 的字段是
         *     false，而 prefs 里是 true → 判定分叉，dcapd2 拿到的还是
         *     App 模式参数。
         *
         * v2：`return loadMapHud(ctx);`  —— 直接读 prefs
         *     字段同步问题解决了，但引入了**更严重**的问题：
         *     map_hud 一旦写进 prefs 就永久生效，之后任何一次投屏
         *     （包括选 App 投屏）都会走 HUD 分支，
         *     用户看到"选 app 投屏却把 HUD 推上仪表"。
         *
         * v3（当前）：读**静态瞬态标志 sHudSession**
         *     · 跨实例可见（static）
         *     · 不持久化（App 重启归零，不污染下一次）
         *     · 由 UI 显式开关：点 HUD / 点开始投屏 / 点停止 都会设置它
         */
        return sHudSession;
    }

    /**
     * 确保车机真正的 HUD（com.ecarx.hud）跑在 Display 1 上。
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么是 com.ecarx.hud，不是地图的 HudActivity
     * ══════════════════════════════════════════════════════════════
     * 实测（2026-11-02）两者完全不同：
     *
     *   com.geely.map/...multiscreen.hud.HudActivity —— 地图给自己做的
     *   小屏渲染。浅底、只有路线和车标、**没有任何文字信息**。不是我们要的。
     *
     *   com.ecarx.hud —— 车机真正的 HUD 应用。黑底，显示：
     *     档位「P」+ 车辆俯视图标
     *     + 转向箭头「↶ 164米进入 无名道路」
     *     + 「剩24公里 22:36到」
     *   **这才是开车要看的。**
     *
     * 它平时就在 Display 1 上跑（系统预装、开机自启）。这台车没 HUD
     * 硬件，那块屏是"空跑"的 —— 正好拿来抓取投到仪表。
     *
     * 不需要让地图进导航：HUD 的转向提示来自车机的导航服务，
     * 地图在后台导航时也会推过去。
     */
    public boolean launchMapHud() {
        try {
            /*
             * ══════════════════════════════════════════════════════════════
             * **HUD 可用性检查 —— 不能只看 pidof**（2026-11-05 修复）
             * ══════════════════════════════════════════════════════════════
             *
             * 「点多次不要跳转别的 App」→ 进程活着就别 am start。
             * 但实测发现：**进程活着不代表界面正常**。
             *
             * 故障现场：
             *   HUD 进程 PID 6090 活着
             *   Activity mBounds = Rect(0,0-0,0)        ← 窗口 0×0
             *   lastActiveTime inactive for 14561s      ← 4 小时没动过
             *   → captureScreen(display=1) 抓到全黑
             *   → STAT capture=30 但 tiles=0 kbps=0
             *
             * 所以判据要升级：
             *   ① pidof 有值
             *   ② Activity 的 mBounds 宽高都 > 0
             * 两条都满足才算"可用"；任一条不满足就 am start 重启它。
             */
            String running = AdbShell.exec("pidof " + HUD_PKG, 3000);
            boolean alive = running != null && !running.trim().isEmpty();

            boolean boundsOk = false;
            int bw = 0, bh = 0;
            if (alive) {
                try {
                    String d = AdbShell.exec(
                            "dumpsys activity activities | grep -A2 '"
                                    + HUD_PKG + "/" + HUD_CLS + "' | grep mBounds", 5000);
                    if (d != null && !d.isEmpty()) {
                        Matcher mm = Pattern.compile(
                                "Rect\\(\\d+, \\d+ - (\\d+), (\\d+)\\)").matcher(d);
                        while (mm.find()) {
                            bw = Integer.parseInt(mm.group(1));
                            bh = Integer.parseInt(mm.group(2));
                        }
                    }
                } catch (Throwable t) {
                    DashLog.w(TAG, "读 HUD bounds 失败: " + t);
                }
                boundsOk = (bw > 0 && bh > 0);
                DashLog.i(TAG, "HUD 检查：pid=" + running.trim()
                        + " bounds=" + bw + "x" + bh
                        + (boundsOk ? "（可用）" : "（0×0 僵死）"));
            }

            if (alive && boundsOk) {
                DashLog.i(TAG, "HUD 已就绪，不重复启动、不抢焦点");
                return true;
            }

            /* 进程不在，或者界面僵死 → 都要重新 am start */
            DashLog.i(TAG, alive
                    ? "HUD 界面僵死（0×0），强制重新拉起"
                    : "HUD 未运行，启动到 Display " + HUD_DISPLAY_ID);
            AdbShell.exec("am start --display " + HUD_DISPLAY_ID
                    + " -n " + HUD_PKG + "/" + HUD_CLS, 6000);
            sleep(1500);

            /* 回读确认 */
            String out = AdbShell.exec(
                    "dumpsys activity activities | grep -A2 '"
                            + HUD_PKG + "/" + HUD_CLS + "' | grep mBounds", 6000);
            boolean ok = false;
            if (out != null) {
                Matcher mm = Pattern.compile(
                        "Rect\\(\\d+, \\d+ - (\\d+), (\\d+)\\)").matcher(out);
                while (mm.find()) {
                    bw = Integer.parseInt(mm.group(1));
                    bh = Integer.parseInt(mm.group(2));
                }
                ok = (bw > 0 && bh > 0);
            }
            DashLog.i(TAG, "HUD 重启后 bounds=" + bw + "x" + bh
                    + "：" + (ok ? "确认到位" : "仍未就绪"));
            return ok;
        } catch (Throwable t) {
            DashLog.w(TAG, "启动 HUD 失败: " + t);
            return false;
        }
    }

    /**
     * 停止时对 HUD 的处理：**什么都不做**。
     *
     * HUD 是系统预装应用，本来就在 Display 1 上常年运行；
     * force-stop 会被立刻拉起，而且用户可能真在看那块屏。
     * 停止投屏只需要 dcapd2 停止抓取，Display 1 上留着 HUD 不影响任何事。
     */
    public void dismissMapHud() {
        DashLog.i(TAG, "HUD 保留在 Display " + HUD_DISPLAY_ID + "（系统应用，无需撤离）");
    }

    // ============================== 设备查询 ==============================

    /** 读目标 App 的窗口矩形。优先 mAppBounds（排除底部导航条）。 */
    /** 供 UI 读取目标 App 窗口矩形（「读取窗口」按钮用） */
    public int[] readWindowRectPublic(String pkg) {
        return windowRect(pkg);
    }

    /** 整屏像素（wm size）。**只在后台线程调** —— 内部走 adb socket。 */
    private int[] cachedScreenPx;
    public int[] getScreenPx() {
        if (cachedScreenPx == null) {
            cachedScreenPx = screenSize();
        }
        return cachedScreenPx;
    }

    /**
     * **供 UI 线程安全读取屏幕尺寸**（不碰 adb）。
     *
     * 为什么必须有这个：`getScreenPx()` 内部走 `AdbShell.exec("wm size")`，
     * 那是**真实 socket 连接**。在 UI 线程调它会卡几百毫秒起，
     * 严重时 ANR（Android 禁止主线程做网络操作）。
     *
     * 实测隐患：MainActivity 里有 5 处（初始化预览、同步配置、
     * 读取窗口、应用投屏）都是在主线程调的 —— 打开面板就卡一下。
     *
     * 这个版本只读缓存，没缓存就返回本车主屏的已知默认值（2560×1600）。
     * 缓存由后台线程（loop 里 / 应用投屏的后台任务）填。
     */
    private static final int[] DEFAULT_SCREEN_PX = {2560, 1600};
    public int[] getScreenPxSafe() {
        int[] c = cachedScreenPx;
        return (c != null && c[0] > 0 && c[1] > 0) ? c : DEFAULT_SCREEN_PX;
    }

    /** 后台线程预热屏幕尺寸缓存（打开面板时调一次） */
    public void warmScreenPxAsync() {
        if (cachedScreenPx != null) return;
        new Thread(() -> {
            try {
                cachedScreenPx = screenSize();
            } catch (Throwable ignored) {
            }
        }, "warm-screenpx").start();
    }

    /** 清掉屏幕尺寸缓存（分辨率变化时调） */
    public static void clearScreenPxCache() {
        MirrorController c = sActive;
        if (c != null) c.cachedScreenPx = null;
    }

    private int[] windowRect(String pkg) {
        try {
            String cmd = "dumpsys window windows | awk '/Window\\{/{b=($0 ~ /" + pkg
                    + "/)} b && (/mAppBounds=/ || /mFrame=\\[/){print; if (/mFrame=\\[/) exit}'";
            String out = AdbShell.exec(cmd, 8000);
            if (out == null) return null;

            Matcher ab = Pattern.compile(
                    "mAppBounds=Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)").matcher(out);
            if (ab.find()) {
                int x1 = Integer.parseInt(ab.group(1));
                int y1 = Integer.parseInt(ab.group(2));
                int x2 = Integer.parseInt(ab.group(3));
                int y2 = Integer.parseInt(ab.group(4));
                if (x2 > x1 && y2 > y1) return new int[]{x1, y1, x2 - x1, y2 - y1};
            }

            Matcher m = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matcher(out);
            if (!m.find()) {
                DashLog.w(TAG, "没解析到窗口矩形: " + out);
                return null;
            }
            int x1 = Integer.parseInt(m.group(1));
            int y1 = Integer.parseInt(m.group(2));
            int x2 = Integer.parseInt(m.group(3));
            int y2 = Integer.parseInt(m.group(4));
            if (x2 <= x1 || y2 <= y1) return null;
            return new int[]{x1, y1, x2 - x1, y2 - y1};
        } catch (Throwable t) {
            DashLog.e(TAG, "取窗口矩形失败", t);
            return null;
        }
    }

    private int[] screenSize() {
        try {
            String out = AdbShell.exec("wm size", 4000);
            Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(out == null ? "" : out);
            if (m.find()) {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{2560, 1600};
    }

    private boolean daemonAlive() {
        /*
         * **优先复用组合查询的结果**（2026-11-05）。
         *
         * 主循环每 2 秒会跑 readStatsAndAlive()，它一次就带回了
         * "dcapd2 还在不在"。如果刚查过（3 秒内），直接用那个结果，
         * 不再为同一个事实开第二条 ADB 连接。
         *
         * 超过 3 秒没查过才真的去 pidof —— 覆盖那些不在主循环节奏里的
         * 调用（启动时探测、restartDaemonNow 前后校验）。
         */
        long since = SystemClock.elapsedRealtime() - lastCombinedStatAt;
        if (lastCombinedStatAt > 0 && since < 3000) {
            return lastDaemonAlive;
        }
        try {
            String out = AdbShell.exec("pidof dcapd2", CHUNK);
            return out != null && out.trim().length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean targetAlive() {
        if (pkg == null) return false;
        try {
            String out = AdbShell.exec("pidof " + pkg, 4000);
            if (out != null) return out.trim().length() > 0;
            return false;
        } catch (Throwable ignored) {
            return true;   // 查不到就假设还活着，避免误判导致投屏被结束
        }
    }

    /** 供 UI 读取主屏前台包名（截屏前确认目标 App 真的在前台） */
    public String foregroundPkgPublic() {
        return foregroundPkg();
    }

    /** 取主屏（display 0）的前台包名 */
    private String foregroundPkg() {
        try {
            String out = AdbShell.exec("dumpsys window displays", 5000);
            if (out == null) return null;

            int curDisplay = -1;
            for (String raw : out.split("\n")) {
                String t = raw.trim();
                if (t.startsWith("Display:") && t.contains("mDisplayId=")) {
                    curDisplay = leadingInt(t.substring(t.indexOf("mDisplayId=") + 11));
                } else if (t.startsWith("mCurrentFocus=") && curDisplay == 0) {
                    Matcher m = Pattern.compile("Window\\{\\S+\\s+u\\d+\\s+([^/\\s]+)/").matcher(t);
                    if (m.find()) return m.group(1);
                    return null;
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int leadingInt(String s) {
        int v = 0;
        for (int i = 0; i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9'; i++) {
            v = v * 10 + (s.charAt(i) - '0');
        }
        return v;
    }

    // ============================== 参数与统计 ==============================

    /**
     * **重拉 dcapd2**（按当前配置重新起进程）。
     *
     * 供 UI 改完「截取 App 页面」后调用 —— 只写配置文件是不够的：
     * loop() 拉起 dcapd2 的条件是 `fgIsTarget && !daemonAlive()`，
     * **进程活着就不重起**，抓取参数永远是旧的。
     * 用户看到的就是"截取页面不生效"。
     *
     * ══════════════════════════════════════════════════════════════
     * **用 sActive 而不是 this**（2026-11-03 排查出的隐患）
     * ══════════════════════════════════════════════════════════════
     * MainActivity 持有的 mirror 可能是个旧实例（onCreate 时从静态
     * `prev` 取来的），而真正在跑投屏的是 `sActive`。
     * 如果这里判断 `this.running`，旧实例的字段是 false →
     * 直接 return，dcapd2 根本不重拉 —— 又是"不生效"。
     *
     * 所以统一走静态的活跃实例。
     *
     * 必须在后台线程调用（AdbShell.exec 会开 socket）。
     */
    /**
     * **重拉 dcapd2**（按当前配置重新起进程）。
     *
     * ══════════════════════════════════════════════════════════════
     * **VD 模式下还要重发截取框**（2026-11-03 画面撕裂根因）
     * ══════════════════════════════════════════════════════════════
     *
     * 现象：App 转后台（VD 模式）后改截取区 → 仪表出现"左边头像
     *      右边模糊条纹"的撕裂画面。
     *
     * 根因：dcapd2 重启后按新 src 重算了 g_dst（画布落点），
     *      但 g_vd_crop_*（虚拟屏裁剪框）还是**上一轮的旧值** ——
     *      它是靠 'DCVP' 命令单独下发的，重拉进程时没人再发。
     *      于是：裁剪框 240×176 × 画布落点 976×272 → 错位拉伸。
     *
     * 修法：重拉前后都把 setCropRect 再调一次（内部会换算成
     *      虚拟屏坐标并下发 'DCVP'）。
     */
    public boolean restartDaemonNow() {
        MirrorController c = sActive;
        if (c == null || !c.running) {
            DashLog.i(TAG, "未在投屏（sActive=" + (c != null) + "），"
                    + "restartDaemonNow 跳过（交给 startCast 起新进程）");
            return false;
        }
        synchronized (c.cleanupLock) {
            /*
             * ══════════════════════════════════════════════════════════
             * **优先热更新，能不动进程就不动**（2026-11-05）
             * ══════════════════════════════════════════════════════════
             *
             * 原来一律 pkill 重启：改一次截取框就断一次 TCP（8444 + 8450），
             * 日志里满屏 Broken pipe / ECONNREFUSED，画面还闪一下黑。
             * 实测一场 6 分钟的投屏出现了 9 次这样的循环。
             *
             * 现在先试热更新：
             *   · 主屏抓取区 → 发 'DCSR'（dcapd2 的 g_src_* 是全局变量）
             *   · 遮罩参数   → 写文件后 dcapd2 自己 500ms 轮询重载
             *   · VD 裁剪框  → 发 'DCVP'（本来就有）
             * 这些都能改，就不重启进程了。
             *
             * 只有**编码档 / 画布尺寸**变了才必须重启 —— MediaCodec 和
             * 缓冲池都要重建，没法热改。
             */
            int[] cap = c.currentSrcCap();
            boolean hotOk = false;
            if (c.bgMode && c.vd != null && c.vd.hasControlChannel() && cap != null) {
                /*
                 * 后台跟随模式：dcapd2 在 VD 分支里用的是 g_vd_crop_*，
                 * 主屏抓取区这时反而不重要。但两套都发一遍最稳 ——
                 * 万一这一帧刚好切回主屏，参数也是对的。
                 */
                hotOk = c.vd.sendSrcCrop(cap);
                c.republishVdCrop();
                if (hotOk) {
                    DashLog.i(TAG, "配置已热更新（后台跟随，未重启 dcapd2）");
                    return true;
                }
            } else if (!c.bgMode && c.vd != null && c.vd.hasControlChannel() && cap != null) {
                /* 主屏模式：只需热改抓取区 */
                hotOk = c.vd.sendSrcCrop(cap);
                if (hotOk) {
                    DashLog.i(TAG, "配置已热更新（主屏抓取区，未重启 dcapd2）");
                    return true;
                }
            }

            /* 热更新不可用（没通道 / 参数缺）→ 才真的重启 */
            c.ensureBinary();
            int[] rect = c.lastRect != null ? c.lastRect : new int[]{0, 0, getOutputW(), getOutputH()};
            DashLog.w(TAG, "热更新不可用，重启 dcapd2（用 sActive 实例）");
            boolean ok = c.launchDaemon(rect);
            c.republishVdCrop();
            return ok;
        }
    }

    /**
     * 算出当前应该用的**主屏抓取区**（屏幕坐标）—— 和 launchDaemon 里
     * 传给 dcapd2 的 argv[3..6] 是同一套值。
     *
     * 供热更新使用：只有它变了才需要发 'DCSR'。
     */
    private int[] currentSrcCap() {
        try {
            int[] s = screenSize();
            int[] safeR = lastRect != null ? lastRect
                    : new int[]{0, 0, getOutputW(), getOutputH()};
            if (isMapHudActive()) {
                return new int[]{HUD_SRC_X, HUD_SRC_Y, HUD_SRC_W, HUD_SRC_H};
            }
            return applyMapCrop(safeR);
        } catch (Throwable t) {
            DashLog.w(TAG, "算抓取区失败: " + t);
            return null;
        }
    }

    /** 把当前截取框重新下发给虚拟屏（VD 未激活时是空操作） */
    private void republishVdCrop() {
        try {
            if (!bgMode) return;   /* 主屏模式不需要 */
            LayerConfig lc = LayerConfig.loadFromPrefs(ctx, getOutputW(), getOutputH());
            if (lc != null && lc.srcW > 0 && lc.srcH > 0) {
                int[] crop = {lc.srcX, lc.srcY, lc.srcW, lc.srcH};
                vd.setCropRect(crop, lastRect);
                DashLog.i(TAG, "VD 截取框已重发 → " + lc.srcW + "×" + lc.srcH
                        + " @(" + lc.srcX + "," + lc.srcY + ")");
            } else {
                vd.setCropRect(null, null);
                DashLog.i(TAG, "VD 截取框已清空（取整块虚拟屏）");
            }
        } catch (Throwable t) {
            DashLog.w(TAG, "重发 VD 截取框失败: " + t);
        }
    }

    public static int getFps() {
        return fps;
    }

    public void setFps(Context c, int f) {
        if (f < 5) f = 5;
        if (f > 120) f = 120;
        /*
         * **帧率没变就别重启 dcapd2**（2026-11-05）。
         *
         * 原来无论 old == new 都会走下面的 launchDaemon —— 而 launchDaemon
         * 是 pkill + 重新拉起，TCP 8444 与 8450 全断，CP 侧 dashcastd
         * 收 EOF 自己退出、图层被销毁，仪表闪一下。
         *
         * 调用方是 saveMask()：「保存并应用」每次都传当前帧率，
         * 用户只想改遮罩，却每次都被这个重启打一顿。
         * 日志里 8 分钟 6 次 dcapd2 重启，很大一部分来自这里。
         *
         * 只加一个相等判断，语义不变、代价为零。
         */
        if (f == fps) {
            /* 值没变：只落 prefs（幂等），不碰进程 */
            prefs(c).edit().putInt(PREF_FPS, f).apply();
            return;
        }
        fps = f;
        prefs(c).edit().putInt(PREF_FPS, f).apply();
        DashLog.i(TAG, "帧率切到 " + f + " fps");
        if (isMirroring() && lastRect != null) {
            new Thread(() -> {
                synchronized (cleanupLock) {
                    launchDaemon(lastRect);
                }
            }, "fps-restart").start();
        }
    }

    private static void loadFps(Context c) {
        int f = prefs(c).getInt(PREF_FPS, DEFAULT_FPS);
        if (f < 5 || f > 120) f = DEFAULT_FPS;
        fps = f;
    }

    public static int getCodec() {
        return codec;
    }

    public static void loadCodec(Context c) {
        if (c == null) return;
        codec = prefs(c).getInt(PREF_CODEC, CODEC_BGRA) == CODEC_H264
                ? CODEC_H264 : CODEC_BGRA;
        loadFps(c);
    }

    /**
     * 切编码档。
     *
     * **写一个数字文件，不重启 dcapd2**。
     *
     * 原来走 launchDaemon(lastRect) 会：杀进程 → TCP 断开 →
     * CP 的 dashcastd 收 EOF 自己退出 → layer 99 被销毁 →
     * 仪表闪一下、图层消失、要等几秒才恢复。
     * 用户只是点了一下「切 H.264」，不该付出这个代价。
     *
     * dcapd2 收到新数字后会关掉旧编码器、按新档位重算编码尺寸、
     * 断开并重连一次 —— 走的是它自己的循环，进程号不变。
     */
    public void setCodec(int c) {
        codec = (c == CODEC_H264) ? CODEC_H264 : CODEC_BGRA;
        if (ctx != null) prefs(ctx).edit().putInt(PREF_CODEC, codec).apply();
        markCodecSwitchStart();

        new Thread(() -> {
            try {
                AdbShell.exec("echo " + codec + " > " + CODEC_FILE, 2000);
                DashLog.i(TAG, "编码档位切到 "
                        + (codec == CODEC_H264 ? "H.264" : "BGRA+LZ4")
                        + "（已写入 " + CODEC_FILE + "，dcapd2 自行切换）");
            } catch (Throwable t) {
                DashLog.w(TAG, "写编码档文件失败: " + t.getMessage());
            }
        }, "codec-switch").start();
    }

    /**
     * 等 dcapd2 **真的**切完编码档再返回（最多 3 秒）。
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么必须等（2026-11-03 "H.264 不按原遮罩位置投屏"的根因）
     * ══════════════════════════════════════════════════════════════
     * setCodec() 只是写了一个数字文件，dcapd2 **每 500ms 才轮询一次**。
     * 而 MainActivity 切档后立刻 applyFully() → 按**新档位**算 destination
     * 并下发 setLayerRect。两条路赛跑：
     *   · CP 已按 H.264 把图层缩到遮罩矩形
     *   · dcapd2 还在吐 BGRA（整块画布 + alpha 挖洞）
     *   → 画布被压进那块矩形，位置和大小全不对。
     * 要等下一次「保存并应用」才会纠正 —— 正是用户反馈的现象。
     *
     * 这里用日志里的切换完成标记来判定，等不到就退化成固定等待。
     *
     * 必须在后台线程调用。
     */
    public static void awaitCodecSwitched(long maxMs) {
        final long baseline = sCodecLogBaseline;
        long t0 = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - t0 < maxMs) {
            try {
                /*
                 * 只数**基线之后**的行 —— 日志里本来就有历史切换记录，
                 * 从头 grep 会永远命中，等于没等。
                 */
                String cmd = baseline > 0
                        ? "tail -n +" + (baseline + 1) + " " + LOGF
                            + " 2>/dev/null | grep -c '编码档切换完成'"
                        : "tail -n 40 " + LOGF + " 2>/dev/null | grep -c '编码档切换完成'";
                String out = AdbShell.exec(cmd, 2000);
                if (out != null) {
                    int n = 0;
                    try {
                        n = Integer.parseInt(out.trim().replaceAll("[^0-9]", ""));
                    } catch (Throwable ignored) {
                    }
                    if (n > 0) {
                        DashLog.i(TAG, "dcapd2 已完成编码档切换（"
                                + (SystemClock.elapsedRealtime() - t0) + "ms）");
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        /* 等不到标记：固定再等一拍，至少让 dcapd2 赶上一次 500ms 轮询 */
        try {
            Thread.sleep(600);
        } catch (InterruptedException ignored) {
        }
        DashLog.w(TAG, "没等到编码档切换标记，按固定延迟继续（" + maxMs + "ms）");
    }

    /** 切档前记下日志基线行号（只在后台线程写） */
    private static volatile long sCodecLogBaseline = 0;

    private static void markCodecSwitchStart() {
        try {
            String out = AdbShell.exec("wc -l < " + LOGF + " 2>/dev/null", 2000);
            if (out != null) {
                sCodecLogBaseline = Long.parseLong(
                        out.trim().replaceAll("[^0-9]", "").isEmpty()
                                ? "0" : out.trim().replaceAll("[^0-9]", ""));
            }
        } catch (Throwable ignored) {
            sCodecLogBaseline = 0;
        }
    }

    /** 读 dcapd2 的 STAT 行：帧率 / 图块 / 带宽 */
    /**
     * **虚拟屏"无输出"起始时刻**（2026-11-05 替代原来的 vdDeadStreak 计数）。
     *
     * 原来是「连续 3 次采样 capture=0 就判死」，但采样间隔是 1 秒、
     * 而 dcapd2 等首帧最长要 5 秒 —— 阈值比等待期还短，必然误杀。
     *
     * 改成记时刻：从「第一次发现既无帧也无字节」开始计时，
     * 持续 {@link #VD_DEAD_TIMEOUT_MS} 才判死。
     * 0 表示"当前没在计时"。
     */
    private volatile long vdDeadSince = 0;

    /** 虚拟屏判死的持续时长：覆盖 5 秒等首帧 + 10 秒真实观察期 */
    private static final long VD_DEAD_TIMEOUT_MS = 15000;
    /**
     * **VD 失败冷却期**（2026-11-05 修复死循环）。
     *
     * 现象：日志每 7 秒循环一次
     *   「App 已转后台（虚拟屏 N）→ 后台跟随失败 → 已退回主屏」
     *   虚拟屏编号从 15 一路涨到 18+，一直在新建。
     *
     * 根因：readStats 检测到 VD 无帧 → exitBg() 退回主屏；
     *      紧接着 loop() 下一轮又发现"目标不在前台" → enterBg()；
     *      新建虚拟屏照样无帧 → 又被退回 → 无限循环。
     *
     * 修法：VD 失败后 30 秒内不再尝试 enterBg，
     *      直接保持主屏抓取（画面不理想但至少能用），
     *      避免反复建屏消耗资源。
     */
    private volatile long vdFailCooldownUntil = 0;

    /**
     * 上一次 [daemonAlive + readStats] 组合查询的时间戳。
     * 两条周期性任务共用一次 ADB 连接，见 readStatsAndAlive()。
     */
    private volatile long lastCombinedStatAt = 0;
    /** 组合查询得出的「dcapd2 是否存活」，供 daemonAlive() 复用 */
    private volatile boolean lastDaemonAlive = false;

    /**
     * ══════════════════════════════════════════════════════════════
     * **一次 ADB 连接同时拿「进程是否活着」和「STAT 行」**（2026-11-05）
     * ══════════════════════════════════════════════════════════════
     *
     * 原来主循环里是两次独立调用：
     *     if (daemonAlive()) readStats();
     * 每条都走一次完整的 TCP 握手 + ADB CNXN 协商，
     * 而它们读的是同一个地方（/data/local/tmp 下的进程和日志）。
     *
     * 合并成一条 shell 之后：
     *   · ADB 连接数从 2 次/周期降到 1 次/周期
     *   · 两个结果天然同一时刻，不会出现"pidof 说有、tail 却读到旧行"
     *
     * 用 `pidof dcapd2 || echo NOTRUNNING` 而不是 `pidof`：
     * pidof 找不到时返回非 0，`;` 后面的命令照常执行，
     * 但我们要能区分"没进程"和"日志为空"，所以显式打一个标记。
     */
    private void readStatsAndAlive() {
        try {
            /*
             * 一条命令输出两段：第一行是 pid 标记，之后是日志尾部。
             * 用 '---STAT---' 作为分隔符，避免和日志内容混淆。
             */
            String cmd = "echo '---PID---'; pidof dcapd2 || echo NOTRUNNING; "
                    + "echo '---STAT---'; tail -3 " + LOGF + " 2>/dev/null";
            String out = AdbShell.exec(cmd, 5000);
            lastCombinedStatAt = SystemClock.elapsedRealtime();
            if (out == null) {
                lastDaemonAlive = false;
                return;
            }

            int pidIdx = out.indexOf("---PID---");
            int statIdx = out.indexOf("---STAT---");
            String pidPart = "";
            String statPart = out;
            if (pidIdx >= 0 && statIdx > pidIdx) {
                pidPart = out.substring(pidIdx + 9, statIdx).trim();
                statPart = out.substring(statIdx + 10);
            }
            lastDaemonAlive = !pidPart.isEmpty() && !pidPart.contains("NOTRUNNING");

            if (!lastDaemonAlive) return;   /* 进程不在，没必要解析 STAT */
            parseStats(statPart);
        } catch (Throwable t) {
            lastDaemonAlive = false;
            DashLog.w(TAG, "组合查询失败: " + t);
        }
    }

    private void readStats() {
        try {
            String out = AdbShell.exec("tail -3 " + LOGF, 4000);
            if (out == null) return;
            parseStats(out);
        } catch (Throwable ignored) {
        }
    }

    /** 解析 STAT 行（原 readStats 的主体，抽出来给两条路径共用） */
    private void parseStats(String out) {
        try {
            Matcher m = Pattern.compile(
                    "STAT fps=(\\d+) capture=(\\d+) tiles=(\\d+) kbps=(\\d+)").matcher(out);
            String last = null;
            int f = 0, t = 0, k = 0, cap = 0;
            String src = "";
            while (m.find()) {
                last = m.group(0);
                f = Integer.parseInt(m.group(1));
                cap = Integer.parseInt(m.group(2));
                t = Integer.parseInt(m.group(3));
                k = Integer.parseInt(m.group(4));
                /*
                 * ══════════════════════════════════════════════════════════
                 * **src 必须取自「同一个匹配」的那一行**（2026-11-05 修复）
                 * ══════════════════════════════════════════════════════════
                 *
                 * 原来是 `out.indexOf(m.group(0))` —— 它**永远返回第一处**
                 * 出现位置，而不是本次 m.find() 所在的那处。
                 * `tail -3` 里若同时有 src=SCREEN 和 src=VD 两行，
                 * cap 取的是最后一行、src 却取的是第一行 → 两者错配，
                 * 会把"主屏的 0 帧"当成"VD 的 0 帧"判死。
                 *
                 * 改成从 m.end() 往后找行尾：那个位置之后就是本行的剩余部分。
                 */
                int eol = out.indexOf('\n', m.end());
                String tail = eol > 0 ? out.substring(m.end(), eol) : out.substring(m.end());
                if (tail.contains("src=VD")) src = "VD";
                else if (tail.contains("src=SCREEN")) src = "SCREEN";
            }
            if (last == null) return;

            if (listener != null) listener.onStats(f, k, t);

            /*
             * ══════════════════════════════════════════════════════════════
             * **虚拟屏无帧检测**（2026-11-05 重写：原判定会误杀正常场景）
             * ══════════════════════════════════════════════════════════════
             *
             * 日志实证（dashcast-3.log）：
             *   18:33:19.130 进入后台跟随 gen=7
             *   18:33:23.732 虚拟屏连续 3 次无帧，退回主屏    ← 只隔 4.6 秒
             * 同一会话里这条出现了 4 次，全是同一个套路。
             * 但那 4.6 秒内 dcapd2 并不是"死了"，而是在**等首帧**：
             * `frame_is_blank()` 会主动丢弃最多 150 帧（约 5 秒）的纯黑画面，
             * 等 App 从主屏搬进虚拟屏后重新画出来。这段"一个字都不发"
             * 是**设计好的**，不是故障。
             *
             * 原来的判定只看 `capture==0`，窗口 3 秒 —— 两个时序直接打架：
             * 一边规定"最多等 5 秒"，另一边"3 秒就判死"。
             *
             * 新判定要同时满足三个条件，才算真死：
             *   ① src=VD                —— 确实在虚拟屏模式
             *   ② cap==0 && k==0        —— 既没合成帧、也没发出任何字节
             *                              （frame_is_blank 丢弃时也是 0 字节，
             *                                所以必须"持续够久"才能区分）
             *   ③ 持续 15 秒            —— 覆盖 5 秒等首帧 + 10 秒真实观察期
             *
             * 15 秒仍无任何字节 → 虚拟屏真的没在喂帧（owner 被别的进程抢走、
             * state OFF），此时退回主屏（画面不理想但比纯黑好）。
             */
            boolean vdSilent = "VD".equals(src) && cap == 0 && k == 0;
            if (vdSilent) {
                long now2 = SystemClock.elapsedRealtime();
                if (vdDeadSince == 0) vdDeadSince = now2;
                long silentMs = now2 - vdDeadSince;
                if (silentMs >= VD_DEAD_TIMEOUT_MS && bgMode) {
                    DashLog.w(TAG, "虚拟屏持续 " + (silentMs / 1000)
                            + " 秒无输出（capture=0 kbps=0），退回主屏抓取");
                    vdDeadSince = 0;
                    /* 设置 30 秒冷却，避免 enterBg ↔ exitBg 死循环 */
                    vdFailCooldownUntil = SystemClock.elapsedRealtime() + 30000;
                    state("后台跟随失败（虚拟屏长时间无输出）—— 已退回主屏", true);
                    try {
                        exitBg();
                    } catch (Throwable ex) {
                        DashLog.w(TAG, "退出后台跟随失败: " + ex);
                    }
                }
            } else {
                /*
                 * 有帧或有字节 → 立刻清零计时。
                 * 注意：判死之后 enterBg 会重新建屏，那时也要清零，
                 * 见 enterBg() 里的 vdDeadSince = 0。
                 */
                vdDeadSince = 0;
            }

            // 有整帧要发却一个字节都没发出去 → 基本是 CP 端把连接关了。
            // 把 dashcastd 的日志拉回来，省得靠猜。
            if (k == 0 && t > 0 && SystemClock.elapsedRealtime() - lastCpLog > 10000) {
                lastCpLog = SystemClock.elapsedRealtime();
                new Thread(() -> {
                    try {
                        String s = cp.tailLog(40);
                        for (String line : s.split("\n")) DashLog.w(TAG + "/CP", line);
                    } catch (Throwable ignored) {
                    }
                }, "cp-log").start();
            }
        } catch (Throwable ignored) {
        }
    }

    // ============================== 暂停 ==============================

    private void setPause() {
        try {
            AdbShell.exec("touch " + PAUSE, CHUNK);
            DashLog.i(TAG, "已暂停推流（保持连接，仪表留最后一帧）");
        } catch (Throwable ignored) {
        }
    }

    private void clearPause() {
        try {
            AdbShell.exec("rm -f " + PAUSE, CHUNK);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 上报状态。
     *
     * **必须挡掉迟到的回调**：stop() 之后旧 worker 可能还在 sleep 里，
     * 醒来照样调 state()，就会把"投屏运行中"写回界面 —— 用户看到点了
     * 停止却还是运行中。running 已经置 false，这里直接不外发。
     */
    public void state(String msg, boolean ok) {
        DashLog.i(TAG, msg);
        if (!running) {
            DashLog.i(TAG, "已停止，忽略迟到的状态回调: " + msg);
            return;
        }
        if (listener != null) listener.onState(msg, ok);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
