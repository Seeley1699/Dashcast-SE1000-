package com.hermes.dashcast;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.kooo.evcc.evccapi.EvccApiContract;
import com.kooo.evcc.evccapi.EvccApiInfo;
import com.kooo.evcc.evccapi.EvccMusicState;
import com.kooo.evcc.evccapi.EvccOperationResult;
import com.kooo.evcc.evccapi.EvccPropertyRecord;
import com.kooo.evcc.evccapi.EvccPropertySelector;
import com.kooo.evcc.evccapi.EvccSessionInfo;
import com.kooo.evcc.evccapi.EvccSourceState;
import com.kooo.evcc.evccapi.EvccValue;
import com.kooo.evcc.evccapi.EvccMusicResource;
import com.kooo.evcc.evccapi.EvccVariableRecord;
import com.kooo.evcc.evccapi.EvccShellResult;
import com.kooo.evcc.evccapi.client.EvccApiClient;
import com.kooo.evcc.evccapi.client.EvccMusicResources;
import com.hud.dash.HudData;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EVCC 车辆数据源（2026-11-12）
 *
 * ══════════════════════════════════════════════════════════════════
 * 为什么改走 EVCC
 * ══════════════════════════════════════════════════════════════════
 * VHAL 里有些属性是「死属性」——声明了但 timestamp 恒为 0：
 *   0x11400E01 HIGH_BEAM_LIGHTS_STATE   远光  ts=0（拿到的 [1] 是默认值）
 *   0x11400E03 HAZARD_LIGHTS_STATE      双闪  ts=0
 * 实测 30 秒内操作双闪，1116 个属性零变化，VHAL 日志只处理 2 个信号。
 *
 * EVCC 自己维护了 9 路属性源，其中 **APVP 源已启用并有 4161 个 CAN 信号**，
 * VHAL 源有 1026 个属性。通过它的属性目录找到了真正的数据源：
 *   双闪  0x2140752D DOUBLE_FLASH           VHAL  枚举 0=关 1=开  ts 有值 ✅
 *   远光  0x124007F0 ExtrLtgStsHiBeam       APVP  枚举 0=Off 1=On
 *
 * ══════════════════════════════════════════════════════════════════
 * 数据来源对照（全部经 EVCC 目录实测确认）
 * ══════════════════════════════════════════════════════════════════
 *   车速      0x11600207 PERF_VEHICLE_SPEED          VHAL  km/h
 *   续航      0x21607300 ENDURANCE_MILEAGE_EV        VHAL  EV 续航 km
 *   油续航    0x216072FF ENDURANCE_MILEAGE_FUEL      VHAL  燃油续航 km
 *   电量%     0x21607337 TYPE_EV_BATTERY_PERCENTAGE  VHAL  %
 *   油量%     0x2160733C TYPE_FUEL_PERCENTAGE        VHAL  %
 *   档位      0x21408064 GearLvrIndcn                VHAL  0=P 1=R 2=N 3=D 4=M
 *   驾驶模式  0x2140710C DM_FUNC_DRIVE_MODE_SELECT   VHAL  0=ECO…7=自定义
 *   转向灯    0x11400408 TURN_SIGNAL_STATE           VHAL  0=无 1=右 2=左
 *   近光      0x11400E00 HEADLIGHTS_STATE            VHAL
 *   远光      0x124007F0 ExtrLtgStsHiBeam            APVP  0=Off 1=On
 *   雾灯      0x11400E02 FOG_LIGHTS_STATE            VHAL
 *   双闪      0x2140752D DOUBLE_FLASH                VHAL  0=关 1=开
 *   胎压      0x17600309 TIRE_PRESSURE               VHAL  4 zone，kPa
 *   车外温度  0x11600703 ENV_OUTSIDE_TEMPERATURE     VHAL
 *   车内温度  0x21607522 SENSOR_INNER_TEMP           VHAL
 *   歌曲/歌手 readMusicState()                       EVCC 音乐 API
 *   歌词      readMusicLyrics()                      EVCC 音乐 API（完整 LRC）
 *
 * ══════════════════════════════════════════════════════════════════
 * 设计要点
 * ══════════════════════════════════════════════════════════════════
 * · 用**订阅**（replaceSubscriptions）而不是轮询 —— EVCC 推变化，零延迟
 * · 音乐单独走 setMusicSubscription，歌词走 onMusicLyrics 回调
 * · 全部写入调用方传进来的 HudData，不碰任何 UI
 * · EVCC 断开时自动重连（SDK 负责），期间 HudData 保持最后值
 */
