package com.hermes.dashcast;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/**
 * VdMirror —— 后台跟随模式的帧源
 *
 * 为什么需要它：按 HOME 之后目标 App 的窗口表面会被销毁（实测 mHasSurface=false），
 * 主屏上已经没有它的像素，抓屏路线到这里就断了。想让它后台继续画、仪表继续跟，
 * 唯一办法是给它一块还在合成的屏 —— 虚拟屏。
 *
 * 这里用 DisplayManager 建一块私有虚拟屏（1280x720，和 App 窗口同比例），
 * surface 直接挂 ImageReader：App 被搬进来之后，画到这块屏上的每一帧都会
 * 从 ImageReader 出来。实测这块屏会以 ~60fps 不停出帧（SF flips 持续增长）。
 *
 * 帧不去 CP。dcapd2 那条 TCP 是仪表图层的命根子，断了 dashcastd 就拆图层，
 * 仪表会闪回原厂。所以帧走环回 TCP（127.0.0.1:8450）喂给 dcapd2，由它用
 * 同一条连接、同一套图块差量继续推 —— 仪表上模式切换零感知。
 *
 * 结构教训：第一版是「回调 → 队列 → 发送线程」，跑一帧就断流 —— ImageReader
 * 的 maxImages 被 60fps 的回调灌满，acquire 永远失败，队列却永远等不到东西。
 * 现在改成回调里 acquire → 发 → close 一条龙，最多只占 1 个 buffer，跳帧靠
 * acquireLatestImage 自己合并。
 */
public class VdMirror {

    private static final String TAG = "DashcastVd";

    /* 虚拟屏的物理尺寸减半（省带宽），但 **密度必须跟着减** ——
     * 见 start() 里的注释。主屏 2560x1440@160dpi，虚拟屏 1280x720，
     * 密度就得是 80，这样 App 在两块屏上排出来的是同一个布局，
     * 画面比例完全一致 —— 否则一进后台 UI 全部放大一倍。 */
    private static final int MIN_W = 640, MAX_W = 2560;
    private int vdW = 1280, vdH = 720;   /* 按目标窗口的真实像素来，见 start() */
    /**
     * **回读面尺寸 = 真正发给 dcapd2 的帧尺寸**（2026-11-05 修复关键）。
     *
     * 虚拟屏按窗口真像素建（vdW×vdH，比如 2560×1600），但 ImageReader
     * 只开一半（1280×800）——合成器把画面缩到回读面再交给我们，
     * 省带宽。`img.getWidth()` 拿到的就是 1280，发出去的帧也是 1280×800。
     *
     * 所以 'DCVP' 的裁剪框必须按**回读面**坐标算，不能按 vdW×vdH：
     * 按 2560×1600 算出来的 2006×1432 @(318,0) 发过去，
     * dcapd2 那边帧只有 1280×800，直接被夹成 962×800 ——
     * 用户看到的就是"App 进后台后截取位置对不上"。
     *
     * 日志实证（17:48:37 / 17:51:19）：
     *   后台跟随截取区 → 虚拟屏 2006×1432 @(318,0)（虚拟屏 2560×1600）
     *                                          ↑ 这个数比回读面 1280×800 还大
     */
    private int vdReadW = 1280, vdReadH = 800;
    private int vdDpi = 80;
    private static final int PROXY_PORT = 8450;
    private static final int MAGIC = 0x56444631;   /* 'VDF1' */
    private static final int CMD_BG = 0x47424344;  /* 'DCBG'：立刻停抓主屏 */
    /* 'DCVP'：告诉 dcapd2「虚拟屏里只取这么一块」（后台跟随时的截取区） */
private static final int CMD_VCROP = 0x50564344;
/* 'DCSR':主屏抓取区热更新(16 字节 x,y,w,h,屏幕坐标) */
private static final int CMD_SRC = 0x52534344;

    private static final long MIN_INTERVAL = 33;   // 30fps 上限，省 CPU（dcapd2 还会再限一次）

    /** flags 的取舍见 DashcastSenderService.createVirtualDisplay 的注释：
     *  只要不带 AUTO_MIRROR 就不需要任何权限。PUBLIC|OWN_CONTENT_ONLY 是
     *  给"别的 App 的 task 要搬进来"留的一档 —— 私有屏不一定收外人。 */
    private static final int PUB = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int OWN = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

    public interface Listener {
        void onState(String msg, boolean ok);
    }

    private final Context ctx;
    private Listener listener;

    private ImageReader reader;
    private VirtualDisplay vd;
    private int displayId = -1;

    private HandlerThread cbThread;
    private Handler cbHandler;
    private volatile boolean sending;
    private volatile boolean armed;         // App 搬上虚拟屏之前不许发帧
    private volatile long lastSend;
    private long sentFrames;

    private volatile Socket sock;
    private volatile OutputStream out;
    /** 重连退避：早于这个时刻不再尝试（elapsedRealtime） */
    private volatile long nextConnectAllowedMs = 0;
    private volatile int  connectFailCount = 0;

    private ByteBuffer hdr;
    private byte[] bigBuf;

