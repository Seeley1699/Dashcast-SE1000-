/*
 * DashcastSenderService.java — AP 侧抓帧推流端（方案 A：真 APK）
 *
 * 职责链（协议.md §四.6 方案 4，原理.md §二 的 ①②）：
 *   ① 建虚拟屏  DisplayManager.createVirtualDisplay（公开 API，零权限，不弹授权框）
 *                 —— 只有真 APK 才有 IApplicationThread appToken，app_process 做不到
 *   ② 抓帧      ImageReader(PixelFormat.RGBA_8888) → direct ByteBuffer
 *   ③ 编码+发送 全部下沉到 libdcap.so：拆 stride / 64×64 图块比对 /
 *                 RGBA→BGRA / LZ4 / 组帧 / TCP 推 CP:8444
 *
 * 本类只干三件事：建屏、把 buffer 递给 native、维护连接与统计。
 * 每帧只跨一次 JNI 边界，所以 1920×532 能跑满 30fps。
 *
 * 与 Java 版的关键差异：
 *   - 逐像素 ByteBuffer.get()（每帧 400 万次调用）→ native 一遍 memcmp
 *   - Java 版 LZ4（每次重新分配 4096 int 哈希表）→ native 复用哈希表
 *   - 每帧多次 write + flush → native 两次 write（仍关 Nagle）
 *   - 画面没变 → 一个字节都不发（0 图块直接返回）
 */
