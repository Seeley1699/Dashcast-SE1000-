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
    private static final String BIN_MD5 = "dfd158c2257eced5ff6a62f90a03c368";
    private static final String PAUSE = "/data/local/tmp/dcapd2.pause";
    private static final String LOGF = "/data/local/tmp/dcapd2.log";
    /** 编码档位文件：App 写数字，dcapd2 每 500ms 轮询，运行时切档不重启进程 */
    private static final String CODEC_FILE = "/data/local/tmp/dc_codec";

    private static final String PREF_CODEC = "codec";
    private static final String PREF_FPS = "fps";
    private static final String PREF_MAP_MODE = "map_mode";

    /** 仪表画布固定尺寸 */
    private static final int OUT_W = 1920;
    private static final int OUT_H = 532;

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
        Log.i(TAG, "HUD 会话 " + (on ? "开启" : "清除"));
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
                        Log.i(TAG, "HUD 模式：忽略后台事件（无需虚拟屏跟随）");
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
        Log.i(TAG, "start 目标=" + pkg + " gen=" + myGen);

        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            ctx.registerReceiver(leavingReceiver, f);
            Log.i(TAG, "已注册 HOME/熄屏 监听（按下即暂停，不等轮询）");
        } catch (Throwable t) {
            Log.w(TAG, "注册离开广播失败（不影响推流，仅降级为轮询）", t);
        }

        worker = new Thread(() -> loop(myGen), "mirror-loop");
        worker.start();
        sActive = this;
        setCastingFlag(true);
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
                Log.i(TAG, "停止流程已被新会话取代（gen " + myGen + " → "
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
                    Log.i(TAG, "清理开始时代次已变，放弃");
                    return;
                }
                boolean hadDaemon = daemonAlive();
                int logLines = (hadDaemon && cp != null) ? cp.dashcastdLogLines() : -1;

                for (int i = 0; i < 4; i++) {
                    if (gen.get() != myGen) {
                        Log.i(TAG, "清理中途代次变化，停止 pkill（新会话已在跑）");
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
                            Log.i(TAG, "清理确认完成（第 " + (i + 1) + " 次）");
                            if (hadDaemon && logLines >= 0) {
                                if (cp.verifySessionEnded(logLines, 3000)) {
                                    Log.i(TAG, "dashcastd 已确认会话结束，图层已还原");
                                } else {
                                    Log.e(TAG, "dashcastd 未报告会话结束 —— 图层可能仍在，"
                                            + "但不再阻塞（restoreDashboard 会再兜一次）");
                                }
                            }
                            return;
                        }
                        Log.w(TAG, "清理未确认（第 " + (i + 1) + " 次）: "
                                + (out == null ? "null" : out.trim().replace('\n', ' ')));
                    } catch (Throwable te) {
                        Log.w(TAG, "清理命令失败（第 " + (i + 1) + " 次）: " + te);
                    }
                    sleep(400);
                }
                Log.e(TAG, "清理重试 4 次仍未确认，dcapd2 可能还在按旧目标推流");
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
        state("回桌面 —— App 转后台，仪表继续实时跟随", true);
        Log.i(TAG, "进入后台跟随模式 gen=" + myGen);

        if (gen.get() != myGen) return;

        int srcW = lastRect != null ? lastRect[2]
                : ctx.getResources().getDisplayMetrics().widthPixels;
        int srcH = lastRect != null ? lastRect[3]
                : ctx.getResources().getDisplayMetrics().heightPixels;
        int mainDpi = ctx.getResources().getDisplayMetrics().densityDpi;

        VdMirror.setProxyWanted(true);   /* 挂控制通道（按 HOME 停抓用） */
        VdMirror.setFeedFrames(true);    /* 虚拟屏模式：允许喂帧 */

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
            Log.i(TAG, "enterBg 期间代次变化（已停止），收回虚拟屏");
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
            state("后台跟随不可用 —— 仪表停在最后一帧", true);
        } else {
            try {
                AdbShell.exec("touch /data/local/tmp/dcapd2.bg", CHUNK);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 目标回前台 → 断帧源（dcapd2 自动恢复主屏抓帧），摘掉后台标志。 */
    private void exitBg() {
        if (!bgMode) return;
        bgMode = false;
        Log.i(TAG, "退出后台跟随模式");
        vd.stop(true);
        VdMirror.setFeedFrames(false);
        try {
            AdbShell.exec("rm -f /data/local/tmp/dcapd2.bg", CHUNK);
        } catch (Throwable ignored) {
        }
        vd.hold();   // 回到主屏模式后立刻把控制通道重新挂上
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
        Log.i(TAG, "结束投屏会话：" + reason);
        new Thread(() -> {
            try {
                stop();
            } catch (Throwable t) {
                Log.w(TAG, "结束会话时清理异常", t);
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
                            clearPause();
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
                if (!hud && now - lastFgCheck >= 800) {
                    lastFgCheck = now;
                    String fg = foregroundPkg();
                    fgIsTarget = pkg != null && pkg.equals(fg);
                    if (fgIsTarget) {
                        if (bgMode) exitBg();      /* 目标回前台 → 退出虚拟屏跟随 */
                        seenTargetAlive = true;
                    } else if (!bgMode) {
                        enterBg(myGen);            /* 目标离开前台 → 进虚拟屏跟随 */
                        if (gen.get() != myGen) return;
                    }
                }

                /* ③ 每秒一次：帧率 / 带宽 / 图块 */
                if (now - lastStats >= 1000) {
                    lastStats = now;
                    if (daemonAlive()) readStats();
                }

                /* ④ CP 侧 dashcastd 探活（5 秒一次，或矩形连败 3 次立即触发）。
                 *    原来 30 秒一次 —— 切编码档后 dashcastd 会自己退出，
                 *    30 秒意味着仪表要黑几十秒。 */
                if (now - lastCpAliveCheck >= 5000 || rectFailCount >= 3) {
                    lastCpAliveCheck = now;
                    if (gen.get() != myGen) return;
                    if (!cp.isDashcastdRunning()) {
                        Log.w(TAG, "CP 侧 dashcastd 不在运行，尝试恢复…");
                        if (cp.ensureDashcastd() && cp.startDashcastd()) {
                            Log.i(TAG, "CP 侧 dashcastd 已恢复，重置图层同步退避");
                            resetRectBackoff();
                            lastRectSync = 0;
                            forceFullFrame();    /* CP 是新会话，整屏补一帧 */
                        } else {
                            Log.e(TAG, "CP 侧 dashcastd 恢复失败（CP 不可达或文件推不上）");
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
                        Log.i(TAG, "目标 App 已退出，结束投屏会话");
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
            Log.e(TAG, "mirror 循环异常", t);
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
                Log.i(TAG, "dcapd2 已是最新");
                return true;
            }

            String b64 = readAsset("dcapd2.b64");
            if (b64 == null || b64.isEmpty()) {
                Log.e(TAG, "assets 里没有 dcapd2.b64");
                return false;
            }

            Log.i(TAG, "推送 dcapd2（" + b64.length() + " 字符）");
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
            Log.i(TAG, ok ? "dcapd2 落盘校验通过" : "dcapd2 落盘校验失败: " + chk);
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "部署失败: " + t);
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
            Log.e(TAG, "读 asset 失败: " + name, t);
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
            int[] safeR = r != null ? r : new int[]{0, 0, OUT_W, OUT_H};
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
            Log.i(TAG, "launchDaemon 判定: isMapHudActive=" + hud
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
                Log.i(TAG, "HUD 投屏：抓 Display " + HUD_DISPLAY_ID
                        + "（物理屏 #" + HUD_PHYSICAL_INDEX + "）"
                        + "源 " + HUD_W + "×" + HUD_H
                        + " → 裁 x=" + HUD_SRC_X + " y=" + HUD_SRC_Y
                        + " " + HUD_SRC_W + "×" + HUD_SRC_H);
            } else if (mapMode) {
                Log.i(TAG, "地图模式：源 " + safeR[2] + "×" + safeR[3]
                        + " → 抓 " + cap[2] + "×" + cap[3]
                        + " @ (" + cap[0] + "," + cap[1] + ")");
            }

            String cmd = "rm -f " + PAUSE + "; pkill -x dcapd2; "
                    + "cd /data/local/tmp && nohup ./dcapd2 "
                    + s[0] + " " + s[1] + " "
                    + cap[0] + " " + cap[1] + " " + cap[2] + " " + cap[3] + " "
                    + OUT_W + " " + OUT_H + " "
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
                    Log.i(TAG, "dcapd2 已启动");
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
                Log.i(TAG, "已请求整屏全量重画");
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
                int[] r = LayerGeometry.destinationFor(codec, currentMaskRect(), OUT_W, OUT_H);

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
                                Log.i(TAG, "图层矩形已应用 → " + key);
                            }
                            /* 保活成功：完全静默。任何级别都会淹掉真信息 */
                            done = true;
                            return;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "图层矩形同步异常（第 " + (i + 1) + " 次）: " + t);
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
                    Log.w(TAG, "图层矩形同步失败，退避 " + (back / 1000) + " 秒后再试");
                }
            }
        }, "layer-rect").start();
    }

    /** 读当前遮罩矩形（从 prefs）；没有遮罩就返回整屏 */
    private int[] currentMaskRect() {
        try {
            LayerConfig lc = LayerConfig.loadFromPrefs(ctx, OUT_W, OUT_H);
            if (lc != null && lc.enabled && lc.w > 0 && lc.h > 0) {
                return new int[]{lc.x, lc.y, lc.w, lc.h};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{0, 0, OUT_W, OUT_H};
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
        if (!mapMode) return win;

        LayerConfig lc = LayerConfig.loadFromPrefs(ctx, OUT_W, OUT_H);
        if (lc == null || lc.w <= 0 || lc.h <= 0
                || lc.w >= OUT_W || lc.h >= OUT_H) {
            return win;   /* 无遮罩或遮罩是整屏 → 不裁 */
        }

        /*
         * 优先用**显式指定的抓取源区域**（layer_src_x / _y / _h）。
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

        double px0 = (double) lc.x / OUT_W;
        double py0 = (double) lc.y / OUT_H;
        double px1 = (double) (lc.x + lc.w) / OUT_W;
        double py1 = (double) (lc.y + lc.h) / OUT_H;

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
        Log.i(TAG, "地图模式 " + (on ? "开启（按遮罩裁剪）" : "关闭（整窗口缩放）"));
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
             * **HUD 通常已经在跑**（系统预装、开机自启）。
             * 先判断进程，活着就完全不动它 ——
             * 这是"点多次不要跳转别的 App"的根本保证。
             *
             * 为什么不能每次 `am start`：
             *   `am start` 即使指定了 display，也会把目标 Activity
             *   **带到前台 / 抢焦点**，于是 dashcast 自己的界面被挤走。
             *   用户看到的就是"点几次地图 HUD 就跳到别的 App"。
             *   实测确认。
             *
             * 而且我们抓的是 Display 1 的**像素**，跟它是否在前台无关 ——
             * 进程活着就行。
             */
            String running = AdbShell.exec("pidof " + HUD_PKG, 3000);
            boolean alive = running != null && !running.trim().isEmpty();

            if (alive) {
                Log.i(TAG, "HUD 进程已在运行（pid=" + running.trim()
                        + "），不重复启动、不抢焦点");
                return true;
            }

            /* 真没在跑才启动（这时才可能抢焦点，但属于必要的一次） */
            AdbShell.exec("am start --display " + HUD_DISPLAY_ID
                    + " -n " + HUD_PKG + "/" + HUD_CLS, 6000);
            sleep(1200);

            /* 回读确认 */
            String out = AdbShell.exec(
                    "dumpsys activity activities | grep -A3 'Display #"
                            + HUD_DISPLAY_ID + "'", 6000);
            boolean ok = out != null && out.contains(HUD_PKG);
            Log.i(TAG, "HUD 投 Display " + HUD_DISPLAY_ID
                    + "：" + (ok ? "确认到位" : "未确认（看 dumpsys）"));
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "启动 HUD 失败: " + t);
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
        Log.i(TAG, "HUD 保留在 Display " + HUD_DISPLAY_ID + "（系统应用，无需撤离）");
    }

    // ============================== 设备查询 ==============================

    /** 读目标 App 的窗口矩形。优先 mAppBounds（排除底部导航条）。 */
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
                Log.w(TAG, "没解析到窗口矩形: " + out);
                return null;
            }
            int x1 = Integer.parseInt(m.group(1));
            int y1 = Integer.parseInt(m.group(2));
            int x2 = Integer.parseInt(m.group(3));
            int y2 = Integer.parseInt(m.group(4));
            if (x2 <= x1 || y2 <= y1) return null;
            return new int[]{x1, y1, x2 - x1, y2 - y1};
        } catch (Throwable t) {
            Log.e(TAG, "取窗口矩形失败", t);
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

    public static int getFps() {
        return fps;
    }

    public void setFps(Context c, int f) {
        if (f < 5) f = 5;
        if (f > 120) f = 120;
        fps = f;
        prefs(c).edit().putInt(PREF_FPS, f).apply();
        Log.i(TAG, "帧率切到 " + f + " fps");
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

        new Thread(() -> {
            try {
                AdbShell.exec("echo " + codec + " > " + CODEC_FILE, 2000);
                Log.i(TAG, "编码档位切到 "
                        + (codec == CODEC_H264 ? "H.264" : "BGRA+LZ4")
                        + "（已写入 " + CODEC_FILE + "，dcapd2 自行切换）");
            } catch (Throwable t) {
                Log.w(TAG, "写编码档文件失败: " + t.getMessage());
            }
        }, "codec-switch").start();
    }

    /** 读 dcapd2 的 STAT 行：帧率 / 图块 / 带宽 */
    private void readStats() {
        try {
            String out = AdbShell.exec("tail -3 " + LOGF, 4000);
            if (out == null) return;

            Matcher m = Pattern.compile(
                    "STAT fps=(\\d+) capture=(\\d+) tiles=(\\d+) kbps=(\\d+)").matcher(out);
            String last = null;
            int f = 0, t = 0, k = 0;
            while (m.find()) {
                last = m.group(0);
                f = Integer.parseInt(m.group(1));
                t = Integer.parseInt(m.group(3));
                k = Integer.parseInt(m.group(4));
            }
            if (last == null) return;

            if (listener != null) listener.onStats(f, k, t);

            // 有整帧要发却一个字节都没发出去 → 基本是 CP 端把连接关了。
            // 把 dashcastd 的日志拉回来，省得靠猜。
            if (k == 0 && t > 0 && SystemClock.elapsedRealtime() - lastCpLog > 10000) {
                lastCpLog = SystemClock.elapsedRealtime();
                new Thread(() -> {
                    try {
                        String s = cp.tailLog(40);
                        for (String line : s.split("\n")) Log.w(TAG + "/CP", line);
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
            Log.i(TAG, "已暂停推流（保持连接，仪表留最后一帧）");
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
        Log.i(TAG, msg);
        if (!running) {
            Log.i(TAG, "已停止，忽略迟到的状态回调: " + msg);
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
