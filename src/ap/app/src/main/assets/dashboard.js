/* ============================================================================
   dashboard.js  —  1920 × 538 车机仪表盘 · 纯 Canvas 2D 渲染
   ----------------------------------------------------------------------------
   只读仪表：不接受任何鼠标 / 触摸输入，画面 100% 由 setData() 驱动。
   完整数据字典（字段 / 单位 / 范围 / 触发规则 / 接入示例）见同目录
   《仪表盘交接文档.md》——改字段前先对照那份表。

   画面分区（设计稿坐标）：
     速度卡   16,16   512×314   圆盘 cx272 cy150 r120，量程 0–220，超 120 整盘转红
     电量卡   16,342  512×84    低于 20% 转黄
     燃油卡   16,438  512×84    低于 20% 转红
     灯光栏   544,16  864×40    7 个灯位：左转/自动大灯/近光/远光/自动驻车/后雾/右转
     地图     544,64  864×382   路线 + 三车道 + 自车 + 车道引导
     横条     544,458 864×64    导航 / 行程 / 能耗 / 胎压 四种内容
     时钟     1424,16 480×92    取系统时间，无需喂数据
     媒体     1424,120 480×326  播放态（唱片+歌词）/ 待机态
     档位     1424,458 480×64   档位字母 + 四个混动模式胶囊

   用法一（自动全屏挂载）：  <script src="dashboard.js"></script>
   用法二（手动挂载，推荐接真实数据）：
        const hud = new HUD(canvas);
        hud.autoSpeed = false;      // 关闭内置模拟，改为跟随真实车速
        hud.autoPower = false;      // 关闭功率模拟
        hud.start();
        hud.setData({ speed: 108, evRange: 130, fuelRange: 1370, power: 142 });
   ========================================================================== */