package com.hermes.dashcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class DashcastSenderService extends Service {

    private static final String TAG = "DashcastSender";

    // ===== 目标参数（CP 仪表屏实际尺寸 1920×532）=====
    public static final String CP_HOST = "198.18.34.2";
    public static final int CP_PORT = 8444;
    public static final int VIRT_W = 1920;
    public static final int VIRT_H = 532;
    public static final int VIRT_DPI = 160;

    /** MainActivity 会把 CP 实测到的屏幕尺寸通过这两个 extra 传进来 */
    public static final String EXTRA_WIDTH = "virt_w";
    public static final String EXTRA_HEIGHT = "virt_h";

    private int virtW = VIRT_W;
    private int virtH = VIRT_H;

    private static final int RECONNECT_DELAY_MS = 1500;

    /**
     * 帧率上限 30fps。仪表上的投屏内容不需要 60，砍一半带宽、也把 CPU 让给车机自己。
     * 协议.md §二 的带宽账：全屏视频 30fps 已经是 12–15MB/s，60fps 只会更糟。
     */
    private static final long MIN_FRAME_INTERVAL_MS = 33;

    /** MainActivity 授权后塞进 Intent 的两个 extra（MediaProjection token） */
    public static final String EXTRA_PROJECTION_CODE = "projection_code";
    public static final String EXTRA_PROJECTION_DATA = "projection_data";

    private Intent startIntent;

    private DisplayManager displayManager;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private MediaProjection mediaProjection;
    private long nativeCtx;
    private CpManager cpManager;
    private boolean cpLogDumped;
    private long connectedAt;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    /** 单个 direct buffer 不够用时的兜底拷贝区（ImageReader 一般直接给 direct） */
    private ByteBuffer fallbackBuf;

    // ===== 对 UI 的静态回调 =====
    public interface DisplayReadyListener {
        void onDisplayReady(int displayId);
    }

    public interface StatsListener {
        void onStats(int fps, int kbps, int tiles, boolean connected);
    }

    private static volatile DisplayReadyListener displayReadyListener;
    private static volatile StatsListener statsListener;

    public static void setDisplayReadyListener(DisplayReadyListener l) {
        displayReadyListener = l;
    }

    public static void setStatsListener(StatsListener l) {
        statsListener = l;
    }

    private static volatile int sDisplayId = -1;
    private static volatile boolean sRunning = false;
    private static volatile String sLastError = "";
    /** 这次失败是不是"缺 CAPTURE_VIDEO_OUTPUT"—— 是的话 UI 才去弹授权框 */
    private static volatile boolean sNeedProjection = false;

    public static boolean isProjectionRequired() {
        return sNeedProjection;
    }

    public static int getDisplayId() {
        return sDisplayId;
    }

    public static boolean isRunning() {
        return sRunning;
    }

    public static String getLastError() {
        return sLastError;
    }

    /**
     * 常驻进程里取服务单例用不到，但 MainActivity 需要判断"服务是不是还活着"：
     * 直接看静态标志，进程被杀时会一起没了，比 binder 更省事。
     */

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
    }

    /** 主动停投：带上这个 action 起服务，会立刻释放虚拟屏（不等 onDestroy） */
    public static final String ACTION_STOP = "com.hermes.dashcast.action.STOP";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "收到停止指令，释放虚拟屏");
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!sRunning) {
            startIntent = intent;
            start();
        } else if (virtualDisplay != null) {
            // 已经在投了：把现有的 displayId 再报一次给 UI，别让调用方干等回调
            Log.i(TAG, "服务已在运行，重发 displayId=" + sDisplayId);
            if (displayReadyListener != null) {
                displayReadyListener.onDisplayReady(sDisplayId);
            }
        } else {
            // 状态不一致（标志还在、屏没了），重建一次
            Log.w(TAG, "状态不一致：sRunning=true 但没有虚拟屏，重新建屏");
            startIntent = intent;
            start();
        }
        return START_NOT_STICKY;
    }

    /**
     * 释放屏 + 停 projection + 收线程。
     * 单独抽出来是因为 onDestroy 不一定会被及时调用（曾经出现过"服务没了、虚拟屏还在"的泄漏）。
     */
    private void stopEverything() {
        running.set(false);
        sRunning = false;
        sDisplayId = -1;

        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Throwable t) {
                Log.w(TAG, "release 虚拟屏失败: " + t);
            }
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Throwable t) {
                Log.w(TAG, "stop projection 失败: " + t);
            }
            mediaProjection = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Throwable ignored) {
            }
            imageReader = null;
        }
    }

    // ============================== 启动 ==============================

    private void start() {
        sNeedProjection = false;
        // 先只按 DATA_SYNC 起前台：这条路径不需要任何捕获权限。
        // 万一后面真的退到 MediaProjection 兜底，再补上 MEDIA_PROJECTION 类型。
        startForeground(1, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        // 尺寸优先用 CP 实测值（MainActivity 通过 extra 传进来），拿不到才退回常量
        if (startIntent != null) {
            int w = startIntent.getIntExtra(EXTRA_WIDTH, 0);
            int h = startIntent.getIntExtra(EXTRA_HEIGHT, 0);
            if (w >= 640 && h >= 320) {
                virtW = w;
                virtH = h;
                Log.i(TAG, "采用 CP 实测尺寸 " + virtW + "x" + virtH);
            } else {
                Log.i(TAG, "没有 CP 尺寸，用默认 " + virtW + "x" + virtH);
            }
        }

        // 防御：上一场的屏如果还挂着，先收掉再建。否则会同时存在两个 DashcastVirtual，
        // 一个空的一个有画面的，排查时非常误导。
        if (virtualDisplay != null) {
            Log.w(TAG, "检测到残留虚拟屏，先释放");
            try {
                virtualDisplay.release();
            } catch (Throwable t) {
                Log.w(TAG, "释放残留屏失败: " + t);
            }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Throwable ignored) {
            }
            imageReader = null;
        }

        mediaProjection = obtainProjection();
        if (mediaProjection != null) {
            // 只有真拿到 token 才声明这个前台类型，否则系统会认为我们在骗它
            startForeground(1, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }

        // LZ4 编码器 ↔ CP 解码器 往返自检（一次性，几毫秒）
        int st = DcapNative.selfTest();
        if (st != 0) {
            Log.e(TAG, "LZ4 自检失败，用例数 " + st + "（投出去的图块 CP 会解不开）");
            sLastError = "LZ4 自检失败";
        }

        nativeCtx = DcapNative.create(virtW, virtH);
        if (nativeCtx == 0) {
            fail("native 上下文分配失败");
            return;
        }

        imageReader = ImageReader.newInstance(virtW, virtH,
                PixelFormat.RGBA_8888, 3);

        virtualDisplay = createVirtualDisplay();
        if (virtualDisplay == null) {
            fail("createVirtualDisplay 返回 null（ROM 可能禁止非系统应用建 PUBLIC 屏）");
            return;
        }

        Display disp = virtualDisplay.getDisplay();
        int displayId = disp == null ? -1 : disp.getDisplayId();
        sDisplayId = displayId;
        sRunning = true;

        Log.i(TAG, "虚拟屏 id=" + displayId + " 状态=" + (disp == null ? "null" : disp.getState())
                + " " + virtW + "x" + virtH + "@" + VIRT_DPI);

        if (displayReadyListener != null) {
            displayReadyListener.onDisplayReady(displayId);
        }

        running.set(true);
        worker = new Thread(this::workerLoop, "dashcast-worker");
        worker.setPriority(Thread.NORM_PRIORITY + 1);
        worker.start();
    }

    /**
     * 取 MediaProjection token。
     *
     * 为什么需要：这台 ROM 的 DisplayManagerService 对 PUBLIC 虚拟屏的检查是
     *   "CAPTURE_VIDEO_OUTPUT 权限  或  一个有效的 MediaProjection token"
     * 普通安装拿不到前者的（signature|privileged），所以走后者：MainActivity 弹一次
     * 系统授权框，结果通过 Intent 传进来。授权一次，进程活着就一直有效。
     */
    private MediaProjection obtainProjection() {
        if (startIntent == null) return null;
        int code = startIntent.getIntExtra(EXTRA_PROJECTION_CODE, Integer.MIN_VALUE);
        Intent data = startIntent.getParcelableExtra(EXTRA_PROJECTION_DATA);
        if (code == Integer.MIN_VALUE || data == null) {
            Log.i(TAG, "无 MediaProjection token，尝试直接建屏（需要 CAPTURE_VIDEO_OUTPUT）");
            return null;
        }
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection mp = mpm.getMediaProjection(code, data);
            Log.i(TAG, mp != null ? "已取得 MediaProjection token" : "getMediaProjection 返回 null");
            return mp;
        } catch (Throwable t) {
            Log.e(TAG, "getMediaProjection 失败: " + t);
            sLastError = "MediaProjection: " + t.getMessage();
            return null;
        }
    }

    /**
     * 建屏。flags 的取舍只在一件事上：AUTO_MIRROR 位。
     *
     * AOSP 11 DisplayManagerService.createVirtualDisplay() 的检查顺序：
     *   PUBLIC            → 系统强制 flags |= AUTO_MIRROR
     *   OWN_CONTENT_ONLY  → 系统强制 flags &= ~AUTO_MIRROR
     *   (flags & AUTO_MIRROR) != 0 且 callingUid != SYSTEM_UID 且没有 CAPTURE_VIDEO_OUTPUT
     *                     → SecurityException
     *
     * 换句话说：只要不带 AUTO_MIRROR，建屏根本不需要任何权限 ——
     * 那不是"截取屏幕"，只是"给自己开一块画布"。PUBLIC 的副作用才是权限的来源。
     *
     * 所以默认走 flags=0（私有屏、不镜像）。后面几档是兜底：万一某台 ROM 的
     * WindowManager 只肯把 task 搬到 PUBLIC 屏上，再退回去要 token。
     */
    private VirtualDisplay createVirtualDisplay() {
        int pub = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
        int own = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        int pres = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        int[] candidates = mediaProjection != null
                ? new int[]{ 0, pub | own, pub, pub | pres }
                : new int[]{ 0, pub | own, pub };

        for (int flags : candidates) {
            VirtualDisplay vd = tryCreate(flags);
            if (vd != null) {
                Log.i(TAG, "建屏成功 flags=0x" + Integer.toHexString(flags)
                        + (mediaProjection != null ? "（projection 路径）" : "（免授权路径）"));
                return vd;
            }
        }
        return null;
    }

    private VirtualDisplay tryCreate(int flags) {
        try {
            if (mediaProjection != null) {
                return mediaProjection.createVirtualDisplay("DashcastVirtual",
                        virtW, virtH, VIRT_DPI, flags,
                        imageReader.getSurface(), null, null);
            }
            return displayManager.createVirtualDisplay("DashcastVirtual",
                    virtW, virtH, VIRT_DPI, imageReader.getSurface(), flags);
        } catch (Throwable t) {
            String msg = String.valueOf(t.getMessage());
            Log.w(TAG, "createVirtualDisplay(flags=0x" + Integer.toHexString(flags)
                    + ") 抛 " + t.getClass().getSimpleName() + ": " + msg);
            sLastError = t.getClass().getSimpleName() + ": " + msg;
            if (msg.contains("CAPTURE_VIDEO_OUTPUT")
                    || msg.contains("CAPTURE_SECURE_VIDEO_OUTPUT")) {
                sNeedProjection = true;
            }
            return null;
        }
    }

    private void fail(String why) {
        Log.e(TAG, why);
        sLastError = why;
        sRunning = false;
        if (displayReadyListener != null) {
            displayReadyListener.onDisplayReady(-1);
        }
        stopSelf();
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("dashcast", "投屏",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            return new Notification.Builder(this, "dashcast")
                    .setContentTitle("仪表投屏运行中")
                    .setContentText("推流到 " + CP_HOST + ":" + CP_PORT)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("仪表投屏运行中")
                .setContentText("推流到 " + CP_HOST + ":" + CP_PORT)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    // ============================== 主循环 ==============================

    private void workerLoop() {
        boolean connected = false;
        boolean forceFull = true;

        int framesThisWindow = 0;
        int acqThisWindow = 0;      // 这一秒从虚拟屏拿到多少个 buffer（含被丢掉的）
        long lastPushAt = 0;        // 上一次真正推送的时刻（节流基准）
        long pushMsSum = 0;         // 这一秒里推送耗时总和，用来判断瓶颈在哪
        int pushCount = 0;
        long windowStart = SystemClock.elapsedRealtime();
        long bytesMark = 0;
        long[] stat = new long[5];

        while (running.get()) {
            if (!connected) {
                if (!DcapNative.connect(nativeCtx, CP_HOST, CP_PORT)) {
                    sleepWhileRunning(RECONNECT_DELAY_MS);
                    continue;
                }
                connected = true;
                forceFull = true;
                bytesMark = 0;
                cpLogDumped = false;
                connectedAt = SystemClock.elapsedRealtime();
                Log.i(TAG, "已连 CP " + CP_HOST + ":" + CP_PORT + "（握手 OK）");
            }

            Image image = null;
            try {
                image = imageReader.acquireLatestImage();
            } catch (Throwable t) {
                Log.w(TAG, "acquireLatestImage 异常: " + t);
                sleepQuiet(20);
                continue;
            }

            if (image == null) {
                sleepQuiet(4);   // 画面没变化时不会有新 buffer
            } else {
                acqThisWindow++;
                // 节流按"两次真正推送的间隔"算，不能再在循环尾部无脑睡 33ms：
                // 旧写法 = 33ms 睡眠 + 27ms 实际工作 → 每帧 60ms，天花板只有 16fps，
                // 这正是"画面能动但很卡"的根因。现在超频的帧直接 close 丢掉。
                final long nowMs = SystemClock.elapsedRealtime();
                if (nowMs - lastPushAt < MIN_FRAME_INTERVAL_MS) {
                    image.close();
                } else {
                    lastPushAt = nowMs;
                    long t0 = SystemClock.elapsedRealtimeNanos();
                    int tiles;
                    try {
                        tiles = pushFrame(image, forceFull);
                    } finally {
                        image.close();
                    }
                    pushMsSum += (SystemClock.elapsedRealtimeNanos() - t0) / 1000000L;
                    pushCount++;

                    if (tiles < 0) {
                        Log.w(TAG, "发送失败，断开重连");
                        DcapNative.disconnect(nativeCtx);
                        connected = false;
                        connectedAt = 0;
                        forceFull = true;
                        if (!cpLogDumped) {
                            cpLogDumped = true;
                            new Thread(this::dumpCpLogOnce, "cp-log").start();
                        }
                        sleepQuiet(300);
                        continue;
                    }

                    if (tiles > 0) {
                        forceFull = false;
                        framesThisWindow++;
                    }
                }
            }

            // 每秒一次统计
            long now = SystemClock.elapsedRealtime();
            long dt = now - windowStart;
            if (dt >= 1000) {
                DcapNative.stats(nativeCtx, stat);
                long total = stat[3];
                int kbps = 0;
                if (bytesMark != 0 && dt > 0) {
                    long delta = total - bytesMark;
                    kbps = (int) (delta * 1000L / dt / 1024L);
                }
                bytesMark = total;
                int fps = (int) (framesThisWindow * 1000L / dt);

                if (statsListener != null) {
                    statsListener.onStats(fps, kbps, (int) stat[0], connected);
                }
                if (fps > 0 || acqThisWindow > 0) {
                    long avgMs = pushCount > 0 ? pushMsSum / pushCount : 0;
                    Log.i(TAG, "acq=" + acqThisWindow + " fps=" + fps + " kbps=" + kbps
                            + " tiles=" + stat[0] + " bytes=" + stat[1]
                            + " push=" + avgMs + "ms"
                            + " totalKB=" + (total / 1024));
                }

                framesThisWindow = 0;
                acqThisWindow = 0;
                pushMsSum = 0;
                pushCount = 0;
                windowStart = now;

                // 起来 3 秒后去 CP 拉一次日志：此时图层已经有内容，
                // dashcastd 的"首帧后复核"也写完了，能看到 render order 到底成没成
                if (connected && !cpLogDumped && connectedAt != 0 && now - connectedAt > 3000) {
                    cpLogDumped = true;
                    new Thread(this::dumpCpLogOnce, "cp-log").start();
                }
            }
        }

        if (connected) DcapNative.disconnect(nativeCtx);
        Log.i(TAG, "推流线程退出");
    }

    /** 把一帧 Image 交给 native；返回 native 的图块数 */
    private int pushFrame(Image image, boolean forceFull) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return 0;

        Image.Plane plane = planes[0];
        ByteBuffer buf = plane.getBuffer();
        if (buf == null) return 0;

        if (!buf.isDirect()) {
            // 正常路径永远是 direct（ImageReader 直接映射 gralloc 内存）。
            // 万一不是，拷进我们自己的 direct buffer，保功能不保速度。
            int need = plane.getRowStride() * virtH;
            if (fallbackBuf == null || fallbackBuf.capacity() < need) {
                fallbackBuf = ByteBuffer.allocateDirect(need);
            }
            fallbackBuf.clear();
            fallbackBuf.put(buf);
            fallbackBuf.flip();
            return DcapNative.process(nativeCtx, fallbackBuf,
                    plane.getRowStride(), plane.getPixelStride(), forceFull);
        }

        return DcapNative.process(nativeCtx, buf, plane.getRowStride(),
                plane.getPixelStride(), forceFull);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 可被"停止投屏"打断的等待：停投后线程要能立刻退出 */
    private void sleepWhileRunning(long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (running.get() && SystemClock.elapsedRealtime() < end) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** 去 CP 把 dashcastd 的日志拉回来（调用方负责用 cpLogDumped 去重） */
    private void dumpCpLogOnce() {
        try {
            if (cpManager == null) cpManager = new CpManager(this);
            String s = cpManager.tailLog(30);
            for (String line : s.split("\n")) {
                Log.i(TAG + "/CP", line);
            }
        } catch (Throwable t) {
            Log.w(TAG, "取 CP 日志失败: " + t);
        }
    }

    // ============================== 停止 ==============================

    @Override
    public void onDestroy() {
        stopEverything();

        Thread t = worker;
        worker = null;
        boolean dead = true;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
            }
            dead = !t.isAlive();
        }

        if (nativeCtx != 0) {
            if (dead) {
                DcapNative.destroy(nativeCtx);
            } else {
                // 线程可能还卡在 native connect 里，这时候 free 会 use-after-free。
                // 宁可漏这一块（几 MB，进程退出就回收），也不要崩。
                Log.w(TAG, "推流线程未在 2s 内退出，跳过 native 释放");
            }
            nativeCtx = 0;
        }

        Log.i(TAG, "服务停止");
        super.onDestroy();
    }
}
