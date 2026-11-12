package com.hermes.dashcast;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.hud.dash.DashView;
import com.hud.dash.HudData;

/**
 * JavaDashEngine —— Java 渲染投屏引擎（替代 Frida/WebView 链路的终局方案）
 *
 * ════════════════════════════════════════════════════════════════
 * 架构（2026-11-11 定稿，全部环节有车机实测背书）
 * ════════════════════════════════════════════════════════════════
 *
 *   ┌─ 车辆数据（CarPropertyManager，实测 cpm 通路可用）─┐
 *   │                                                    ▼
 *   │                                            HudData 字段赋值
 *   │                                                    ▼
 *   ├─ 地图帧：注入 VD(1920x538) → ImageReaderA ──▶ drawBitmap 进 MAP 洞
 *   │                                                    ▼
 *   └──────────────▶ DashView (Choreographer, 实测 59fps)
 *                                                            ▼
 *                            VirtualDisplay B(1920x538) → ImageReaderB
 *                                                            ▼
 *                                     RGBA→BGRA 原地换序 → VDF1(TCP 8450)
 *                                                            ▼
 *                                        dcapd2 → CP:8444 → 仪表盘
 *
 * 实测背书：
 *   · DashView 渲染 59~65fps（DashPerfActivity 2026-11-10 23:58）
 *   · ImageReader 采集 43~49fps（同上）
 *   · dcapd2 VDF1 帧源协议：16 字节头 magic 'VDF1' + w/h/stride，
 *     像素 BGRA（mapcast.js d2_connect 发 'DCBG' 切源，我们发同样的握手）
 *   · ImageReader RGBA→BGRA：R/B 原地交换（DashDump v1 实测反色教训）
 *
 * 生命周期：由 MapCastService.startJavaDash()/stopJavaDash() 驱动。
 */
public class JavaDashEngine {

    private static final String TAG = "JavaDash";

    /** EVCC 数据源（优先；连不上时退回 VehicleFeed 本地 pump） */
    private EvccVehicleFeed evccFeed;
    private static final int W = 1920, H = 532;   /* 新版设计稿 532，与 CP 出口一致 */
    /* dcapd2 画布 = CP 真实分辨率（运行时从 MirrorController 读，
     * 它缓存了 queryScreenSize() 的结果）。
     * 硬编码 1920x532 在 CP 屏非此尺寸时画面被拉伸/裁切 ——「适配不上」根因。
     * 渲染 VD 恒为设计稿 1920x538；发送时适配到出口尺寸。 */
    private int OUT_W = 1920, OUT_H = 532;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /* ── 渲染侧 ── */
    private VirtualDisplay renderVd;
    private ImageReader renderReader;
    private Presentation pres;
    private DashViewEx dashView;

    /* ── 地图侧 ── */
    private VirtualDisplay mapVd;
    private ImageReader mapReader;
    private Bitmap mapBitmap;                    /* 复用，避免每帧分配 */
    private final Object mapLock = new Object();
    private int mapFrameCount = 0;

    /* ── 缩放中转（出口 ≠ 设计稿时用，复用避免每帧分配） ── */
    private Bitmap srcBitmap, scaleBitmap;
    private Canvas scaleCanvas;
    private Paint scalePaint;

    /* ── 发送侧 ── */
    private Socket sock;
    private OutputStream out;
    private volatile boolean sockOk = false;
    private Thread senderThread;
    private volatile boolean running = false;
    private int mapVdId = -1;

    /* 发送节拍：33ms = 30fps（逆向方案 CAST_FPS=30 同款目标）。
     * 实测标定（2026-11-11）：
     *   33ms 节拍 → 发送 20.4fps（转换+写出 16ms < 33ms，节拍主导）
     *    8ms 节拍 → 发送 45.9fps（工作 16ms 主导），但 dcapd2 端出口
     *               仍 17~18fps（它单线程 read 4MB + 合成 + LZ4 ≈ 55ms/帧）。
     *   → 出口瓶颈在 dcapd2，发送 30fps 已足够喂饱，节拍定稿 33ms。 */
    /*
     * 发送节拍（2026-11-11 优化）
     *
     * 原来是固定 33ms（30fps 目标），但实测每帧工作耗时
     * （转换 13ms + 写出 11ms = 24ms）**大于** 33ms 节拍时，
     * 实际周期 = 33 + 24 = 57ms → 只有 17fps。
     *
     * 改成 0：工作完立即下一帧，周期 = 纯工作耗时 ~24ms → 约 40fps。
     * acquireLatestImage 本身就会等新帧，天然不会空转。
     */
    /*
     * 发送节拍（2026-11-11 定稿）
     *
     * 0 = 工作完立即下一帧。原来担心会抢渲染 CPU，实测不会：
     * acquireLatestImage 在 VD 没出新帧时直接返回 null，
     * 而我们已加了 sleep(4) 兜底，不会空转。
     *
     * 保持 0 的收益：处理 21ms 时能跑满 VD 的 30fps 上限，
     * 而不是被固定节拍压到 24fps（33+21=54ms）。
     */
    private static final long SEND_INTERVAL_MS = 0;

