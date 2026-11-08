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

            /*
             * ⑥.5 用 shell 身份把 HudActivity 拉到我们的 VirtualDisplay
             *
             * **为什么必须从 App 侧发这条 adb 命令**（2026-11-08）
             *
             * 注入的脚本跑在 com.geely.map 进程里，它调 startActivity 到「别人
             * 创建的 VirtualDisplay」会被系统拒绝：
             *   SecurityException: Permission Denial: starting Intent {...}
             * 跨 display 启动需要 INTERNAL_SYSTEM_WINDOW 级权限，普通 App 拿不到。
             *
             * 而 `adb shell am start --display N -n ...` 用 shell 身份能成功
             * （实测：dumpsys 显示 Activity Resumed 在 display 35）。
             *
             * 所以脚本只把 VDID 打到日志，这里读出来再用 adb shell 发起。
             *
             * 换成 HudActivity 的原因：引擎只在 Activity 生命周期内给
             * 「路线 / 红绿灯 / 车道」创建 native 图层（RoutePathLayer 等）。
             * 手动 HUDControl.initSurfaceView 拿到的只有底图。
             */
            int vdId = readVdid(10000);
            if (vdId >= 0) {
                /*
                 * **launchMode=singleTask 会阻止 activity 换 display**（2026-11-08）
                 *
                 * HudActivity 在清单里是 launchMode=singleTask，配合
                 * FLAG_ACTIVITY_NEW_TASK 时系统行为是：
                 *   "Warning: Activity not started, its current task has been
                 *    brought to the front"
                 * 即把它**已有那个 task 提到前台**，而不是在 --display 指定
                 * 的新 display 上创建。实测：display 37 建起来了但 stacks=0，
                 * HudActivity 仍留在旧 task 1102405。
                 *
                 * 用 --activity-clear-task 先把旧 task 里的实例清掉，再让它在新
                 * display 上 onCreate。注意**不能** force-stop 地图 App 或
                 * Dashcast（会把正在跑的进程一起杀掉）。
                 */
                AdbShell.exec("am start --display " + vdId
                        + " --activity-clear-task --activity-new-task"
                        + " -n com.geely.map/com.ecarx.navi.multiscreen.hud.HudActivity",
                        12000);
                String lr = AdbShell.exec("am start --display " + vdId
                        + " -n com.geely.map/com.ecarx.navi.multiscreen.hud.HudActivity",
                        12000);
                Log.i(TAG, "HudActivity launch(display=" + vdId + ") → " + lr);
                /* 给它几秒 onCreate + 建 SurfaceView */
                Thread.sleep(2500);
            } else {
                Log.w(TAG, "拿不到 VDID，HudActivity 未拉起");
            }

            /* ⑦ 巡检 */
            startWatchdog();
            Log.i(TAG, "bootSequence 完成");

            /* ⑧ 等第一帧真画面，再报"运行中" */
            /*
             * **必须等 dcapd2 真的出帧才算就绪**（2026-11-08）
             *
             * bootSequence 结束时只是"进程都起来了"，地图那边还要：
             *   VirtualDisplay 建好 → MapView 加窗 → initSurfaceView 注册 slot 5
             *   → 引擎开始画 → PixelCopy 出帧 → VDF1 到 dcapd2
             * 这段实测要 5-10 秒。提前报"已就绪"会让用户以为投上了，
             * 结果仪表还是原厂画面。
             */
            /*
             * **窗口给到 25 秒**（2026-11-08 实测）
             *
             * 从 injectMap 返回到 dcapd2 真的收到 VD 帧，实测要 15-20 秒：
             *   injector 起来（3s）→ 建 VirtualDisplay → addView
             *   → initSurfaceView 注册 slot 5 → 引擎开始画
             *   → PixelCopy 出第一帧 → VDF1 到 dcapd2 → 日志出现 "VD 帧"
             * 原来给 12 秒，日志显示 "capture=11 tiles=4 totalKB=816"
             * 已经在推流了，但判据那次采样还没看到 → 误报"未出帧"。
             */
            if (waitFirstFrame(25000)) {
                notify2("地图投屏运行中");
            } else {
                notify2("已启动，但暂未出帧（地图可能刚重启，稍等会自动重注入）");
            }

        } catch (Throwable t) {
            Log.e(TAG, "boot fail", t);
            notify2("启动失败: " + brief(t));
            running = false;
        }
    }

    /** 把 assets 里的资源解压/复制到设备路径 */
    private boolean ensureBinary(String assetName, String devPath, boolean exec) {
        try {
            /* 期望大小：assets 里的原始大小
             *
             * 对于 .bin（gzip 的 injector）要和**解压后**的大小比，
             * 这里拿不到，所以用 asset 原始大小做下限即可 —— injector
             * 解压后 56MB ≫ 24MB，不会被误判。
             */
            long assetSize = -1;
            try {
                /* openFd 只对**未压缩**的 assets 有效；APK 里 assets 默认是
                 * deflate 压缩的，openFd 会直接抛 FileNotFoundException。
                 * 测下来 /data/local/tmp/mapcast.js 一直显示 asset=-1B，
                 * 于是退回老的 `>1024` 判断 —— 这就是上一次"明明换了脚本
                 * 设备上还是旧版"的原因。
                 *
                 * available() 对压缩 asset 会给解压后的字节数，任何情况下都能用。 */
                java.io.InputStream is = appCtx.getAssets().open(assetName);
                try {
                    assetSize = is.available();
                } finally {
                    try { is.close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {
            }

            File f = new File(devPath);
            /*
             * **跳过条件必须比对大小，不能只看 >1024**（2026-11-08 修）
             *
             * 原来写 `f.length() > 1024` 就跳过。结果我调试时手工推了一份
             * 16KB 的 map_to_dash_prod.js（**没拼 java-bridge**）到
             * /data/local/tmp/mapcast.js，App 看 16KB > 1024 就认为"已就绪"，
             * 一直复用它。mapcast.js 一跑就 ReferenceError: Java is not defined，
             * 地图侧 frames 恒为 0，dcapd2 收不到帧 —— 但 UI 显示"运行中"，
             * 极难查。
             *
             * 现在：dev 文件必须 >= assets 原始大小（.gz/.bin 用其压缩后大小
             * 做下限是安全的，因为解压只会更大），否则重新解压覆盖。
             */
            boolean ok = f.exists() && (assetSize < 0
                    ? f.length() > 1024
                    : f.length() >= assetSize);
            if (ok) {
                Log.i(TAG, devPath + " 已存在 (" + f.length() + "B, asset="
                        + assetSize + "B)，跳过");
                if (exec) {
                    AdbShell.exec("chmod 755 " + devPath, 3000);
                }
                return true;
            }
            if (f.exists()) {
                Log.w(TAG, devPath + " 大小不符（dev " + f.length()
                        + "B < asset " + assetSize + "B），重新部署");
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
            /* 清场分两步，中间必须真的等进程死透
             *
             * （2026-11-08，第三次才搞对。前两次的失败原因都记在这里）
             *
             *   ① `rm -f dcapd2.log` —— 旧进程持有 fd，`rm` 只是 unlink，
             *      内容继续写进已删的 inode；新进程重建同名文件 → 两份交错。
             *
             *   ② `pkill; : > log` 一条命令串 —— 还是不行。
             *      实测：`pkill` 发完信号立刻 truncate，被杀进程的缓冲区
             *      还没来得及 flush 到 inode 就被清掉了，但内核回收 fd 时
             *      **会把它最后一次 flush 写回去**，日志又变回旧内容。
             *      手动复现：先 echo 一行 → 跑这条命令串 → wc -l 仍是 25。
             *
             *   ③ 正确顺序：kill → **等 2 秒真的死透** → truncate → 启动。
             *      分两次 AdbShell.exec，中间 Thread.sleep。
             */
            AdbShell.exec("pkill -9 -f dcapd2 2>/dev/null; echo KILLED", 6000);
            Thread.sleep(2000);

            /*
             * ★ 和「图层遮罩」关联（2026-11-08）
             *
             * 原来这里写的是 `rm -f dcapd2.layer` —— 每次地图投屏都**删掉**遮罩
             * 配置，所以界面上调的遮罩对地图投屏完全不起作用。
             *
             * 但 dcapd2 本身是支持遮罩的：
             *   · 启动时 dc_layer_load_file(LAYER_CFG_FILE) 读配置（dcapd2.c:338）
             *   · 每帧编码前 dc_layer_apply(&g_mask, ...) 应用（dcapd2.c:2108）
             *   · VD 模式下同样生效
             *
             * 现在改成：先把 App 里当前的遮罩配置推下去（LayerConfig.push()），
             * dcapd2 启动时自然读到 —— 这样地图投屏用的就是界面上设置的遮罩。
             *
             * 注意 push() 内部会检查线程（主线程调用会抛异常），
             * 而 startDcapd2() 本身就跑在 worker 线程，可以直接调。
             */
            try {
                LayerConfig lc = LayerConfig.loadFromPrefs(appCtx, W, H);
                if (lc != null) {
                    lc.push();
                    Log.i(TAG, "已下发遮罩配置（enabled=" + lc.enabled
                            + " shape=" + lc.shape
                            + " " + lc.x + "," + lc.y + " " + lc.w + "x" + lc.h
                            + " 圆角=" + lc.rTl + " 羽化=" + lc.feather + "）");
                }
            } catch (Throwable t) {
                Log.w(TAG, "下发遮罩配置失败（不影响投屏）: " + t);
            }

            AdbShell.exec(": > " + TMP + "/dcapd2.log; "
                    + "wc -l " + TMP + "/dcapd2.log", 8000);

            /*
             * 启动参数里的 srcRect / dstRect 也要跟遮罩对齐。
             *
             * dcapd2 的 argv 是：
             *   argv[1] dispW   argv[2] dispH     ← 画布（固定 1920x532）
             *   argv[3] srcX    argv[4] srcY      ← 抓取源矩形
             *   argv[5] srcW    argv[6] srcH
             *   argv[7..]                         ← 目标/画布尺寸
             *
             * 地图投屏的「源」是我们自己的 VirtualDisplay，它本身就是 1920×532
             * 全幅，所以 srcRect 保持 0,0,W,H 不动 —— 遮罩裁切交给
             * dc_layer_apply 在编码前做，这样遮罩的圆角/羽化/梯形都能用上，
             * 而不是只能靠 srcRect 裁一个硬边矩形。
             */
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
            /* **必须挑 u11 那个进程，不能取 pidof 的最后一个**（2026-11-08）
             *
             * 实测地图有两个同包名进程：
             *   766  u11_a128  com.geely.map   ← 真正的 UI/渲染进程，要注这个
             *   8788 u0_a128   com.geely.map   ← 辅助进程，注它的话
             *                                    PixelCopy 全部 ERROR_SOURCE_INVALID(3)
             *
             * 原来看起来"取最后一个正合适"只是碰巧顺序，pidof 的输出顺序
             * 没有任何保证。而且当时日志里 VirtualDisplay 建在 displayId=23，
             * 但地图根本没在上面画 —— 因为 8788 这个进程没有 UI、没有窗口。
             *
             * 改为：ps 全量列出来，按用户名筛 u11 前缀的那个。
             */
            String r = AdbShell.exec(
                    "ps -A -o pid,user,args 2>/dev/null | grep 'com.geely.map' "
                    + "| grep -v grep", 6000);
            if (r == null) return false;

            int pid = -1;
            String picked = "";
            for (String line : r.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                String[] cols = t.split("\\s+");
                if (cols.length < 3) continue;
                String user = cols[1];
                if (user.startsWith("u11")) {
                    try {
                        pid = Integer.parseInt(cols[0]);
                        picked = t;
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (pid < 0) {
                /* 没有 u11 进程 → 地图可能刚重启还没起 UI，稍后 watchdog 会重试 */
                notify2("地图主进程(u11)未就绪，等待…");
                Log.w(TAG, "ps 输出:\n" + r);
                return false;
            }

            if (pid == injectedPid) {
                return true;   /* 同一个进程，已注入 */
            }

            notify2("注入地图进程 " + pid + " …");
            /* 同 startDcapd2：LongLivedShell 持有通道，adbd 才不动这个进程 */
            /* 同 startDcapd2：kill → 等死透 → truncate，不能一条命令串做完 */
            AdbShell.exec("pkill -9 -f hudcast-inject 2>/dev/null; echo KILLED", 6000);
            Thread.sleep(1500);
            AdbShell.exec(": > " + TMP + "/mapcast.log; echo OK", 6000);
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
                /* 和 injectMap 用同一套挑 pid 的规则：只看 u11 那个进程，
                 * 否则 u0 的辅助进程会让这里一直误判"进程变了"→ 反复重注入 */
                String r = AdbShell.exec(
                        "ps -A -o pid,user,args 2>/dev/null | grep 'com.geely.map' "
                        + "| grep -v grep", 5000);
                if (r != null) {
                    int pid = -1;
                    for (String line : r.split("\n")) {
                        String t = line.trim();
                        if (t.isEmpty()) continue;
                        String[] cols = t.split("\\s+");
                        if (cols.length < 3) continue;
                        if (cols[1].startsWith("u11")) {
                            try { pid = Integer.parseInt(cols[0]); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                    if (pid > 0 && pid != injectedPid) {
                        Log.i(TAG, "地图主进程变了 " + injectedPid + " → " + pid + "，重注入");
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

    /**
     * 从 mapcast.log 里读脚本打出来的 `VDID=<n>`。
     *
     * 脚本在创建 VirtualDisplay 后立刻打这一行，是我们和脚本之间唯一
     * 可靠的「displayId 握手」——因为 App 进程没法直接读到地图进程里的 VD 对象。
     *
     * 注意日志是 `>>` 追加的，所以要取**最后一条** VDID（上一次会话的残留
     * 会留在文件里），injectMap() 每次启动前已经 truncate 过，但要防
     * truncate 因进程持有 fd 而失效（见 startDcapd2 的注释），所以用 tail -1。
     */
    private int readVdid(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                String r = AdbShell.exec(
                        "grep -o 'VDID=[0-9]*' " + TMP + "/mapcast.log 2>/dev/null | tail -1",
                        5000);
                if (r != null) {
                    String t = r.trim();
                    if (t.startsWith("VDID=")) {
                        try {
                            int id = Integer.parseInt(t.substring(5));
                            if (id > 0) {
                                Log.i(TAG, "读到 VDID=" + id);
                                return id;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (Throwable ignored) {
            }
            try { Thread.sleep(800); } catch (Throwable ignored) {}
        }
        return -1;
    }

    /**
     * 等 dcapd2 收到来自我们虚拟屏的第一帧。
     *
     * **判据：日志里出现 `VD 帧`（且 `live=1` 或中心像素非黑）**
     * （2026-11-08，六版才定稿。前面为什么全错，记下来免得再踩）
     *
     *   ① `tail -20 | grep '出真画面'` —— 只在启动时打一次，尾部全是 STAT → 永不命中
     *   ② 全文 grep + 行数基线 —— `>>` 追加时 `rm -f` 清不掉 fd，基线错位
     *   ③ `totalKB>0` —— **假阳性**：dcapd2 启动先推主屏（src=SCREEN, 492KB）
     *   ④ `src=VD && tiles>0` —— **假阴性**：静止画面无差量，tiles=0 是常态
     *   ⑤ `totalKB` 增长 —— **还是假阴性**：画面静止时 totalKB 也不涨
     *      （实测卡在 599 十几秒不动，但链路其实是好的）
     *
     * **关键认知**：dcapd2 只在画面**有变化**时才编码发图块。地图静止 →
     * tiles=0 / totalKB 不变，但**帧源已经切到 VD 且帧持续流入**了。
     * 所以真正该看的是 "VD 帧N: ... " 这行 —— 它由帧源收到的每一帧触发，
     * 不受"有没有差量"影响。
     */
    private boolean waitFirstFrame(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                String r = AdbShell.exec(
                        "grep -c 'VD 帧[0-9]' " + TMP + "/dcapd2.log 2>/dev/null || echo 0",
                        5000);
                if (r != null && r.trim().matches("\\d+") && Integer.parseInt(r.trim()) > 0) {
                    /* 再确认不是空屏：取最后一帧的 live/空屏 字段 */
                    String detail = AdbShell.exec(
                            "grep 'VD 帧[0-9]' " + TMP + "/dcapd2.log 2>/dev/null | tail -1",
                            5000);
                    String d = detail == null ? "" : detail.trim();
                    if (d.contains("空屏=0")) {
                        Log.i(TAG, "首帧已出（" + d + "）");
                        return true;
                    }
                    Log.i(TAG, "VD 帧已到但仍是空屏：" + d);
                }
            } catch (Throwable ignored) {
            }
            try { Thread.sleep(1000); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static String brief(Throwable t) {
        String s = String.valueOf(t);
        return s.length() > 70 ? s.substring(0, 70) : s;
    }
}