    public VdMirror(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public int getDisplayId() {
        return displayId;
    }

    public boolean isSending() {
        return sending;
    }

    /**
     * 建 VD（只建一次，之后复用）→ 把 App 搬上来 → 开始收帧喂 dcapd2。
     * 返回 false 时调用方要退回"冻在最后一帧"的兜底行为。
     */
    public synchronized boolean start(String component, int srcW, int srcH,
                                      int mainDpi, Listener l) {
        this.listener = l;
        try {
            // 虚拟屏必须和窗口**像素尺寸完全一致**，否则一进后台画面就变。
            //
            // 原因分两类 App：
            //   · 按 dp 排版的（多数）：只要 dp 尺寸一致就一样 —— 早期 1280x720@80dpi
            //     就是按这个思路做的，能对上。
            //   · 按 px 排版的（抖音的车机 Activity 就是）：像素一变，整套 UI 等比放大。
            //     密度怎么调都没用，只能给它一模一样的像素。
            // 所以直接照抄窗口像素：2560x1440 的窗口 → 2560x1440 的虚拟屏，
            // dp 和 px 两套算法同时成立，谁都不会跳。
            if (srcW < MIN_W) srcW = MIN_W;
            if (srcW > MAX_W) srcW = MAX_W;
            if (srcH < 2) srcH = srcW * 9 / 16;
            int origW = Math.max(1, srcW);
            vdW = srcW;
            vdH = (int) ((long) vdW * srcH / origW);
            if (vdH < 2) vdH = vdW * 9 / 16;
            // 像素一致时密度自然也是主屏密度（2560/2560 * 160 = 160）
            vdDpi = Math.max(40, Math.min(640, mainDpi * vdW / Math.max(1, srcW)));
            sending = true;
            armed = false;

            // ① 先把"立刻停抓主屏"的 4 字节命令发出去 —— 这是按 HOME 不让桌面漏上
            //    仪表的命门。连接在投屏开始时就挂好了（hold()），这里只是一次
            //    微秒级的 write，dcapd2 收到后立刻停抓，桌面根本来不及被抓到。
            if (!ensureConnected() || !sendCmdBg()) {
                DashLog.e(TAG, "控制通道不可用，后台跟随不可用");
                sending = false;
                closeSocket();
                return false;
            }

            if (vd == null && !createDisplay()) {
                state("建虚拟屏失败", false);
                sending = false;
                closeSocket();
                return false;
            }

            lastSend = 0;

            if (!moveTaskIn(component)) {
                DashLog.e(TAG, "App 搬不上虚拟屏（displayId=" + displayId + "）");
                sending = false;
                closeSocket();
                return false;
            }

            // 搬完了才开始发帧。之前发出去的第一帧是**空虚拟屏**（全黑）——
            // 因为 ImageReader 在 move-stack 之前就已经有画面了，仪表就会
            // 从 App 闪一下黑再到 App。armed 把这段窗口焊死。
            armed = true;

            /*
             * **把截取框也带给 dcapd2**（2026-11-03 修复"按 HOME 截取失效"）。
             *
             * 虚拟屏建好后 vdW×vdH 是已知的，截取框（屏幕坐标）可以按
             * 窗口→虚拟屏的比例换算。换算结果交给 dcapd2，它在 VD 分支里
             * 按这个源矩形 blit，而不是整块。
             */
            sendVdCrop();

            state("App 已转后台（虚拟屏 " + displayId + "），仪表实时跟随", true);
            return true;        } catch (Throwable t) {
            DashLog.e(TAG, "start 失败", t);
            sending = false;
            closeSocket();
            return false;
        }
    }

    /** 停帧源。releaseDisplay=true 时连虚拟屏一起拆（整个投屏结束才用） */
    public synchronized void stop(boolean releaseDisplay) {
        sending = false;
        armed = false;
        closeSocket();
        if (releaseDisplay) {
            teardownDisplay();
        }
    }

    /**
     * 设置后台跟随时的截取框（**屏幕坐标**，和「截取 App 页面」里的 src_* 一致）。
     *
     * 传 null = 取整块虚拟屏。
     *
     * 虚拟屏里装的是目标 App 的窗口，所以换算必须知道**窗口在屏幕上的位置**：
     *   虚拟屏的 (0,0) 对应窗口左上角，不是屏幕左上角。
     *   截取框 (srcX,srcY) 是屏幕坐标 → 相对窗口 = (srcX-winX, srcY-winY)。
     *
     * @param crop 截取框 {x,y,w,h}（屏幕坐标；null/全 0 = 整块）
     * @param win  目标窗口矩形 {x,y,w,h}（屏幕坐标；null = 未知，按整屏处理）
     */
    public void setCropRect(int[] crop, int[] win) {
        sCrop = crop;
        sWin = win;
        /*
         * 只有在虚拟屏已经建好、帧已经在喂的时候才真的发 ——
         * 否则 vdW/vdH 还是上一次的（甚至是初值），换算出来的框是错的。
         * start() 末尾 armed=true 之后会再调一次 sendVdCrop()，那时尺寸才是准的。
         */
        if (sending && armed && vd != null) sendVdCrop();
        else sPendingCrop = true;
    }

    private static volatile int[] sCrop = null;
    private static volatile int[] sWin = null;

    /**
     * 控制通道写锁 —— **所有对 8450 socket 的 write 都必须拿它**。
     *
     * 为什么：这个连接上混着两种东西
     *   ① 帧数据（ImageReader 回调线程，每帧 16 字节头 + 整帧 RGBA）
     *   ② 控制命令（UI / mirror 线程，DCBG / DCVP / DCSR）
     *
     * 两条线程 concurrent write 会让字节交错 —— 帧头被插进命令中间，
     * 或者命令头被插进帧数据里。dcapd2 读到不认识的 magic 就
     * `return -1` → 主动 close(fd) → App 看到 "Connection reset"：
     *   · 一帧都发不出去
     *   · 帧源连接反复断开重连（日志里满屏"已连 dcapd2 帧源"）
     *   · 后台跟随时画面卡死
     *
     * 日志实证（2026-11-05 17:09:14）：
     *   .369 主屏抓取区热更新（DCSR 写入）
     *   .404 发帧失败，重连: java.net.SocketException: Connection reset
     * 两次写只隔 35ms —— 就是交错踩掉了。
     */
    private final Object writeLock = new Object();

    /**
     * 建连锁 —— 只保护 ensureConnected()，保证「同一时刻只有一条连接被建立」。
     *
     * 不共用 writeLock 的原因：socket.connect() 最长要等 2 秒，
     * 如果和出帧线程抢 writeLock，会卡住整个投屏出帧（画面卡顿）。
     * 两把锁的临界区不重叠（建连在 connectLock 里、真正写字节
     * 在 writeLock 里），不会死锁。
     */
    private final Object connectLock = new Object();

    /** 把 sCrop（屏幕坐标）换算成**回读面坐标**并下发 */
    private void sendVdCrop() {
        try {
            int cx = 0, cy = 0, cw = vdReadW, ch = vdReadH;
            int[] c = sCrop;
            int[] w = sWin;
            if (c != null && c.length >= 4 && c[2] > 0 && c[3] > 0
                    && w != null && w.length >= 4 && w[2] > 0 && w[3] > 0) {
                /*
                 * ══════════════════════════════════════════════════════════════
                 * **按「回读面 / 虚拟屏」的比例换算**（2026-11-05 关键修复）
                 * ══════════════════════════════════════════════════════════════
                 *
                 * 用户反馈："截取应用页面 app 返回 home 或让其后台，
                 *            匹配不上设置的界面位置"
                 *
                 * 链路里有三个尺寸，原来一直混用：
                 *   窗口    w[2]×w[3]     = 2560×1440（截取框就是这套坐标）
                 *   虚拟屏  vdW×vdH       = 2560×1600（和窗口**同像素**，App 布局才不变）
                 *   回读面  vdReadW×H     = 1280×800 （ImageReader 只开一半，省带宽）
                 *
                 * dcapd2 收到的帧是**回读面**那 1280×800，
                 * 所以 'DCVP' 的源矩形必须落在这个 1280×800 坐标系里。
                 *
                 * 正确的换算只有一步：
                 *   截取框相对窗口左上角 → 在虚拟屏上也是同样的像素偏移
                 *   （vdW/vdH 与窗口同像素，1:1）→ 再乘回读面的缩小系数
                 *     kx = vdReadW / vdW
                 *     ky = vdReadH / vdH
                 *
                 * 原来按 vdW/vdH 直接算（相当于假设回读面=虚拟屏），
                 * 得到 2006×1432 @(318,0) —— 比回读面 1280×800 还大，
                 * dcapd2 夹成 962×800，于是"进后台后截取位置完全对不上"。
                 *
                 * 日志实证（17:48:37 / 17:51:19）：
                 *   后台跟随截取区 → 虚拟屏 2006×1432 @(318,0)（虚拟屏 2560×1600）
                 *   可那一帧实际只有 1280×800。
                 */
                int relX = c[0] - w[0];
                int relY = c[1] - w[1];
                /*
                 * ══════════════════════════════════════════════════════
                 * **唯一正确的换算：截取框相对窗口的比例 → 回读面**
                 * ══════════════════════════════════════════════════════
                 *
                 * 拿到的帧尺寸是「回读面」vdReadW×vdReadH（虚拟屏的一半），
                 * 而截取框是相对窗口 w[2]×w[3] 的坐标。
                 *
                 * 虚拟屏宽度本来就等于窗口宽（vdW==w[2]），
                 * 所以回读面 / 窗口 就是一步到底的比例：
                 *   kx = vdReadW / w[2]
                 *   ky = vdReadH / w[3]
                 *
                 * 之前我加了"窗口→虚拟屏"再"虚拟屏→回读面"两步，
                 * 但 vdH(1600) ≠ 窗口高(1440)（差的是状态栏/导航栏），
                 * 两步会引入两次取整和一次不必要的比例，
                 * 垂直方向就漂了 —— 用户说的"位置对不上"正是这个。
                 *
                 * 直接用回读面/窗口，一次性算完，不引入中间量。
                 */
                double kx = (double) vdReadW / (double) Math.max(1, w[2]);
                double ky = (double) vdReadH / (double) Math.max(1, w[3]);
                DashLog.i(TAG, "VD 换算: 窗口 " + w[2] + "x" + w[3]
                        + " 虚拟屏 " + vdW + "x" + vdH
                        + " 回读面 " + vdReadW + "x" + vdReadH
                        + " → 比例 " + String.format(Locale.US, "%.4f×%.4f", kx, ky)
                        + "  截取 " + c[2] + "x" + c[3]
                        + " @(" + c[0] + "," + c[1] + ")");
                cx = (int) Math.round(relX * kx);
                cy = (int) Math.round(relY * ky);
                cw = (int) Math.round(c[2] * kx);
                ch = (int) Math.round(c[3] * ky);
                if (cx < 0) cx = 0;
                if (cy < 0) cy = 0;
                if (cw < 16) cw = 16;
                if (ch < 16) ch = 16;
                if (cw > vdReadW) cw = vdReadW;
                if (ch > vdReadH) ch = vdReadH;
                if (cx + cw > vdReadW) cx = Math.max(0, vdReadW - cw);
                if (cy + ch > vdReadH) cy = Math.max(0, vdReadH - ch);
            }
            OutputStream o = out;
            if (o == null) {
                /* 连接还没挂上：start() 里 armed=true 之后会再调一次 */
                sPendingCrop = true;
                return;
            }
            ByteBuffer b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(CMD_VCROP);
            b.putInt(cx);
            b.putInt(cy);
            b.putInt(cw);
            b.putInt(ch);
            synchronized (writeLock) {
                o.write(b.array(), 0, 20);
                o.flush();
            }
            sPendingCrop = false;
            DashLog.i(TAG, "后台跟随截取区 → 回读面 " + cw + "×" + ch
                    + " @(" + cx + "," + cy + ")（回读面 " + vdReadW + "×" + vdReadH
                    + " 虚拟屏 " + vdW + "×" + vdH + "）");
        } catch (Throwable t) {
            DashLog.w(TAG, "下发 VD 截取框失败: " + t);
            closeSocket();
        }
    }

    private volatile boolean sPendingCrop = false;

    // ============================== 建屏 ==============================

    private boolean createDisplay() {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        // 第一档必须带 OWN_CONTENT_ONLY：不带它的虚拟屏在某些 ROM 上会**镜像主屏**，
        // 于是搬 App 之前那几帧里装的就是桌面 —— 用户看到"按 HOME 先跳一下 home"。
        int[] candidates = {PUB | OWN, PUB, 0};
        for (int flags : candidates) {
            try {
                // 回读面只要一半尺寸：虚拟屏按窗口真像素排版（App 的布局才对），
                // 合成器会把画面缩到回读面大小再给我们 —— 传输量降到 1/4，
                // 而仪表上看到的内容和尺寸完全一致。
                int rw = Math.max(320, vdW / 2), rh = Math.max(180, vdH / 2);
                vdReadW = rw;
                vdReadH = rh;
                reader = ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 3);
                cbThread = new HandlerThread("vd-reader");
                cbThread.start();
                cbHandler = new Handler(cbThread.getLooper());
                reader.setOnImageAvailableListener(onImage, cbHandler);

                vd = dm.createVirtualDisplay("DashcastBg", vdW, vdH, vdDpi,
                        reader.getSurface(), flags);
                if (vd != null) {
                    Display d = vd.getDisplay();
                    displayId = d == null ? -1 : d.getDisplayId();
                    DashLog.i(TAG, "建屏成功 flags=0x" + Integer.toHexString(flags)
                            + " displayId=" + displayId + " " + vdW + "x" + vdH + " @" + vdDpi
                            + "dpi 回读 " + rw + "x" + rh);
                    return displayId >= 0;
                }
            } catch (Throwable t) {
                DashLog.w(TAG, "createVirtualDisplay(flags=0x" + Integer.toHexString(flags)
                        + ") 失败: " + t);
            }
            teardownDisplay();
        }
        return false;
    }

