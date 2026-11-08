/* ═══════════════════════════════════════════════════════════════════
 * map_to_dash_prod.js —— 地图渲染到仪表盘（生产版 v3）
 * ═══════════════════════════════════════════════════════════════════
 *
 * 目标：仪表盘显示地图，不碰 HUD（Display 1 / com.ecarx.hud）
 *
 * ── v3 关键优化（对比 v2）─────────────────────────────────────
 *
 * v2 问题：抓帧只有 2 次（12 fps 目标，实际 0.2/s）
 * 根因：PixelCopy 回调里用 JS 逐像素循环转 ARGB→BGRA
 *       W*H = 384,000 次迭代 ≈ 每帧阻塞数百毫秒
 *       → hh.postDelayed(下一帧) 被排队堵死
 *
 * v3 修法：Bitmap.ARGB_8888 在 Android 内存里就是 BGRA 布局（小端）
 *       用 copyPixelsToBuffer(ByteBuffer) 一次性拷出 = 原生内存拷贝
 *       不需要任何 JS 循环
 *
 * ── 已验证的技术参数 ──────────────────────────────────────────
 *   VirtualDisplay flags  = 0x3  (PUBLIC | PRESENTATION)
 *   Window type           = 2038 (TYPE_APPLICATION_OVERLAY)
 *   HUD 槽位               = 5    (SURFACE_VIEW_ID_HUD)
 *   DCBG  (切 VD 模式)     = 小端 44 43 42 47
 *   VDF1  (帧头 magic)     = 小端 31 46 44 56
 *   帧包 = magic+w+h+stride_px + BGRA(stride_px*h*4)，全小端
 *
 * ── 线程模型 ──────────────────────────────────────────────────
 *   Android 主线程 : MapView / PixelCopy / copyPixelsToBuffer
 *   Frida JS 线程  : socket 写入（避开 NetworkOnMainThread）
 */

var CFG = {
    W: 1920, H: 532,
    /*
     * FPS = 30（2026-11-08 从 12 提到 30）
     *
     * 实测对照（同一台车，dcapd2 maxFps=30）：
     *   FPS=12 → 抓 11.0fps，发 11.0fps，dcapd2 capture=11/秒
     *   FPS=30 → 抓 24.8fps，发 19.8fps，dcapd2 capture=19~21/秒
     *   收益 +82%，地图侧 CPU 仍 23%（不变），按 2ms 一帧算每帧只占 6% 核心
     *
     * 上限在哪：不是引擎，是 dcapd2 的 VD 分支
     *   `poll(&pf, 1, g_proxy_active ? 20 : 0)` + 每轮只读一帧 → 实际 20fps。
     *   再往上要改 dcapd2.c（一轮多读几帧），光调 FPS 或 maxFps 都没用：
     *   实测 maxFps=60 时 capture 仍是 20，只把 dcapd2 CPU 从 23% 拉到 75%。
     */
    FPS: 30,
    /*
     * 动态帧率（2026-11-08）
     *
     * 实测 CPU：30fps 固定 → 地图 25% + dcapd2 30% = 55%
     *           15fps 静止 → 约 40%
     * 这台车机本身负载就高（loadavg 5.2 / 6 核，内存只剩 935MB），
     * 所以导航和不导航分档跑：
     *   画面在动（导航中，路线/车标/车道箭头一直变）→ FPS(30)
     *   画面静止超过 IDLE_MS（停车、没路线）      → IDLE_FPS(15)
     *
     * 判据不依赖任何私有导航 API，直接采样 16 个像素点比对前后帧 —— 
     * 试过 GuidanceManager / RouteManager / GuideService 找 isNaving，
     * 都没暴露可用的状态方法，而且那些类初始化很重（探一次就超时）。
     */
    IDLE_FPS: 15,
    /* 多久没动就降档：3 秒。太短会在低速转弯时来回切，太长了省不下电 */
    IDLE_MS: 3000,
    /* 发送节奏：50ms=20Hz。比 FPS 稍慢一点，让发送端自然丢弃重复帧，
     * 避免 JS 线程和主线程抢同一份 buffer。实测设 33ms 反而掉到 15fps。 */
    SEND_MS: 50,
    D2_HOST: '127.0.0.1',
    D2_PORT: 8450,
    HUD_SLOT: 5,
    WIN_TYPE: 2038,
    DISP_NAME: 'DashcastMap',
    RUN_SECONDS: 0          /* 0 = 一直跑 */
};