public final class EvccVehicleFeed {

    private static final String TAG = "DashcastEvccFeed";

    /* ── 属性 ID（全部经 EVCC 目录实测确认存在且有推送）── */
    private static final long P_SPEED        = 0x11600207L;  // PERF_VEHICLE_SPEED
    private static final long P_EV_RANGE     = 0x21607300L;  // 纯电续航 km
    private static final long P_FUEL_RANGE   = 0x216072FFL;  // 燃油续航 km
    private static final long P_EV_PCT       = 0x21607337L;  // 电量 %
    private static final long P_FUEL_PCT     = 0x2160733CL;  // 油量 %
    private static final long P_GEAR         = 0x21408064L;  // 换挡杆指示
    private static final long P_DRIVE_MODE   = 0x2140710CL;  // 驾驶模式
    private static final long P_TURN         = 0x11400408L;  // 转向灯
    private static final long P_LOW_BEAM     = 0x11400E00L;  // 近光
    private static final long P_HIGH_BEAM    = 0x124007F0L;  // 远光（APVP）
    private static final long P_FOG          = 0x11400E02L;  // 雾灯
    private static final long P_HAZARD       = 0x2140752DL;  // 双闪
    private static final long P_TIRE         = 0x17600309L;  // 胎压（4 zone）
    private static final long P_OUT_TEMP     = 0x11600703L;  // 车外温度
    private static final long P_IN_TEMP      = 0x21607522L;  // 车内温度
    /*
     * 自动驻车（2026-11-12 新增）
     *
     * 从参考源码 i/a.java 第 306 行反查出来的真实标识：
     *   canonicalId = 557872709 (0x21407645)
     *   name        = "SETTING_FUNC_AUTO_HOLD_ACTIVE"
     *   source      = "APVP"
     *
     * 语义（用户确认）：**D 档 + 刹车踩到底 → 自动驻车生效 → 灯亮**。
     * 它不是"功能有没有开"，而是"这一刻有没有真的驻住"。
     *
     * 之前那盏灯一直亮，是因为 dashboard.js 里 lights.hold 默认写死 true
     * 且从来没被赋值 —— 现在由这个属性驱动。
     */
    private static final long P_AUTO_HOLD    = 0x21407645L;  // SETTING_FUNC_AUTO_HOLD_ACTIVE

    /**
     * 车速换算系数（2026-11-12 用户实车确认）
     *
     * 0x11600207 PERF_VEHICLE_SPEED 标准单位是 m/s，正常应 ×3.6 得 km/h。
     * 但这台 SE1000 上报的原始值偏小，用户拿实车仪表对比后确认
     * **×3.5 才是对的**。
     *
     * 改这一个常量就能调，不用翻代码。
     */
    private static final double SPEED_SCALE = 3.5;

    /** 驾驶模式枚举（0x2140710C 的 EVCC 目录定义） */
    private static final String[] DRIVE_MODES = {
            "经济", "舒适", "标准", "运动", "纯电", "混动", "省电", "自定义"
    };

    /** 换挡杆指示枚举（0x21408064） */
    private static final String[] GEARS = {"P", "R", "N", "D", "M"};

    private final Context app;
    private final HudData data;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EvccApiClient client;
    private volatile boolean running = false;
    private volatile boolean sessionReady = false;

    /* 统计 */
    private long propEvents = 0;
    /** 前 N 条属性打印详情，定位 ID 映射问题 */
    private int dbgLeft = 40;
    private long musicEvents = 0;
    private long lastStatAt = System.currentTimeMillis();
    private String lastLyricLine = "";

    /* LRC 时间轴（applyLyrics 解析后缓存） */
    private List<long[]> lrcTimes = new ArrayList<>();
    private List<String> lrcTexts = new ArrayList<>();
    /* 当前播放位置（applyMusic 更新，用于歌词滚动） */
    private long currentPositionMs = 0;
    /* 封面版本（变了才重新拉） */
    private long lastArtworkRev = -1;

