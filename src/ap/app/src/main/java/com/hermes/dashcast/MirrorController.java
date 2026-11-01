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
    private static final String BIN_MD5 = "a144d5d26c6abf6dfbe9fbf7cce89fb9";
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
    private int[] lastRect;
    private Thread worker;
    private Listener listener;
    private boolean mapMode = false;

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
        gen.incrementAndGet();   // 立刻作废旧代次，旧循环下次判断就 return
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
            try {
                vd.stop(true);
            } catch (Throwable ignored) {
            }
            VdMirror.setFeedFrames(false);
            forceCleanup();
        }, "mirror-stop").start();
    }

    /** 强制清理：摘后台标志 + 杀 dcapd2，每一步都验证结果，不行就重试。 */
    private void forceCleanup() {
        Thread t = new Thread(() -> {
            synchronized (cleanupLock) {
                boolean hadDaemon = daemonAlive();
                int logLines = (hadDaemon && cp != null) ? cp.dashcastdLogLines() : -1;

                for (int i = 0; i < 4; i++) {
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

        if (!vd.start(comp, srcW, srcH, mainDpi, this::state)) {
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

                /* ① 首次进入：目标 App 必然在后台（用户是在我们界面点的按钮），
                 *    主动拉到前台，否则会一直停在"等目标 App 到前台"。 */
                if (!broughtForward) {
                    broughtForward = true;
                    state("正在打开目标 App…", false);
                    try {
                        AdbShell.exec("am start --display 0 -n " + component, 6000);
                    } catch (Throwable ignored) {
                    }
                    sleep(1000);
                    if (gen.get() != myGen) return;
                }

                /* ② 前台判定：800ms 一次。dumpsys window 约 60ms，占不到单核 10%。 */
                boolean fgIsTarget = false;
                if (now - lastFgCheck >= 800) {
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
            int[] cap = applyMapCrop(r);

            if (mapMode) {
                Log.i(TAG, "地图模式：源 " + r[2] + "×" + r[3]
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
                int[] r = currentMaskRect();

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

    private void killDaemon() {
        try {
            AdbShell.exec("rm -f " + PAUSE + "; pkill -x dcapd2", 4000);
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
