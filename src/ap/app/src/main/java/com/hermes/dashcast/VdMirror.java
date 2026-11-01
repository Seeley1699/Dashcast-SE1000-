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
    private int vdDpi = 80;
    private static final int PROXY_PORT = 8450;
    private static final int MAGIC = 0x56444631;   /* 'VDF1' */
    private static final int CMD_BG = 0x47424344;  /* 'DCBG'：立刻停抓主屏 */
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
                Log.e(TAG, "控制通道不可用，后台跟随不可用");
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
                Log.e(TAG, "App 搬不上虚拟屏（displayId=" + displayId + "）");
                sending = false;
                closeSocket();
                return false;
            }

            // 搬完了才开始发帧。之前发出去的第一帧是**空虚拟屏**（全黑）——
            // 因为 ImageReader 在 move-stack 之前就已经有画面了，仪表就会
            // 从 App 闪一下黑再到 App。armed 把这段窗口焊死。
            armed = true;

            state("App 已转后台（虚拟屏 " + displayId + "），仪表实时跟随", true);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "start 失败", t);
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
                    Log.i(TAG, "建屏成功 flags=0x" + Integer.toHexString(flags)
                            + " displayId=" + displayId + " " + vdW + "x" + vdH + " @" + vdDpi
                            + "dpi 回读 " + rw + "x" + rh);
                    return displayId >= 0;
                }
            } catch (Throwable t) {
                Log.w(TAG, "createVirtualDisplay(flags=0x" + Integer.toHexString(flags)
                        + ") 失败: " + t);
            }
            teardownDisplay();
        }
        return false;
    }

    private void teardownDisplay() {
        if (reader != null) { try { reader.close(); } catch (Throwable ignored) {} reader = null; }
        if (vd != null)     { try { vd.release(); } catch (Throwable ignored) {} vd = null; }
        if (cbThread != null) { cbThread.quitSafely(); cbThread = null; }
        displayId = -1;
    }

    // ============================== 搬 task ==============================

    /** 先试 am display move-stack（不重建、状态不丢），失败再 am start --display */
    private boolean moveTaskIn(String component) {
        String pkg = component.contains("/")
                ? component.substring(0, component.indexOf('/')) : component;

        int taskId = findTaskId(pkg, displayId);   // 排除已经在目标屏上的
        if (taskId > 0) {
            try {
                String r = AdbShell.exec("am display move-stack " + taskId + " " + displayId, 8000);
                Log.i(TAG, "move-stack " + taskId + " → " + displayId + ": "
                        + (r == null ? "null" : r.trim().replace('\n', ' ')));
                if (r != null && !r.contains("Exception") && !r.contains("Error")) {
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "move-stack 失败: " + t);
            }
        }

        try {
            AdbShell.exec("am start --display " + displayId + " -n " + component, 6000);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "am start --display 失败: " + t);
            return false;
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
                    if (pkg.equals(tpkg) && curDisplay != excludeDisplay) return id;
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

                /* 只有真的在虚拟屏模式才喂帧 —— 否则 dcapd2 会切到空的 VD 源 */
                if (!sFeedFrames || !ensureConnected()) return;
                sendFrame(img);
                lastSend = now;
                sentFrames++;
                if (sentFrames == 1 || sentFrames % 150 == 0) {
                    Log.i(TAG, "已发 " + sentFrames + " 帧 → dcapd2");
                }
            } catch (Throwable t) {
                Log.w(TAG, "发帧失败，重连: " + t);
                closeSocket();
            } finally {
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
        out.write(hdr.array(), 0, 16);
        out.write(bigBuf, 0, total);
        out.flush();
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
            o.write(b.array(), 0, 4);
            o.flush();
            Log.i(TAG, "已发 DCBG（dcapd2 立刻停抓主屏）");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "发 DCBG 失败: " + t);
            closeSocket();
            return false;
        }
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
        if (out != null) return true;
        if (out != null) return true;
        /*
         * **失败退避**（2026-10-31 修复刷屏）：
         *
         * 原来这里没有退避 —— onImageAvailable 每来一帧就调一次 ensureConnected，
         * 而 ECONNREFUSED 是**立即返回**的（不等 2000ms 超时），所以失败时
         * 会以每秒几十次的频率狂连，日志实测间隔仅 17ms，刷屏上千条。
         *
         * 场景：停止投屏后 dcapd2 被杀，ImageReader 里还有积压帧在回调，
         * 每次回调都重连失败 → 日志爆炸。
         *
         * 退避策略：首次失败等 300ms，连续失败翻倍，上限 2 秒。
         * 成功连上清零。这样"帧源不在"时最多 2 秒试一次，日志干净。
         */
        long now = SystemClock.elapsedRealtime();
        if (now < nextConnectAllowedMs) return false;

        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", PROXY_PORT), 2000);
            s.setTcpNoDelay(true);
            s.setSendBufferSize(4 * 1024 * 1024);
            sock = s;
            out = s.getOutputStream();
            connectFailCount = 0;
            nextConnectAllowedMs = 0;
            Log.i(TAG, "已连 dcapd2 帧源 127.0.0.1:" + PROXY_PORT);
            return true;
        } catch (Throwable t) {
            if (connectFailCount < 10) connectFailCount++;
            long back = Math.min(2000L, 300L * (1L << Math.min(3, connectFailCount - 1)));
            nextConnectAllowedMs = now + back;
            /* 只在首次/每 10 次打一条，避免刷屏 */
            if (connectFailCount == 1 || connectFailCount % 10 == 0) {
                Log.w(TAG, "连 dcapd2 帧源失败（第 " + connectFailCount
                        + " 次，退避 " + back + "ms）: " + t.getMessage());
            }
            return false;
        }
    }

    private void closeSocket() {
        try { if (out != null) out.close(); } catch (Throwable ignored) {}
        try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        out = null;
        sock = null;
    }

    private void state(String msg, boolean ok) {
        Log.i(TAG, msg);
        Listener l = listener;
        if (l != null) l.onState(msg, ok);
    }
}