    public EvccVehicleFeed(Context ctx, HudData data) {
        this.app = ctx.getApplicationContext();
        this.data = data;
    }

    /** 建立连接 + 订阅。失败只记日志，不抛异常（EVCC 可选）。 */
    public void start() {
        if (running) return;
        running = true;

        client = new EvccApiClient(app, app.getPackageName(), new EvccApiClient.Listener() {
            @Override public void onConnectionStateChanged(
                    EvccApiClient.ConnectionState s, String detail) {
                Log.i(TAG, "连接状态 " + s + (detail == null ? "" : " · " + detail));
            }

            @Override public void onSessionReady(EvccApiInfo info, EvccSessionInfo session) {
                sessionReady = true;
                Log.i(TAG, "会话就绪 API " + info.getApiMajor() + "." + info.getApiMinor()
                        + " EVCC " + info.getServiceVersionName()
                        + " 预设 " + session.getPreset());
                List<String> perms = session.getGrantedPermissions();
                if (perms == null || !perms.contains("property.listen")
                        || !perms.contains("property.read")) {
                    Log.e(TAG, "缺少 property.read/listen 权限，数据将不可用。"
                            + "请在 EVCC → 开放 API → 开放权限的应用 里授权 Dashcast");
                    return;
                }
                /* 订阅放在工作线程，Binder 同步调用不能阻塞主线程 */
                new Thread(EvccVehicleFeed.this::subscribeAll, "EvccSub").start();
            }

            @Override public void onSessionClosed(EvccOperationResult r) {
                sessionReady = false;
                Log.w(TAG, "会话关闭 " + (r == null ? "" : r.getStatus()));
            }

            @Override public void onPropertyEvent(EvccPropertyRecord r, long seq, String type) {
                if (r == null) return;
                propEvents++;
                applyProperty(r);
                maybeStat();
            }

            @Override public void onMusicStateChanged(EvccMusicState st, long seq, String type) {
                musicEvents++;
                applyMusic(st);
            }

            @Override public void onMusicLyrics(EvccMusicResources.Lyrics lyr) {
                applyLyrics(lyr);
            }

            @Override public void onVariableEvent(EvccVariableRecord r, long seq, String t) {}
            @Override public void onSourceStateChanged(EvccSourceState st, long seq) {}
            @Override public void onShellCompleted(EvccShellResult r) {}

            @Override public void onResyncRequired(long expected, long actual) {
                Log.w(TAG, "事件缺口 " + expected + "→" + actual + "，重新订阅");
                new Thread(EvccVehicleFeed.this::subscribeAll, "EvccResub").start();
            }

            @Override public void onError(String op, Throwable t) {
                Log.w(TAG, "EVCC 错误 " + op + ": " + t);
            }
        });

        client.connect();
        Log.i(TAG, "已发起连接");
    }