function log(s) { console.log('[MAP] ' + s); }

var NET = { sock: null, out: null, ok: false, err: 'none' };
var FP  = { seq: 0, arr: null, prod: 0, sent: 0, err: 0 };
var CTX = { stopper: false, hh: null, bmp: null, buf: null, mv: null, hc: null, listener: null };
/* 双缓冲：两个 ByteBuffer 轮换，避免 JS 线程读到正在写的缓冲 */
var BUF = { b0: null, b1: null, wi: 0 };

/*
 * 动态帧率的采样点：4×4 网格，每格取中心
 * （BGRA 每像素 4 字节，所以下标 = (y*W + x) * 4）
 * 16 个点足够判断"地图在不在动"，成本 16 次数组访问 = 微秒级
 */
var SAMPLE_OFFS = (function () {
    var a = [];
    for (var gy = 0; gy < 4; gy++) {
        for (var gx = 0; gx < 4; gx++) {
            var x = Math.floor(CFG.W * (gx + 0.5) / 4);
            var y = Math.floor(CFG.H * (gy + 0.5) / 4);
            a.push((y * CFG.W + x) * 4);
        }
    }
    return a;
})();

/* 动态帧率状态 */
var DYN = {
    last: new Array(16).fill(-1),   /* 上一帧的 16 个采样值 */
    lastMoveAt: Date.now(),          /* 最后一次检测到"画面在动"的时刻 */
    fps: 30                          /* 当前生效帧率，初始按高帧率 */
};

/* ── 网络 ─────────────────────────────────────────────────── */
function d2_connect() {
    try {
        if (NET.sock !== null) { try { NET.sock.close(); } catch (e) {} }
        NET.sock = Java.use('java.net.Socket').$new(CFG.D2_HOST, CFG.D2_PORT);
        NET.sock.setTcpNoDelay(true);
        NET.out = NET.sock.getOutputStream();
        NET.out.write.overload('[B').call(NET.out,
            Java.array('byte', [0x44, 0x43, 0x42, 0x47]));   /* DCBG */
        NET.out.flush();
        NET.ok = true;
        NET.err = 'none';
        log('已连 dcapd2 并发送 DCBG（切 src=VD）');
        return true;
    } catch (e) {
        NET.ok = false;
        NET.err = String(e).substring(0, 70);
        log('连接失败: ' + NET.err);
        return false;
    }
}

/* ── Frida 线程发送 ──────────────────────────────────────── */
var lastSeq = 0;
setInterval(function () {
    if (FP.seq === lastSeq || FP.arr === null) return;
    if (!NET.ok) { d2_connect(); return; }

    var arr = FP.arr;
    lastSeq = FP.seq;

    try {
        var hdr = Java.array('byte', [
            0x31, 0x46, 0x44, 0x56,
            (CFG.W & 0xFF), ((CFG.W >> 8) & 0xFF), 0, 0,
            (CFG.H & 0xFF), ((CFG.H >> 8) & 0xFF), 0, 0,
            (CFG.W & 0xFF), ((CFG.W >> 8) & 0xFF), 0, 0
        ]);
        NET.out.write.overload('[B').call(NET.out, hdr);
        NET.out.write.overload('[B').call(NET.out, arr);
        NET.out.flush();
        FP.sent++;
    } catch (e) {
        NET.ok = false;
        NET.err = String(e).substring(0, 70);
        log('发送失败，将重连: ' + NET.err);
    }
}, CFG.SEND_MS);

