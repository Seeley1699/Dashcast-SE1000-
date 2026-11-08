/*
 * MapCastService.java —— 地图投屏总控（方案 ①：APK 内一键投屏）
 *
 * 流程：
 *   ① 从 assets/hudcast-inject.gz 解压 injector 到 /data/local/tmp/
 *   ② 找地图进程 pid（pidof com.geely.map）
 *   ③ 推 map_to_dash_prod.js 脚本
 *   ④ hudcast-inject -p <pid> -s <script>
 *   ⑤ 起 dcapd2（1920x532 → CP 8444）
 *   ⑥ 部署 + 启动 CP 侧 dashcastd（走 CpManager）
 *   ⑦ 定时巡检：地图进程变了就重新注入
 *
 * 依赖：
 *   AdbShell.exec()  —— 已有 root shell 通道
 *   CpManager        —— 已有 CP 部署能力
 *
 * 关键限制（已实测）：
 *   · Dashcast 进程没有 CAPTURE_VIDEO_OUTPUT，不能自己建 VirtualDisplay
 *     → 必须靠注入地图进程（它有权）
 *   · 地图进程重启后注入失效 → 必须巡检重注入
 */

package com.hermes.dashcast;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

public final class MapCastService {

    private static final String TAG = "DashcastMapCast";

    /* 设备端路径 */
    private static final String TMP = "/data/local/tmp";
    private static final String INJECTOR = TMP + "/hudcast-inject";
    private static final String SCRIPT = TMP + "/mapcast.js";
    private static final String DCAPD2 = TMP + "/dcapd2";

    /* assets 里的资源名
     *
     * 为什么叫 .bin 不叫 .gz：
     *   aapt 对 assets 里的 .gz 会**自动解压**（实测打进 APK 的是
     *   56MB 的明文而不是 24MB 的 gz），APK 直接胖 32MB。
     *   改成 .bin 后 aapt 原样打包，App 里按魔数 1f 8b 判断是否 gzip。
     */
    private static final String AS_INJECTOR = "hudcast-inject.bin";
    private static final String AS_SCRIPT = "mapcast.js";
    private static final String AS_DCAPD2 = "dcapd2";

    /* 显示参数 —— 与 CP 屏一致 */
    private static final int W = 1920;
    private static final int H = 532;

    /* 地图包名 */
    private static final String MAP_PKG = "com.geely.map";

    /* CP 地址 */
    private static final String CP_HOST = "198.18.34.2";
    private static final int CP_PORT = 8444;

    private static final MapCastService INSTANCE = new MapCastService();

    public static MapCastService get() { return INSTANCE; }

    private final Object lock = new Object();
    private volatile boolean running = false;
    private volatile int injectedPid = -1;
    private volatile String dcapd2Pid = "";
    private HandlerThread worker;
    private Handler wh;
    private Context appCtx;

    public interface Callback {
        void onState(String msg);
    }

    private Callback cb;

    /* ─────────────────────────── 对外接口 ─────────────────────────── */

    public boolean isRunning() { return running; }

    public int injectedPid() { return injectedPid; }

    /** 启动地图投屏（幂等） */
    public boolean start(Context ctx, Callback callback) {
        synchronized (lock) {
            if (running) {
                notify2("已在运行");
                return true;
            }
            appCtx = ctx.getApplicationContext();
            cb = callback;

            if (worker == null) {
                worker = new HandlerThread("mapcast");
                worker.start();
                wh = new Handler(worker.getLooper());
            }

            // 全流程放后台线程（解压 58MB 会阻塞）
            wh.post(this::bootSequence);
            return true;
        }
    }

    /** 停止地图投屏 */
    public boolean stop() {
        synchronized (lock) {
            running = false;
            injectedPid = -1;
            dcapd2Pid = "";
            /*
             * **先释放长活通道**（2026-11-08）。
             *
             * LongLivedShell 持有的 socket 一关，adbd 立刻回收它创建的
             * 进程组 —— dcapd2 / hudcast-inject 跟着退，不用再 pkill。
             * 顺序很重要：先释放再 pkill，否则 pkill 前进程可能已被
             * 重新拉起（地图是系统应用，它的注入器被杀了不影响地图本身，
             * 但 dcapd2 被 KeepAlive 之类拉起过一次，实测会残留）。
             */
            try {
                LongLivedShell.releaseAll();
            } catch (Throwable t) {
                Log.w(TAG, "releaseAll err: " + t);
            }
            try {
                AdbShell.exec("pkill -9 -f hudcast-inject 2>/dev/null; "
                        + "pkill -9 -f dcapd2 2>/dev/null; echo DONE", 6000);
            } catch (Throwable t) {
                Log.w(TAG, "stop err: " + t);
            }
            try {
                CpManager cpm = new CpManager(appCtx);
                cpm.stopDashcastd();
                cpm.restoreDashboard();
            } catch (Throwable t) {
                Log.w(TAG, "cp stop err: " + t);
            }
            notify2("已停止");
            return true;
        }
    }

