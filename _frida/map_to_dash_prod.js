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

    var startPc = function () {
                try {
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
                                    /* ★ 抓 HudActivity 自己的 SurfaceView
                                     *
                                     * 原来是 mv.getHolder().getSurface()，mv 是我们手动
                                     * 创建的 basemap.MapView —— 那条路引擎不给导航图层。
                                     * 现在抓 HudActivity 内建的 SurfaceView，
                                     * 它背后是完整的 GuidanceLayer。
                                     *
                                     * 优先用 Surface 重载（不含盖在上面的 View），
                                     * 拿不到 Surface 再退回 SurfaceView 重载。
                                     */
                                    var surface = null;
                                    try {
                                        surface = CTX.sv.getHolder().getSurface();
                                    } catch (e) {}

                                    if (surface && surface.isValid()) {
                                        PC.request.overload(
                                            'android.view.Surface',
                                            'android.graphics.Bitmap',
                                            'android.view.PixelCopy$OnPixelCopyFinishedListener',
                                            'android.os.Handler'
                                        ).call(PC, surface, bmp, CTX.listener, hh);
                                    } else {
                                        PC.request.overload(
                                            'android.view.SurfaceView',
                                            'android.graphics.Bitmap',
                                            'android.view.PixelCopy$OnPixelCopyFinishedListener',
                                            'android.os.Handler'
                                        ).call(PC, CTX.sv, bmp, CTX.listener, hh);
                                    }
                                } catch (e) {
                                    FP.err++;
                                    if (FP.err <= 3) log('request 失败 ' + String(e).substring(0, 60));
                                    hh.postDelayed(trigInst, 200);
                                }
                            }
                        }
                    });
                    var trigInst = TrigCls.$new();

                    hh.postDelayed(trigInst, 1200);
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
    };
    var Main = Java.registerClass({
        name: 'com.dashcast.MapProd3' + Process.id,
        implements: [R],
        methods: {
            run: function () {
                try {
                    /*
                     * ② 启动 HudActivity 到我们的 VirtualDisplay
                     *
                     * **为什么不用手动 HUDControl.initSurfaceView**（2026-11-08）
                     *
                     * 之前的做法是自己 new HUDControl() + initSurfaceView(MapView)，
                     * 引擎只给了底图（MapLayer），不给 GuidanceLayer ——
                     * 结果仪表盘上没有导航路线、没有红绿灯、没有车道指引。
                     *
                     * 实测原因：引擎把「路线/红绿灯/车道」这些 native 层
                     * （RoutePathLayer / 对应 BizControl）只在 Activity 生命周期内
                     * 创建。手动挂载的 MapView 拿不到。
                     *
                     * HudActivity 是地图 App 内置的 HUD 投屏 Activity
                     * （com.ecarx.navi.multiscreen.hud.HudActivity，exported=true,
                     * launchMode=singleTask）。把它启动到我们的 VD 上，引擎会：
                     *   ① 自动建好 SurfaceView (1920x532)
                     *   ② 创建 HUDControl（surfaceViewID=5, mScreenWidth/Height 正确）
                     *   ③ 完整创建 GuidanceLayer(slot5) + 9 个 Control
                     *   ④ initMapParam(ExternalMapParam{isTmcVisible=true, ...})
                     *
                     * 我们随后只做一件事：从 HUDControl 的 surfaceViewControl
                     * 里取出 SurfaceView，对它 PixelCopy 抓帧。
                     */
                    /*
                     * ③ 发起 HudActivity
                     *
                     * **实测教训（2026-11-08）**：
                     *   从 App 上下文 startActivity 到「别人创建的 VirtualDisplay」会抛
                     *     SecurityException: Permission Denial: starting Intent {...}
                     *   跨 display 启动需要 INTERNAL_SYSTEM_WINDOW 级权限，普通 App 拿不到。
                     *
                     *   而 `adb shell am start --display N -n ...` 用 shell 身份能成功
                     *   （已实测：dumpsys 显示 Activity Resumed 在 display 35）。
                     *
                     *   所以策略：这里把 displayId 打到日志（VDID=<n>），
                     *   由 Dashcast 侧读日志后调 adb shell 发起。
                     *
                     *   **这里必须 try/catch**：异常漏到外层会让整段 Main 中断，
                     *   轮询和 PixelCopy 都不会启动（第一次改就是这样，0.0fps）。
                     */
                    log('VDID=' + displayId);
                    try {
                        var Intent = Java.use('android.content.Intent');
                        var CN = Java.use('android.content.ComponentName');
                        var AO = Java.use('android.app.ActivityOptions');
                        var it = Intent.$new();
                        it.setComponent(CN.$new('com.geely.map',
                            'com.ecarx.navi.multiscreen.hud.HudActivity'));
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.value);
                        var opts = AO.makeBasic();
                        opts.setLaunchDisplayId(displayId);
                        app.startActivity(it, opts.toBundle());
                        log('HudActivity 已在进程内发起（displayId=' + displayId + '）');
                    } catch (eSelf) {
                        log('进程内启动被拒（预期，等 adb shell 发起）');
                    }

                    /* 必须在主线程轮询：Activity 启动是异步的，onCreate 要几秒 */
                    var want = 20;      /* 最多轮询 20 次 x 1.5s = 30s */
                    var tries = 0;

                    var Poll = Java.registerClass({
                        name: 'com.dashcast.Poll' + Process.id,
                        implements: [R],
                        methods: {
                            run: function () {
                                if (CTX.stopper) return;
                                tries++;
                                try {
                                    var found = null;
                                    Java.choose('com.ecarx.navi.multiscreen.hud.HudActivity', {
                                        onMatch: function (a) {
                                            try {
                                                if (a.getDisplay().getDisplayId() === displayId
                                                    && !a.isDestroyed() && !found) {
                                                    found = a;
                                                }
                                            } catch (e) {}
                                        },
                                        onComplete: function () {}
                                    });

                                    if (found) {
                                        /*
                                         * **全部私有字段都要用反射**（2026-11-08，两次踩坑）
                                         *
                                         * 这条链上有三个 private 字段：
                                         *   HudActivity.service
                                         *   HUDService.mControl
                                         *   HUDControl.surfaceViewControl
                                         *   SurfaceViewControl.mSurfaceView
                                         *
                                         * frida 的 `obj.field.value` 只对 **public** 字段有效；
                                         * private 字段读出来是 undefined，再取 .value 就抛
                                         *   TypeError: Cannot read properties of undefined
                                         *
                                         * 第一次只改了最后一个，仍然在 `found.service.value`
                                         * 这一步失败 —— 日志里的 "轮询 2/3 :: TypeError" 就是这个。
                                         * 统一写个 rf() 帮手，四个字段全走 getDeclaredField。
                                         */
                                        var rf = function (obj, fname) {
                                            try {
                                                var f = obj.getClass()
                                                    .getDeclaredField(fname);
                                                f.setAccessible(true);
                                                return f.get(obj);
                                            } catch (e) {
                                                return null;
                                            }
                                        };

                                        var svc = rf(found, 'service');
                                        if (!svc) { return; }
                                        var hc = rf(svc, 'mControl');
                                        if (!hc) { return; }
                                        var sfc = rf(hc, 'surfaceViewControl');
                                        if (!sfc) { return; }
                                        var msv = rf(sfc, 'mSurfaceView');
                                        if (msv) {
                                            CTX.hudAct = found;
                                            CTX.hc = hc;
                                            CTX.sv = msv;
                                            log('HudActivity 就绪：surfaceViewID='
                                                + rf(hc, 'surfaceViewID')
                                                + '  ' + rf(hc, 'mScreenWidth') + 'x'
                                                + rf(hc, 'mScreenHeight'));
                                            log('SurfaceView = ' + msv);

                                            /*
                                             * ★ 先把 HUDControl 的屏幕尺寸改对（2026-11-08）
                                             *
                                             * 初值是 2560×1440 —— HudActivity 启动时读的是
                                             * **主屏** DisplayMetrics，不是我们 VD 的 1920×532。
                                             * 两个字段都是 private int，走反射 setInt。
                                             */
                                            try {
                                                var fw = hc.getClass()
                                                    .getDeclaredField('mScreenWidth');
                                                fw.setAccessible(true);
                                                fw.setInt(hc, W);
                                                var fhgt = hc.getClass()
                                                    .getDeclaredField('mScreenHeight');
                                                fhgt.setAccessible(true);
                                                fhgt.setInt(hc, H);
                                                log('HUDControl 尺寸 → ' + W + 'x' + H);
                                            } catch (eS) {
                                                log('尺寸修正失败: '
                                                    + String(eS).substring(0, 60));
                                            }

                                            /*
                                             * ★ 修正 MapView 的 viewport（2026-11-08 实测）
                                             *
                                             * mapViewMap 里 slot5 的 MapView 视口是
                                             *   2560×1440
                                             * —— 读的是主屏 DisplayMetrics，不是我们的 VD。
                                             * 于是引擎按 2560×1440 的比例渲染地图，
                                             * 我们只抓左上 1920×532 那块，画面就成了
                                             * 「放大的道路局部」（踩了很久）。
                                             *
                                             * 正确做法：构造 MapViewPortParam 设成 1920×532，
                                             * 连 screenWidth/Height 一起设（两个字段是 long，
                                             * 必须用 setLong，用 set 会抛类型不匹配）。
                                             *
                                             * MapView 只能通过 MapServiceManager.mapViewMap
                                             * 拿到；HashMap 的 get/values 不能直接用 frida
                                             * wrapper 调（会报 argument types do not match），
                                             * 要反射 invok + Java.cast 成 MapView。
                                             */
                                            try {
                                                var CH = Java.use('java.util.concurrent.ConcurrentHashMap');
                                                var MSM2 = Java.use(
                                                    'ecarx.navi.engine.manager.map.MapServiceManager');
                                                var msmInst = MSM2.getInstance();
                                                var fmv = msmInst.getClass()
                                                    .getDeclaredField('mapViewMap');
                                                fmv.setAccessible(true);
                                                var mGet = CH.class.getMethod('get',
                                                    [Java.use('java.lang.Object').class]);
                                                var MapV = Java.use('com.autonavi.gbl.map.MapView');
                                                var mv5r = mGet.invoke(fmv.get(msmInst),
                                                    [Java.use('java.lang.Integer').valueOf(5)]);
                                                var mv5 = mv5r ? Java.cast(mv5r, MapV) : null;
                                                if (mv5) {
                                                    var PP = Java.use(
                                                        'com.autonavi.gbl.map.model.MapViewPortParam');
                                                    var pp = PP.$new();
                                                    var sfL = function (o, n, v) {
                                                        var fd = o.getClass()
                                                            .getDeclaredField(n);
                                                        fd.setAccessible(true);
                                                        fd.setLong(o, v);
                                                    };
                                                    sfL(pp, 'x', 0);
                                                    sfL(pp, 'y', 0);
                                                    sfL(pp, 'width', W);
                                                    sfL(pp, 'height', H);
                                                    sfL(pp, 'screenWidth', W);
                                                    sfL(pp, 'screenHeight', H);
                                                    var before = mv5.getMapviewPort();
                                                    mv5.setMapviewPort(pp);
                                                    var after = mv5.getMapviewPort();
                                                    log('viewport ' + before.width.value + 'x'
                                                        + before.height.value + ' → '
                                                        + after.width.value + 'x'
                                                        + after.height.value);
                                                } else {
                                                    log('取不到 slot5 的 MapView');
                                                }
                                            } catch (eVP) {
                                                log('viewport 修正失败: '
                                                    + String(eVP).substring(0, 80));
                                            }

                                            /*
                                             * ★ 把所有导航开关打开（2026-11-08）
                                             *
                                             * **必须用 .overload().call()**，不能 svc.initMapParam(mp)。
                                             * 直接调用会报 "svc.initMapParam is not a function"，
                                             * 因为 frida 对重载方法要求显式指定签名。
                                             *
                                             * *.value = true 那几行也必须走反射：这些字段
                                             * 是 private boolean / private float，frida 里
                                             * mp.isTmcVisible 是 undefined（不是 wrapper），
                                             * 直接赋值会抛 TypeError。
                                             */
                                            var MP = Java.use(
                                                'ecarx.navi.engine.bean.map.ExternalMapParam');
                                            var mp = MP.$new();
                                            var sfB = function (o, n, v) {
                                                try {
                                                    var fd = o.getClass().getDeclaredField(n);
                                                    fd.setAccessible(true);
                                                    fd.setBoolean(o, v);
                                                } catch (e) {}
                                            };
                                            sfB(mp, 'isTmcVisible', true);
                                            sfB(mp, 'isCrossVisible', true);
                                            sfB(mp, 'isDynamicLevel', true);
                                            sfB(mp, 'isLayerVisible', true);
                                            sfB(mp, 'isShowMutiRoute', true);
                                            try {
                                                var fdLv = mp.getClass()
                                                    .getDeclaredField('mapLevel');
                                                fdLv.setAccessible(true);
                                                fdLv.setFloat(mp, 17.0);
                                            } catch (e) {}
                                            try {
                                                var HS = Java.use(
                                                    'ecarx.navi.business.external.HUDService');
                                                HS.initMapParam.overload(
                                                    'ecarx.navi.engine.bean.map.ExternalMapParam'
                                                ).call(svc, mp);
                                                log('initMapParam OK（路况/路口/图层全开）');
                                            } catch (e2) {
                                                log('initMapParam 失败 '
                                                    + String(e2).substring(0, 70));
                                            }

                                            /*
                                             * ★ 3D 车模（验证成功，2026-11-08）
                                             *
                                             * 参考源码 hud_effects.js:91-116 applyCarStyle()。
                                             * 默认只有一个蓝色三角箭头，因为：
                                             *   · carMode 虽然是 2 (CAR_MODEL)
                                             *   · setSkeletonData 也成功
                                             *   · 但 updateCarStyle(2002) 是「普通模式」
                                             *     → 引擎走 2D 箭头路径
                                             *
                                             * 三个必须一起做的动作：
                                             *   ① setSkeletonData(assets, CarLogoEnum.CAR_MODEL)
                                             *      加载 car_skeleton_logo/2/carLogoLow.dat（971KB 的 3D 模型）
                                             *   ② updateCarStyle(2001)  ← 关键！2001 = 导航中 = 3D 渲染
                                             *   ③ setCarMode(2, true)    ← 第二参数 true 才真正启用 3D
                                             *
                                             * 实测按源码写 updateCarStyle(isGuidance()?2001:2002) 时
                                             * isGuidance()=false → 走 2002 → 一直是箭头。
                                             * 强制 2001 后就变成 3D 车模了。
                                             *
                                             * setCarScale 放大一点让车模更显眼（1.5 是视觉上舒服的值）。
                                             */
                                            try {
                                                var LC5 = Java.use(
                                                    'ecarx.navi.engine.manager.layer.LayerController');
                                                var ml5 = LC5.getInstance().getMapLayer(5);
                                                if (ml5) {
                                                    var CLE = Java.use(
                                                        'ecarx.navi.engine.bean.CarLogoEnum');
                                                    var CHA = Java.use(
                                                        'ecarx.navi.utils.utils.ContextHolder');
                                                    var amA = CHA.getContext().getAssets();

                                                    ml5.setSkeletonData(amA, CLE.CAR_MODEL.value);
                                                    ml5.updateCarStyle(2001);
                                                    var cmOk = ml5.setCarMode(2, true);
                                                    ml5.setCarScale(1.5);
                                                    ml5.setCarAnimationSwitch(true);
                                                    log('3D 车模：setCarMode(2,true)=' + cmOk
                                                        + '  carMode=' + ml5.getCarMode()
                                                        + '  ready=' + ml5.isCarReady());
                                                }
                                            } catch (eCar) {
                                                log('车模设置失败: '
                                                    + String(eCar).substring(0, 80));
                                            }

                                            /*
                                             * ★ 底图元素可见性：路名 / 路况深度 / 道路（2026-11-08）
                                             *
                                             * 参考源码 hud_effects.js:55-64 + 232-239：
                                             *   const States = Java.use(
                                             *       'com.autonavi.gbl.map.model.MapViewStateType');
                                             *   function idState(name){ return Number(States[name].value); }
                                             *   const set = (name,on) =>
                                             *       biz.setMapViewState(idState(name), on);
                                             *
                                             * 实测问题：仪表上只有红绿灯和路线，**没有路名、没有路况**。
                                             * 原因是我们只调了 setTmcVisible(5,true) —— 那只是 TMC 主开关，
                                             * 底图上「路名文字」和「路况路段的深度信息」各自有独立的
                                             * MAP_VIEWSTATE_* 开关，默认没开。
                                             *
                                             * 关键 ID（从源码 names 表抄下来的，都是 MapViewStateType 的静态字段）：
                                             *   MAP_VIEWSTATE_IS_LABLE_ROADNAME_ON    ← 路名
                                             *   MAP_VIEWSTATE_IS_TMC_DEPTH_INFO_ON    ← 路况深度（红黄绿路段）
                                             *   MAP_VIEWSTATE_IS_ROAD_ON              ← 道路本身
                                             *   MAP_VIEWSTATE_IS_ROAD_ARROW_ON        ← 道路箭头
                                             *
                                             * biz 从 MapView 拿：view.getOperatorBusiness()。
                                             */
                                            try {
                                                var CHB = Java.use('java.util.concurrent.ConcurrentHashMap');
                                                var MSMb = Java.use(
                                                    'ecarx.navi.engine.manager.map.MapServiceManager');
                                                var msmb = MSMb.getInstance();
                                                var fb = msmb.getClass().getDeclaredField('mapViewMap');
                                                fb.setAccessible(true);
                                                var mGetB = CHB.class.getMethod('get',
                                                    [Java.use('java.lang.Object').class]);
                                                var MapVB = Java.use('com.autonavi.gbl.map.MapView');
                                                var mv5bRaw = mGetB.invoke(fb.get(msmb),
                                                    [Java.use('java.lang.Integer').valueOf(5)]);
                                                var mv5b = mv5bRaw
                                                    ? Java.cast(mv5bRaw, MapVB) : null;

                                                if (mv5b) {
                                                    var biz = mv5b.getOperatorBusiness();
                                                    var States = Java.use(
                                                        'com.autonavi.gbl.map.model.MapViewStateType');

                                                    /*
                                                     * **变量名必须叫 stateIds，不能叫 want**
                                                     * （2026-11-08 修「轮询 1 次就放弃」）
                                                     *
                                                     * JS 的 var 是函数作用域，不是块作用域。
                                                     * 外层有个 `var want = 20`（轮询次数上限，
                                                     * 见轮询循环的 `if (tries < want)`）。
                                                     * 这里再声明一个 `var want = [...]` 会把那个
                                                     * 数字**覆盖成数组** —— 于是
                                                     *   tries < want  →  1 < 数组  →  false
                                                     * 轮询只跑一次就报「HudActivity 未就绪」，
                                                     * 表现就是地图投屏永远停在"正在启动"。
                                                     */
                                                    var stateIds = [
                                                        'MAP_VIEWSTATE_IS_LABLE_ROADNAME_ON',
                                                        'MAP_VIEWSTATE_IS_TMC_DEPTH_INFO_ON',
                                                        'MAP_VIEWSTATE_IS_ROAD_ON',
                                                        'MAP_VIEWSTATE_IS_ROAD_ARROW_ON'
                                                    ];
                                                    var okN = 0;
                                                    for (var wi = 0; wi < stateIds.length; wi++) {
                                                        var nm = stateIds[wi];
                                                        try {
                                                            var st = States[nm].value;
                                                            biz.setMapViewState(st, true);
                                                            okN++;
                                                        } catch (eSt) {
                                                            log('  ' + nm + ' 失败: '
                                                                + String(eSt).substring(0, 50));
                                                        }
                                                    }
                                                    log('底图开关已开 ' + okN + '/' + stateIds.length
                                                        + '（路名/路况深度/道路/道路箭头）');
                                                } else {
                                                    log('取不到 MapView(5) 做底图开关');
                                                }
                                            } catch (eBiz) {
                                                log('底图开关失败: '
                                                    + String(eBiz).substring(0, 80));
                                            }

                                            /*
                                             * ★ hook updateCarStyle 防止导航时车模被覆盖（2026-11-08）
                                             *
                                             * 现象：车停着时是 3D 车模，一开导航就变回箭头。
                                             *
                                             * 原因：导航状态变化时，引擎/业务层会自己调
                                             * MapLayer.updateCarStyle(...)，把之前设的 2001 覆盖成
                                             * 2002（普通模式）→ 引擎退回 2D 箭头渲染。
                                             *
                                             * 源码用的是同一套思路（hud_effects.js:82-84 hook
                                             * HUDControl.updateMapTheme 保护主题不被 OEM 改）：
                                             * 把方法 hook 住，链到原实现之前先把参数改回我们要的值。
                                             *
                                             * 这里 hook MapLayer.updateCarStyle(int)：
                                             * 无论谁传什么，一律改成 2001（导航态 = 3D 车模）。
                                             * 注意要 return 原方法的返回值，否则调用方拿到 undefined 会出错。
                                             */
                                            try {
                                                var MLH = Java.use(
                                                    'ecarx.navi.engine.manager.layer.MapLayer');
                                                var ucs = MLH.updateCarStyle.overload('int');
                                                /*
                                                 * **必须用 ucs.call(this, 2001) 而不是
                                                 * this.updateCarStyle(2001)** —— 后者会再次
                                                 * 进入这个 implementation，无限递归直到栈溢出
                                                 * （一开导航就崩）。
                                                 *
                                                 * ucs.call 直接走原始实现，绕过 hook。
                                                 */
                                                ucs.implementation = function (style) {
                                                    return ucs.call(this, 2001);
                                                };
                                                log('updateCarStyle 已 hook（强制 2001，防导航时车模退化）');
                                            } catch (eHk) {
                                                log('hook updateCarStyle 失败: '
                                                    + String(eHk).substring(0, 70));
                                            }

                                            /*
                                             * ★ 视角调整：让车模居中偏上、后方留空（2026-11-08）
                                             *
                                             * 需求：车模 + 蓝标完整露出来，车后面（画面下方）留一点空，
                                             * 视觉上像是"车在画面里、视线往前看"。
                                             *
                                             * 现在的问题：车标被投影到画面正中（y ≈ H/2），
                                             * 上方的道路被压得很窄，下方也没有余量 —— 车模容易被裁。
                                             *
                                             * 原理：车标位置 = Camera center 在屏幕上的投影点。
                                             * 改它有两个手段：
                                             *   ① setPitchAngle —— 俯仰角。Pitch 越大视野越前倾，
                                             *      车模在画面里越靠上（3D 导航的标准做法，通常 30-50°）
                                             *   ② setMapCenterOffset / setViewPortOffset —— 直接给屏幕
                                             *      投影加纵向偏移（有的引擎版本叫别的名）
                                             *
                                             * 参考源码 hud_effects.js:251：
                                             *   view.getOperatorPosture().setPitchAngle(pitch)
                                             * 源码没做偏移，靠 pitch 就够了。
                                             *
                                             * 参数取值：
                                             *   PITCH_ANGLE = 45°  —— 标准 3D 导航视角
                                             *   CAR_Y_RATIO = 0.38 —— 车模目标纵向位置（0=顶, 1=底）
                                             *                         0.38 让车模略偏上、下方留 62% 的路况
                                             *
                                             * 实现：
                                             *   · 先设 pitch
                                             *   · 再算需要补的纵向偏移，用 posture 的 setter 补上
                                             *     （拿不到就只靠 pitch，也够用）
                                             */
                                            try {
                                                /* 复用上面底图开关那段取到的 mv5b，
                                                 * 不再重复反射 ConcurrentHashMap.get */
                                                if (mv5b) {
                                                    var posture = mv5b.getOperatorPosture();

                                                    /* ① 俯仰角：45° 标准 3D 导航视角 */
                                                    var PITCH = 45.0;
                                                    try {
                                                        posture.setPitchAngle(PITCH);
                                                        log('pitch = ' + posture.getPitchAngle());
                                                    } catch (eP) {
                                                        log('setPitchAngle 失败: '
                                                            + String(eP).substring(0, 50));
                                                    }

                                                    /*
                                                     * ② 纵向偏移：把车模推到画面 38% 高度处
                                                     *
                                                     * posture 可能暴露 setViewPortY / setMapCenterY
                                                     * 之类的接口（不同引擎版本名字不一样），
                                                     * 逐个试，谁成功用谁。都不成功也不要紧 ——
                                                     * pitch=45 本身就能把车模推到接近目标位置。
                                                     */
                                                    var CAR_Y_RATIO = 0.38;
                                                    var targetY = Math.round(H * CAR_Y_RATIO);
                                                    var centerY = Math.round(H / 2);
                                                    var dy = targetY - centerY;   /* 负值 = 上移 */
                                                    var done = false;
                                                    var cands = [
                                                        'setViewPortOffset', 'setViewportOffset',
                                                        'setMapCenterOffset', 'setCenterOffset'
                                                    ];
                                                    for (var ci = 0; ci < cands.length && !done; ci++) {
                                                        try {
                                                            var mn = cands[ci];
                                                            posture[mn].overload('int', 'int')
                                                                  .call(posture, 0, dy);
                                                            log('  ' + mn + '(0,' + dy + ') OK');
                                                            done = true;
                                                        } catch (e1) {
                                                            try {
                                                                posture[cands[ci]]
                                                                    .overload('float', 'float')
                                                                    .call(posture, 0.0, dy * 1.0);
                                                                log('  ' + cands[ci] + '(0,' + dy + ') OK(float)');
                                                                done = true;
                                                            } catch (e2) {}
                                                        }
                                                    }
                                                    if (!done) {
                                                        log('  无纵向偏移接口，仅靠 pitch=45°（视觉上已接近目标）');
                                                    }

                                                    /* ③ 缩放：17 是导航标准级别，保持不动 */
                                                    try {
                                                        log('  zoom = ' + posture.getZoomLevel());
                                                    } catch (eZ) {}
                                                } else {
                                                    log('取不到 MapView(5) 做视角调整');
                                                }
                                            } catch (eView) {
                                                log('视角调整失败: '
                                                    + String(eView).substring(0, 80));
                                            }

                                            startPc();
                                            return;
                                        }
                                    }
                                } catch (e) {
                                    if (tries <= 3) log('轮询 ' + tries + ' :: ' + String(e).substring(0, 70));
                                }
                                if (tries < want) {
                                    CTX.hh.postDelayed(pollInst, 1500);
                                } else {
                                    log('HudActivity 未就绪（轮询 ' + tries + ' 次后放弃）');
                                }
                            }
                        }
                    });
                    var pollInst = Poll.$new();
                    CTX.hh.postDelayed(pollInst, 1500);
                    log('等待 HudActivity 初始化…');
                } catch (e) {
                    log('主流程异常: ' + String(e).substring(0, 130));
                }
            }
        }
    });

    /* ── PixelCopy 链（等 HudActivity 就绪后再启动） ── */

    CTX.hh.post(Main.$new());
});