    /** 订阅全部属性和音乐 */
    private void subscribeAll() {
        if (client == null || !sessionReady) return;
        try {
            List<EvccPropertySelector> sels = new ArrayList<>();
            sels.add(sel(P_SPEED));
            sels.add(sel(P_EV_RANGE));
            sels.add(sel(P_FUEL_RANGE));
            sels.add(sel(P_EV_PCT));
            sels.add(sel(P_FUEL_PCT));
            sels.add(sel(P_GEAR));
            sels.add(sel(P_DRIVE_MODE));
            sels.add(sel(P_TURN));
            sels.add(sel(P_LOW_BEAM));
            sels.add(sel(P_HIGH_BEAM));
            sels.add(sel(P_FOG));
            sels.add(sel(P_HAZARD));
            sels.add(sel(P_OUT_TEMP));
            sels.add(sel(P_IN_TEMP));
            /* 自动驻车（2026-11-12 新增，APVP 0x21407645） */
            sels.add(sel(P_AUTO_HOLD));

            /* 胎压有 4 个区域，必须逐个带 areaId 订阅 */
            sels.add(sel(P_TIRE, 0x1));
            sels.add(sel(P_TIRE, 0x2));
            sels.add(sel(P_TIRE, 0x4));
            sels.add(sel(P_TIRE, 0x8));

            EvccOperationResult r = client.replaceSubscriptions(sels);
            String st = r == null ? "null" : r.getStatus();
            Log.i(TAG, "属性订阅 " + sels.size() + " 个 → " + st
                    + (r != null && r.getMessage() != null ? " · " + r.getMessage() : ""));

            /*
             * ★ 订阅后主动读一次（2026-11-12 修"档位显示 D"）
             *
             * EVCC 的订阅只推**变化**。静态属性（比如车停着档位一直 P）
             * 可能永远不推，于是 HudData 停在硬编码默认值：
             *   public String gear = "D";    ← 实测车在 P 却显示 D
             *   public float evRange = 130f; ← 续航也是默认值
             *
             * 所以订阅完必须把当前值强制读一遍，把初始状态填进去。
             * forceRefresh=true 绕过缓存直接问上游。
             */
            int filled = 0;
            for (EvccPropertySelector s : sels) {
                try {
                    EvccPropertyRecord rec = client.readProperty(s, true);
                    if (rec != null) {
                        applyProperty(rec);
                        filled++;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "初次读取失败 id=" + s.getId() + ": " + t.getMessage());
                }
            }
            Log.i(TAG, "初次读取 " + filled + "/" + sels.size() + " 个属性已填进 HudData");

            /* 音乐状态也主动读一次，别等回调 */
            try {
                com.kooo.evcc.evccapi.EvccMusicState ms = client.readMusicState();
                if (ms != null) applyMusic(ms);
            } catch (Throwable t) {
                Log.w(TAG, "初次读音乐状态失败: " + t.getMessage());
            }

            /* 音乐订阅（歌词靠它推） */
            try {
                EvccOperationResult mr = client.setMusicSubscription(true);
                Log.i(TAG, "音乐订阅 → " + (mr == null ? "null" : mr.getStatus()));
            } catch (Throwable t) {
                Log.w(TAG, "音乐订阅失败（可能未授权 music.listen）: " + t.getMessage());
            }
        } catch (Throwable t) {
            Log.e(TAG, "订阅失败", t);
        }
    }

    private static EvccPropertySelector sel(long id) {
        return new EvccPropertySelector(id, "", EvccApiContract.Source.AUTO,
                null, EvccApiContract.ResolutionMode.AUTO_PRIORITY);
    }

    private static EvccPropertySelector sel(long id, int area) {
        return new EvccPropertySelector(id, "", EvccApiContract.Source.AUTO,
                area, EvccApiContract.ResolutionMode.AUTO_PRIORITY);
    }