    /* ─────────────────────────── 启动流程 ─────────────────────────── */

    private void bootSequence() {
        try {
            running = true;
            Log.i(TAG, "bootSequence 开始");
            notify2("开始启动");

            /* ① 准备 injector */
            notify2("准备注入器…");
            Log.i(TAG, "→ ensureBinary(injector)");
            if (!ensureBinary(AS_INJECTOR, INJECTOR, true)) {
                notify2("注入器准备失败");
                running = false;
                return;
            }
            Log.i(TAG, "← injector OK");

            /* ② 准备脚本 */
            notify2("准备脚本…");
            Log.i(TAG, "→ ensureBinary(script)");
            if (!ensureBinary(AS_SCRIPT, SCRIPT, false)) {
                notify2("脚本准备失败");
                running = false;
                return;
            }
            Log.i(TAG, "← script OK");

            /* ③ 准备 dcapd2 */
            Log.i(TAG, "→ ensureBinary(dcapd2)");
            ensureBinary(AS_DCAPD2, DCAPD2, true);
            Log.i(TAG, "← dcapd2 OK");

            /* ④ 启动 CP 侧 dashcastd */
            notify2("部署 CP 服务…");
            Log.i(TAG, "→ CP deploy");
            try {
                CpManager cpm = new CpManager(appCtx);
                if (!cpm.isDashcastdRunning()) {
                    cpm.pushDashcastd();
                    cpm.startDashcastd();
                }
                notify2("CP 服务就绪");
                Log.i(TAG, "← CP OK");
            } catch (Throwable t) {
                notify2("CP 部署失败（可稍后重试）: " + brief(t));
                Log.w(TAG, "CP 部署异常", t);
            }

            /* ⑤ 启动 dcapd2 */
            notify2("启动抓帧服务…");
            Log.i(TAG, "→ startDcapd2");
            startDcapd2();
            Log.i(TAG, "← dcapd2 启动完成");

            /* ⑥ 注入地图 */
            Log.i(TAG, "→ injectMap");
            injectMap();
            Log.i(TAG, "← injectMap 完成");

            /* ⑦ 巡检 */
            startWatchdog();
            Log.i(TAG, "bootSequence 完成");

        } catch (Throwable t) {
            Log.e(TAG, "boot fail", t);
            notify2("启动失败: " + brief(t));
            running = false;
        }
    }