    private void teardownDisplay() {
        /*
         * ══════════════════════════════════════════════════════════════
         * **关闭顺序：先摘监听 → 停线程 → 最后才 close reader**
         * ══════════════════════════════════════════════════════════════
         *
         * 原来顺序是「reader.close() → vd.release() → quitSafely()」，反了。
         * 日志实证（18:32:09.859）：
         *   W/DashcastVd 发帧失败，重连: IllegalStateException: Image is already closed
         *
         * 因为 reader.close() 之后，队列里已经投递的 onImageAvailable
         * 还在跑，它手里的 Image 立刻失效 → 读 buffer 抛异常 →
         * 被当成"发帧失败"→ closeSocket() → **把给下一轮用的连接也断了**，
         * 后续 enterBg 连不上 dcapd2（ECONNREFUSED），
         * 日志里"连 dcapd2 帧源失败…Connection refused"就是这么来的。
         *
         * 正确顺序：
         *   ① 摘监听               —— 不再投递新回调
         *   ② quitSafely + join    —— 等在跑的回调自然结束
         *   ③ 最后 close/release   —— 此时已无人持有 Image
         */
        ImageReader rd = reader;
        if (rd != null) {
            try { rd.setOnImageAvailableListener(null, null); } catch (Throwable ignored) {}
        }

        if (cbThread != null) {
            HandlerThread t = cbThread;
            cbThread = null;
            cbHandler = null;
            t.quitSafely();
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (rd != null) { try { rd.close(); } catch (Throwable ignored) {} }
        reader = null;
        if (vd != null) { try { vd.release(); } catch (Throwable ignored) {} }
        vd = null;
        displayId = -1;
    }

    // ============================== 搬 task ==============================

    /** 先试 am display move-stack（不重建、状态不丢），失败再 am start --display */
    /**
     * ══════════════════════════════════════════════════════════════
     * 把目标 App 搬到虚拟屏
     * ══════════════════════════════════════════════════════════════
     *
     * **2026-11-05 修复"首次进虚拟屏失败"**：
     *
     * 实测两次进入同一 App 的表现不同：
     *
     *   第一次（08:39:20）：
     *     建屏成功 displayId=41
     *     App 已转后台（虚拟屏 41）
     *     → 3 秒后「虚拟屏连续 3 次无帧，退回主屏」    ❌
     *
     *   第二次（08:39:55）：
     *     建屏成功 displayId=42
     *     move-stack 1102021 → 42:                    ← 这次有搬栈
     *     App 已转后台（虚拟屏 42）
     *     → 正常出帧                                    ✅
     *
     * 差别就在**第一次的 findTaskId 返回 -1**，于是直接走了
     * `am start --display` 分支 —— 而那个分支在 App 已经运行时
     * 不会把既有 task 搬过去，只是发个 intent，于是界面没上来。
     *
     * 为什么第一次找不到 task：
     *   `am stack list` 的输出刚建屏时可能还没把该 App 的 task
     *   挂到新的 stack 上（或者 move-stack 之前 task 还在主屏 stack 里，
     *   而我们的解析器只认 `Stack id=` 后面的 displayId）。
     *
     * 修法：
     *   ① findTaskId 不排除任何 display —— App 在主屏上也照样找出来搬
     *   ② 找不到就**重试 3 次**（间隔 400ms），等 task 建立
     *   ③ 全失败才退回 `am start --display`
     *   ④ 每一步都落日志，下次再出问题能直接定位
     */
    private boolean moveTaskIn(String component) {
        String pkg = component.contains("/")
                ? component.substring(0, component.indexOf('/')) : component;

        /*
         * ① 找 task：**不排除任何 display**。
         *    App 现在可能还在主屏（displayId=0），正是要把它搬走的时候。
         *    原来 findTaskId(pkg, displayId) 排除"已在目标屏"，是对的，
         *    但那种情况本来就该跳过 —— 这里改成分开判断。
         */
        int taskId = -1;
        for (int attempt = 1; attempt <= 3; attempt++) {
            taskId = findTaskId(pkg, -1);      /* -1 = 不排除任何屏 */
            if (taskId > 0) break;
            DashLog.w(TAG, "第 " + attempt + " 次找不到 " + pkg
                    + " 的 task，400ms 后重试");
            sleepQuiet(400);
        }

        if (taskId <= 0) {
            DashLog.w(TAG, "重试 3 次仍找不到 " + pkg
                    + " 的 task，退回 am start --display");
        } else {
            try {
                String r = AdbShell.exec("am display move-stack "
                        + taskId + " " + displayId, 8000);
                DashLog.i(TAG, "move-stack " + taskId + " → " + displayId + ": "
                        + (r == null ? "null" : r.trim().replace('\n', ' ')));
                if (r != null && !r.contains("Exception") && !r.contains("Error")) {
                    return true;
                }
                DashLog.w(TAG, "move-stack 返回异常内容，退回 am start --display");
            } catch (Throwable t) {
                DashLog.w(TAG, "move-stack 抛异常: " + t);
            }
        }

        /* ③ 最后手段：am start --display（App 未运行时有效） */
        try {
            AdbShell.exec("am start --display " + displayId + " -n " + component, 6000);
            DashLog.i(TAG, "已用 am start --display " + displayId + " 拉起 " + component);
            return true;
        } catch (Throwable t) {
            DashLog.w(TAG, "am start --display 失败: " + t);
            return false;
        }
    }

    /** 静默 sleep，不吞 InterruptedException 的状态 */
    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 在 am stack list 里找这个包的 task（排除已经在 displayId 屏上的） */
    private static int findTaskId(String pkg, int excludeDisplay) {
        try {
            String dump = AdbShell.exec("am stack list", 8000);
            if (dump == null) return -1;
            int curDisplay = -1;
            for (String raw : dump.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("Stack id=")) {
                    int di = line.indexOf("displayId=");
                    curDisplay = di >= 0
                            ? leadingInt(line.substring(di + "displayId=".length())) : -1;
                    continue;
                }
                if (line.startsWith("taskId=")) {
                    int id = leadingInt(line.substring("taskId=".length()));
                    int colon = line.indexOf(": ");
                    if (colon < 0) continue;
                    String rest = line.substring(colon + 2);
                    int slash = rest.indexOf('/');
                    String tpkg = slash > 0 ? rest.substring(0, slash) : "";
                    /*
                     * excludeDisplay = -1 表示"不排除任何屏"（2026-11-05）。
                     *
                     * 原来的写法 curDisplay != excludeDisplay 在
                     * excludeDisplay=-1 时，会把 curDisplay 解析失败（=-1）
                     * 的行也排除掉 —— 那些行恰恰可能是我们要找的 task。
                     */
                    boolean displayOk = (excludeDisplay < 0) || (curDisplay != excludeDisplay);
                    if (pkg.equals(tpkg) && displayOk) return id;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static int leadingInt(String s) {
        int v = 0, i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            v = v * 10 + (s.charAt(i) - '0');
            i++;
        }
        return v;
    }

    // ============================== 收帧 + 发送 ==============================

    private final ImageReader.OnImageAvailableListener onImage = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader r) {
            if (!sending) return;

            if (!armed) {
                // App 还没搬上虚拟屏：这时画面是全黑的，发出去仪表就闪黑。
                // 收下就丢，保证 BufferQueue 不堵。
                try {
                    Image drop = r.acquireLatestImage();
                    if (drop != null) drop.close();
                } catch (Throwable ignored) {
                }
                return;
            }

            Image img;
            try {
                img = r.acquireLatestImage();   // 自动合并积压的旧帧
            } catch (Throwable t) {
                return;                          // 偶发 maxImages 打满，丢这帧就行
            }
            if (img == null) return;

            try {
                long now = SystemClock.elapsedRealtime();
                if (now - lastSend < MIN_INTERVAL) return;   // 跳帧

                /*
                 * **关闭窗口期必须再查一次 sending**（2026-11-05）。
                 *
                 * 日志实证（18:32:09.859）：`发帧失败，重连:
                 * java.lang.IllegalStateException: Image is already closed`
                 *
                 * 原因：teardownDisplay() 里先 reader.close() 再 quitSafely()，
                 * 这中间队列里可能还有一帧已经拿到 img 引用、
                 * 但 reader 已经关了 → 再读 buffer 就抛这个异常。
                 * 异常被 catch 当"发帧失败"处理 → 顺手 closeSocket()，
                 * 于是**明明只是一次正常的收尾，却把帧源连接也断了**，
                 * 造成下一轮 enterBg 时 dcapd2 连不上（ECONNREFUSED）。
                 *
                 * 这里判 sending：拆屏流程一开始 sending=false，
                 * 后续这些"迟到帧"就直接丢弃，不写 socket、不误判断连。
                 */
                if (!sending) return;
                /* 只有真的在虚拟屏模式才喂帧 —— 否则 dcapd2 会切到空的 VD 源 */
                if (!sFeedFrames || !ensureConnected()) return;
                sendFrame(img);
                lastSend = now;
                sentFrames++;
                if (sentFrames == 1 || sentFrames % 150 == 0) {
                    DashLog.i(TAG, "已发 " + sentFrames + " 帧 → dcapd2");
                }
            } catch (Throwable t) {
                /*
                 * **区分"正在收尾"和"真的坏了"**（2026-11-05）：
                 * 拆屏/停帧期间抛的异常不要 closeSocket ——
                 * 那条连接是给下一轮用的，误关会让 dcapd2 掉线。
                 */
                if (sending) {
                    DashLog.w(TAG, "发帧失败，重连: " + t);
                    closeSocket();
                } else {
                    DashLog.i(TAG, "收尾期间的帧已丢弃: " + t);
                }
            } finally {
                /* img.close() 也可能因为 reader 已关而抛，一律吞掉 ——
                 * 这里只负责释放引用，不能让它影响外层状态。 */
                try { img.close(); } catch (Throwable ignored) {}
            }
        }
    };

    /** 一帧 = 16 字节头 + stride*h 行 RGBA，拼进一块大缓冲一次写出去 */
    private void sendFrame(Image img) throws Exception {
        Image.Plane p = img.getPlanes()[0];
        int rowStride = p.getRowStride();
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer b = p.getBuffer();

        int total = rowStride * h;
        if (bigBuf == null || bigBuf.length < total) {
            bigBuf = new byte[total];
            hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        }

        if (rowStride == w * 4) {
            // 无行填充：整块一次拷
            b.position(0);
            b.get(bigBuf, 0, Math.min(total, b.remaining()));
        } else {
            for (int y = 0; y < h; y++) {
                int pos = y * rowStride;
                b.position(Math.min(pos, b.capacity()));
                int n = Math.min(rowStride, b.remaining());
                b.get(bigBuf, pos, n);
                if (n < rowStride) Arrays.fill(bigBuf, pos + n, pos + rowStride, (byte) 0);
            }
        }

        hdr.clear();
        hdr.putInt(MAGIC);
        hdr.putInt(w);
        hdr.putInt(h);
        hdr.putInt(rowStride / 4);
        /*
         * **必须和所有控制命令共用同一把锁**（2026-11-05 修 Connection reset）。
         *
         * 这个 write 跑在 ImageReader 回调线程里，而 sendCmdBg /
         * sendVdCrop / sendSrcCrop 跑在 UI 或 mirror 线程 —— 两条线程
         * 对同一个 TCP 流 concurrent write 会把字节**交错**：
         *   帧头 "VDF1" 被插进命令中间，或者命令头被插进帧数据里。
         * dcapd2 读到不认识的 magic → return -1 → 主动 close(fd) →
         * App 这边就是 "Connection reset"，一帧都发不出去。
         *
         * 日志实证（17:09:14）：主屏抓取区热更新（.369）之后
         * 34ms 就出现 "发帧失败，重连: Connection reset"（.404）。
         */
        synchronized (writeLock) {
            /*
             * **out 必须在锁内二次检查**（2026-11-05）。
             *
             * 调用方（sendFrame 外层）读 out 时它还是非空，
             * 但等走到这里，另一条线程可能已经 closeSocket() 把它置 null ——
             * 直接 out.write() 就是 NullPointerException，
             * 被 catch 吞掉后表现为"偶发丢帧/连接莫名断开"，很难查。
             * 锁内再取一次引用并判空，是唯一稳妥做法。
             */
            OutputStream o = out;
            if (o == null) return;
            o.write(hdr.array(), 0, 16);
            o.write(bigBuf, 0, total);
            o.flush();
        }
    }

    /**
     * 投屏期间就先把控制连接挂上（不发任何数据）。
     * 这样按 HOME 时只需一次 write 就能让 dcapd2 停抓主屏，桌面一帧都漏不上去。
     */
    public boolean hold() {
        return ensureConnected();
    }

    public boolean isHeld() {
        return out != null;
    }

    /** 发 4 字节 'DCBG'：立刻停抓主屏 */
    private boolean sendCmdBg() {
        try {
            OutputStream o = out;
            if (o == null) return false;
            ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(CMD_BG);
            synchronized (writeLock) {
                o.write(b.array(), 0, 4);
                o.flush();
            }
            DashLog.i(TAG, "已发 DCBG（dcapd2 立刻停抓主屏）");
            return true;
        } catch (Throwable t) {
            DashLog.w(TAG, "发 DCBG 失败: " + t);
            closeSocket();
            return false;
        }
    }
    /**
     * ══════════════════════════════════════════════════════════════
     * **主屏抓取区热更新**（2026-11-05 新增）
     * ══════════════════════════════════════════════════════════════
     *
     * 改「截取 App 页面」或「按比例框选」时，原来只能 pkill 再重启
     * dcapd2 才能换抓取区 —— 每次重启 TCP 8444 断开、8450 帧源也断，
     * 日志里满屏 Broken pipe / ECONNREFUSED，画面还闪一下黑。
     *
     * 实测一场 6 分钟的投屏出现了 9 次这样的断连循环。
     *
     * dcapd2 侧的 g_src_* 本来就是全局变量（captureScreen 每帧读它），
     * 所以只要发个 'DCSR' 命令改这四个数就够了 —— 进程不用动。
     *
     * @param cap {x,y,w,h} **屏幕坐标**（和 dcapd2 的 argv[3..6] 同一套）
     * @return true = 已下发；false = 控制通道不可用
     */
    public boolean sendSrcCrop(int[] cap) {
        if (cap == null || cap.length < 4 || cap[2] < 16 || cap[3] < 16) {
            DashLog.w(TAG, "DCSR 参数非法: " + (cap == null ? "null" : java.util.Arrays.toString(cap)));
            return false;
        }
        try {
            OutputStream o = out;
            if (o == null) {
                DashLog.w(TAG, "DCSR 下发失败：控制通道未建立");
                return false;
            }
            ByteBuffer b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(CMD_SRC);
            b.putInt(cap[0]);
            b.putInt(cap[1]);
            b.putInt(cap[2]);
            b.putInt(cap[3]);
            synchronized (writeLock) {
                o.write(b.array(), 0, 20);
                o.flush();
            }
            DashLog.i(TAG, "主屏抓取区热更新 → " + cap[2] + "×" + cap[3]
                    + " @(" + cap[0] + "," + cap[1] + ")（不重启进程）");
            return true;
        } catch (Throwable t) {
            DashLog.w(TAG, "DCSR 下发失败: " + t);
            closeSocket();
            return false;
        }
    }

    /** 有没有可用的控制通道（热更新前提） */
    public boolean hasControlChannel() {
        return out != null;
    }

    /**
     * 8450 帧源闸门。
     *
     * 关键区别：**hold() 只挂控制通道（不发 DCBG 以外的数据），不喂帧**；
     *            **真正的帧只在 enterBg（App 进虚拟屏）之后才喂**。
     *
     * 之前的问题：onImageAvailable 每来一帧就调 ensureConnected 并写 4 字节头，
     * dcapd2 一收到帧数据流就切到 VD 源 —— 主屏镜像时虚拟屏是空的，仪表直接黑。
     *
     * 现在拆成两个开关：
     *   sProxyWanted  → 允许建立 8450 连接（hold 用，只发控制命令）
     *   sFeedFrames   → 允许真的把帧写出去（只有 enterBg 之后才开）
     */
    private static volatile boolean sProxyWanted = false;
    private static volatile boolean sFeedFrames = false;

    /** hold：允许连 8450（只发控制命令，不喂帧） */
    public static void setProxyWanted(boolean want) {
        sProxyWanted = want;
    }

    /**
     * ══════════════════════════════════════════════════════════════
     * **立刻建连 + 发 DCBG**（2026-11-05 修复"回 HOME 抓到桌面画面"）
     * ══════════════════════════════════════════════════════════════
     *
     * 问题时序（日志实证）：
     *   t=0     用户按 HOME
     *   t=0     enterBg() 开始：读 prefs、算屏幕尺寸（几毫秒）
     *   t=0..   **dcapd2 还在抓主屏 → 抓到的是桌面**
     *   t≈5ms   vd.start() 里才 sendCmdBg()
     *   t≈476ms 第一帧虚拟屏画面到达
     *
     * 那 476ms 里仪表上就是桌面 —— 用户看到"按 HOME 漏桌面"。
     *
     * 修法：enterBg 一进来就先调这个，把"停抓主屏"提前到最前面。
     * 建连 + 4 字节 write 是微秒级，不阻塞。
     *
     * @return true = DCBG 已发出（dcapd2 立刻停抓主屏）
     */
    public boolean sendDcbgNow() {
        sProxyWanted = true;
        try {
            if (!ensureConnected()) {
                DashLog.w(TAG, "sendDcbgNow: 建连失败，主屏可能多抓几帧");
                return false;
            }
            return sendCmdBg();
        } catch (Throwable t) {
            DashLog.w(TAG, "sendDcbgNow 失败: " + t);
            return false;
        }
    }

    /** enterBg：允许真的喂帧（这时候虚拟屏里才有内容） */
    public static void setFeedFrames(boolean feed) {
        sFeedFrames = feed;
        if (!feed) sProxyWanted = false;   /* 停止喂帧时顺带断连接 */
    }

    public static boolean isFeedFrames() {
        return sFeedFrames;
    }

    private boolean ensureConnected() {
        if (!sProxyWanted) return false;   /* 主屏模式：不碰 8450 */
        /*
         * ══════════════════════════════════════════════════════════════
         * **建连必须上锁**（2026-11-05 修「连接泄漏 / 偶发丢帧」）
         * ══════════════════════════════════════════════════════════════
         *
         * 这个方法会被两类线程同时调用：
         *   · ImageReader 回调线程（每帧都调，见 onImageAvailable）
         *   · mirror 主循环 / UI 线程（hold()、sendDcbgNow()）
         *
         * 原来完全没有同步：两条线程同时判 `out == null` 都为真，
         * 各自 new Socket → 建两条 TCP 连接，后写的覆盖 sock/out，
         * **先建的那条永远没人关**（fd 泄漏，跑久了 dcapd2 那边
         * accept 队列/句柄耗尽，帧源就时断时连）。
         *
         * 更糟的是：两条连接同时往 dcapd2 发 DCBG/DCVP，
         * dcapd2 只记一个 g_proxy_fd，另一条被丢掉 ——
         * 控制命令偶尔"发出去但没生效"。
         *
         * 这里用独立的 connectLock（不是 writeLock：建连要等 2 秒，
         * 拿 writeLock 会卡住出帧线程）。锁内**二次检查** out，
         * 保证只有一条连接被建立。
         */
        synchronized (connectLock) {
            if (out != null) return true;
            long now = SystemClock.elapsedRealtime();
            if (now < nextConnectAllowedMs) return false;

            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", PROXY_PORT), 2000);
                s.setTcpNoDelay(true);
                s.setSendBufferSize(4 * 1024 * 1024);
                /*
                 * 赋值必须在 writeLock 内完成 —— 否则出帧线程可能
                 * 看到"sock 有值但 out 还没赋"的中间态（虽然这里顺序
                 * 是 sock 先 out 后，但 JMM 下仍需保证可见性顺序）。
                 */
                synchronized (writeLock) {
                    sock = s;
                    out = s.getOutputStream();
                }
                connectFailCount = 0;
                nextConnectAllowedMs = 0;
                DashLog.i(TAG, "已连 dcapd2 帧源 127.0.0.1:" + PROXY_PORT);
                /*
                 * 重连之后 dcapd2 是新进程（裁剪框回到默认的整块），
                 * 已有的截取框必须补发一次，否则"中途断过一次就又整块了"。
                 */
                if (sCrop != null || sPendingCrop) sendVdCrop();
                return true;
            } catch (Throwable t) {
                if (connectFailCount < 10) connectFailCount++;
                long back = Math.min(2000L, 300L * (1L << Math.min(3, connectFailCount - 1)));
                nextConnectAllowedMs = now + back;
                /* 只在首次/每 10 次打一条，避免刷屏 */
                if (connectFailCount == 1 || connectFailCount % 10 == 0) {
                    DashLog.w(TAG, "连 dcapd2 帧源失败（第 " + connectFailCount
                            + " 次，退避 " + back + "ms）: " + t.getMessage());
                }
                return false;
            }
        }
    }

    private void closeSocket() {
        /*
         * 也要拿 connectLock：否则「一边在建连、一边在关闭」时，
         * 关掉之后建连线程又把新 sock/out 写回去，连接状态就乱了。
         * 锁顺序固定为 connectLock → writeLock（和 ensureConnected 一致），
         * 不会死锁。
         */
        synchronized (connectLock) {
            synchronized (writeLock) {
                try { if (out != null) out.close(); } catch (Throwable ignored) {}
                try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
                out = null;
                sock = null;
            }
        }
    }

    private void state(String msg, boolean ok) {
        DashLog.i(TAG, msg);
        Listener l = listener;
        if (l != null) l.onState(msg, ok);
    }
}