    /** 把一条属性记录写进 HudData */
    private void applyProperty(EvccPropertyRecord rec) {
        try {
            /*
             * ★ status 判定（2026-11-12 修）
             *
             * 实测 EVCC 的 readProperty 返回 status="OK"，
             * 而订阅事件才可能用 "AVAILABLE"。
             *
             * 原来只认 AVAILABLE：
             *   if (!Status.AVAILABLE.equals(rec.getStatus())) return;
             * → 35 个属性事件全部被丢弃，仪表停在默认值。
             *   这正是"速度/续航/胎压都没对上"的根因。
             *
             * 现在三种都接受：OK / AVAILABLE，其余（UNAVAILABLE /
             * PENDING / PERMISSION_DENIED 等）保留旧值。
             */
            String st = rec.getStatus();
            boolean stOk = EvccApiContract.Status.OK.equals(st)
                    || EvccApiContract.Status.AVAILABLE.equals(st);
            if (!stOk) {
                if (dbgLeft-- > 0) {
                    Log.i(TAG, "属性跳过 status=" + st + " id=" + selectorId(rec));
                }
                return;
            }
            EvccValue v = rec.getValue();
            if (v == null || !v.getInitialized()) {
                if (dbgLeft-- > 0) {
                    Log.i(TAG, "属性跳过 value init=" + (v == null ? "null" : v.getInitialized())
                            + " id=" + selectorId(rec));
                }
                return;
            }

            long id = selectorId(rec);
            int area = selectorArea(rec);
            if (dbgLeft > 0) {
                Log.i(TAG, "对象检查 id=0x" + Long.toHexString(id)
                        + " dataHash=" + System.identityHashCode(data)
                        + " gear=" + data.gear);
            }
            if (dbgLeft-- > 0) {
                Log.i(TAG, String.format(java.util.Locale.US,
                        "属性到达 id=0x%X area=0x%X type=%s value=%s display=%s",
                        id, area, v.getType(),
                        v.getLongValue() != null ? v.getLongValue().toString()
                                : (v.getDoubleValue() != null ? v.getDoubleValue().toString()
                                : (v.getBooleanValue() != null ? v.getBooleanValue().toString()
                                : nz(v.getStringValue()))),
                        nz(v.getDisplayValue())));
            }

            /*
             * ★ 类型分派（2026-11-12 重构）
             *
             * 原来用 numberValue() 先判浮点、再判整数，两个分支互相拦截：
             *   Double d = numberValue(v);
             *   if (d != null) { ...浮点处理...; return; }
             *   Long lv = longValue(v);            ← 永远走不到
             *
             * 因为 numberValue() 对 LONG 类型也返回非 null（转成 double），
             * 于是所有整数属性（档位/驾驶模式/灯光/转向灯）都被浮点分支
             * 吞掉然后 return 了 —— 这就是"档位一直显示 D"的真正原因。
             *
             * 现在按 type 明确分派，不靠返回值猜。
             */
            String vType = v.getType();
            boolean isFloat = EvccApiContract.ValueType.DOUBLE.equals(vType);
            boolean isInt = EvccApiContract.ValueType.LONG.equals(vType)
                    || EvccApiContract.ValueType.BOOLEAN.equals(vType);

            // ---- 浮点类 ----
            Double d = isFloat ? numberValue(v) : null;
            if (d != null) {
                double f = d;
                if (id == P_SPEED) {
                    /*
                     * **车速 ×3.5**（2026-11-12 用户实车确认）
                     *
                     * 原始值偏小，用户拿仪表对比过：×3.5 才对。
                     * 系数提取成 SPEED_SCALE 常量，以后要调改一行即可。
                     */
                    data.speed = (float) (f * SPEED_SCALE);
                } else if (id == P_EV_RANGE) {
                    data.evRange = (float) f;
                } else if (id == P_FUEL_RANGE) {
                    data.fuelRange = (float) f;
                } else if (id == P_EV_PCT) {
                    /* 电量% → evCap = 当前续航 / 百分比 */
                    if (f > 1 && f <= 100 && data.evRange > 0) {
                        data.evCap = (float) (data.evRange / (f / 100.0));
                    }
                } else if (id == P_FUEL_PCT) {
                    if (f > 0 && f <= 100 && data.fuelRange > 0) {
                        data.fuelCap = (float) (data.fuelRange / (f / 100.0));
                    }
                } else if (id == P_TIRE) {
                    int idx = tireIndex(area);
                    if (idx >= 0) {
                        float bar = (f > 10) ? (float) (f / 100.0) : (float) f;
                        if (bar > 1.0f && bar < 4.5f) data.tireBars[idx] = bar;
                    }
                } else if (id == P_OUT_TEMP) {
                    if (f > -50 && f < 60) {
                        data.outdoor = Math.round(f) + "\u00b0";
                    }
                } else if (id == P_IN_TEMP) {
                    if (f > -50 && f < 70) {
                        data.indoor = Math.round(f) + "\u00b0";
                    }
                }
                return;
            }

            // ---- 整数类 ----
            Long lv = isInt ? longValue(v) : null;
            if (lv == null) {
                /*
                 * ★ STRING 类型的枚举（2026-11-12 修）
                 *
                 * APVP 源的远光 0x124007F0 返回的是 STRING：
                 *   type=STRING display="0 (Off )"
                 * 而 VHAL 源的都是 LONG。
                 *
                 * 原来只处理 LONG/DOUBLE/BOOLEAN，STRING 直接被丢弃，
                 * 所以远光灯永远不动 —— 这就是"远光对应不上"的根因。
                 *
                 * 这里从 display 字符串里抽前导数字：
                 *   "0 (Off )"  → 0
                 *   "1 (On )"   → 1
                 */
                Long parsed = parseIntFromString(v);
                if (parsed == null) return;
                lv = parsed;
            }
            int iv = (int) (long) lv;

            if (id == P_GEAR) {
                /* 验证日志：确认这个分支真的被执行到 */
                Log.i(TAG, "★档位分支 id=0x" + Long.toHexString(id)
                        + " iv=" + iv + " 旧=" + data.gear
                        + " 新=" + (iv >= 0 && iv < GEARS.length ? GEARS[iv] : "越界")
                        + " dataHash=" + System.identityHashCode(data));
                if (iv >= 0 && iv < GEARS.length) data.gear = GEARS[iv];
            } else if (id == P_DRIVE_MODE) {
                if (iv >= 0 && iv < DRIVE_MODES.length) {
                    data.driveMode = DRIVE_MODES[iv];
                }
            } else if (id == P_TURN) {
                data.lightLeft = (iv == 2);
                data.lightRight = (iv == 1);
            } else if (id == P_LOW_BEAM) {
                data.lightLow = (iv > 0);
            } else if (id == P_HIGH_BEAM) {
                data.lightHigh = (iv == 1 || iv > 0);   /* APVP: 0=Off 1=On 2=Err */
            } else if (id == P_FOG) {
                /* 雾灯：HudData 里字段名是 lightFogR（右雾灯），
                 * 且 0x11400E02 在 VHAL 里是死属性（ts=0），值不可信。
                 * 这里先不写，避免显示假的雾灯状态。 */
            } else if (id == P_HAZARD) {
                data.lightHazard = (iv == 1);           /* DOUBLE_FLASH 0=关 1=开 */
            } else if (id == P_AUTO_HOLD) {
                /*
                 * 自动驻车（2026-11-12 新增）
                 *
                 * SETTING_FUNC_AUTO_HOLD_ACTIVE，非 0 = 已驻住。
                 * 用户语义："D 档 + 刹车踩到底 → 自动驻车生效 → 灯亮"。
                 *
                 * 这里存 boolean，dashboard.js 的 lights.hold 直接读它。
                 * 注意 dashboard.js 那边已经把默认值改成 false 了 ——
                 * 两边都改才算修完：默认不亮 + 真实值驱动。
                 */
                data.lightHold = (iv != 0);
                Log.i(TAG, "★自动驻车 id=0x" + Long.toHexString(id)
                        + " iv=" + iv + " → lightHold=" + data.lightHold);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyProperty: " + t);
        }
    }

    /** 音乐状态 → HudData */
    private void applyMusic(EvccMusicState st) {
        if (st == null) return;
        try {
            boolean available = EvccApiContract.Status.AVAILABLE.equals(st.getStatus());
            if (!available) {
                data.mediaPlaying = false;
                return;
            }
            data.mediaTitle = nz(st.getTitle());
            data.mediaArtist = nz(st.getArtist());
            data.mediaPlaying = "PLAYING".equals(st.getPlaybackState());

            /* 播放位置：用于歌词滚动定位 */
            if (st.getPositionMs() >= 0) {
                currentPositionMs = st.getPositionMs();
                renderLyricAt(currentPositionMs);
            }

            /* 封面：revision 变了才重新拉（2026-11-12 新增）
             * 实测 artworkStatus=AVAILABLE, artworkRev=1
             * 原来完全没实现，所以封面永远不变。 */
            if (EvccApiContract.Status.AVAILABLE.equals(st.getArtworkStatus())
                    && st.getArtworkRevision() != lastArtworkRev) {
                lastArtworkRev = st.getArtworkRevision();
                final String tid = nz(st.getTrackId());
                final long rev = st.getArtworkRevision();
                new Thread(() -> fetchArtwork(tid, rev), "EvccArt").start();
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyMusic: " + t);
        }
    }

    /**
     * 按播放位置渲染 4 行歌词：上一行 + 当前行 + 下两行。
     *
     * 这是仪表上歌词该有的样子 —— 中间那行高亮（lyric1），
     * 上下各有上下文。原来只显示一行且还是"下一行"，
     * 所以看着永远对不上。
     */
    private void renderLyricAt(long posMs) {
        if (lrcTimes.isEmpty() || lrcTexts.isEmpty()) return;
        /* 找最后一个 time <= posMs 的行号 */
        int cur = -1;
        for (int i = 0; i < lrcTimes.size(); i++) {
            long t = lrcTimes.get(i)[0];
            if (t < 0) continue;          /* 无时间的行跳过定位 */
            if (t <= posMs) cur = i;
            else break;
        }
        if (cur < 0) cur = 0;

        /* 4 行窗口：cur-1 .. cur+2，中间行是当前 */
        String[] win = new String[4];
        for (int k = 0; k < 4; k++) {
            int idx = cur - 1 + k;
            win[k] = (idx >= 0 && idx < lrcTexts.size()) ? lrcTexts.get(idx) : "";
        }
        if (!win[1].equals(data.lyric1) || !win[0].equals(data.lyric0)) {
            data.lyric0 = win[0];
            data.lyric1 = win[1];
            data.lyric2 = win[2];
            data.lyric3 = win[3];
            Log.d(TAG, "歌词滚动 → [" + win[1] + "] @" + posMs + "ms");
        }
    }

    /**
     * 完整歌词（LRC）→ 按当前播放位置滚动显示
     *
     * ★ 2026-11-12 重写：解决"歌词只能获取下一行"
     *
     * 原来用的是 state.getCurrentLyric()，那是 EVCC 已经算好的
     * **当前行**——但实测它给的是"即将播放的那一行"
     * （positionMs=268386 时 currentLyric 显示 lineStartMs=265470 之后的行，
     *  听起来像是提前了一行）。
     *
     * 现在改成：拿完整 LRC（实测 46 行），自己按 positionMs 定位，
     * 并且一次显示 4 行（上一行 + 当前行 + 下两行），这才是歌词该有的样子。
     */
    private void applyLyrics(EvccMusicResources.Lyrics lyr) {
        if (lyr == null) return;
        try {
            String text = lyr.getText();
            if (text == null || text.isEmpty()) {
                data.lyric0 = "";
                data.lyric1 = "";
                data.lyric2 = "";
                data.lyric3 = "";
                return;
            }
            /* 解析 LRC：每行 "[mm:ss.xx]文本"，存 (时间ms, 文本) */
            String[] lines = text.split("\\r?\\n");
            List<long[]> times = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            java.util.regex.Pattern p =
                    java.util.regex.Pattern.compile("^\\[(\\d+):(\\d+)[.:](\\d+)\\](.*)$");
            for (String ln : lines) {
                if (ln == null) continue;
                java.util.regex.Matcher m = p.matcher(ln.trim());
                if (m.matches()) {
                    try {
                        long min = Long.parseLong(m.group(1));
                        long sec = Long.parseLong(m.group(2));
                        String frac = m.group(3);
                        long ms = Long.parseLong(frac) * (frac.length() == 2 ? 10 : 1);
                        String t = m.group(4) == null ? "" : m.group(4).trim();
                        if (!t.isEmpty()) {
                            times.add(new long[]{min * 60000 + sec * 1000 + ms});
                            texts.add(t);
                        }
                    } catch (Throwable ignored) {}
                } else {
                    String t = ln.replaceAll("^\\[[^\\]]*\\]", "").trim();
                    if (!t.isEmpty()) {
                        times.add(new long[]{-1});
                        texts.add(t);
                    }
                }
            }
            if (texts.isEmpty()) return;

            lrcTimes = times;
            lrcTexts = texts;
            Log.i(TAG, "歌词已解析 " + texts.size() + " 行（含时间轴）");

            /* 立刻按当前播放位置渲染一次 */
            renderLyricAt(currentPositionMs);
        } catch (Throwable t) {
            Log.w(TAG, "applyLyrics: " + t);
        }
    }

    /* ── 工具 ── */

    private static long selectorId(EvccPropertyRecord rec) {
        try {
            if (rec.getRequestedSelector() != null
                    && rec.getRequestedSelector().getId() != null) {
                return rec.getRequestedSelector().getId();
            }
            if (rec.getDescriptor() != null) {
                return rec.getDescriptor().getCanonicalId();
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static int selectorArea(EvccPropertyRecord rec) {
        try {
            if (rec.getRequestedSelector() != null
                    && rec.getRequestedSelector().getAreaId() != null) {
                return rec.getRequestedSelector().getAreaId();
            }
            if (rec.getDescriptor() != null) {
                return rec.getDescriptor().getAreaId();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 车轮区域 → 数组下标（0=左前 1=右前 2=左后 3=右后） */
    private static int tireIndex(int area) {
        switch (area) {
            case 0x1: return 0;
            case 0x2: return 1;
            case 0x4: return 2;
            case 0x8: return 3;
            default: return -1;
        }
    }

    /** 从 EvccValue 取数值（long / double / boolean 都转 double） */
    private static Double numberValue(EvccValue v) {
        try {
            String t = v.getType();
            if (EvccApiContract.ValueType.DOUBLE.equals(t) && v.getDoubleValue() != null) {
                return v.getDoubleValue();
            }
            if (EvccApiContract.ValueType.LONG.equals(t) && v.getLongValue() != null) {
                return v.getLongValue().doubleValue();
            }
            if (EvccApiContract.ValueType.BOOLEAN.equals(t) && v.getBooleanValue() != null) {
                return v.getBooleanValue() ? 1.0 : 0.0;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 从 STRING 类型的 EvccValue 里抽前导整数。
     * EVCC 的 APVP 源会把枚举值格式化成 "0 (Off )" 这种，
     * 需要把开头的数字取出来。
     */
    private static Long parseIntFromString(EvccValue v) {
        try {
            String t = v.getType();
            if (!EvccApiContract.ValueType.STRING.equals(t)) return null;
            String s = v.getDisplayValue();
            if (s == null || s.isEmpty()) s = v.getStringValue();
            if (s == null || s.isEmpty()) return null;
            s = s.trim();
            int i = 0;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            int start = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == start) return null;
            return Long.parseLong(s.substring(0, i));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从 EvccValue 取整数 */
    private static Long longValue(EvccValue v) {
        try {
            String t = v.getType();
            if (EvccApiContract.ValueType.LONG.equals(t) && v.getLongValue() != null) {
                return v.getLongValue();
            }
            if (EvccApiContract.ValueType.DOUBLE.equals(t) && v.getDoubleValue() != null) {
                return (long) (double) v.getDoubleValue();
            }
            if (EvccApiContract.ValueType.BOOLEAN.equals(t) && v.getBooleanValue() != null) {
                return v.getBooleanValue() ? 1L : 0L;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 拉封面并存到 files/cover.jpg，同时通知 DashView 重绘。
     *
     * EVCC 的 openMusicArtwork 返回 ParcelFileDescriptor（只读 fd），
     * 要用 EvccMusicResources.openStream 读成 InputStream。
     */
    private void fetchArtwork(String trackId, long revision) {
        if (client == null) return;
        try {
            EvccMusicResource res = client.openMusicArtwork(trackId, revision);
            if (res == null) return;
            try (java.io.InputStream in = EvccMusicResources.openStream(res)) {
                if (in == null) return;
                File out = new File(app.getFilesDir(), "cover.jpg");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                Log.i(TAG, "封面已保存 " + out.length() + " 字节 → " + out.getAbsolutePath());
                data.mediaCoverPath = out.getAbsolutePath();
                data.mediaCoverRev = revision;
            }
        } catch (Throwable t) {
            Log.w(TAG, "拉封面失败: " + t);
        }
    }

    private void maybeStat() {
        long now = System.currentTimeMillis();
        if (now - lastStatAt < 30000) return;
        lastStatAt = now;
        Log.i(TAG, String.format(Locale.US,
                "[EVCC数据] 属性事件=%d 音乐事件=%d | 车速=%.0f 档=%s 模式=%s "
                        + "电量=%.0f/%.0fkm 油量=%.0fkm 双闪=%s 远光=%s 转向=%s "
                        + "胎压=%.2f/%.2f/%.2f/%.2f 歌=%s/%s",
                propEvents, musicEvents,
                data.speed, data.gear, data.driveMode,
                data.evRange, data.evCap, data.fuelRange,
                data.lightHazard, data.lightHigh,
                data.lightLeft ? "左" : data.lightRight ? "右" : "无",
                data.tireBars[0], data.tireBars[1], data.tireBars[2], data.tireBars[3],
                data.mediaTitle, data.mediaArtist, System.identityHashCode(data)));
        propEvents = 0;
        musicEvents = 0;
    }

    public void stop() {
        running = false;
        sessionReady = false;
        try {
            if (client != null) client.close();
        } catch (Throwable ignored) {}
        client = null;
        Log.i(TAG, "已停止");
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