    /** 把 assets 里的资源解压/复制到设备路径 */
    private boolean ensureBinary(String assetName, String devPath, boolean exec) {
        try {
            File f = new File(devPath);
            /* 已存在且大小合理就跳过（避免每次解压 58MB） */
            if (f.exists() && f.length() > 1024) {
                Log.i(TAG, devPath + " 已存在 (" + f.length() + "B)，跳过");
                if (exec) {
                    AdbShell.exec("chmod 755 " + devPath, 3000);
                }
                return true;
            }
            notify2("解压 " + assetName + " …");
            InputStream raw = appCtx.getAssets().open(assetName);
            /* 按魔数判断是否 gzip（1f 8b）—— assets 里叫 .bin，aapt 不会自动解压 */
            java.io.PushbackInputStream pin =
                    new java.io.PushbackInputStream(raw, 2);
            byte[] magic = new byte[2];
            int mr = pin.read(magic);
            pin.unread(magic, 0, mr);
            InputStream in = pin;
            if (mr == 2 && (magic[0] & 0xFF) == 0x1f && (magic[1] & 0xFF) == 0x8b) {
                in = new GZIPInputStream(pin, 8192);
                Log.i(TAG, assetName + " 识别为 gzip，开始解压");
            } else {
                Log.i(TAG, assetName + " 非 gzip，直接拷贝");
            }
            /* 先落到 AP 侧临时文件，再用 root 拷到 tmp（普通 app 写不进 tmp） */
            File stage = new File(appCtx.getFilesDir(), assetName + ".stage");
            FileOutputStream fo = new FileOutputStream(stage);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                total += n;
            }
            fo.close();
            in.close();
            Log.i(TAG, assetName + " 解压完成 " + total + "B");

            /* root 拷贝 */
            String cp = "cp " + stage.getAbsolutePath() + " " + devPath
                    + (exec ? " && chmod 755 " + devPath : "")
                    + " && echo OK";
            String r = AdbShell.exec(cp, 30000);
            stage.delete();
            if (r == null || !r.contains("OK")) {
                Log.w(TAG, "拷贝失败: " + r);
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "ensureBinary fail " + assetName, t);
            notify2("解压失败: " + brief(t));
            return false;
        }
    }

    /** 启动 dcapd2（1920x532 → CP）
     *
     * **进程存活问题：六轮才定位到真因**（2026-11-08）
     *
     *   ① `( cmd & )`            —— 被 SIGHUP 带走
     *   ② `setsid --fork cmd &`  —— Toybox setsid 无 --fork
     *   ③ `setsid cmd &`         —— 能起，几秒后消失
     *   ④ `nohup setsid cmd &`   —— 手动 adb shell 跑能活，经 AdbShell 跑就死
     *   ⑤ `sh -c '...' &` 双层   —— 还是死
     *   ⑥ 真因：AdbShell.runCommand 收到 A_CLSE 就返回，
     *      exec() 随即 closeQuietly() / 复用 socket；
     *      adbd 在这条 shell 流关闭时会把**该流创建的子进程组**一起回收。
     *      手动 adb 命令行不关流，所以进程能活。
     *
     * **解法：用 LongLivedShell 开独立通道并持有不关。**
     *   只要那条 socket 活着，adbd 就动不了这个进程组。
     *   停止投屏时 releaseAll() 关 socket → 进程组随之被回收，
     *   正好对应"会话结束、抓帧该退"的语义。
     */
    private void startDcapd2() {
        try {
            /* 先清场（用普通通道，短命令没问题） */
            AdbShell.exec("pkill -9 -f dcapd2 2>/dev/null; rm -f "
                    + TMP + "/dcapd2.layer; echo OK", 8000);

            String cmd = DCAPD2
                    + " " + W + " " + H + " 0 0 " + W + " " + H + " " + W + " " + H
                    + " " + CP_HOST + " " + CP_PORT + " 30 0"
                    + " >> " + TMP + "/dcapd2.log 2>&1";
            LongLivedShell.Handle h = LongLivedShell.spawnDcapd2(cmd);
            if (h == null || !h.isAlive()) {
                notify2("抓帧通道建立失败");
                return;
            }

            Thread.sleep(3000);
            String chk = AdbShell.exec("pidof dcapd2", 6000);
            String p = chk == null ? "" : chk.trim();
            Log.i(TAG, "dcapd2 pid = [" + p + "]");
            if (p.isEmpty()) {
                notify2("抓帧服务未启动");
            } else {
                dcapd2Pid = p;
                notify2("抓帧服务已启动 pid=" + p);
            }
        } catch (Throwable t) {
            notify2("抓帧服务启动失败: " + brief(t));
        }
    }

    /** 找地图 pid 并注入 */
    private boolean injectMap() {
        try {
            String r = AdbShell.exec("pidof " + MAP_PKG, 5000);
            if (r == null) return false;
            String s = r.trim();
            if (s.isEmpty()) {
                notify2("地图未运行，等待…");
                return false;
            }
            /* 可能返回多个 pid，取最后一个（u11 主进程通常在最后） */
            String[] parts = s.split("\\s+");
            int pid = Integer.parseInt(parts[parts.length - 1]);
            if (pid == injectedPid) {
                return true;   /* 同一个进程，已注入 */
            }

            notify2("注入地图进程 " + pid + " …");
            /* 同 startDcapd2：LongLivedShell 持有通道，adbd 才不动这个进程 */
            AdbShell.exec("pkill -9 -f hudcast-inject 2>/dev/null; echo OK", 8000);
            String cmd = INJECTOR + " -p " + pid
                    + " -s " + SCRIPT + " -R v8"
                    + " >> " + TMP + "/mapcast.log 2>&1";
            LongLivedShell.Handle h = LongLivedShell.spawnInjector(cmd);
            if (h == null || !h.isAlive()) {
                notify2("注入通道建立失败");
                return false;
            }

            Thread.sleep(3500);
            String chk = AdbShell.exec("pidof hudcast-inject", 6000);
            String p = chk == null ? "" : chk.trim();
            Log.i(TAG, "inject pid = [" + p + "]");
            if (p.isEmpty()) {
                notify2("注入失败（进程未起）");
                return false;
            }
            injectedPid = pid;
            notify2("注入成功 pid=" + pid);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "inject fail", t);
            notify2("注入失败: " + brief(t));
            return false;
        }
    }

    /** 巡检：地图进程变了就重注入 */
    private void startWatchdog() {
        final Runnable[] task = new Runnable[1];
        task[0] = () -> {
            if (!running) return;
            try {
                String r = AdbShell.exec("pidof " + MAP_PKG, 3000);
                if (r != null && !r.trim().isEmpty()) {
                    String[] parts = r.trim().split("\\s+");
                    int pid = Integer.parseInt(parts[parts.length - 1]);
                    if (pid != injectedPid) {
                        Log.i(TAG, "地图进程变了 " + injectedPid + " → " + pid + "，重注入");
                        notify2("地图重启，重新注入…");
                        injectMap();
                    }
                }
            } catch (Throwable ignored) {
            }
            if (running) wh.postDelayed(task[0], 5000);
        };
        wh.postDelayed(task[0], 5000);
    }

    private void notify2(String msg) {
        Log.i(TAG, msg);
        Callback c = cb;
        if (c != null) {
            try { c.onState(msg); } catch (Throwable ignored) {}
        }
    }

    private static String brief(Throwable t) {
        String s = String.valueOf(t);
        return s.length() > 70 ? s.substring(0, 70) : s;
    }
}