/* ── 统计 ─────────────────────────────────────────────────── */
var tPrev = Date.now(), pPrev = 0, sPrev = 0;
setInterval(function () {
    var now = Date.now(), dt = (now - tPrev) / 1000;
    if (dt <= 0) return;
    var pf = (FP.prod - pPrev) / dt, sf = (FP.sent - sPrev) / dt;
    tPrev = now; pPrev = FP.prod; sPrev = FP.sent;
    log('STAT 抓=' + FP.prod + '(' + pf.toFixed(1) + 'fps)'
        + ' 发=' + FP.sent + '(' + sf.toFixed(1) + 'fps)'
        + ' 耗时=' + (FP.cost || 0) + 'ms 错=' + FP.err
        + ' 帧率=' + DYN.fps + (DYN.fps === CFG.IDLE_FPS ? '(静止)' : '(活动)')
        + ' 网=' + (NET.ok ? 'OK' : 'DOWN'));
}, 5000);

/* ── 主流程 ──────────────────────────────────────────────── */
Java.perform(function () {
    log('start, pid=' + Process.id);

    var app = Java.use('android.app.ActivityThread').currentApplication();
    var Context = Java.use('android.content.Context');
    var Looper = Java.use('android.os.Looper');
    var Handler = Java.use('android.os.Handler');
    var W = CFG.W, H = CFG.H;
    var FRAME_BYTES = W * H * 4;

    if (!d2_connect()) log('dcapd2 未就绪，会自动重试');

    /* ① VirtualDisplay（PRESENTATION 标志是 Presentation 能工作的前提） */
    var SurfaceTexture = Java.use('android.graphics.SurfaceTexture');
    var Surface = Java.use('android.view.Surface');
    var st = SurfaceTexture.$new(0);
    st.setDefaultBufferSize(W, H);
    var surf = Surface.$new(st);

    var dm = app.getSystemService(Context.DISPLAY_SERVICE.value);
    var CD = Java.use('android.hardware.display.DisplayManager');
    var cdOv = null;
    CD.createVirtualDisplay.overloads.forEach(function (m) {
        if (m.argumentTypes.length === 6) cdOv = m;
    });
    var vd = cdOv.call(dm, CFG.DISP_NAME, W, H, 160, surf, 0x3);
    var displayId = vd.getDisplay().getDisplayId();
    log('VirtualDisplay displayId=' + displayId + ' flags=0x3');

    CTX.hh = Handler.$new(Looper.getMainLooper());
    var R = Java.use('java.lang.Runnable');
    var ByteBuffer = Java.use('java.nio.ByteBuffer');

    var Main = Java.registerClass({
        name: 'com.dashcast.MapProd3' + Process.id,
        implements: [R],
        methods: {
            run: function () {
                try {
                    /* ② MapView 挂到我们的 VirtualDisplay */
                    var DM = Java.use('android.hardware.display.DisplayManager');
                    var mgr2 = app.getSystemService(Context.DISPLAY_SERVICE.value);
                    var tgt = null;
                    DM.getDisplay.overloads.forEach(function (m) {
                        if (m.argumentTypes.length === 1 &&
                            m.argumentTypes[0].className === 'int') {
                            tgt = m.call(mgr2, displayId);
                        }
                    });
                    var dctx = app.createDisplayContext(tgt);

                    var MVC = Java.use('ecarx.navi.engine.basemap.MapView');
                    var mv = MVC.$new(dctx);
                    mv.setSurfaceWidthAndHeight(W, H);

                    var vm = Java.cast(dctx.getSystemService(Context.WINDOW_SERVICE.value),
                        Java.use('android.view.ViewManager'));
                    var WLP = Java.use('android.view.WindowManager$LayoutParams');
                    var lp = WLP.$new();
                    lp.width.value = W;
                    lp.height.value = H;
                    lp.type.value = CFG.WIN_TYPE;
                    lp.format.value = -3;
                    lp.gravity.value = 51;
                    lp.flags.value = 0x00000100;
                    vm.addView(mv, lp);
                    CTX.mv = mv;
                    log('addView OK (type=' + CFG.WIN_TYPE + ')');

                    /* ③ HUDControl —— 引擎在这里自动注册 slot 5 的 MapView */
                    var HC = Java.use('ecarx.navi.engine.feature.external.control.HUDControl');
                    var hc = HC.$new();
                    hc.surfaceViewID.value = CFG.HUD_SLOT;
                    hc.initSurfaceView(mv, true, W, H);
                    CTX.hc = hc;
                    log('initSurfaceView OK, isInitOk=' + hc.isInitOk());

                    try { hc.setCarLogoVisible(true); } catch (e) {}
                    try { hc.setLayerVisible(true); } catch (e) {}
                    try { hc.setDynamicLevel(true); } catch (e) {}
                    try { hc.renderResume(); } catch (e) {}
                    try { hc.drawRoute(); } catch (e) {}
                    log('渲染启动');

                    /* ④ PixelCopy + 原生内存拷贝（无 JS 循环） */
                    var Bitmap = Java.use('android.graphics.Bitmap');
                    var Config = Java.use('android.graphics.Bitmap$Config');
                    var bmp = Bitmap.createBitmap(W, H, Config.ARGB_8888.value);
                    CTX.bmp = bmp;
                    /* ★ 红蓝互换用的目标 Bitmap + Canvas（Java 层原生速度） */
                    var swapBmp = Bitmap.createBitmap(W, H, Config.ARGB_8888.value);
                    var CanvasC = Java.use('android.graphics.Canvas');
                    var swapCanvas = CanvasC.$new(swapBmp);
                    var CMC = Java.use('android.graphics.ColorMatrix');
                    var cmArr = Java.array('float', [0,0,1,0,0,  0,1,0,0,0,  1,0,0,0,0,  0,0,0,1,0]);
                    var cm = CMC.$new(cmArr);
                    var CPC = Java.use('android.graphics.ColorMatrixColorFilter');
                    var cf = CPC.$new(cm);
                    var PC2 = Java.use('android.graphics.Paint');
                    var paint = PC2.$new();
                    paint.setColorFilter(cf);

/* ★ 字节序说明（实测）:
 *   Bitmap.copyPixelsToBuffer(ARGB_8888) → 内存是 R,G,B,A 序
 *   dcapd2 的 blit_bilinear 期待 B,G,R,A 序
 *   直接发会导致红蓝互换（绿通道不受影响）
 *
 *   本轮先按原样发送，验证通道吞吐；颜色修正留待后续（可用 ColorMatrix 或
 *   dcapd2 侧加标志位）。
 */
                    var BO = Java.use('java.nio.ByteOrder');
                    BUF.b0 = ByteBuffer.allocateDirect(FRAME_BYTES);
                    BUF.b1 = ByteBuffer.allocateDirect(FRAME_BYTES);
                    /* ★ 预分配 byte[] 复用，消除每帧 1.5MB JS 数组创建 */
                    BUF.a0 = Java.array('byte', new Array(FRAME_BYTES).fill(0));
                    BUF.a1 = Java.array('byte', new Array(FRAME_BYTES).fill(0));

                    var PC = Java.use('android.view.PixelCopy');
                    var PCL = Java.use('android.view.PixelCopy$OnPixelCopyFinishedListener');
                    var hh = CTX.hh;

                    CTX.listener = Java.registerClass({
                        name: 'com.dashcast.MapProd3PC' + Process.id,
                        implements: [PCL],
                        methods: {
                            onPixelCopyFinished: function (res) {
                                if (CTX.stopper) return;

                                if (res === 0) {
                                    try {
                                        /* 双缓冲：idx 同步切换，bb 与 arr 配对使用 */
                                        var t0 = Date.now();
                                        var idx = BUF.wi;
                                        BUF.wi ^= 1;

                                        var bb = (idx === 0) ? BUF.b0 : BUF.b1;
                                        var arr = (idx === 0) ? BUF.a0 : BUF.a1;

                                        /* ★ 直接拷贝（copyPixelsToBuffer 出的就是
                                           dcapd2 要的字节序，无需 ColorMatrix 转换）
                                           实测：加了 R/B 互换反而让车标由蓝变橙 */
                                        bb.rewind();
                                        bmp.copyPixelsToBuffer(bb);

                                        /* ★ 复用预分配 byte[]，无 JS 数组创建 */
                                        bb.rewind();
                                        bb.get(arr, 0, FRAME_BYTES);

                                        FP.arr = arr;
                                        FP.seq++;
                                        FP.prod++;
                                        FP.cost = Date.now() - t0;

                                        /* ── 画面变化检测（动态帧率用） ──
                                         *
                                         * 采样 16 个点（4×4 网格，各取中心），逐帧比对。
                                         * 变化点数 > 阈值就用高帧率，否则降档。
                                         *
                                         * 为什么采样而不是全图比对：全图 1920×532×4 = 4MB，
                                         * 每帧扫一遍在 JS 里会拖垮主线程（实测能到 100ms+）。
                                         * 16 个点足够判断"地图有没有在动" —— 导航中路线、
                                         * 车标、车道箭头一直在动；停车时几乎全静止。
                                         */
                                        try {
                                            var changed = 0;
                                            for (var si = 0; si < SAMPLE_OFFS.length; si++) {
                                                var off = SAMPLE_OFFS[si];
                                                var cur = arr[off] | (arr[off + 1] << 8) | (arr[off + 2] << 16);
                                                if (cur !== DYN.last[si]) {
                                                    changed++;
                                                    DYN.last[si] = cur;
                                                }
                                            }
                                            var now2 = Date.now();
                                            if (changed >= SAMPLE_OFFS.length / 4) {
                                                /* 4 个点以上变了 → 认为在动，立刻升档 */
                                                DYN.lastMoveAt = now2;
                                            }
                                            /* 超过 3 秒没动 → 降档 */
                                            var moving = (now2 - DYN.lastMoveAt) < CFG.IDLE_MS;
                                            var targetFps = moving ? CFG.FPS : CFG.IDLE_FPS;
                                            if (targetFps !== DYN.fps) {
                                                DYN.fps = targetFps;
                                                log('帧率切换 ' + targetFps + 'fps（'
                                                    + (moving ? '画面在动' : '画面静止')
                                                    + '，变化点 ' + changed + '/' + SAMPLE_OFFS.length + '）');
                                            }
                                        } catch (e) {}
                                    } catch (e) {
                                        FP.err++;
                                        if (FP.err <= 3) log('拷贝失败 ' + String(e).substring(0, 60));
                                    }
                                } else {
                                    FP.err++;
                                    if (FP.err <= 3) log('PC 失败 code=' + res);
                                }

                                /* 自链：trigInst 是闭包变量。
                                 * 间隔用 DYN.fps（动态帧率），不是固定的 CFG.FPS */
                                if (!CTX.stopper) {
                                    hh.postDelayed(trigInst, Math.round(1000 / DYN.fps));
                                }
                            }
                        }
                    }).$new();

                    var TrigCls = Java.registerClass({
                        name: 'com.dashcast.MapProd3T' + Process.id,
                        implements: [R],
                        methods: {
                            run: function () {
                                if (CTX.stopper) return;
                                try {
                                    /* ★ 用 Surface 版：只抓 Surface 内容，
                                       不含覆盖其上的 View（搜索框等 UI） */
                                    PC.request.overload(
                                        'android.view.Surface',
                                        'android.graphics.Bitmap',
                                        'android.view.PixelCopy$OnPixelCopyFinishedListener',
                                        'android.os.Handler'
                                    ).call(PC, mv.getHolder().getSurface(), bmp, CTX.listener, hh);
                                } catch (e) {
                                    FP.err++;
                                    if (FP.err <= 3) log('request 失败 ' + String(e).substring(0, 60));
                                    hh.postDelayed(trigInst, 200);
                                }
                            }
                        }
                    });
                    var trigInst = TrigCls.$new();

                    hh.postDelayed(trigInst, 2500);
                    log('PixelCopy 链已启动（目标 ' + CFG.FPS + 'fps）');

                    if (CFG.RUN_SECONDS > 0) {
                        var StopR = Java.registerClass({
                            name: 'com.dashcast.MapProd3Stop' + Process.id,
                            implements: [R],
                            methods: {
                                run: function () {
                                    CTX.stopper = true;
                                    log('自动停止 抓=' + FP.prod + ' 发=' + FP.sent);
                                }
                            }
                        });
                        hh.postDelayed(StopR.$new(), CFG.RUN_SECONDS * 1000);
                    }

                } catch (e) {
                    log('ERR ' + String(e).substring(0, 130));
                }
            }
        }
    });

    CTX.hh.post(Main.$new());
});