    /** 供 Service/Activity 读状态 */
    public int getMapVdId() { return mapVdId; }
    public DashViewEx getDashView() { return dashView; }

    public JavaDashEngine(Context c) { ctx = c; }

    /* ══════════════════════════════ 启动 ══════════════════════ */

    public void start() throws Throwable {
        if (running) return;
        running = true;

        /* 出口尺寸跟随 CP 真实分辨率：
         * MirrorController 静态字段在 MainActivity 启动投屏前已被
         * queryScreenSize() 刷新（含 prefs 缓存），这里直接读。 */
        OUT_W = MirrorController.getOutputW();
        OUT_H = MirrorController.getOutputH();
        Log.i(TAG, "出口尺寸 = " + OUT_W + "x" + OUT_H
                + "（渲染 VD 恒为 " + W + "x" + H + "，发送时适配）");

        /* ── ① 渲染 VD + DashView（私有 Presentation 屏） ── */
        renderReader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        renderVd = dm.createVirtualDisplay("DashcastJavaVD", W, H, 160,
                renderReader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        Log.i(TAG, "渲染 VD displayId=" + renderVd.getDisplay().getDisplayId());

        ui.post(new Runnable() { @Override public void run() {
            try {
                pres = new Presentation(ctx, renderVd.getDisplay());
                pres.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
                dashView = new DashViewEx(ctx);
                dashView.attachEngine(JavaDashEngine.this);
                /* ★ 数据同源：VehicleFeed 写的必须是 renderer 读的同一个对象。
                 *  2026-11-11 08:35 实测：引擎自建 HudData 导致双对象，
                 *  车速写入被读端无视 → 时速永远不变。 */
                dashView.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

                /* ══════════════════════════════════════════════════
                 * 演示/真实模式开关（2026-11-11 修车速被覆盖 bug）
                 *
                 * bug：autoSpeed 默认 true → step() 每帧用模拟车速
                 * 覆盖 VehicleFeed 写入的 data.speed —— 真车数据上不了屏。
                 *
                 * 现在默认走真实数据（autoSpeed=false）：
                 *   · 车速/功率 = VehicleFeed 的 cpm 读数
                 *   · 停车时画面静止 → dcapd2 tiles 掉到 0 是正确行为
                 *     （深色背景有内容，空屏判定不会误杀，已实测）
                 *
                 * 回到演示模式：设系统属性
                 *   adb shell setprop persist.dash.demo 1
                 * 然后重启投屏。
                 * ══════════════════════════════════════════════════ */
                boolean demo = readSwitch("dash_demo", false);
                dashView.autoSpeed = demo;
                dashView.autoPower = demo;
                Log.i(TAG, "模式: " + (demo ? "演示动画" : "真实车辆数据"));

                /* 地图开关：persist.dash.mapext=1 → DashView 留洞，
                 * 由 mapReader 的 HudActivity 帧填洞（需 HudActivity 在 mapVD 上）；
                 * =0（默认）→ renderer 自带示意路网。 */
                boolean mapExt = readSwitch("dash_mapext", false);
                dashView.data.mapExternal = mapExt;
                Log.i(TAG, "地图模式: " + (mapExt ? "外部真地图（留洞）" : "示意路网"));

                FrameLayoutHost host = new FrameLayoutHost(ctx);
                host.addView(dashView);
                pres.setContentView(host);
                pres.show();
                Log.i(TAG, "DashView 已上 VD");
                /*
                 * VehicleFeed 启动包 try/catch（2026-11-12 加固）
                 *
                 * 原来裸调用，任何异常都会被外面的大 catch 吞成
                 * "Presentation 失败"，而 AddView.show() 已经成功 ——
                 * 结果画面正常但数据面静默不启动，所有数据都是默认值。
                 */
                /*
                 * ══════════════════════════════════════════════════════
                 * 车辆数据源：优先 EVCC 开放 API，失败退回本地 pump
                 * ══════════════════════════════════════════════════════
                 *
                 * 为什么优先 EVCC（2026-11-12 实测结论）：
                 *
                 * VHAL 里有一部分属性是「死属性」——声明了但 timestamp
                 * 恒为 0，车机从启动到现在从没被写过：
                 *   0x11400E01 HIGH_BEAM_LIGHTS_STATE   远光
                 *   0x11400E03 HAZARD_LIGHTS_STATE      双闪
                 * 实测 30 秒内操作双闪 + 拨远光，1116 个属性零变化，
                 * VHAL 日志（1644 行）只处理 SENSOR_INNER_TEMP 和
                 * CHARGE_FUNC_CHARGING_WORK_VOLTAGE 两个信号。
                 *
                 * 而 EVCC 有 9 路属性源（APVP 已启用 4161 个 CAN 信号、
                 * VHAL 1026 个），从中找到了真正的数据源：
                 *   双闪  0x2140752D DOUBLE_FLASH        VHAL  ts 有值 ✅
                 *   远光  0x124007F0 ExtrLtgStsHiBeam    APVP  0=Off 1=On
                 *
                 * 所以：EVCC 能用就全用 EVCC（属性 + 音乐 + 歌词），
                 * 连不上再退回本地 pump（至少车速/续航/轮胎还有数据）。
                 */
                boolean evccOk = false;
                try {
                    evccFeed = new EvccVehicleFeed(ctx, dashView.data);
                    evccFeed.start();
                    Log.i(TAG, "EVCC 数据源已启动（优先）");
                    evccOk = true;
                } catch (Throwable te) {
                    Log.w(TAG, "EVCC 启动失败，退回本地 pump: " + te);
                }

                if (!evccOk) {
                    try {
                        VehicleFeed.start(ctx, ui, dashView.data);
                        Log.i(TAG, "本地 pump 数据源已启动（回退）");
                    } catch (Throwable tf) {
                        Log.e(TAG, "VehicleFeed.start 异常", tf);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Presentation 失败", t);
            }
        }});

        /* ── ② 地图 VD + ImageReader（供 move-stack 搬 HudActivity） ── */
        mapReader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3);
        mapReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader r) {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    int w = img.getWidth(), h = img.getHeight();
                    Image.Plane p = img.getPlanes()[0];
                    ByteBuffer bb = p.getBuffer();
                    int rs = p.getRowStride(), ps = p.getPixelStride();
                    synchronized (mapLock) {
                        if (mapBitmap == null || mapBitmap.getWidth() != w
                                || mapBitmap.getHeight() != h) {
                            mapBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        }
                        /* RGBA → ARGB（DashDump v1 反色教训：必须 R<<16 B<<8） */
                        int[] px = new int[w * h];
                        for (int y = 0; y < h; y++) {
                            int rowOff = y * rs;
                            for (int x = 0; x < w; x++) {
                                int i = rowOff + x * ps;
                                int rr = bb.get(i) & 0xFF, gg = bb.get(i+1) & 0xFF;
                                int bbl = bb.get(i+2) & 0xFF, aa = bb.get(i+3) & 0xFF;
                                px[y*w+x] = (aa<<24)|(rr<<16)|(gg<<8)|bbl;
                            }
                        }
                        mapBitmap.setPixels(px, 0, w, 0, 0, w, h);
                        mapFrameCount++;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) try { img.close(); } catch (Throwable ignored) {}
                }
            }
        }, ui);
        mapVd = dm.createVirtualDisplay("DashcastMapFeed", W, H, 160,
                mapReader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        mapVdId = mapVd.getDisplay().getDisplayId();
        Log.i(TAG, "地图 VD displayId=" + mapVdId);

        /*
         * ── ③ JIT 预热（2026-11-11 加）──
         *
         * 实测首帧转换 92.6ms，稳定后 1.9ms —— 差 48 倍。
         * 热循环（102 万次位运算 + IntBuffer bulk）头几十次还是
         * 解释执行，要跑够了才被 C2 编译。
         *
         * 冷启动前 30 秒用户能看到明显卡顿（24.8fps vs 29.9fps）。
         * 这里在起发送线程前先把同样的循环跑 50 遍，让 JIT 提前编译。
         */
        new Thread(new Runnable() { @Override public void run() {
            try {
                int total = OUT_W * OUT_H;
                int[] warm = new int[total];
                java.nio.IntBuffer dummySrc = java.nio.ByteBuffer
                        .allocateDirect(total * 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asIntBuffer();
                java.nio.IntBuffer dummyDst = java.nio.ByteBuffer
                        .allocateDirect(total * 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asIntBuffer();
                final int CHUNK = OUT_W * 4;
                for (int pass = 0; pass < 50; pass++) {
                    for (int off = 0; off < total; off += CHUNK) {
                        int n = Math.min(CHUNK, total - off);
                        dummySrc.position(off);
                        dummySrc.get(warm, off, n);
                        int i = off;
                        final int lim = off + n - 3;
                        for (; i < lim; i += 4) {
                            int c0 = warm[i], c1 = warm[i+1];
                            int c2 = warm[i+2], c3 = warm[i+3];
                            warm[i]   = (c0 & 0xFF00FF00) | ((c0 & 0x00FF0000) >>> 16) | ((c0 & 0xFF) << 16);
                            warm[i+1] = (c1 & 0xFF00FF00) | ((c1 & 0x00FF0000) >>> 16) | ((c1 & 0xFF) << 16);
                            warm[i+2] = (c2 & 0xFF00FF00) | ((c2 & 0x00FF0000) >>> 16) | ((c2 & 0xFF) << 16);
                            warm[i+3] = (c3 & 0xFF00FF00) | ((c3 & 0x00FF0000) >>> 16) | ((c3 & 0xFF) << 16);
                        }
                        for (; i < off + n; i++) {
                            int c = warm[i];
                            warm[i] = (c & 0xFF00FF00) | ((c & 0x00FF0000) >>> 16) | ((c & 0xFF) << 16);
                        }
                        dummyDst.position(off);
                        dummyDst.put(warm, off, n);
                    }
                }
                Log.i(TAG, "JIT 预热完成（50 轮），转换进入编译态");
            } catch (Throwable t) {
                Log.w(TAG, "JIT 预热失败（不影响功能）: " + t);
            }
        }}, "JavaDashWarmup").start();

        /* ── ③.b VDF1 发送线程 ── */
        senderThread = new Thread(new Runnable() { @Override public void run() {
            /*
             * 线程优先级（2026-11-11）
             *
             * 发送线程是"硬实时"路径：晚 5ms 就是一帧掉队。
             * UI 渲染（Choreographer）跑在主线程，默认优先级更高，
             * 会让发送线程频繁被抢占 —— 实测转换耗时波动 14.5~22ms。
             *
             * THREAD_PRIORITY_DISPLAY 让它和渲染同档，
             * 减少被打断的次数。
             */
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_DISPLAY);
            } catch (Throwable ignored) {}
            sendLoop();
        }}, "JavaDashSender");
        senderThread.start();

        /* ── ④ 车辆数据轮询：挪进 ui.post（dashView 在 UI 线程建，
         *    data 引用要等它就绪；原来到处是时序竞态） ── */

        Log.i(TAG, "JavaDashEngine 已启动");
    }

    /** 读设置开关：settings put global dash_demo 1 → 演示模式 */
    private boolean readSwitch(String key, boolean def) {
        try {
            int v = android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), key, def ? 1 : 0);
            return v == 1;
        } catch (Throwable t) {
            return def;
        }
    }

    /** Presentation 需要一个 content view 容器 */
    private static class FrameLayoutHost extends android.widget.FrameLayout {
        FrameLayoutHost(Context c) { super(c); setBackgroundColor(0xFF000000); }
    }

    /* ══════════════════════════════ 发送 ══════════════════════ */

    /* ── 发送侧统计（5 秒一条，定位吞吐瓶颈用） ── */
    private long statAcquired = 0, statNull = 0, statSent = 0;
    private int frameSeq = 0;
    private long statT0 = 0, statConvMs = 0, statWriteMs = 0, statAcqMs = 0;

    private void statTick(boolean sent, long convMs, long writeMs, long acqMs) {
        if (statT0 == 0) statT0 = System.currentTimeMillis();
        if (sent) { statSent++; statConvMs += convMs; statWriteMs += writeMs; statAcqMs += acqMs; }
        long now = System.currentTimeMillis();
        if (now - statT0 >= 30000) {
            double sec = (now - statT0) / 1000.0;
            Log.i(TAG, String.format(
                "[吞吐] 发送=%d(%.1ffps) 等帧=%.1fms 转换=%.1fms 写出=%.1fms",
                statSent, statSent / sec,
                statSent > 0 ? statAcqMs / (double) statSent : 0,
                statSent > 0 ? statConvMs / (double) statSent : 0,
                statSent > 0 ? statWriteMs / (double) statSent : 0));
            statAcqMs = 0;
            statAcquired = 0; statNull = 0; statSent = 0;
            statConvMs = 0; statWriteMs = 0; statT0 = now;
        }
    }

    private void sendLoop() {
        /* VDF1: magic "VDF1"(4) + w(4) + h(4) + stride(4) = 16B 头，BGRA 像素
         * 握手 "DCBG"：dcapd2 收到后把帧源切到本连接（与 mapcast.js 一致） */
        int stride = OUT_W * 4;
        byte[] header = new byte[16];
        /* magic 'VDF1' = 0x56444631，dcapd2 读 uint32 后直接比较，
         * 即小端内存序 31 46 44 56（2026-11-11 01:50 实测：写反会被 reset） */
        header[0] = 0x31; header[1] = 0x46; header[2] = 0x44; header[3] = 0x56;
        writeIntLE(header, 4, OUT_W);
        writeIntLE(header, 8, OUT_H);
        writeIntLE(header, 12, OUT_W);   /* stride_px：像素数，不是字节（mapcast 同源） */

        long nextDeadline = System.currentTimeMillis();
        while (running) {
            try {
                if (!sockOk) connect();
                if (!sockOk) { Thread.sleep(1500); continue; }

                /* 从渲染 VD 拿一帧（latest） */
                Image img = null;
                long acqT0 = System.currentTimeMillis();
                long acqMs = 0;
                try {
                    img = renderReader.acquireLatestImage();
                } catch (Throwable t) {
                    Log.w(TAG, "acquire: " + t);
                }
                acqMs = System.currentTimeMillis() - acqT0;
                /*
                 * img == null：VD 这一轮没出新帧。
                 *
                 * ★ SEND_INTERVAL_MS = 0 时，原来这里 sleepTo(now) 立即返回
                 *   → 死循环空转，把 CPU 吃满（实测 dashcast 70%+）。
                 *
                 * 现在：null 时主动 sleep 4ms。
                 *   · 渲染 33ms/帧，4ms 轮询粒度足够及时拿到新帧
                 *   · 平均每帧最多多等 4ms，但 CPU 降一大截
                 */
                if (img == null) {
                    statTick(false, 0, 0, acqMs);
                    /* 4ms 太密（每秒 250 次 JNI 调用白烧 CPU）。
                     * 渲染 33ms/帧，等 6ms 够：最坏晚 6ms，平均 3ms。 */
                    try { Thread.sleep(6); } catch (InterruptedException e) { return; }
                    continue;
                }

                long convT0 = System.currentTimeMillis();
                try {
                    Image.Plane p = img.getPlanes()[0];
                    ByteBuffer bb = p.getBuffer();
                    int rs = p.getRowStride(), ps = p.getPixelStride();
                    int iw = img.getWidth(), ih = img.getHeight();

                    /* RGBA → BGRA：bulk 拷贝 + int 视角原地换 R/B。
                     * v1 逐字节 get() 一帧 400 万次调用 → 实测 1~2fps；
                     * int[] 视角一次读 4 字节，swap 后一次性写回，
                     * 实测该循环 <3ms（DashDump 同款手法）。 */
                    /* RESOLUTION-FIT (2026-11-11): render VD stays 1920x538,
                     *  output follows CP real size. Same size: one-pass int convert.
                     *  Different: Bitmap scale (GPU ~3ms) then extract BGRA. */
                    byte[] frame = getFrameBuf();

                    if (iw == OUT_W && ih == OUT_H && rs == iw * 4 && ps == 4) {
                        /*
                         * 同尺寸快路径（2026-11-11 二次优化）
                         *
                         * 旧写法：逐像素写 4 个 byte = 每帧 400 万次数组写入，
                         * 实测 20ms/帧（JIT 对 byte[] 单元素写优化有限）。
                         *
                         * 新写法：把目标 byte[] 也当 IntBuffer 用（同一个
                         * ByteBuffer 底层数组不能双视图，所以单独 wrap 一次），
                         * 一次 put 一个像素（int）—— 1 次调用 vs 4 次，
                         * 实测降到 6~8ms/帧。
                         *
                         * 换序：ImageReader RGBA(内存序 R,G,B,A) →
                         *       wl_shm ARGB8888 要内存序 B,G,R,A。
                         * 小端 int 下：
                         *   源 int = A<<24 | B<<16 | G<<8 | R
                         *   目标 int = A<<24 | R<<16 | G<<8 | B
                         * 即 R/B 互换位置。
                         */
                        java.nio.IntBuffer srcInt = bb.order(ByteOrder.LITTLE_ENDIAN)
                                .asIntBuffer();
                        java.nio.IntBuffer dstInt = java.nio.ByteBuffer
                                .wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
                                .asIntBuffer();
                        /*
                         * 整块 bulk（2026-11-11 三次优化）
                         *
                         * 演进：
                         *   v1 逐字节 byte 写 400 万次  → 20ms
                         *   v2 逐行 int[] get/put       → 13ms
                         *   v3 整块 get/put（本版）     → 目标 <8ms
                         *
                         * v2 每帧要切 532 次 position()，JIT 无法把
                         * 循环里的 position/get/position/put 合成一次
                         * bulk 调用。改成整块后只有 2 次 bulk 调用
                         * + 1 个纯内存循环。
                         *
                         * 换序用位运算（无分支）：
                         *   A<<24 | B<<16 | G<<8 | R  →  A<<24 | R<<16 | G<<8 | B
                         */
                        /*
                         * ★ 分段 bulk（2026-11-11 四次优化）
                         *
                         * 整块 102 万 int 的 get/put 一次性走 4MB native
                         * memcpy + 全量换序，换序循环超出 L2 cache，
                         * 实测 14.5ms。
                         *
                         * 改成按 4 行一段（7680 int = 30KB，正好在 L2
                         * 内），换序循环全程命中缓存：
                         *   · get/put 各 133 次 bulk（vs 1 次超大）
                         *   · 每次只在 30KB 上循环，cache 命中率高
                         */
                        final int total = OUT_W * OUT_H;
                        if (allInt == null || allInt.length != total) {
                            allInt = new int[total];
                        }
                        final int CHUNK = OUT_W * 4;   /* 4 行 = 7680 int ≈ 30KB */
                        long _tg = 0, _ts = 0, _tp = 0, _t1 = 0, _t2 = 0;
                        final boolean _prof = (convSeq++ & 63) == 0;
                        for (int off = 0; off < total; off += CHUNK) {
                            int n = Math.min(CHUNK, total - off);
                            _t1 = System.nanoTime();
                            srcInt.position(off);
                            srcInt.get(allInt, off, n);
                            if (_prof) { _t2 = System.nanoTime(); _tg += _t2 - _t1; }
                            /*
                             * 换序优化（2026-11-11 五次优化）
                             *
                             * 实测：get=1.0ms put=1.2ms，而换序占 5~14ms
                             * （总耗时的 85%）。这是纯 102 万次位运算。
                             *
                             * 循环展开 4 路：4 组独立的位运算可以
                             * 并行发射到不同 ALU，实测比顺序循环快 2~3 倍
                             * （依赖消除 + 指令级并行）。
                             */
                            int i = off;
                            final int lim = off + n - 3;
                            for (; i < lim; i += 4) {
                                int c0 = allInt[i];
                                int c1 = allInt[i + 1];
                                int c2 = allInt[i + 2];
                                int c3 = allInt[i + 3];
                                allInt[i]     = (c0 & 0xFF00FF00)
                                        | ((c0 & 0x00FF0000) >>> 16) | ((c0 & 0xFF) << 16);
                                allInt[i + 1] = (c1 & 0xFF00FF00)
                                        | ((c1 & 0x00FF0000) >>> 16) | ((c1 & 0xFF) << 16);
                                allInt[i + 2] = (c2 & 0xFF00FF00)
                                        | ((c2 & 0x00FF0000) >>> 16) | ((c2 & 0xFF) << 16);
                                allInt[i + 3] = (c3 & 0xFF00FF00)
                                        | ((c3 & 0x00FF0000) >>> 16) | ((c3 & 0xFF) << 16);
                            }
                            for (; i < off + n; i++) {
                                int c = allInt[i];
                                allInt[i] = (c & 0xFF00FF00)
                                        | ((c & 0x00FF0000) >>> 16) | ((c & 0xFF) << 16);
                            }
                            if (_prof) { _t1 = System.nanoTime(); _ts += _t1 - _t2; }
                            dstInt.position(off);
                            dstInt.put(allInt, off, n);
                            if (_prof) _tp += System.nanoTime() - _t1;
                        }
                        if (_prof) {
                            Log.i(TAG, String.format(
                                    "转换细分: get=%.1fms 换序=%.1fms put=%.1fms",
                                    _tg / 1e6, _ts / 1e6, _tp / 1e6));
                        }
                    } else {
                        ensureScaleBufs(OUT_W, OUT_H, iw, ih);
                        int[] px = new int[iw * ih];
                        for (int y = 0; y < ih; y++) {
                            int rowOff = y * rs;
                            for (int x = 0; x < iw; x++) {
                                int i = rowOff + x * ps;
                                int r_ = bb.get(i) & 0xFF, g_ = bb.get(i+1) & 0xFF;
                                int b_ = bb.get(i+2) & 0xFF, a_ = bb.get(i+3) & 0xFF;
                                px[y*iw+x] = (a_<<24)|(r_<<16)|(g_<<8)|b_;
                            }
                        }
                        srcBitmap.setPixels(px, 0, iw, 0, 0, iw, ih);
                        RectF dst = new RectF(0, 0, OUT_W, OUT_H);
                        scaleCanvas.drawBitmap(srcBitmap, null, dst, scalePaint);
                        int n2 = OUT_W * OUT_H;
                        scaleBitmap.getPixels(px, 0, OUT_W, 0, 0, OUT_W, OUT_H);
                        for (int i = 0; i < n2; i++) {
                            int c = px[i];
                            int d = i << 2;
                            frame[d]     = (byte) ((c >>> 16) & 0xFF);
                            frame[d + 1] = (byte) ((c >>> 8) & 0xFF);
                            frame[d + 2] = (byte) (c & 0xFF);
                            frame[d + 3] = (byte) ((c >>> 24) & 0xFF);
                        }
                    }

                    long convDone = System.currentTimeMillis();
                    /* 前 3 帧打线上字节 hex（放 write 前：write 可能被 reset 打断） */
                    if (frameSeq < 3) {
                        StringBuilder hx = new StringBuilder("帧" + frameSeq + " 头hex:");
                        for (int i = 0; i < 16; i++)
                            hx.append(String.format(" %02x", header[i]));
                        hx.append(" len=").append(frame.length);
                        Log.i(TAG, hx.toString());
                        frameSeq++;
                    }
                    /*
                     * 16B 头 + 像素（2026-11-11 优化）
                     *
                     * 去掉 out.flush()：原来每次 write 完强制立刻下发，
                     * 绕过 TCP 发送缓冲的聚合。TCP_NODELAY 已开，
                     * 小包不会人为延迟，但大帧（4MB）分两次 write 时
                     * flush 会让内核多做一次拷贝。
                     */
                    out.write(header);
                    out.write(frame);
                    long writeT0 = convDone;
                    nextDeadline = System.currentTimeMillis() + SEND_INTERVAL_MS;
                    statTick(true, convDone - convT0, System.currentTimeMillis() - writeT0, acqMs);
                } finally {
                    try { img.close(); } catch (Throwable ignored) {}
                }

                sleepTo(nextDeadline);
            } catch (Throwable t) {
                Log.w(TAG, "send err: " + t);
                sockOk = false;
                try { Thread.sleep(1500); } catch (InterruptedException ignored2) {}
            }
        }
    }

    private void connect() {
        try {
            if (sock != null) try { sock.close(); } catch (Throwable ignored) {}
            sock = new Socket();
            /* dcapd2 在 127.0.0.1:8450 监听帧源（实测：'监听 127.0.0.1:8450'） */
            sock.connect(new InetSocketAddress("127.0.0.1", 8450), 2500);
            sock.setTcpNoDelay(true);
            /* 加大发送缓冲：每帧 4MB 写下去，默认缓冲太小会让
             * out.write 阻塞在 TCP 窗口上（实测写出 27ms/帧） */
            try { sock.setSendBufferSize(4 * 1024 * 1024); } catch (Throwable ignored) {}
            out = sock.getOutputStream();
            /* DCBG 握手：告诉 dcapd2 帧源切到 VD 模式 */
            out.write(new byte[]{'D', 'C', 'B', 'G'});
            out.flush();
            sockOk = true;
            Log.i(TAG, "已连 dcapd2:8450 并握手 DCBG");
        } catch (Throwable t) {
            sockOk = false;
            Log.w(TAG, "connect dcapd2 失败（会重试）: " + t);
        }
    }

    private static void writeIntLE(byte[] b, int off, int v) {
        b[off]   = (byte) v;
        b[off+1] = (byte) (v >>> 8);
        b[off+2] = (byte) (v >>> 16);
        b[off+3] = (byte) (v >>> 24);
    }

    private void ensureScaleBufs(int ow, int oh, int iw, int ih) {
        if (scaleBitmap == null || scaleBitmap.getWidth() != ow
                || scaleBitmap.getHeight() != oh) {
            scaleBitmap = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            scaleCanvas = new Canvas(scaleBitmap);
            scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        }
        if (srcBitmap == null || srcBitmap.getWidth() != iw
                || srcBitmap.getHeight() != ih) {
            srcBitmap = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
        }
    }

    /** 复用的帧缓冲（避免每帧 4MB 分配） */
    private final java.util.concurrent.atomic.AtomicReference<byte[]> frameBuf =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 整块 RGBA→BGRA 转换用（避免每帧分配 4MB int[]） */
    private int[] allInt;
    private int convSeq = 0;

    private byte[] getFrameBuf() {
        byte[] b = frameBuf.get();
        if (b == null) {
            b = new byte[OUT_W * OUT_H * 4];
            frameBuf.set(b);
        }
        return b;
    }

    /** int 视图复用（每帧 swap 100 万像素，不能重复分配 4MB int[]） */
    private java.nio.IntBuffer swapView;

    /** 原地交换 R/B：int 视角小端 0xAARRGGBB → 0xAABBGGRR */
    private void swapRB(byte[] f, int pixCount) {
        if (swapView == null || swapView.capacity() < pixCount) {
            swapView = ByteBuffer.wrap(f)
                    .order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        }
        swapView.position(0);
        swapView.limit(pixCount);
        for (int i = 0; i < pixCount; i++) {
            int c = swapView.get(i);
            swapView.put(i, (c & 0xFF00FF00)
                    | ((c & 0x00FF0000) >>> 16) | ((c & 0x000000FF) << 16));
        }
    }

    private void sleepTo(long deadline) {
        long d = deadline - System.currentTimeMillis();
        if (d > 0) { try { Thread.sleep(d); } catch (InterruptedException ignored) {} }
    }

    /* ══════════════════════════════ 地图喂给 UI ═══════════════ */

    /** DashView 每帧绘制时取最新地图帧（null = 暂无）。
     *  在 DashRenderer.drawMapPanel 前调，由 DashView 侧接线（见 DashViewHook）。 */
    public Bitmap snapshotMap() {
        synchronized (mapLock) {
            return mapBitmap;
        }
    }

    public int mapFrames() { return mapFrameCount; }

    /* ══════════════════════════════ 停止 ══════════════════════ */

    public void stop() {
        running = false;
        try {
            if (evccFeed != null) { evccFeed.stop(); evccFeed = null; }
        } catch (Throwable ignored) {}
        VehicleFeed.stop();
        try { if (senderThread != null) senderThread.join(2000); } catch (Throwable ignored) {}
        ui.post(new Runnable() { @Override public void run() {
            try { if (pres != null) pres.dismiss(); } catch (Throwable ignored) {}
            try { if (dashView != null) dashView.stop(); } catch (Throwable ignored) {}
            pres = null; dashView = null;
        }});
        try { if (renderVd != null) renderVd.release(); } catch (Throwable ignored) {}
        try { if (renderReader != null) renderReader.close(); } catch (Throwable ignored) {}
        try { if (mapVd != null) mapVd.release(); } catch (Throwable ignored) {}
        try { if (mapReader != null) mapReader.close(); } catch (Throwable ignored) {}
        try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        renderVd = null; renderReader = null; mapVd = null; mapReader = null;
        sock = null; out = null; sockOk = false;
        synchronized (mapLock) { mapBitmap = null; }
        Log.i(TAG, "JavaDashEngine 已停止");
    }
}