(function (global) {
  'use strict';

  const W = 1920, H = 538;

  /* ------------------------------ 调色板 ------------------------------ */
  const C = {
    bgA: '#080C0E', bgB: '#05090B', bgC: '#040708',
    teal: '#43E8C8',
    tealLt: '#B6FFF2',
    amber: '#FFB24D',
    amberLt: '#FFD9A3',
    warn: '#FFC93C',      // 电量低
    warnLt: '#FFE7A6',
    red: '#FF5252',       // 燃油低 / 超速
    redLt: '#FFB3B3',
    txt: '#E6F1F1',
    dim: '#75898E',
    dim2: '#5E7277',
    dim3: '#4E6165',
    line: 'rgba(120,170,180,0.14)'
  };

  const FONT = '"Bahnschrift","DIN Alternate","Segoe UI","Microsoft YaHei",sans-serif';
  const f = (size, weight) => `${weight || 400} ${size}px ${FONT}`;
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

  /* ------------------------------ 布局 ------------------------------ */
  const PAD = 16, GAP = 16;
  const L = {
    leftX: PAD, leftW: 512,
    gauge: { x: PAD, y: PAD, w: 512, h: 314 },
    evCard: { x: PAD, y: 342, w: 512, h: 84 },
    fuCard: { x: PAD, y: 438, w: 512, h: 84 },
    map: { x: 544, y: 64, w: 864, h: 382 },
    rail: { x: 544, y: PAD, w: 864, h: 40 },
    navStrip: { x: 544, y: 458, w: 864, h: 64 },
    rightX: 1424, rightW: 480,
    clock: { x: 1424, y: PAD, w: 480, h: 92 },
    media: { x: 1424, y: 120, w: 480, h: 326 },
    gear: { x: 1424, y: 458, w: 480, h: 64 }
  };

  /* 速度表几何：0–220，超速阈值 100 */
  const G = { cx: PAD + 256, cy: PAD + 134, r: 120, a0: -122, a1: 122, max: 220, over: 120 };

  /* ------------------------------ 小工具 ------------------------------ */
  function rr(ctx, x, y, w, h, r) {
    r = Math.min(r, Math.abs(w) / 2, Math.abs(h) / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.arcTo(x + w, y, x + w, y + r, r);
    ctx.lineTo(x + w, y + h - r);
    ctx.arcTo(x + w, y + h, x + w - r, y + h, r);
    ctx.lineTo(x + r, y + h);
    ctx.arcTo(x, y + h, x, y + h - r, r);
    ctx.lineTo(x, y + r);
    ctx.arcTo(x, y, x + r, y, r);
    ctx.closePath();
  }

  function setLS(ctx, px) { if ('letterSpacing' in ctx) ctx.letterSpacing = px + 'px'; }

  function arcPath(ctx, cx, cy, r, a0, a1) {
    ctx.beginPath();
    ctx.arc(cx, cy, r, (a0 - 90) * Math.PI / 180, (a1 - 90) * Math.PI / 180, false);
  }

  function polar(cx, cy, r, a) {
    const t = a * Math.PI / 180;
    return [cx + r * Math.sin(t), cy - r * Math.cos(t)];
  }

  /* 颜色插值：#rrggbb × 2 → rgb() */
  function rgba(hex, a) {
    const r = parseInt(hex.slice(1, 3), 16);
    const g = parseInt(hex.slice(3, 5), 16);
    const b = parseInt(hex.slice(5, 7), 16);
    return `rgba(${r},${g},${b},${a})`;
  }

  function lerpColor(a, b, t) {
    const p = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
    const A = p(a), B = p(b);
    const h = v => Math.round(v).toString(16).padStart(2, '0');
    return '#' + h(A[0] + (B[0] - A[0]) * t) + h(A[1] + (B[1] - A[1]) * t) + h(A[2] + (B[2] - A[2]) * t);
  }

  /* 折线：长度 / 采样 / 点 / 切线 */
  function polyMeta(pts) {
    const cum = [0];
    let len = 0;
    for (let i = 1; i < pts.length; i++) {
      len += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
      cum.push(len);
    }
    return { pts, cum, len };
  }
  function polyAt(m, u) {
    const d = clamp(u, 0, 1) * m.len;
    let lo = 0, hi = m.cum.length - 1;
    while (lo < hi - 1) {
      const mid = (lo + hi) >> 1;
      if (m.cum[mid] <= d) lo = mid; else hi = mid;
    }
    const seg = m.cum[hi] - m.cum[lo] || 1;
    const t = (d - m.cum[lo]) / seg;
    const a = m.pts[lo], b = m.pts[hi];
    return {
      x: a[0] + (b[0] - a[0]) * t,
      y: a[1] + (b[1] - a[1]) * t,
      ang: Math.atan2(b[1] - a[1], b[0] - a[0])
    };
  }
  function tracePoly(ctx, m) {
    ctx.beginPath();
    ctx.moveTo(m.pts[0][0], m.pts[0][1]);
    for (let i = 1; i < m.pts.length; i++) ctx.lineTo(m.pts[i][0], m.pts[i][1]);
  }

  /* 沿折线法线方向偏移，用于生成车道边界 */
  function offsetPoly(pts, d) {
    const out = [];
    for (let i = 0; i < pts.length; i++) {
      const a = pts[Math.max(0, i - 1)];
      const b = pts[Math.min(pts.length - 1, i + 1)];
      const dx = b[0] - a[0], dy = b[1] - a[1];
      const len = Math.hypot(dx, dy) || 1;
      out.push([pts[i][0] - dy / len * d, pts[i][1] + dx / len * d]);
    }
    return out;
  }

  function tracePts(ctx, pts) {
    ctx.beginPath();
    ctx.moveTo(pts[0][0], pts[0][1]);
    for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]);
  }
  function sampleBezier(segs, per) {
    per = per || 26;
    const out = [];
    let cur = [0, 0];
    for (const s of segs) {
      if (s.t === 'M' || s.t === 'L') { cur = s.p.slice(); out.push(cur.slice()); }
      else if (s.t === 'C') {
        const [x1, y1, x2, y2, x3, y3] = s.p;
        for (let i = 1; i <= per; i++) {
          const t = i / per, u = 1 - t;
          out.push([
            u * u * u * cur[0] + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
            u * u * u * cur[1] + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
          ]);
        }
        cur = [x3, y3];
      }
    }
    return out;
  }

  /* ------------------------------ 地图素材 ------------------------------ */
  const ROUTE_SEGS = [
    { t: 'M', p: [262, 470] },
    { t: 'C', p: [262, 408, 338, 394, 392, 358] },
    { t: 'C', p: [452, 316, 470, 302, 562, 294] },
    { t: 'C', p: [660, 284, 706, 238, 764, 188] },
    { t: 'L', p: [842, 128] }
  ];
  const ROUTE = polyMeta(sampleBezier(ROUTE_SEGS, 30));
  const HIGHWAY = polyMeta([[-40, 512], [250, 358], [520, 320], [772, 208], [1080, 80]]);
  const TRAFFIC = [
    { m: polyMeta([[-30, 296], [1070, 296]]), dur: 11, c: C.teal, u: 0.12 },
    { m: polyMeta([[1070, 96], [-30, 96]]), dur: 16, c: C.amber, u: 0.55 },
    { m: HIGHWAY, dur: 19, c: '#8FB6BC', u: 0.3 }
  ];
  const MINOR_V = [110, 240, 380, 520, 660, 800, 930];
  const MINOR_H = [70, 184, 290, 400, 498];
  const MAJOR_V = [200, 520, 812];
  const MAJOR_H = [96, 296, 430];
  const BLOCKS = [
    [128, 116, 66, 52], [228, 112, 92, 60], [356, 120, 74, 48],
    [452, 330, 86, 56], [560, 128, 64, 60], [828, 228, 88, 54],
    [148, 330, 76, 58], [852, 336, 74, 52]
  ];
  const MAP_LABELS = [
    [612, 292, '中山北路', '#5C7176', -3],
    [246, 92, '环城高架', '#5C7176', -2],
    [126, 424, '滨江大道', '#5C7176', -2],
    [655, 352, '中央公园', '#3E7A63', 0],
    [848, 196, '科技园', '#5C7176', 0],
    [392, 512, '老城区', '#4B5F64', 0]
  ];

  /* ============================================================================
     HUD
     ========================================================================== */
  class HUD {
    constructor(canvas, opts) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.dpr = Math.min(global.devicePixelRatio || 1, 2);

      this.data = Object.assign({
        speed: 0, noa: true, noaLabel: 'NOA', noaSpeed: 120,
        /* 档位 + 混动模式 */
        gear: 'D', driveMode: '纯电',
        modes: ['智能', '纯电', '增程', '性能'],
        /* 续航：value 为当前续航（km），cap 为满值（km），低于 20% 触发告警色 */
        evRange: 130, evCap: 180,
        fuelRange: 1370, fuelCap: 1560,
        /* 总功率 kW */
        power: 142, powerMax: 300,

        /* 车道引导：推荐车道用 active 索引标出 */
        lanes: ['left', 'straight', 'straight', 'straight', 'right'],
        laneActive: [2, 3],

        tripKm: '24.6', tripAvg: '42', tripMin: '35', tripEnergy: '15.8', tripFuel: '6.2',
        navActive: false,
        navEnd: 'exited',           // 导航结束原因：arrived（到达，显示本次行程）| exited（退出，显示胎压）
        tires: [['左前', 2.4], ['右前', 2.4], ['左后', 2.5], ['右后', 2.1]],
        navDist: '300', navUnit: '米', navStreet: '中山北路', navTurn: '右',
        navLeft: '12.4 km', navEta: '09 : 52', navTime: '23 分钟',
        outdoor: '18°', indoor: '22°', weather: '晴 · 东南风 2 级',
        /* 灯光状态：true = 点亮 */
        lights: {
          left: true, auto: true, low: true, high: false,
          /*
           * **hold 默认必须是 false**（2026-11-12 修「自动驻车一直亮」）
           *
           * 原来写的是 hold: true —— 而且从来没有任何代码给它赋过真实值，
           * 所以那盏灯永远亮着，跟车实际状态完全对不上。
           *
           * 正确语义（用户确认）：D 档 + 刹车踩到底 → 自动驻车生效 → 灯亮。
           * 真实值由车辆数据层写进 UIA.data.lights.hold
           * （APVP 0x21407645 SETTING_FUNC_AUTO_HOLD_ACTIVE），读不到就是 false。
           *
           * 默认 false 比 true 安全：万一数据通道挂了，
           * 用户看到灯不亮，而不是误以为车已经驻住了 ——
           * 这是安全指示，宁可漏报不能误报。
           */
          hold: false, fogR: false, right: false, hazard: false
        },
        /* 媒体 / 歌词 */
        mediaTitle: '夜航西飞', mediaArtist: '陈明', mediaAlbum: '长夜',
        lyric0: '风把城市 吹得很远',
        lyric1: '云在脚下 慢慢退潮',
        lyric2: '我把夜航 折成一封信',
        lyric3: '而你还在 灯火那边',
        mediaPct: 42, mediaTime: '1:42', mediaDur: '4:05',
        mediaPlaying: false,
      }, opts || {});

      this.animEv = 0;
      this.animFu = 0;
      this.t0 = performance.now();
      this.sim = 0;
      this.pow = 0;
      this.running = false;

      this.time = 0;
      this.last = 0;
      this.autoSpeed = true;
      this.autoPower = true;
      /* 外部地图模式：true 时地图区留透明洞，交给外部真地图填充
       * （WebView 方案里 SurfaceView 垫在底下，SurfaceFlinger 合成）
       *
       * ★ 修（2026-11-10）：opts 原本只喂给 this.data，设不了实例属性，
       *   于是 new HUD(cv, {mapExternal:true}) 会静默失效 ——
       *   地图区不被留洞，真地图被 UI 盖死。
       *   这里显式把 opts 里的这三个开关接出来。
       */
      this.mapExternal = !!(opts && opts.mapExternal);
      if (opts && opts.autoSpeed !== undefined) this.autoSpeed = !!opts.autoSpeed;
      if (opts && opts.autoPower !== undefined) this.autoPower = !!opts.autoPower;
      this.noaPulseAt = null;
      this.fps = 0;
      this._frames = 0;
      this._fpsT = 0;

      this.fit = this.fit.bind(this);
      this.loop = this.loop.bind(this);
      this.resize();
      global.addEventListener('resize', this.fit);
      if (global.ResizeObserver && canvas.parentElement) {
        this._ro = new ResizeObserver(() => this.fit());
        this._ro.observe(canvas.parentElement);
      }
    }

    /* 卸载：车机切页 / 热更新时调用，避免监听泄漏 */
    destroy() {
      this.stop();
      if (this._ro) { this._ro.disconnect(); this._ro = null; }
      global.removeEventListener('resize', this.fit);
    }


    resize() {
      const c = this.canvas;
      const d = Math.min(global.devicePixelRatio || 1, 2);
      if (d !== this.dpr) this.dpr = d;
      c.width = Math.round(W * this.dpr);
      c.height = Math.round(H * this.dpr);
      const s = Math.min(global.innerWidth / W, global.innerHeight / H);
      c.style.width = Math.round(W * s) + 'px';
      c.style.height = Math.round(H * s) + 'px';
      c.style.display = 'block';
      if (c.parentElement) {
        c.parentElement.style.display = 'flex';
        c.parentElement.style.alignItems = 'center';
        c.parentElement.style.justifyContent = 'center';
      }
    }
    fit() { this.resize(); }
    setData(d) {
      // NOA 状态变化时触发一次接管闪环
      if (d && 'noa' in d && d.noa !== this.data.noa) this.noaPulseAt = this.time;
      Object.assign(this.data, d);
    }

    start() {
      if (this.running) return;
      this.running = true;
      this.last = performance.now();
      this._fpsT = this.last;
      this._frames = 0;
      requestAnimationFrame(this.loop);
    }
    stop() { this.running = false; }

    loop(now) {
      if (!this.running) return;
      const dt = Math.min((now - this.last) / 1000, 0.1);
      this.last = now;
      this.time += dt;
      const t = this.time;

      this._frames++;
      if (now - this._fpsT >= 500) {
        this.fps = Math.round(this._frames * 1000 / (now - this._fpsT));
        this._frames = 0;
        this._fpsT = now;
      }

      let target;
      if (!this.autoSpeed) target = this.data.speed;
      else if (this.data.speed > 0) target = this.data.speed;
      else target = clamp(96 + Math.sin(t * 0.31) * 34 + Math.sin(t * 1.37) * 9 + Math.sin(t * 2.9) * 3, 18, 196);
      this.sim += (target - this.sim) * 0.055;

      // 总功率：自动模式下跟随车速起伏，锁定模式用真实值
      const pTarget = this.autoPower
        ? clamp(this.sim * 1.18 + 14 + Math.sin(t * 0.9) * 9, 0, this.data.powerMax)
        : this.data.power;
      this.pow += (pTarget - this.pow) * 0.06;

      this.animEv += (1 - this.animEv) * 0.055;
      this.animFu += (1 - this.animFu) * 0.055;

      this.draw(t);
      requestAnimationFrame(this.loop);
    }

    /* ============================ 主绘制 ============================ */
    draw(t) {
      const ctx = this.ctx;
      ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);

      this.drawBackdrop(ctx);
      this.drawSpeedo(ctx, t);
      this.drawRange(ctx, L.evCard, {
        name: '电量续航', val: this.data.evRange, cap: this.data.evCap,
        anim: this.animEv, kind: 'ev'
      });
      this.drawRange(ctx, L.fuCard, {
        name: '燃油续航', val: this.data.fuelRange, cap: this.data.fuelCap,
        anim: this.animFu, kind: 'fuel'
      });
      this.drawLightRail(ctx, t);
      this.drawMap(ctx, t);
      this.drawClock(ctx);
      this.drawNavStrip(ctx);
      this.drawMedia(ctx, t);
      this.drawGear(ctx);
    }

    drawBackdrop(ctx) {
      /* ── 外部地图模式（this.mapExternal） ─────────────────────────
       *
       * 打开后：地图区（L.map）完全不涂，留给外部真地图
       * （Android SurfaceView / TextureView 垫在 WebView 底下，
       *  SurfaceFlinger 硬件合成，零拷贝）。
       *
       * 背景按"回"字形分四块填，跳过地图矩形：
       *   ┌──────────────┐
       *   │      ①       │
       *   ├───┬──────┬───┤
       *   │ ② │ 留空 │ ④ │
       *   ├───┴──────┴───┤
       *   │      ③       │
       *   └──────────────┘
       *
       * ══════════════════════════════════════════════════════════════
       * ★ 静态缓存（2026-11-10，车机 WebView 实测得出的关键优化）
       * ══════════════════════════════════════════════════════════════
       *
       * 实测（SE1000 / WebView 83）：
       *   · 逐模块 JS 计时合计只有 5.74ms（30fps 预算 16ms）
       *   · 但整帧 rAF 间隔 50.1ms（≈20fps），比空转的 33.4ms 多出 ~16ms
       *   · E3 只画背景（JS 0.05ms）就让帧间隔从 33.4 → 50.0ms
       *
       * 结论：drawBackdrop 的 JS 开销可以忽略，真正烧的是
       * **软件光栅化** —— 4 块全屏线性渐变 ≈ 2.2 个全屏面积的
       * 逐像素插值，WebView 83 走 CPU 光栅 ≈ 11~16ms。
       *
       * 修法：背景完全静态（只依赖 mapExternal 与画布尺寸），
       * 画一次进离屏 canvas，之后每帧只做一次 drawImage blit
       * （整幅位块搬运 ≈ 1.5ms，GPU 合成路径下更便宜）。
       *
       * 失效条件：mapExternal 切换、画布尺寸变化。
       */
      const m = L.map;
      const ext = this.mapExternal;
      const key = (ext ? 'ext' : 'int') + '@' + ctx.canvas.width + 'x' + ctx.canvas.height;

      /* 建缓存：document.createElement 在车机 WebView / 浏览器都可用；
       * Frida 环境（dash_bridge 桩）没有 getContext，会走 fallback，
       * 退回旧的全量绘制路径 —— 不破坏现有链路。 */
      if (!this._bgCache || this._bgCacheKey !== key) {
        try {
          const off = document.createElement('canvas');
          off.width = ctx.canvas.width;
          off.height = ctx.canvas.height;
          const octx = off.getContext && off.getContext('2d');
          if (octx) {
            /* 原始绘制逻辑照搬，目标换成 octx */
            const fillRegion = (x, y, w, h) => {
              if (w <= 0 || h <= 0) return;
              const g = octx.createLinearGradient(0, 0, 0, H);
              g.addColorStop(0, C.bgA); g.addColorStop(0.62, C.bgB); g.addColorStop(1, C.bgC);
              octx.fillStyle = g; octx.fillRect(x, y, w, h);
            };
            if (ext) {
              fillRegion(0, 0, W, m.y);
              fillRegion(0, m.y + m.h, W, H - m.y - m.h);
              fillRegion(0, m.y, m.x, m.h);
              fillRegion(m.x + m.w, m.y, W - m.x - m.w, m.h);
            } else {
              fillRegion(0, 0, W, H);
              /* 两处环境辉光整屏铺 —— 只有内部模式才有 */
              let rg = octx.createRadialGradient(272, 0, 0, 272, 0, 760);
              rg.addColorStop(0, 'rgba(67,232,200,0.075)'); rg.addColorStop(1, 'rgba(67,232,200,0)');
              octx.fillStyle = rg; octx.fillRect(0, 0, W, H);

              rg = octx.createRadialGradient(1664, H, 0, 1664, H, 620);
              rg.addColorStop(0, 'rgba(255,178,77,0.055)'); rg.addColorStop(1, 'rgba(255,178,77,0)');
              octx.fillStyle = rg; octx.fillRect(0, 0, W, H);
            }
            this._bgCache = off;
            this._bgCacheKey = key;
          }
        } catch (eBg) {
          /* 建缓存失败就退回直绘，保底不黑屏 */
          this._bgCache = null;
        }
      }

      if (this._bgCache) {
        /* 命中缓存：一次 blit（绘制坐标系当前可能带 dpr 变换，
         * 用画布 CSS 尺寸作为目标矩形，与 setTransform 后的坐标一致） */
        ctx.drawImage(this._bgCache, 0, 0, W, H);
        return;
      }

      /* ── fallback：无缓存能力时走原来的直绘路径 ─────────────── */
      const fillRegion = (x, y, w, h) => {
        if (w <= 0 || h <= 0) return;
        const g = ctx.createLinearGradient(0, 0, 0, H);
        g.addColorStop(0, C.bgA); g.addColorStop(0.62, C.bgB); g.addColorStop(1, C.bgC);
        ctx.fillStyle = g; ctx.fillRect(x, y, w, h);
      };

      if (ext) {
        fillRegion(0, 0, W, m.y);
        fillRegion(0, m.y + m.h, W, H - m.y - m.h);
        fillRegion(0, m.y, m.x, m.h);
        fillRegion(m.x + m.w, m.y, W - m.x - m.w, m.h);
      } else {
        fillRegion(0, 0, W, H);
      }

      /* 两处环境辉光整屏铺，会糊到真地图上 —— 外部模式下跳过 */
      if (!ext) {
        let rg = ctx.createRadialGradient(272, 0, 0, 272, 0, 760);
        rg.addColorStop(0, 'rgba(67,232,200,0.075)'); rg.addColorStop(1, 'rgba(67,232,200,0)');
        ctx.fillStyle = rg; ctx.fillRect(0, 0, W, H);

        rg = ctx.createRadialGradient(1664, H, 0, 1664, H, 620);
        rg.addColorStop(0, 'rgba(255,178,77,0.055)'); rg.addColorStop(1, 'rgba(255,178,77,0)');
        ctx.fillStyle = rg; ctx.fillRect(0, 0, W, H);
      }
    }

    panel(ctx, x, y, w, h, r) {
      r = r === undefined ? 20 : r;
      const g = ctx.createLinearGradient(x, y, x + w * 0.55, y + h);
      g.addColorStop(0, 'rgba(21,31,36,0.94)');
      g.addColorStop(1, 'rgba(8,13,16,0.97)');
      rr(ctx, x + 0.5, y + 0.5, w - 1, h - 1, r);
      ctx.fillStyle = g; ctx.fill();
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();

      ctx.save();
      rr(ctx, x + 1, y + 1, w - 2, h - 2, r - 1);
      ctx.clip();
      const gl = ctx.createLinearGradient(0, y, 0, y + h * 0.42);
      gl.addColorStop(0, 'rgba(255,255,255,0.052)');
      gl.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = gl; ctx.fillRect(x, y, w, h * 0.42);
      ctx.restore();
    }

    /* --------------------------- 速度圆盘 --------------------------- */
    drawSpeedo(ctx, t) {
      const card = L.gauge;
      this.panel(ctx, card.x, card.y, card.w, card.h);

      const { cx, cy, r, a0, a1, max, over } = G;
      const frac = clamp(this.sim / max, 0, 1);
      // 超速程度：0=青绿，1=红
      const hot = clamp((this.sim - over) / 4, 0, 1);

      // 超过阈值时整盘染色
      const colTrack = lerpColor('#13242B', '#341A1C', hot);
      const colMajor = lerpColor('#7A9096', '#C46A6A', hot);
      const colMinor = lerpColor('#33474E', '#5E2C2E', hot);
      const colLabel = lerpColor('#63787D', '#D98C8C', hot);
      const colInner = lerpColor('#8A9A9E', '#B06A6A', hot);

      /* 轨道 */
      arcPath(ctx, cx, cy, r, a0, a1);
      ctx.strokeStyle = colTrack; ctx.lineWidth = 10;
      ctx.lineCap = 'round'; ctx.stroke();

      arcPath(ctx, cx, cy, r - 30, a0, a1);
      ctx.strokeStyle = rgba(colInner, 0.9); ctx.lineWidth = 1; ctx.stroke();

      /* 刻度：每 10 一格，每 20 主线 + 数字 */
      for (let v = 0; v <= max; v += 10) {
        const a = a0 + (a1 - a0) * (v / max);
        const major = v % 20 === 0;
        const p1 = polar(cx, cy, r, a);
        const p2 = polar(cx, cy, major ? r - 18 : r - 10, a);

        const isThr = v === over;
        ctx.beginPath();
        ctx.moveTo(p1[0], p1[1]); ctx.lineTo(p2[0], p2[1]);
        ctx.strokeStyle = isThr ? (hot > 0.5 ? '#FFFFFF' : 'rgba(255,82,82,0.85)') : (major ? colMajor : colMinor);
        ctx.lineWidth = isThr ? 3 : (major ? 2.2 : 1.3);
        ctx.lineCap = 'round';
        if (isThr) { ctx.shadowColor = 'rgba(255,82,82,0.7)'; ctx.shadowBlur = 8; }
        ctx.stroke();
        ctx.shadowBlur = 0;

        if (major) {
          const lp = polar(cx, cy, r - 36, a);
          ctx.save();
          setLS(ctx, 0.4);
          ctx.fillStyle = isThr ? (hot > 0.5 ? '#FFFFFF' : 'rgba(255,140,140,0.95)') : colLabel;
          ctx.font = f(11.5, 400);
          ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
          ctx.fillText(v, lp[0], lp[1]);
          ctx.restore();
        }
      }

      /* 进度弧：超过阈值整条转红 */
      const aNow = a0 + (a1 - a0) * frac;

      if (frac > 0) {
        const grad = ctx.createLinearGradient(cx - r, cy + r, cx + r, cy - r);
        grad.addColorStop(0, lerpColor('#1E8E7C', '#C43C3C', hot));
        grad.addColorStop(0.55, lerpColor(C.teal, '#FF7B7B', hot));
        grad.addColorStop(1, lerpColor('#B6FFF2', '#FFD9D9', hot));
        ctx.save();
        ctx.shadowColor = hot > 0.5 ? 'rgba(255,82,82,0.62)' : rgba(C.teal, 0.5);
        ctx.shadowBlur = 14;
        arcPath(ctx, cx, cy, r, a0, aNow);
        ctx.strokeStyle = grad;
        ctx.lineWidth = 10; ctx.lineCap = 'round';
        ctx.stroke();
        ctx.restore();
      }

      /* NOA 外环：底环 + 缓转扫描带 + 跟随指针的彗尾 + 接管闪环 */
      if (this.data.noa !== false) {
        const ringR = r + 12;
        const ringHex = hot > 0.5 ? C.red : C.teal;

        // 底环
        arcPath(ctx, cx, cy, ringR, a0, a1);
        ctx.strokeStyle = rgba(C.teal, 0.10);
        ctx.lineWidth = 2.5;
        ctx.lineCap = 'round';
        ctx.stroke();

        // 雷达扫描带：9 段递减排布，逆时针缓转
        const span = a1 - a0;
        const pos = a1 - ((t * 15) % span);
        for (let i = 0; i < 9; i++) {
          const s0 = pos + i * 11;
          if (s0 >= a1) break;
          arcPath(ctx, cx, cy, ringR, s0, Math.min(a1, s0 + 11));
          ctx.strokeStyle = rgba(ringHex, 0.16 * (1 - i / 9));
          ctx.lineWidth = 2.5;
          ctx.stroke();
        }

        // 彗尾
        if (aNow - a0 > 4) {
          const tail = Math.max(a0, aNow - 30);
          ctx.save();
          ctx.shadowColor = rgba(ringHex, 0.55);
          ctx.shadowBlur = 10;
          arcPath(ctx, cx, cy, ringR, tail, aNow);
          ctx.strokeStyle = rgba(ringHex, 0.62);
          ctx.lineWidth = 2.5;
          ctx.lineCap = 'round';
          ctx.stroke();
          ctx.restore();

          const hp = polar(cx, cy, ringR, aNow);
          ctx.save();
          ctx.beginPath();
          ctx.arc(hp[0], hp[1], 2.6, 0, 6.2832);
          ctx.fillStyle = ringHex;
          ctx.shadowColor = rgba(ringHex, 0.9);
          ctx.shadowBlur = 10;
          ctx.fill();
          ctx.restore();
        }

        // 设定车速标记（打断底环的小圆点）
        const setA = a0 + (a1 - a0) * ((this.data.noaSpeed || 120) / max);
        const sp2 = polar(cx, cy, ringR, setA);
        ctx.beginPath(); ctx.arc(sp2[0], sp2[1], 5, 0, 6.2832);
        ctx.fillStyle = '#0A1114'; ctx.fill();
        ctx.beginPath(); ctx.arc(sp2[0], sp2[1], 3, 0, 6.2832);
        ctx.fillStyle = hot > 0.5 ? C.red : C.teal; ctx.fill();

        // 接管瞬间：整圈闪一下（0.45s 衰减）
        if (this.noaPulseAt != null) {
          const p = (this.time - this.noaPulseAt) / 0.45;
          if (p >= 1) {
            this.noaPulseAt = null;
          } else {
            ctx.save();
            arcPath(ctx, cx, cy, ringR, a0, a1);
            ctx.strokeStyle = rgba(C.teal, 0.6 * (1 - p));
            ctx.lineWidth = 3.5;
            ctx.shadowColor = rgba(C.teal, 0.8 * (1 - p));
            ctx.shadowBlur = 14;
            ctx.stroke();
            ctx.restore();
          }
        }
      }

      /* 指针 */
      const nTip = lerpColor('#EAFFFA', '#FFE2E2', hot);
      const nMid = lerpColor(C.teal, C.red, hot);
      const nBase = lerpColor('#123A38', '#3A1414', hot);
      const ang = aNow;
      ctx.save();
      ctx.translate(cx, cy);
      ctx.rotate(ang * Math.PI / 180);
      ctx.shadowColor = hot > 0.5 ? 'rgba(255,82,82,0.6)' : 'rgba(67,232,200,0.5)';
      ctx.shadowBlur = 12;
      const ng = ctx.createLinearGradient(0, -r + 6, 0, 20);
      ng.addColorStop(0, nTip); ng.addColorStop(0.5, nMid); ng.addColorStop(1, nBase);
      ctx.beginPath();
      ctx.moveTo(0, -r + 6);
      ctx.lineTo(5.4, -6); ctx.lineTo(2.6, 20); ctx.lineTo(-2.6, 20); ctx.lineTo(-5.4, -6);
      ctx.closePath();
      ctx.fillStyle = ng; ctx.fill();
      ctx.restore();

      /* 轴心 */
      ctx.beginPath(); ctx.arc(cx, cy, 11, 0, 6.2832);
      ctx.fillStyle = '#0A1114'; ctx.fill();
      ctx.strokeStyle = hot > 0.5 ? '#5A2020' : '#2B424A'; ctx.lineWidth = 1.2; ctx.stroke();
      ctx.beginPath(); ctx.arc(cx, cy, 4, 0, 6.2832);
      ctx.fillStyle = lerpColor(C.teal, C.red, hot); ctx.fill();
      ctx.beginPath(); ctx.arc(cx, cy, 1.6, 0, 6.2832);
      ctx.fillStyle = '#0A1114'; ctx.fill();

      /* 数字车速 */
      ctx.save();
      ctx.textAlign = 'center';
      ctx.shadowColor = hot > 0.5 ? 'rgba(255,110,110,0.45)' : 'rgba(200,255,245,0.35)';
      ctx.shadowBlur = 16;
      ctx.fillStyle = hot > 0.5 ? '#FF9E9E' : '#F0FBFA';
      ctx.font = f(62, 600);
      setLS(ctx, -2);
      ctx.fillText(String(Math.round(this.sim)), cx, 248);
      ctx.restore();

      ctx.save();
      ctx.textAlign = 'center';
      ctx.fillStyle = hot > 0.5 ? '#B06060' : '#6B8085';
      ctx.font = f(11, 400);
      setLS(ctx, 3.6);
      ctx.fillText('km / h', cx, 271);
      ctx.restore();

      /* 智驾模式 + 设定车速 + 加减：排在同一行 */
      const noaOn = this.data.noa !== false;
      const setSpd = this.data.noaSpeed != null ? this.data.noaSpeed : 120;
      const noaCol = noaOn ? C.teal : '#E4EFEF';   // 未启用时为白色

      /* 状态点：激活时缓慢呼吸 */
      ctx.save();
      ctx.beginPath();
      ctx.arc(card.x + 11, 302, 3, 0, 6.2832);
      if (noaOn) {
        const br = 0.55 + 0.45 * Math.sin(t * 2.2);
        ctx.globalAlpha = br;
        ctx.fillStyle = C.teal;
        ctx.shadowColor = rgba(C.teal, 0.9);
        ctx.shadowBlur = 10;
      } else {
        ctx.fillStyle = 'rgba(230,241,241,0.42)';
      }
      ctx.fill();
      ctx.restore();

      /* NOA 字标 */
      const labelTxt = this.data.noaLabel || 'NOA';
      ctx.save();
      ctx.textAlign = 'left';
      ctx.fillStyle = noaCol;
      ctx.font = f(15, 600);
      setLS(ctx, 2.2);
      if (noaOn) {
        ctx.shadowColor = rgba(C.teal, 0.55);
        ctx.shadowBlur = 8;
      }
      ctx.fillText(labelTxt, card.x + 22, 307);
      const labelW = ctx.measureText(labelTxt).width;
      ctx.restore();

      /* 设定速度方牌：NOA 亮则亮，灭则灭 */
      const bdgX = card.x + 22 + labelW + 10;
      const bdgY = 289, bdgW = 40, bdgH = 26;

      ctx.save();
      rr(ctx, bdgX, bdgY, bdgW, bdgH, 9);
      if (noaOn) {
        const fg = ctx.createLinearGradient(0, bdgY, 0, bdgY + bdgH);
        fg.addColorStop(0, rgba(C.teal, 0.24));
        fg.addColorStop(1, rgba(C.teal, 0.06));
        ctx.fillStyle = fg;
      } else {
        const fg = ctx.createLinearGradient(0, bdgY, 0, bdgY + bdgH);
        fg.addColorStop(0, 'rgba(235,245,245,0.08)');
        fg.addColorStop(1, 'rgba(235,245,245,0.02)');
        ctx.fillStyle = fg;
      }
      ctx.fill();

      ctx.strokeStyle = noaOn ? rgba(C.teal, 0.9) : 'rgba(232,242,242,0.42)';
      ctx.lineWidth = 1.5;
      if (noaOn) {
        ctx.shadowColor = rgba(C.teal, 0.6);
        ctx.shadowBlur = 10;
      }
      ctx.stroke();
      ctx.restore();

      /* 内顶高光：让牌面有一点厚度 */
      ctx.save();
      rr(ctx, bdgX, bdgY, bdgW, bdgH, 9);
      ctx.clip();
      const hl = ctx.createLinearGradient(0, bdgY, 0, bdgY + 8);
      hl.addColorStop(0, noaOn ? 'rgba(255,255,255,0.16)' : 'rgba(255,255,255,0.10)');
      hl.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = hl;
      ctx.fillRect(bdgX, bdgY, bdgW, 8);
      ctx.restore();

      ctx.save();
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = noaOn ? '#E8FFFA' : '#DDE9E9';
      if (noaOn) {
        ctx.shadowColor = rgba(C.teal, 0.7);
        ctx.shadowBlur = 7;
      }
      ctx.font = f(15, 600);
      setLS(ctx, 0.6);
      ctx.fillText(String(setSpd), bdgX + bdgW / 2, bdgY + bdgH / 2 + 0.5);
      ctx.restore();

      ctx.save();
      const lx0 = 466, ly0 = 302, lr = 18;
      ctx.beginPath(); ctx.arc(lx0, ly0, lr, 0, 6.2832);
      ctx.fillStyle = 'rgba(12,19,22,0.92)'; ctx.fill();
      ctx.strokeStyle = 'rgba(224,72,64,0.8)';
      ctx.lineWidth = 2.6; ctx.stroke();
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillStyle = '#EAF6F6';
      ctx.font = f(12, 600);
      setLS(ctx, 0.5);
      ctx.fillText('120', lx0, ly0 + 0.5);
      ctx.restore();

      /* 总功率 —— 温度计样式 */
      this.drawPower(ctx, t);
    }

    /* --------------------------- 总功率温度计 --------------------------- */
    drawPower(ctx, t) {
      const { cx } = G;
      const x0 = cx - 132, x1 = cx + 132;
      const val = clamp(this.pow, 0, this.data.powerMax);
      const frac = val / this.data.powerMax;

      // 标题 + 读数
      ctx.save();
      ctx.textBaseline = 'alphabetic';
      ctx.fillStyle = C.dim2;
      ctx.font = f(9.5, 400);
      setLS(ctx, 2.4);
      ctx.textAlign = 'left';
      ctx.fillText('总功率', x0, 288);

      ctx.textAlign = 'right';
      ctx.font = f(10.5, 500);
      setLS(ctx, 1);
      ctx.fillStyle = frac > 0.85 ? C.redLt : '#CFE1E1';
      ctx.fillText(Math.round(val) + ' kW', x1, 288);
      ctx.restore();

      // 温度计主体：左侧球泡 + 右侧管
      const ty = 296, th = 12, bulbR = 10;
      const bx = x0 + 6, byy = ty + th / 2;

      // 槽体
      ctx.save();
      rr(ctx, bx + 8, ty, x1 - bx - 8, th, th / 2);
      ctx.fillStyle = '#0F191D'; ctx.fill();
      ctx.strokeStyle = 'rgba(120,170,180,0.18)'; ctx.lineWidth = 1; ctx.stroke();

      ctx.beginPath(); ctx.arc(bx, byy, bulbR, 0, 6.2832);
      ctx.fillStyle = '#0F191D'; ctx.fill();
      ctx.strokeStyle = 'rgba(120,170,180,0.18)'; ctx.stroke();
      ctx.restore();

      // 填充：整条渐变按位置取色（青 → 黄 → 红）
      const tubeL = bx + 8, tubeR = x1;
      const totalW = tubeR - tubeL;
      const grad = ctx.createLinearGradient(tubeL, 0, tubeR, 0);
      grad.addColorStop(0, '#2FBFA8');
      grad.addColorStop(0.45, C.warn);
      grad.addColorStop(0.78, '#FF7A45');
      grad.addColorStop(1, C.red);

      const fw = totalW * frac;
      if (fw > 0.8) {
        ctx.save();
        rr(ctx, tubeL, ty + 1, totalW, th - 2, (th - 2) / 2);
        ctx.clip();
        ctx.shadowColor = 'rgba(255,140,80,0.35)';
        ctx.shadowBlur = 10;
        ctx.fillStyle = grad;
        ctx.fillRect(tubeL, ty, fw, th);
        ctx.restore();

        // 球泡充能
        ctx.save();
        ctx.beginPath(); ctx.arc(bx, byy, bulbR - 2.5, 0, 6.2832);
        ctx.fillStyle = '#2FBFA8';
        ctx.shadowColor = 'rgba(47,191,168,0.5)'; ctx.shadowBlur = 8;
        ctx.fill();
        ctx.restore();
      }

      // 液面端点高光
      if (fw > 2) {
        const lx = tubeL + fw;
        ctx.save();
        ctx.beginPath(); ctx.arc(lx, byy, 2.4, 0, 6.2832);
        ctx.fillStyle = '#FFFFFF'; ctx.globalAlpha = 0.9;
        ctx.shadowColor = '#FFF'; ctx.shadowBlur = 8;
        ctx.fill();
        ctx.restore();
      }

      // 刻线：25 / 50 / 75 %
      ctx.save();
      for (let i = 1; i <= 3; i++) {
        const tx = tubeL + totalW * (i / 4);
        ctx.beginPath();
        ctx.moveTo(tx, ty + 2);
        ctx.lineTo(tx, ty + th - 2);
        ctx.strokeStyle = 'rgba(0,0,0,0.35)';
        ctx.lineWidth = 1;
        ctx.stroke();
      }
      ctx.restore();
    }

    /* --------------------------- 续航卡 --------------------------- */
    drawRange(ctx, card, o) {
      this.panel(ctx, card.x, card.y, card.w, card.h);

      const x = card.x + 18, right = card.x + card.w - 18, w = card.w - 36;
      const ratio = clamp(o.val / o.cap, 0, 1);
      const low = ratio < 0.2;
      const pct = Math.round(ratio * 100);

      /* 颜色规则：
         常态按「能量类型」分色相——电量 >20% 绿 #5CE38A，燃油 >20% 蓝 #4FC3FF
         告警沿用既定规则——电量 <20% 黄 #FFC93C、<10% 转红；燃油 <20% 直接转红
         条用同色相明度渐变，避免跨色相渐变造成的浑浊 */
      let main, light, g0, g1;
      const critical = ratio < 0.1;
      if (o.kind === 'ev') {
        if (critical) { main = C.red; light = C.redLt; g0 = '#8E2626'; g1 = C.red; }
        else if (low) { main = C.warn; light = C.warnLt; g0 = '#8E6A14'; g1 = C.warn; }
        else { main = '#5CE38A'; light = '#D2FFE4'; g0 = '#1E7A48'; g1 = '#5CE38A'; }
      } else {
        // 燃油沿用既定规则：低于 20% 即转红
        if (low) { main = C.red; light = C.redLt; g0 = '#8E2626'; g1 = C.red; }
        else { main = '#4FC3FF'; light = '#C6ECFF'; g0 = '#175D80'; g1 = '#4FC3FF'; }
      }

      /* 告警描边：低量时整张卡有一圈同色轮廓 */
      if (low) {
        rr(ctx, card.x + 1.5, card.y + 1.5, card.w - 3, card.h - 3, 18.5);
        ctx.strokeStyle = rgba(main, critical ? 0.45 : 0.26);
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }

      /* 1. 色晕：让两张卡各有一种气质 */
      ctx.save();
      rr(ctx, card.x + 1, card.y + 1, card.w - 2, card.h - 2, 19);
      ctx.clip();
      const tint = ctx.createRadialGradient(x + 6, card.y + 4, 0, x + 6, card.y + 4, 230);
      tint.addColorStop(0, rgba(main, low ? 0.13 : 0.09));
      tint.addColorStop(0.55, rgba(main, 0.025));
      tint.addColorStop(1, rgba(main, 0));
      ctx.fillStyle = tint;
      ctx.fillRect(card.x, card.y, card.w, card.h);
      ctx.restore();

      /* 2. 图标胶囊 */
      const ts = 30, tx = x, ty = card.y + 12;
      ctx.save();
      rr(ctx, tx, ty, ts, ts, 9);
      const ig = ctx.createLinearGradient(tx, ty, tx + ts, ty + ts);
      ig.addColorStop(0, rgba(main, 0.18));
      ig.addColorStop(1, rgba(main, 0.035));
      ctx.fillStyle = ig; ctx.fill();
      ctx.strokeStyle = rgba(main, 0.26); ctx.lineWidth = 1; ctx.stroke();

      ctx.translate(tx + ts / 2, ty + ts / 2);
      ctx.strokeStyle = main; ctx.lineWidth = 1.6;
      ctx.lineJoin = 'round'; ctx.lineCap = 'round';
      if (o.kind === 'ev') {
        rr(ctx, -6, -4, 12, 8, 2); ctx.stroke();
        ctx.beginPath(); ctx.moveTo(8, -1); ctx.lineTo(8, 1); ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(1.5, -3); ctx.lineTo(-1.5, 0.5); ctx.lineTo(1.5, 0.5); ctx.lineTo(-1, 3.5);
        ctx.stroke();
      } else {
        ctx.beginPath();
        ctx.moveTo(-6, 6); ctx.lineTo(-6, -5);
        ctx.quadraticCurveTo(-6, -6.5, -4.5, -6.5);
        ctx.lineTo(-2, -6.5);
        ctx.quadraticCurveTo(-0.5, -6.5, -0.5, -5);
        ctx.lineTo(-0.5, 6);
        ctx.stroke();
        ctx.beginPath(); ctx.moveTo(-6, 0); ctx.lineTo(-0.5, 0); ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(-0.5, 1.5); ctx.lineTo(3, 1.5);
        ctx.quadraticCurveTo(5, 1.5, 5, 3.5);
        ctx.lineTo(5, 5.5);
        ctx.stroke();
      }
      ctx.restore();

      /* 3. 名称 + 副信息（原右侧百分比挪下来，信息更连贯） */
      ctx.save();
      ctx.textBaseline = 'alphabetic';
      ctx.fillStyle = low ? main : '#8CA2A7';
      ctx.font = f(11, 400);
      setLS(ctx, 2.2);
      ctx.fillText(o.name, x + 42, card.y + 26);

      ctx.fillStyle = low ? rgba(main, 0.95) : '#4E6165';
      ctx.font = f(9.5, 400);
      setLS(ctx, 1.1);
      ctx.fillText(
        low ? '低于 20% · 请尽快补充' : ('剩余 ' + pct + '% · 满值 ' + o.cap + ' km'),
        x + 42, card.y + 42);
      ctx.restore();

      /* 4. 数值：km 先右对齐，数字贴其左侧 */
      ctx.save();
      ctx.textAlign = 'right';
      ctx.font = f(10.5, 400);
      ctx.fillStyle = C.dim;
      setLS(ctx, 1);
      ctx.fillText('km', right, card.y + 40);
      const uw = ctx.measureText('km').width;

      ctx.font = f(30, 600);
      ctx.fillStyle = light;
      setLS(ctx, -0.5);
      if (low) { ctx.shadowColor = rgba(main, 0.85); ctx.shadowBlur = 14; }
      ctx.fillText(String(o.val), right - uw - 6, card.y + 40);
      ctx.restore();

      /* 5. 进度条 */
      const by = card.y + 56, bh = 9, bw = w;

      // 槽
      ctx.save();
      rr(ctx, x, by, bw, bh, bh / 2);
      ctx.fillStyle = '#0A1114'; ctx.fill();
      ctx.strokeStyle = 'rgba(255,255,255,0.055)'; ctx.lineWidth = 1; ctx.stroke();

      // 槽内顶部阴影，做出凹槽感
      rr(ctx, x, by, bw, bh, bh / 2);
      ctx.clip();
      const sh = ctx.createLinearGradient(0, by, 0, by + bh);
      sh.addColorStop(0, 'rgba(0,0,0,0.55)');
      sh.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = sh;
      ctx.fillRect(x, by, bw, bh * 0.7);
      ctx.restore();

      // 填充
      const fw = bw * ratio * Math.min(1, (o.anim || 1) * 1.15);
      if (fw > 1) {
        ctx.save();
        rr(ctx, x, by, bw, bh, bh / 2);
        ctx.clip();

        const bg = ctx.createLinearGradient(x, 0, x + fw, 0);
        bg.addColorStop(0, g0);
        bg.addColorStop(1, g1);
        ctx.fillStyle = bg;
        ctx.shadowColor = rgba(main, low ? 0.75 : 0.5);
        ctx.shadowBlur = 12;
        ctx.fillRect(x, by, fw, bh);

        // 顶部高光 + 底部压暗：让条子有圆柱感
        ctx.shadowBlur = 0;
        const hl = ctx.createLinearGradient(0, by, 0, by + bh);
        hl.addColorStop(0, 'rgba(255,255,255,0.30)');
        hl.addColorStop(0.45, 'rgba(255,255,255,0.04)');
        hl.addColorStop(1, 'rgba(0,0,0,0.14)');
        ctx.fillStyle = hl;
        ctx.fillRect(x, by, fw, bh);
        ctx.restore();

        // 液面端点光点
        if (fw > 6) {
          const ex = x + fw - bh / 2;
          ctx.save();
          ctx.beginPath();
          ctx.arc(ex, by + bh / 2, 2.6, 0, 6.2832);
          ctx.fillStyle = 'rgba(255,255,255,0.92)';
          ctx.shadowColor = rgba(main, 0.95);
          ctx.shadowBlur = 10;
          ctx.fill();
          ctx.restore();
        }
      }

      /* 6. 20% 阈值刻度 */
      const thrX = x + bw * 0.2;
      ctx.save();
      ctx.beginPath();
      ctx.moveTo(thrX, by + bh + 2);
      ctx.lineTo(thrX, by + bh + 5);
      ctx.strokeStyle = low ? rgba(main, 0.9) : 'rgba(190,210,214,0.22)';
      ctx.lineWidth = 1;
      ctx.stroke();
      ctx.restore();
    }

    /* --------------------------- 灯光状态栏 --------------------------- */
    drawLightRail(ctx, t) {
      const r = L.rail;
      this.panel(ctx, r.x, r.y, r.w, r.h, 20);

      const on = this.data.lights || {};
      const cy = r.y + r.h / 2;
      const blink = Math.floor(t * 1.7) % 2 === 0;   // 转向灯闪烁节拍
      const hz = !!on.hazard;                        // 双闪：左右同相闪烁

      const items = [
        ['left', (hz || on.left) && blink, C.amber],
        ['auto', !!on.auto, C.teal],
        ['low', !!on.low, '#DCEAEA'],
        ['high', !!on.high, '#7FD2FF'],
        ['hold', !!on.hold, C.teal],
        ['fogR', !!on.fogR, C.red],
        ['right', (hz || on.right) && blink, C.amber]
      ];

      const slot = r.w / items.length;
      items.forEach(([kind, active, color], i) => {
        const x = r.x + slot * (i + 0.5);
        ctx.save();
        ctx.translate(x, cy);
        if (active) {
          rr(ctx, -19, -15, 38, 30, 10);
          ctx.fillStyle = rgba(color, 0.10); ctx.fill();
          ctx.strokeStyle = rgba(color, 0.18); ctx.lineWidth = 1; ctx.stroke();
        }
        this.drawLightIcon(ctx, kind, active ? color : '#35484E', active);
        ctx.restore();
      });
    }

    /* 单个灯光图标：以 0,0 为中心，约 24×20 范围 */
    drawLightIcon(ctx, kind, color, active) {
      ctx.save();
      ctx.strokeStyle = color;
      ctx.fillStyle = color;
      ctx.lineWidth = 2;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      if (active) { ctx.shadowColor = color; ctx.shadowBlur = 10; }

      /* 转向灯：带尾巴的实心箭头，尖端朝左/右 */
      if (kind === 'left' || kind === 'right') {
        const s = kind === 'left' ? -1 : 1;   // -1 指向左，+1 指向右
        ctx.beginPath();
        ctx.moveTo(10 * s, 0);        // 尖端
        ctx.lineTo(-1 * s, -8);
        ctx.lineTo(-1 * s, -3);
        ctx.lineTo(-9 * s, -3);       // 箭尾
        ctx.lineTo(-9 * s, 3);
        ctx.lineTo(-1 * s, 3);
        ctx.lineTo(-1 * s, 8);
        ctx.closePath();
        ctx.fill();
        ctx.restore();
        return;
      }

      /* 自动驻车：(A) 双括号 + A */
      if (kind === 'hold') {
        ctx.beginPath(); ctx.arc(0, 0, 8, 0, 6.2832); ctx.stroke();
        ctx.beginPath(); ctx.arc(0, 0, 11.6, -0.44, 0.44); ctx.stroke();
        ctx.beginPath(); ctx.arc(0, 0, 11.6, Math.PI - 0.44, Math.PI + 0.44); ctx.stroke();
        ctx.font = f(9.5, 700);
        setLS(ctx, 0);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('A', 0, 0.5);
        ctx.restore();
        return;
      }

      /* 灯体 */
      ctx.beginPath();
      ctx.ellipse(-1, 0, 5.5, 8.5, -0.3, 0, 6.2832);
      ctx.stroke();

      if (kind === 'auto') {
        // 灯体内的 A
        ctx.font = f(8.5, 700);
        setLS(ctx, 0);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('A', -1, 0.5);
        // 短光束
        ctx.beginPath();
        ctx.moveTo(8, -5); ctx.lineTo(12, -5);
        ctx.moveTo(9, 0); ctx.lineTo(13, 0);
        ctx.moveTo(8, 5); ctx.lineTo(12, 5);
        ctx.stroke();
      } else if (kind === 'high') {
        // 远光：平直长光束
        ctx.beginPath();
        ctx.moveTo(8, -6); ctx.lineTo(15, -6);
        ctx.moveTo(9, 0); ctx.lineTo(16, 0);
        ctx.moveTo(8, 6); ctx.lineTo(15, 6);
        ctx.stroke();
      } else if (kind === 'low') {
        // 近光：向下倾斜
        ctx.beginPath();
        ctx.moveTo(7, -5); ctx.lineTo(13, 1);
        ctx.moveTo(8, 0.5); ctx.lineTo(14, 6);
        ctx.moveTo(7, 5); ctx.lineTo(13, 11);
        ctx.stroke();
      } else {
        // 雾灯：斜光束 + 雾线
        ctx.beginPath();
        ctx.moveTo(7, -5); ctx.lineTo(12, 0);
        ctx.moveTo(8, 1); ctx.lineTo(13, 6);
        ctx.stroke();

        ctx.beginPath();
        ctx.lineWidth = 1.8;
        if (kind === 'fogR') {
          ctx.moveTo(-6, 11); ctx.lineTo(10, 11);
        } else {
          ctx.moveTo(-6, 11);
          ctx.bezierCurveTo(-2, 8, 2, 14, 6, 11);
          ctx.lineTo(10, 11);
        }
        ctx.stroke();
      }
      ctx.restore();
    }

    /* --------------------------- 地图 --------------------------- */
    drawMap(ctx, t) {
      const m = L.map;
      ctx.save();
      rr(ctx, m.x, m.y, m.w, m.h, 20);
      ctx.clip();

      /* ★ 外部地图模式：路网/车道/路线/车流/自车/暗角全部跳过
       *
       * 那块区域由外部真地图（SurfaceView 垫在 WebView 下）负责，
       * 这里只保留「浮层」—— 左上角实时路况 chip、右下角比例尺、
       * 顶部车道引导条。这些都是 dashboard 自己的元素，地图没有。
       *
       * 好处：
       *   · 真地图不被遮蔽
       *   · 少画 500 行路网代码（每帧省 ~40 次绘制调用）
       */
      if (this.mapExternal) {
        ctx.restore();   // 摆脱 clip，浮层要能画到边界外一点点
        this.drawMapOverlay(ctx, t);
        this.drawLaneGuide(ctx);
        /* ★ 补边框（2026-11-10）
         *
         * 内部模式下这段边框在方法末尾画，外部模式提前 return 就跳过了，
         * 地图区四周会缺一圈 1px 描边，和旁边卡片接不上。
         * 这里补一次，保证两种模式外观一致。
         */
        ctx.save();
        rr(ctx, m.x + 0.5, m.y + 0.5, m.w - 1, m.h - 1, 20);
        ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();
        ctx.restore();
        return;
      }

      // 底色（clip 之后是绝对坐标，必须落在卡片区域内）
      let rg = ctx.createRadialGradient(
        m.x + m.w * 0.5, m.y + m.h * 0.46, 40,
        m.x + m.w * 0.5, m.y + m.h * 0.46, m.w * 0.72);
      rg.addColorStop(0, '#0F1B21'); rg.addColorStop(1, '#060B0E');
      ctx.fillStyle = rg; ctx.fillRect(m.x, m.y, m.w, m.h);

      ctx.save();
      // 路网以 1040×520 虚拟坐标绘制，cover 缩放居中：不拉伸、不压扁
      const VW = 1040, VH = 520;
      const ms = Math.max(m.w / VW, m.h / VH);
      ctx.translate(m.x + (m.w - VW * ms) / 2, m.y + (m.h - VH * ms) / 2);
      ctx.scale(ms, ms);

      // 水系
      ctx.beginPath();
      ctx.moveTo(-40, -40); ctx.lineTo(300, -40);
      ctx.bezierCurveTo(276, 26, 214, 62, 146, 96);
      ctx.bezierCurveTo(86, 126, 22, 138, -40, 120);
      ctx.closePath();
      ctx.fillStyle = '#0A1721'; ctx.fill();
      ctx.strokeStyle = '#122A38'; ctx.lineWidth = 1.5; ctx.stroke();

      // 绿地
      rr(ctx, 612, 316, 186, 126, 16);
      ctx.fillStyle = '#0B1E18'; ctx.fill();
      ctx.strokeStyle = '#14352A'; ctx.lineWidth = 1; ctx.stroke();
      const trees = [[650, 352, 5], [672, 360, 4], [700, 346, 6], [736, 368, 5],
                     [762, 352, 4], [690, 398, 5], [742, 404, 6], [770, 400, 4]];
      ctx.fillStyle = '#16402F';
      for (const [tx, ty, tr] of trees) { ctx.beginPath(); ctx.arc(tx, ty, tr, 0, 6.2832); ctx.fill(); }

      // 街区
      ctx.fillStyle = '#0C1518';
      for (const b of BLOCKS) { rr(ctx, b[0], b[1], b[2], b[3], 3); ctx.fill(); }

      // 支路
      ctx.save();
      ctx.translate(520, 260); ctx.rotate(-2 * Math.PI / 180); ctx.translate(-520, -260);
      ctx.strokeStyle = '#122026'; ctx.lineWidth = 7; ctx.lineCap = 'round';
      for (const x of MINOR_V) { ctx.beginPath(); ctx.moveTo(x, -30); ctx.lineTo(x, 560); ctx.stroke(); }
      for (const y of MINOR_H) { ctx.beginPath(); ctx.moveTo(-30, y); ctx.lineTo(1080, y); ctx.stroke(); }
      ctx.restore();

      // 主干道
      ctx.strokeStyle = '#1D323A'; ctx.lineWidth = 13;
      for (const x of MAJOR_V) { ctx.beginPath(); ctx.moveTo(x, -30); ctx.lineTo(x, 560); ctx.stroke(); }
      for (const y of MAJOR_H) { ctx.beginPath(); ctx.moveTo(-30, y); ctx.lineTo(1080, y); ctx.stroke(); }

      // 高架
      tracePoly(ctx, HIGHWAY);
      ctx.strokeStyle = '#243B44'; ctx.lineWidth = 26;
      ctx.lineJoin = 'round'; ctx.stroke();
      ctx.save();
      ctx.setLineDash([14, 16]);
      ctx.strokeStyle = 'rgba(62,97,107,0.55)'; ctx.lineWidth = 2;
      ctx.stroke();
      ctx.restore();

      // 路名
      ctx.save();
      ctx.font = f(11.5, 400);
      setLS(ctx, 1.6);
      for (const [lx, ly, txt, col, rot] of MAP_LABELS) {
        ctx.save();
        ctx.translate(lx, ly);
        ctx.rotate(rot * Math.PI / 180);
        ctx.fillStyle = col;
        ctx.fillText(txt, 0, 0);
        ctx.restore();
      }
      ctx.restore();

      if (this.data.navActive !== false) {
      /* 车道：三车道，本车道高亮 */
      const LW = 18;
      const laneB = [-1.5 * LW, -0.5 * LW, 0.5 * LW, 1.5 * LW].map(d => offsetPoly(ROUTE.pts, d));

      const bandPath = (a, b) => {
        ctx.beginPath();
        ctx.moveTo(a[0][0], a[0][1]);
        for (let i = 1; i < a.length; i++) ctx.lineTo(a[i][0], a[i][1]);
        for (let i = b.length - 1; i >= 0; i--) ctx.lineTo(b[i][0], b[i][1]);
        ctx.closePath();
      };

      // 路面
      bandPath(laneB[0], laneB[3]);
      ctx.fillStyle = 'rgba(23,38,45,0.60)';
      ctx.fill();

      // 本车道
      bandPath(laneB[1], laneB[2]);
      ctx.fillStyle = 'rgba(67,232,200,0.055)';
      ctx.fill();

      ctx.save();
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';

      // 路缘
      for (const i of [0, 3]) {
        tracePts(ctx, laneB[i]);
        ctx.strokeStyle = 'rgba(180,215,220,0.13)';
        ctx.lineWidth = 1.4;
        ctx.stroke();
      }

      // 车道分隔虚线
      ctx.setLineDash([9, 11]);
      for (const i of [1, 2]) {
        tracePts(ctx, laneB[i]);
        ctx.strokeStyle = 'rgba(226,245,246,0.26)';
        ctx.lineWidth = 1.4;
        ctx.stroke();
      }
      ctx.setLineDash([]);
      ctx.restore();

      // 路线
      ctx.save();
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      tracePoly(ctx, ROUTE);
      ctx.strokeStyle = 'rgba(67,232,200,0.10)';
      ctx.lineWidth = 14; ctx.stroke();

      const rgd = ctx.createLinearGradient(262, 470, 842, 128);
      rgd.addColorStop(0, '#2FBFA8'); rgd.addColorStop(1, '#7BFFE6');
      tracePoly(ctx, ROUTE);
      ctx.strokeStyle = rgd; ctx.lineWidth = 6; ctx.stroke();

      ctx.setLineDash([10, 12]);
      ctx.lineDashOffset = -(t * 20) % 22;
      ctx.strokeStyle = 'rgba(201,255,246,0.85)';
      ctx.lineWidth = 1.6; ctx.stroke();
      ctx.restore();
      }

      // 车流
      for (const tf of TRAFFIC) {
        const u = (tf.u + (t / tf.dur)) % 1;
        const p = polyAt(tf.m, tf.m === HIGHWAY ? u : ((u <= 0.5) ? u * 2 : (1 - u) * 2));
        ctx.beginPath();
        ctx.arc(p.x, p.y, 3.2, 0, 6.2832);
        ctx.fillStyle = tf.c;
        ctx.globalAlpha = 0.72; ctx.fill();
        ctx.globalAlpha = 1;
      }

      // 终点
      if (this.data.navActive !== false) {
      ctx.save();
      ctx.beginPath(); ctx.arc(842, 128, 14, 0, 6.2832);
      ctx.fillStyle = 'rgba(67,232,200,0.14)'; ctx.fill();
      ctx.beginPath(); ctx.arc(842, 128, 6, 0, 6.2832);
      ctx.fillStyle = C.teal;
      ctx.shadowColor = 'rgba(67,232,200,0.8)'; ctx.shadowBlur = 14;
      ctx.fill();
      ctx.shadowBlur = 0;
      ctx.font = f(11, 400); setLS(ctx, 1.4);
      ctx.fillStyle = '#9FE3D6'; ctx.fillText('目的地', 862, 124);
      ctx.font = f(10, 400);
      ctx.fillStyle = '#57726F'; ctx.fillText('09:52 到达', 862, 140);
      ctx.restore();
      }

      // 自车
      const cp = polyAt(ROUTE, 0.168);
      const pulse = (t * 0.42) % 1;
      ctx.save();
      ctx.beginPath();
      ctx.arc(cp.x, cp.y, 8 + pulse * 14, 0, 6.2832);
      ctx.strokeStyle = `rgba(123,255,230,${0.5 * (1 - pulse)})`;
      ctx.lineWidth = 2; ctx.stroke();
      ctx.restore();

      ctx.save();
      ctx.translate(cp.x, cp.y);
      ctx.beginPath(); ctx.arc(0, 0, 9, 0, 6.2832);
      ctx.fillStyle = 'rgba(9,18,20,0.9)'; ctx.fill();
      ctx.strokeStyle = '#7BFFE6'; ctx.lineWidth = 1.4; ctx.stroke();
      ctx.rotate(cp.ang + Math.PI / 2);
      ctx.beginPath();
      ctx.moveTo(0, -6.5); ctx.lineTo(4.2, 5); ctx.lineTo(0, 3); ctx.lineTo(-4.2, 5);
      ctx.closePath();
      ctx.fillStyle = '#CFFEF6'; ctx.fill();
      ctx.restore();

      ctx.restore(); // 回到画布坐标

      // 暗角
      rg = ctx.createRadialGradient(m.x + m.w / 2, m.y + m.h / 2, m.h * 0.42,
                                    m.x + m.w / 2, m.y + m.h / 2, m.w * 0.62);
      rg.addColorStop(0, 'rgba(3,7,9,0)');
      rg.addColorStop(1, 'rgba(3,7,9,0.82)');
      ctx.fillStyle = rg; ctx.fillRect(m.x, m.y, m.w, m.h);
      ctx.restore();

      rr(ctx, m.x + 0.5, m.y + 0.5, m.w - 1, m.h - 1, 20);
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();

      // 浮层
      this.drawMapOverlay(ctx, t);

      // 车道引导条与是否导航无关：有 lanes 数据就画，空数组自动隐藏
      this.drawLaneGuide(ctx);
    }

    /* ------------------- 地图浮层（chip + 比例尺） -------------------
     *
     * 抽出来是因为「外部地图模式」也要用：
     * 路网由外部真地图提供，但实时路况 chip、指南针、缩放按钮、
     * 比例尺这些是 dashboard 自己的 UI，必须照画。
     */
    drawMapOverlay(ctx, t) {
      const m = L.map;

      this.chip(ctx, m.x + 14, m.y + 14, 0, () => {
        ctx.beginPath(); ctx.arc(0, 0, 3, 0, 6.2832);
        const pulse = (t * 0.45) % 1;
        ctx.globalAlpha = 1 - pulse * 0.6;
        ctx.fillStyle = C.teal;
        ctx.shadowColor = C.teal; ctx.shadowBlur = 8;
        ctx.fill(); ctx.shadowBlur = 0; ctx.globalAlpha = 1;
      }, '实时路况 · 畅通', 122);

      this.chip(ctx, m.x + m.w - 14 - 118, m.y + 14, 0, () => {
        ctx.save();
        ctx.strokeStyle = '#8FB6BC'; ctx.lineWidth = 1.8; ctx.lineJoin = 'round';
        ctx.beginPath();
        ctx.moveTo(0, -6); ctx.lineTo(-6, 6); ctx.lineTo(0, 2.5); ctx.lineTo(6, 6);
        ctx.closePath(); ctx.stroke();
        ctx.restore();
      }, 'N · 北偏东 12°', 118, 8);

      this.chip(ctx, m.x + 14, m.y + m.h - 14 - 28, 0, () => {
        ctx.save();
        ctx.strokeStyle = '#9FB4B8'; ctx.lineWidth = 1.4;
        ctx.beginPath(); ctx.moveTo(-4, 0); ctx.lineTo(4, 0);
        ctx.moveTo(0, -4); ctx.lineTo(0, 4); ctx.stroke();
        ctx.restore();
      }, '', 64, 0, true);

      ctx.save();
      const sw = 62, sx = m.x + m.w - 14 - sw, sy = m.y + m.h - 14 - 20;
      rr(ctx, sx, sy, sw, 20, 10);
      ctx.fillStyle = 'rgba(8,14,17,0.78)'; ctx.fill();
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();
      ctx.font = f(10.5, 400); setLS(ctx, 1.6);
      ctx.fillStyle = '#7C9095';
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText('200 m', sx + sw / 2, sy + 10.5);
      ctx.restore();
    }

    /* --------------------------- 车道引导条 --------------------------- */
    drawLaneGuide(ctx) {
      const m = L.map;
      /* 归一化：支持字符串数组 + laneActive，或 [{dir, active}] 对象数组 */
      const DIR = v => {
        const k = String(v == null ? '' : v).toLowerCase();
        if (k === 'left' || k === 'l' || k === '←' || k === '左' || k === '左转') return 'left';
        if (k === 'right' || k === 'r' || k === '→' || k === '↱' || k === '右' || k === '右转') return 'right';
        return 'straight';
      };
      const actIdx = this.data.laneActive || [];
      const lanes = (this.data.lanes || []).map((l, i) => (
        l && typeof l === 'object'
          ? { dir: DIR(l.dir), on: !!l.active }
          : { dir: DIR(l), on: actIdx.indexOf(i) >= 0 }
      ));
      if (!lanes.length) return;

      const cell = 50, pad = 12, h = 46;
      const w = lanes.length * cell + pad * 2;
      const x = m.x + (m.w - w) / 2;
      const y = m.y + 14;

      ctx.save();
      rr(ctx, x, y, w, h, 12);
      ctx.fillStyle = 'rgba(7,12,15,0.86)';
      ctx.fill();
      ctx.strokeStyle = 'rgba(120,170,180,0.20)';
      ctx.lineWidth = 1;
      ctx.stroke();

      lanes.forEach((lane, i) => {
        const cx = x + pad + cell * (i + 0.5);
        const cy = y + h / 2;
        const dir = lane.dir, on = lane.on;

        if (on) {
          const g = ctx.createRadialGradient(cx, cy + 8, 2, cx, cy + 8, 26);
          g.addColorStop(0, rgba(C.teal, 0.20));
          g.addColorStop(1, rgba(C.teal, 0));
          ctx.fillStyle = g;
          ctx.fillRect(cx - 26, cy - 16, 52, 34);
        }

        this.laneArrow(ctx, cx, cy, dir, on ? C.teal : 'rgba(226,241,242,0.50)', on);

        if (i < lanes.length - 1) {
          const dx = x + pad + cell * (i + 1);
          ctx.beginPath();
          ctx.moveTo(dx, cy - 9);
          ctx.lineTo(dx, cy + 9);
          ctx.strokeStyle = 'rgba(120,170,180,0.22)';
          ctx.lineWidth = 1;
          ctx.stroke();
        }
      });
      ctx.restore();
    }

    /* 单个车道箭头 */
    laneArrow(ctx, cx, cy, dir, color, glow) {
      ctx.save();
      ctx.translate(cx, cy);
      if (dir === 'left') ctx.scale(-1, 1);
      ctx.fillStyle = color;
      ctx.strokeStyle = color;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      if (glow) {
        ctx.shadowColor = rgba(C.teal, 0.85);
        ctx.shadowBlur = 12;
      }

      if (dir === 'straight') {
        ctx.beginPath();
        ctx.moveTo(0, -11);
        ctx.lineTo(6.5, -3);
        ctx.lineTo(2.7, -3);
        ctx.lineTo(2.7, 11);
        ctx.lineTo(-2.7, 11);
        ctx.lineTo(-2.7, -3);
        ctx.lineTo(-6.5, -3);
        ctx.closePath();
        ctx.fill();
      } else {
        ctx.lineWidth = 3.4;
        ctx.beginPath();
        ctx.moveTo(-1, 11);
        ctx.lineTo(-1, -2);
        ctx.quadraticCurveTo(-1, -7.5, 4.5, -7.5);
        ctx.lineTo(6.5, -7.5);
        ctx.stroke();

        ctx.beginPath();
        ctx.moveTo(6, -13);
        ctx.lineTo(13, -7.5);
        ctx.lineTo(6, -2);
        ctx.closePath();
        ctx.fill();
      }
      ctx.restore();
    }

    chip(ctx, x, y, w, drawIcon, label, width, iconGap, isZoom) {
      const h = 28;
      ctx.save();
      if (isZoom) {
        rr(ctx, x, y, 60, h, 14);
        ctx.fillStyle = 'rgba(8,14,17,0.78)'; ctx.fill();
        ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();
        ctx.beginPath(); ctx.moveTo(x + 30, y + 5); ctx.lineTo(x + 30, y + h - 5);
        ctx.strokeStyle = C.line; ctx.stroke();
        ctx.translate(x + 15, y + h / 2); drawIcon();
        ctx.translate(30, 0);
        ctx.strokeStyle = '#9FB4B8'; ctx.lineWidth = 1.4;
        ctx.beginPath(); ctx.moveTo(-4, 0); ctx.lineTo(4, 0); ctx.stroke();
        ctx.restore();
        return;
      }
      const tw = width || 120;
      rr(ctx, x, y, tw, h, 14);
      ctx.fillStyle = 'rgba(8,14,17,0.78)'; ctx.fill();
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();

      ctx.save();
      ctx.translate(x + 15, y + h / 2);
      if (drawIcon) drawIcon();
      ctx.restore();

      if (label) {
        ctx.save();
        ctx.font = f(11.5, 400);
        setLS(ctx, 1.4);
        ctx.fillStyle = '#A9BEC2';
        ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
        ctx.fillText(label, x + 26, y + h / 2 + 0.5);
        ctx.restore();
      }
      ctx.restore();
    }

    /* --------------------------- 时钟卡 --------------------------- */
    drawClock(ctx) {
      const c = L.clock;
      this.panel(ctx, c.x, c.y, c.w, c.h);

      const d = new Date();
      const hh = String(d.getHours()).padStart(2, '0');
      const mm = String(d.getMinutes()).padStart(2, '0');

      ctx.save();
      ctx.fillStyle = '#F2FAFA';
      ctx.font = f(44, 300);
      setLS(ctx, 2);
      ctx.textBaseline = 'alphabetic';
      ctx.fillText(hh + ':' + mm, c.x + 20, c.y + 54);

      const wd = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()];
      ctx.fillStyle = C.dim;
      ctx.font = f(11.5, 400);
      setLS(ctx, 2);
      ctx.fillText(wd + ' · ' + (d.getMonth() + 1) + '月' + d.getDate() + '日',
                   c.x + 22, c.y + 76);
      ctx.restore();

      ctx.save();
      ctx.textAlign = 'right';
      ctx.fillStyle = '#DCEBEB';
      ctx.font = f(28, 400);
      setLS(ctx, 1);
      ctx.fillText(this.data.outdoor, c.x + c.w - 20, c.y + 46);

      ctx.fillStyle = C.dim;
      ctx.font = f(11, 400);
      setLS(ctx, 1.6);
      ctx.fillText(this.data.weather, c.x + c.w - 20, c.y + 66);
      ctx.fillStyle = C.dim3;
      ctx.font = f(10.5, 400);
      setLS(ctx, 1.2);
      ctx.fillText('车外 ' + this.data.outdoor + ' / 车内 ' + this.data.indoor,
                   c.x + c.w - 20, c.y + 82);
      ctx.restore();
    }

    /* --------------------------- 导航 / 行程横条（地图下方） --------------------------- */
    drawNavStrip(ctx) {
      const r = L.navStrip;
      this.panel(ctx, r.x, r.y, r.w, r.h);

      const cyC = r.y + r.h / 2;
      const navOn = this.data.navActive !== false;
      // 导航结束后按结束原因分流：到达目的地 → 本次行程；主动退出 → 胎压
      const endedByArrival = this.data.navEnd === 'arrived';

      if (!navOn && !endedByArrival) { this.drawTireStrip(ctx, r); return; }

      /* 左侧图标 */
      const ts = 34, tx0 = r.x + 18, ty0 = cyC - ts / 2;
      rr(ctx, tx0, ty0, ts, ts, 10);
      const bg = ctx.createLinearGradient(tx0, ty0, tx0 + ts, ty0 + ts);
      bg.addColorStop(0, 'rgba(67,232,200,0.18)');
      bg.addColorStop(1, 'rgba(67,232,200,0.04)');
      ctx.fillStyle = bg; ctx.fill();
      ctx.strokeStyle = 'rgba(67,232,200,0.28)';
      ctx.lineWidth = 1; ctx.stroke();

      ctx.save();
      ctx.translate(tx0 + ts / 2, ty0 + ts / 2);
      ctx.strokeStyle = C.teal;
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      if (navOn) {
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(-4, 5); ctx.lineTo(-4, -1);
        ctx.quadraticCurveTo(-4, -4, -1, -4);
        ctx.lineTo(5, -4);
        ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(2, -7); ctx.lineTo(6, -4); ctx.lineTo(2, -1);
        ctx.stroke();
      } else {
        ctx.lineWidth = 1.8;
        ctx.beginPath();
        ctx.moveTo(-7, 8); ctx.lineTo(-3, -8);
        ctx.moveTo(7, 8); ctx.lineTo(3, -8);
        ctx.stroke();
        ctx.setLineDash([3, 3.5]);
        ctx.beginPath();
        ctx.moveTo(0, 8); ctx.lineTo(0, -8);
        ctx.stroke();
      }
      ctx.restore();

      /* 主文字 */
      const textX = tx0 + ts + 14;
      ctx.save();
      ctx.textAlign = 'left';
      if (navOn) {
        ctx.fillStyle = '#F0FBFA';
        ctx.font = f(22, 600);
        setLS(ctx, 0.5);
        ctx.fillText(this.data.navDist, textX, cyC - 2);
        const dw = ctx.measureText(this.data.navDist).width;

        ctx.fillStyle = C.dim;
        ctx.font = f(11, 400);
        setLS(ctx, 1);
        ctx.fillText(this.data.navUnit, textX + dw + 5, cyC - 2);

        ctx.fillStyle = '#93A9AE';
        ctx.font = f(11.5, 400);
        setLS(ctx, 1.2);
        ctx.fillText(this.data.navTurn + '转进入 ', textX, cyC + 16);
        const tw2 = ctx.measureText(this.data.navTurn + '转进入 ').width;
        ctx.fillStyle = '#D8E9E9';
        ctx.font = f(11.5, 500);
        ctx.fillText(this.data.navStreet, textX + tw2 + 2, cyC + 16);
      } else {
        ctx.fillStyle = '#F0FBFA';
        ctx.font = f(16, 500);
        setLS(ctx, 1);
        ctx.fillText('本次行程', textX, cyC - 2);

        ctx.fillStyle = C.dim;
        ctx.font = f(11, 400);
        setLS(ctx, 1.2);
        ctx.fillText('平均车速 ' + this.data.tripAvg + ' km/h', textX, cyC + 16);
      }
      ctx.restore();

      /* 竖分隔 */
      ctx.beginPath();
      ctx.moveTo(r.x + 320, r.y + 14);
      ctx.lineTo(r.x + 320, r.y + r.h - 14);
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();

      /* 信息列：导航 3 列 / 行程 4 列，按列数等分 */
      const cols = navOn
        ? [['剩余里程', this.data.navLeft, ''],
           ['预计到达', this.data.navEta, ''],
           ['剩余时间', this.data.navTime, '']]
        : [['行驶里程', this.data.tripKm, 'km'],
           ['平均电耗', this.data.tripEnergy, 'kWh/100km'],
           ['平均油耗', this.data.tripFuel, 'L/100km'],
           ['行驶时间', this.data.tripMin, '分钟']];

      const cx0 = r.x + 330, cx1 = r.x + r.w - 22;
      const step = (cx1 - cx0) / cols.length;

      cols.forEach(([k, v, u], i) => {
        const ccx = cx0 + step * (i + 0.5);

        ctx.save();
        ctx.textAlign = 'center';
        ctx.fillStyle = C.dim;
        ctx.font = f(10, 400);
        setLS(ctx, 1.8);
        ctx.fillText(k, ccx, r.y + 26);

        ctx.font = f(15, 500);
        setLS(ctx, 0.6);
        const vw = ctx.measureText(v).width;
        ctx.font = f(9.5, 400);
        setLS(ctx, 0.8);
        const uw = u ? ctx.measureText(u).width + 4 : 0;
        const startX = ccx - (vw + uw) / 2;

        ctx.textAlign = 'left';
        ctx.font = f(15, 500);
        ctx.fillStyle = '#DCEAEA';
        ctx.fillText(v, startX, r.y + 47);

        if (u) {
          ctx.font = f(9.5, 400);
          ctx.fillStyle = C.dim;
          ctx.fillText(u, startX + vw + 4, r.y + 47);
        }
        ctx.restore();

        if (i < cols.length - 1) {
          const dx = cx0 + step * (i + 1);
          ctx.beginPath();
          ctx.moveTo(dx, r.y + 16);
          ctx.lineTo(dx, r.y + 48);
          ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();
        }
            });
    }

    /* 非导航态 B：胎压监测 */
    drawTireStrip(ctx, r) {
      const cyC = r.y + r.h / 2;
      const ts = 34, tx0 = r.x + 18, ty0 = cyC - ts / 2;

      rr(ctx, tx0, ty0, ts, ts, 10);
      const bg = ctx.createLinearGradient(tx0, ty0, tx0 + ts, ty0 + ts);
      bg.addColorStop(0, 'rgba(67,232,200,0.18)');
      bg.addColorStop(1, 'rgba(67,232,200,0.04)');
      ctx.fillStyle = bg; ctx.fill();
      ctx.strokeStyle = 'rgba(67,232,200,0.28)'; ctx.lineWidth = 1; ctx.stroke();

      ctx.save();
      ctx.translate(tx0 + ts / 2, ty0 + ts / 2);
      ctx.strokeStyle = C.teal;
      ctx.lineWidth = 1.6;
      rr(ctx, -5, -9, 10, 18, 4);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(-3, -3); ctx.lineTo(3, -3);
      ctx.moveTo(-3, 3); ctx.lineTo(3, 3);
      ctx.stroke();
      ctx.restore();

      const textX = tx0 + ts + 14;
      const tires = this.data.tires || [];
      const low = tires.some(t => t[1] < 2.2);

      ctx.save();
      ctx.textAlign = 'left';
      ctx.fillStyle = '#F0FBFA';
      ctx.font = f(15, 500);
      setLS(ctx, 1);
      ctx.fillText('胎压监测', textX, cyC - 3);

      ctx.fillStyle = low ? C.warn : C.teal;
      ctx.font = f(11, 400);
      setLS(ctx, 1.2);
      ctx.fillText(low ? '右后偏低 · 建议检查' : '四轮正常', textX, cyC + 15);
      ctx.restore();

      tires.forEach(([name, v], i) => {
        const cx = r.x + 300 + i * 140;
        const warn = v < 2.2;

        ctx.save();
        ctx.textAlign = 'left';
        ctx.fillStyle = C.dim;
        ctx.font = f(10, 400);
        setLS(ctx, 1.8);
        ctx.fillText(name, cx, r.y + 22);

        ctx.fillStyle = warn ? C.warn : '#DCEAEA';
        ctx.font = f(16, 500);
        setLS(ctx, 0.6);
        if (warn) { ctx.shadowColor = rgba(C.warn, 0.7); ctx.shadowBlur = 8; }
        ctx.fillText(v.toFixed(1), cx, r.y + 44);
        const vw = ctx.measureText(v.toFixed(1)).width;
        ctx.fillStyle = C.dim;
        ctx.font = f(9.5, 400);
        ctx.shadowBlur = 0;
        ctx.fillText('bar', cx + vw + 4, r.y + 44);
        ctx.restore();

        /* 压力刻度：2.0 → 3.0，正常区 2.2–2.6 */
        const gw = 100, gy = r.y + 53;
        const T = p => cx + gw * clamp((p - 2.0) / 1.0, 0, 1);

        ctx.save();
        rr(ctx, cx, gy, gw, 3, 1.5);
        ctx.fillStyle = 'rgba(255,255,255,0.07)'; ctx.fill();

        rr(ctx, T(2.2), gy, Math.max(1, T(2.6) - T(2.2)), 3, 1.5);
        ctx.fillStyle = 'rgba(67,232,200,0.20)'; ctx.fill();

        ctx.fillStyle = warn ? C.warn : C.teal;
        if (warn) { ctx.shadowColor = rgba(C.warn, 0.8); ctx.shadowBlur = 8; }
        rr(ctx, T(v) - 1, gy - 3, 2, 9, 1);
        ctx.fill();
        ctx.restore();
      });
    }

    /* --------------------------- 媒体待机态 --------------------------- */
    drawMediaIdle(ctx) {
      const c = L.media;
      this.panel(ctx, c.x, c.y, c.w, c.h);

      const cs = 132, cx0 = c.x + 22, cy0 = c.y + 26;
      const ccx = cx0 + cs / 2, ccy = cy0 + cs / 2, cr = cs / 2;

      /* 唱片压暗、静止，不画进度只作留白 */
      ctx.save();
      ctx.globalAlpha = 0.5;
      ctx.beginPath(); ctx.arc(ccx, ccy, cr, 0, 6.2832);
      ctx.fillStyle = '#0A1215'; ctx.fill();

      const cg = ctx.createLinearGradient(cx0, cy0, cx0 + cs, cy0 + cs);
      cg.addColorStop(0, '#0B1B22');
      cg.addColorStop(0.55, '#0E2129');
      cg.addColorStop(1, '#171430');
      ctx.beginPath(); ctx.arc(ccx, ccy, cr - 9, 0, 6.2832);
      ctx.fillStyle = cg; ctx.fill();

      for (let i = 0; i < 4; i++) {
        ctx.beginPath();
        ctx.arc(ccx, ccy, cr - 7 + i * 3, 0, 6.2832);
        ctx.strokeStyle = 'rgba(255,255,255,0.03)';
        ctx.lineWidth = 1; ctx.stroke();
      }

      ctx.beginPath(); ctx.arc(ccx, ccy, cr - 0.5, 0, 6.2832);
      ctx.strokeStyle = 'rgba(120,200,190,0.16)';
      ctx.lineWidth = 1; ctx.stroke();
      ctx.restore();

      /* 转轴 */
      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, 4.5, 0, 6.2832);
      ctx.fillStyle = 'rgba(6,12,15,0.9)'; ctx.fill();
      ctx.strokeStyle = 'rgba(120,200,190,0.18)';
      ctx.lineWidth = 1; ctx.stroke();
      ctx.restore();

      /* 文案 */
      const tx = cx0 + cs + 18;
      ctx.save();
      ctx.fillStyle = '#DCEAEA';
      ctx.font = f(15, 500);
      setLS(ctx, 0.8);
      ctx.fillText('未在播放', tx, c.y + 58);

      /* 空闲状态点 */
      ctx.beginPath();
      ctx.arc(tx + 3, c.y + 80, 2.4, 0, 6.2832);
      ctx.fillStyle = '#3D5054';
      ctx.fill();

      ctx.fillStyle = '#3D5054';
      ctx.font = f(9.5, 400);
      setLS(ctx, 1.6);
      ctx.fillText('等待媒体信号', tx + 12, c.y + 84);
      ctx.restore();

      /* 静止频谱 */
      const eqX = c.x + 22, eqW = c.w - 44, eqY = c.y + 236, eqH = 18;
      const bars = 42, bw = 5, gapB = (eqW - bars * bw) / (bars - 1);
      ctx.save();
      for (let i = 0; i < bars; i++) {
        const hh = 4 + 5 * Math.abs(Math.sin(i * 0.7));
        rr(ctx, eqX + i * (bw + gapB), eqY + eqH - hh, bw, hh, 2);
        ctx.fillStyle = 'rgba(67,232,200,0.07)';
        ctx.fill();
      }
      ctx.restore();

      /* 空进度 */
      const px = c.x + 22, pw = c.w - 44, py = c.y + 288;
      rr(ctx, px, py, pw, 3, 1.5);
      ctx.fillStyle = 'rgba(255,255,255,0.06)'; ctx.fill();

      ctx.save();
      ctx.font = f(9, 400);
      setLS(ctx, 0.6);
      ctx.fillStyle = C.dim3;
      ctx.fillText('0:00', px, c.y + 306);
      ctx.textAlign = 'right';
      ctx.fillText('--:--', px + pw, c.y + 306);
      ctx.restore();
    }

    /* --------------------------- 媒体卡：封面 + 歌词 --------------------------- */
    drawMedia(ctx, t) {
      if (this.data.mediaPlaying === false) return this.drawMediaIdle(ctx);
      const c = L.media;
      this.panel(ctx, c.x, c.y, c.w, c.h);

      /* ---------- 圆形唱片 ---------- */
      const cs = 132, cx0 = c.x + 22, cy0 = c.y + 26;
      const ccx = cx0 + cs / 2, ccy = cy0 + cs / 2, cr = cs / 2;
      const artR = cr - 9;

      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, cr, 0, 6.2832);
      ctx.fillStyle = '#0A1215'; ctx.fill();
      for (let i = 0; i < 4; i++) {
        ctx.beginPath();
        ctx.arc(ccx, ccy, artR + 2 + i * 3, 0, 6.2832);
        ctx.strokeStyle = 'rgba(255,255,255,0.035)';
        ctx.lineWidth = 1; ctx.stroke();
      }
      ctx.restore();

      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, artR, 0, 6.2832); ctx.clip();
      ctx.translate(ccx, ccy);
      ctx.rotate(t * 0.45);
      ctx.translate(-ccx, -ccy);

      const cg = ctx.createLinearGradient(cx0, cy0, cx0 + cs, cy0 + cs);
      cg.addColorStop(0, '#0D2C36');
      cg.addColorStop(0.5, '#123A46');
      cg.addColorStop(1, '#241A3E');
      ctx.fillStyle = cg;
      ctx.fillRect(cx0 - 4, cy0 - 4, cs + 8, cs + 8);

      const stars = [[0.18, 0.20, 1.2], [0.30, 0.34, 0.9], [0.12, 0.44, 1.0],
                     [0.46, 0.16, 0.8], [0.86, 0.52, 1.1], [0.72, 0.70, 0.8],
                     [0.56, 0.88, 0.9], [0.22, 0.72, 0.7]];
      ctx.fillStyle = 'rgba(200,255,246,0.55)';
      for (const [sx, sy, sr] of stars) {
        ctx.beginPath();
        ctx.arc(cx0 + cs * sx, cy0 + cs * sy, sr, 0, 6.2832);
        ctx.fill();
      }

      const mx = cx0 + cs * 0.66, my = cy0 + cs * 0.30;
      ctx.beginPath(); ctx.arc(mx, my, cs * 0.23, 0, 6.2832);
      ctx.fillStyle = 'rgba(123,255,230,0.10)'; ctx.fill();
      ctx.beginPath(); ctx.arc(mx, my, cs * 0.15, 0, 6.2832);
      ctx.fillStyle = 'rgba(182,255,242,0.9)';
      ctx.shadowColor = 'rgba(67,232,200,0.9)';
      ctx.shadowBlur = 18;
      ctx.fill();
      ctx.shadowBlur = 0;

      ctx.beginPath();
      ctx.moveTo(cx0 - 16, cy0 + cs * 0.70);
      ctx.lineTo(cx0 + cs + 16, cy0 + cs * 0.40);
      ctx.lineTo(cx0 + cs + 16, cy0 + cs * 0.56);
      ctx.lineTo(cx0 - 16, cy0 + cs * 0.86);
      ctx.closePath();
      ctx.fillStyle = 'rgba(123,255,230,0.10)'; ctx.fill();

      ctx.beginPath();
      ctx.moveTo(cx0 - 16, cy0 + cs * 0.88);
      ctx.lineTo(cx0 + cs + 16, cy0 + cs * 0.62);
      ctx.lineTo(cx0 + cs + 16, cy0 + cs * 0.71);
      ctx.lineTo(cx0 - 16, cy0 + cs);
      ctx.closePath();
      ctx.fillStyle = 'rgba(255,255,255,0.06)'; ctx.fill();

      const vg = ctx.createRadialGradient(ccx, ccy, artR * 0.45, ccx, ccy, artR);
      vg.addColorStop(0, 'rgba(4,8,10,0)');
      vg.addColorStop(1, 'rgba(4,8,10,0.55)');
      ctx.fillStyle = vg;
      ctx.fillRect(cx0 - 4, cy0 - 4, cs + 8, cs + 8);
      ctx.restore();

      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, 4.5, 0, 6.2832);
      ctx.fillStyle = '#05090B'; ctx.fill();
      ctx.strokeStyle = 'rgba(120,200,190,0.35)';
      ctx.lineWidth = 1; ctx.stroke();
      ctx.restore();

      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, cr - 0.5, 0, 6.2832);
      ctx.strokeStyle = 'rgba(120,200,190,0.28)';
      ctx.lineWidth = 1;
      ctx.shadowColor = 'rgba(67,232,200,0.35)';
      ctx.shadowBlur = 10;
      ctx.stroke();
      ctx.restore();

      ctx.save();
      ctx.beginPath(); ctx.arc(ccx, ccy, cr - 1, 0, 6.2832);
      ctx.clip();
      const sg = ctx.createLinearGradient(cx0, cy0, cx0 + cs * 0.72, cy0 + cs);
      sg.addColorStop(0, 'rgba(255,255,255,0.10)');
      sg.addColorStop(0.45, 'rgba(255,255,255,0.02)');
      sg.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = sg;
      ctx.fillRect(cx0, cy0, cs, cs);
      ctx.restore();

      /* ---------- 歌名 ---------- */
      const tx = cx0 + cs + 18;
      ctx.save();
      ctx.fillStyle = '#DCEAEA';
      ctx.font = f(15, 500);
      setLS(ctx, 0.8);
      ctx.fillText(this.data.mediaTitle, tx, c.y + 58);
      const titleW = ctx.measureText(this.data.mediaTitle).width;

      ctx.fillStyle = C.dim2;
      ctx.font = f(10.5, 400);
      setLS(ctx, 1.2);
      ctx.fillText('· ' + this.data.mediaArtist, tx + titleW + 4, c.y + 58);
      ctx.restore();

      /* ---------- 歌词 ---------- */
      const lx = tx + 10;
      ctx.save();
      ctx.fillStyle = '#46595E';
      ctx.font = f(11, 400);
      setLS(ctx, 1.2);
      ctx.fillText(this.data.lyric0, lx, c.y + 104);

      ctx.fillStyle = C.teal;
      ctx.shadowColor = 'rgba(67,232,200,0.6)';
      ctx.shadowBlur = 8;
      rr(ctx, lx - 11, c.y + 121, 2.5, 18, 1.25);
      ctx.fill();
      ctx.shadowBlur = 0;

      ctx.fillStyle = '#F2FBFA';
      ctx.font = f(17, 400);
      setLS(ctx, 1.6);
      ctx.fillText(this.data.lyric1, lx, c.y + 140);

      ctx.fillStyle = '#75898E';
      ctx.font = f(13, 400);
      setLS(ctx, 1.2);
      ctx.fillText(this.data.lyric2, lx, c.y + 168);

      ctx.fillStyle = '#46595E';
      ctx.font = f(11.5, 400);
      ctx.fillText(this.data.lyric3, lx, c.y + 192);
      ctx.restore();

      /* ---------- 频谱条 ---------- */
      const eqX = c.x + 22, eqW = c.w - 44, eqY = c.y + 214, eqH = 40;
      const bars = 42, bw = 5, gapB = (eqW - bars * bw) / (bars - 1);
      ctx.save();
      for (let i = 0; i < bars; i++) {
        const hh = 6 + (eqH - 6) *
          (0.35 + 0.65 * Math.abs(Math.sin(t * 2.4 + i * 0.55))) *
          (0.55 + 0.45 * Math.sin(i * 0.9));
        const a = 0.16 + 0.30 * (hh / eqH);
        rr(ctx, eqX + i * (bw + gapB), eqY + eqH - hh, bw, hh, 2.5);
        ctx.fillStyle = `rgba(67,232,200,${a.toFixed(3)})`;
        ctx.fill();
      }
      ctx.restore();

      /* ---------- 进度 ---------- */
      const px = c.x + 22, pw = c.w - 44, py = c.y + 288;

      rr(ctx, px, py, pw, 3, 1.5);
      ctx.fillStyle = 'rgba(255,255,255,0.08)'; ctx.fill();

      const fw = pw * (this.data.mediaPct / 100);
      if (fw > 0.5) {
        const pg = ctx.createLinearGradient(px, 0, px + fw, 0);
        pg.addColorStop(0, '#1E8E7C');
        pg.addColorStop(1, C.teal);
        rr(ctx, px, py, fw, 3, 1.5);
        ctx.fillStyle = pg;
        ctx.shadowColor = 'rgba(67,232,200,0.5)'; ctx.shadowBlur = 8;
        ctx.fill();
        ctx.shadowBlur = 0;

        ctx.beginPath(); ctx.arc(px + fw, py + 1.5, 3.2, 0, 6.2832);
        ctx.fillStyle = '#EAFFFA';
        ctx.shadowColor = 'rgba(67,232,200,0.9)'; ctx.shadowBlur = 8;
        ctx.fill();
        ctx.shadowBlur = 0;
      }

      ctx.save();
      ctx.font = f(9, 400);
      setLS(ctx, 0.6);
      ctx.fillStyle = C.dim3;
      ctx.fillText(this.data.mediaTime, px, c.y + 306);
      ctx.textAlign = 'right';
      ctx.fillText(this.data.mediaDur, px + pw, c.y + 306);
      ctx.restore();
    }

    /* --------------------------- 档位 + 混动模式 --------------------------- */
    drawGear(ctx) {
      const c = L.gear;
      this.panel(ctx, c.x, c.y, c.w, c.h);

      const cyC = c.y + c.h / 2;

      /* 档位字母 */
      ctx.save();
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = '#F0FBFA';
      ctx.font = f(24, 600);
      setLS(ctx, 1);
      ctx.fillText(this.data.gear || 'D', c.x + 34, cyC + 1);
      ctx.restore();

      ctx.beginPath();
      ctx.moveTo(c.x + 58, c.y + 14);
      ctx.lineTo(c.x + 58, c.y + c.h - 14);
      ctx.strokeStyle = C.line; ctx.lineWidth = 1; ctx.stroke();

      /* 混动模式胶囊 */
      const modes = this.data.modes || [];
      const padL = c.x + 70, padR = c.x + c.w - 16;
      const n = modes.length || 1;
      const gap = 8;
      const cw = (padR - padL - gap * (n - 1)) / n;
      const ch = 26;
      const cy0 = cyC - ch / 2;

      modes.forEach((m, i) => {
        const x = padL + (cw + gap) * i;
        const on = m === this.data.driveMode;

        rr(ctx, x, cy0, cw, ch, 8);
        if (on) {
          const g = ctx.createLinearGradient(0, cy0, 0, cy0 + ch);
          g.addColorStop(0, rgba(C.teal, 0.24));
          g.addColorStop(1, rgba(C.teal, 0.08));
          ctx.fillStyle = g;
        } else {
          ctx.fillStyle = 'rgba(255,255,255,0.035)';
        }
        ctx.fill();
        ctx.strokeStyle = on ? rgba(C.teal, 0.55) : 'rgba(120,170,180,0.16)';
        ctx.lineWidth = 1;
        ctx.stroke();

        ctx.save();
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = on ? '#B6FFF2' : '#6E8287';
        ctx.font = f(11.5, on ? 500 : 400);
        setLS(ctx, 1.8);
        if (on) {
          ctx.shadowColor = rgba(C.teal, 0.5);
          ctx.shadowBlur = 8;
        }
        ctx.fillText(m, x + cw / 2, cy0 + ch / 2 + 0.5);
        ctx.restore();

      });
    }
  }

  /* ------------------------------ 导出 / 自动挂载 ------------------------------ */
  /* ------------------------------ 导出 / 自动挂载 ------------------------------ */
  global.HUD = HUD;

  /*
   * 车机（Frida）环境适配（2026-11-10）：
   *
   * 原版会自动 createElement('canvas') 挂到 document.body。
   * Frida 里没有 DOM，canvas 由 dash_bridge.js 手动 new HUD(adapter) 创建。
   * 所以只在真的能 createElement 出带 getContext 的对象时才自动 boot。
   */
  function boot() {
    if (typeof document === 'undefined') return;
    if (!document.createElement) return;
    if (document.getElementById && document.getElementById('hud')) return;
    const c = document.createElement('canvas');
    if (!c || !c.getContext) return;
    c.id = 'hud';
    document.body.appendChild(c);
    document.body.style.margin = '0';
    document.body.style.background = '#000';
    document.body.style.overflow = 'hidden';
    document.body.style.height = '100vh';
    const hud = new HUD(c);
    hud.start();
    global.hud = hud;
  }
  if (typeof document !== 'undefined') {
    try { boot(); } catch (e) { /* Frida 环境：静默跳过 */ }
  }
})(typeof globalThis !== 'undefined' ? globalThis : (typeof window !== 'undefined' ? window : this));
