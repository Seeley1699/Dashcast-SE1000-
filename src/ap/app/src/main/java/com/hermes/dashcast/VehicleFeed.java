package com.hermes.dashcast;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.hud.dash.HudData;

/**
 * VehicleFeed - vehicle data to HudData.
 *
 * Data plane: ROOT SHELL dumpsys. Reason: Dashcast is a normal app (u11),
 * CarPropertyManager.getPropertyList() returns 0 props (CarService filters
 * everything for unprivileged apps - verified on car 2026-11-11 18:37).
 * The shell (uid 0) channel reads all 1122 property lines via
 *   dumpsys car_service get-property-value
 * through the existing AdbShell root channel.
 *
 * IDs verified against EVCC_VHAL_20260706_221056.csv (on-car export).
 */
final class VehicleFeed {

    private static final String TAG = "JavaDashVeh";

    private static volatile boolean running = false;
    private static boolean shellInited = false;
    private static boolean probed = false;
    private static boolean mediaDumped = false;
    private static boolean lyricDumped = false;
    private static Thread poller;
    private static long pollCnt = 0, pollCostSum = 0, statLast = System.currentTimeMillis();

    /* all IDs verified against EVCC_VHAL_20260706_221056.csv */
    private static final int P_SPEED       = 291504647;   // PERF_VEHICLE_SPEED
    private static final int P_ODOMETER    = 291504644;   // PERF_ODOMETER km
    /* ★ 2026-11-12 补全：续航/油量/模式 全部改用 ECARX 私有属性
     * （实测 AP 侧 dumpsys 有值，且 CSV 里有官方名称）*/
    private static final int P_EV_RANGE2   = 559969024;   // 0x21607300 EV 续航 km
    private static final int P_FUEL_RANGE2 = 559969023;   // 0x216072ff 燃油续航 km
    private static final int P_FUEL_PCT    = 559969084;   // 0x2160733c 燃油百分比
    private static final int P_EV_RANGE    = 291504904;   // RANGE_REMAINING km（总）
    private static final int P_EV_CAP      = 291504390;   // INFO_EV_BATTERY_CAPACITY
    private static final int P_EV_PCT      = 559969079;   // TYPE_EV_BATTERY_PERCENTAGE
    private static final int P_FUEL_CAP    = 291504388;   // INFO_FUEL_CAPACITY
    private static final int P_FUEL_LEVEL  = 291504903;   // FUEL_LEVEL mL
    private static final int P_OUT_TEMP    = 291505923;   // ENV_OUTSIDE_TEMPERATURE
    /*
     * ★ 档位改用 GearLvrIndcn（2026-11-12 修）
     *
     * 原来用 0x11400401 CURRENT_GEAR（AAOS 标准），是**位掩码**：
     *   GEAR_PARK=4, GEAR_REVERSE=2, GEAR_NEUTRAL=1, GEAR_DRIVE=8
     * 而代码按序号映射（4 → "D"）→ 实测车在 P 挡（值=4）却显示 D。
     *
     * ECARX 私有段有专用属性 0x21408064 GearLvrIndcn，
     * 枚举来自 APVP 车厂信号表（0x124005BB 的 AP 镜像）：
     *   0=ParkIndcn(P)  1=RvsIndcn(R)  2=NeutIndcn(N)  3=DrvIndcn(D)
     *   4=ManModeIndcn(M)  5/6=Reserved  7=Undefd
     * 直接查表，零歧义。
     */
    private static final int P_GEAR        = 557875300;   // 0x21408064 GearLvrIndcn
    private static final int P_GEAR_AAOS   = 289408001;   // 0x11400401 CURRENT_GEAR（备用）

    /* lights: ECARX uses NON-standard 2894xxxx ids */
    private static final int P_TURN_SIGNAL = 289408008;   // 0=none 1=right 2=left
    private static final int P_HEADLIGHTS  = 289410560;   // HEADLIGHTS_STATE
    private static final int P_HIGH_BEAM   = 289410561;   // HIGH_BEAM_LIGHTS_STATE
    private static final int P_HAZARD      = 289410563;   // HAZARD_LIGHTS_STATE

    private static final int P_TIRE_PRESS  = 392168201;   // TIRE_PRESSURE kPa
    private static final int P_DRIVE_MODE  = 557871372;   // 0=ECO 1=comf 2=std 3=sport 4=EV 5=hyb
    private static final int P_BATT_SOC    = 559968667;   // HYBRID_FUNC_BATTERY_SOC %

    /* media stack (vendor Bytes props = official HUD feed) */
    private static final int P_MEDIA_PLAY  = 561017977;   // VENDOR_MEDIA_PLAY_INFO
    private static final int P_MEDIA_LYRIC = 561018280;   // VENDOR_MEDAI_LYRIC_INFO
    private static final int P_MEDIA_STATUS = 561017978;  // VENDOR_MEDIA_STATUS_INFO

    private static final float FALLBACK_FUEL_RANGE = 1370f;

    /* only the props we consume: 144KB -> ~3KB, cuts adb roundtrip time */
    /*
     * 直接 dumpsys 的完整命令（备用通道 + 泵脚本模板）。
     *
     * 注意：**ID 必须全小写**。dumpsys 输出小写十六进制，
     * 而 grep 区分大小写 —— 之前写 0x11400E00（大写 E）导致
     * 灯光属性一条都匹配不到，仪表灯光栏永远全灭。
     *
     * 2026-11-12 补全：续航/油量改用 ECARX 私有段独立属性
     *   0x21607300 EV 续航    0x216072ff 燃油续航   0x2160733c 燃油%
     */
    private static final String CMD_DUMP =
        "dumpsys car_service get-property-value 2>/dev/null | grep -Ei '"
        + "0x11600207|0x11600204|0x11600308|0x11600106|0x21607337|"
        + "0x2160733c|0x21607300|0x216072ff|"
        + "0x11600104|0x11600307|0x11600703|0x11400401|0x11400408|"
        + "0x11400e00|0x11400e01|0x11400e02|0x11400e03|0x17600309|"
        + "0x2140710c|0x2160719b|0x21707479|0x217075a8'";

    private VehicleFeed() {}

    static synchronized void start(Context ctx, Handler ui, final HudData data) {
        ctxRef = ctx.getApplicationContext();
        Log.w(TAG, "★ VehicleFeed.start 进入 running=" + running);
        if (running) return;
        running = true;
        Log.w(TAG, "★ VehicleFeed 线程即将启动");

        poller = new Thread(new Runnable() { @Override public void run() {
            if (!shellInited) { shellInited = initShell(); ensurePump(ctx); }
            while (running) {
                try {
                    if (shellInited) {
                        if (!probed) { probed = true; probeShell(); }
                        /*
                         * 泵看门狗（2026-11-12 加）
                         *
                         * 泵会被系统 OOM / 后台清理杀掉 —— 实测跑 4 小时
                         * 就没了，仪表数据全部冻结在最后一帧，而 UI 上
                         * 完全看不出来（仍显示最后那组正常数值）。
                         *
                         * 每 30 轮（约 7 秒）检查一次，不在就拉起。
                         */
                        if (++pumpCheckTick >= 30) {
                            pumpCheckTick = 0;
                            ensurePump(ctxRef);
                        }
                        long t0 = System.currentTimeMillis();
                        pollOnceShell(data);
                        long dt = System.currentTimeMillis() - t0;
                        pollCnt++;
                        pollCostSum += dt;
                        if (System.currentTimeMillis() - statLast > 30000) {
                            Log.i(TAG, "[数据面] " + (pollCnt * 1000L
                                    / Math.max(1, System.currentTimeMillis() - statLast))
                                    + "Hz 读取=" + (pollCnt > 0 ? pollCostSum / pollCnt : 0)
                                    + "ms/次 解析后车速=" + data.speed
                                    + " 转向=" + (data.lightLeft ? "左" : data.lightRight ? "右" : "无")
                                    + " 远光=" + data.lightHigh
                                    + " 电量=" + Math.round(data.evRange) + "km/" + Math.round(data.evCap) + "km"
                                    + " 歌=" + data.mediaTitle + "/" + data.mediaArtist
                                    + " 油量=" + Math.round(data.fuelRange) + "km"
                                    + " 档=" + data.gear + " 模式=" + data.driveMode);
                            pollCnt = 0; pollCostSum = 0;
                            statLast = System.currentTimeMillis();
                        }
                    }
                    if (data.fuelRange <= 0f) data.fuelRange = FALLBACK_FUEL_RANGE;
                } catch (Throwable t) {
                    Log.w(TAG, "poll: " + t);
                }
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        }}, "JavaDashVeh");
        poller.start();
        Log.i(TAG, "VehicleFeed started (shell data plane)");
    }

    /** Deploy + start the root data pump (idempotent, survives app restart). */
    /** 确保数据泵在运行（App 侧看门狗）
     *
     * 判据用「文件新鲜度」而不是「进程在不在」：
     * 试过三种进程判据全都踩坑（pgrep 匹配到自己、toybox 不支持行尾锚、
     * ps|grep 的管道自身也含 dash-veh），后果都是「永远认为在跑、从不重启」
     * → 数据静默冻结（实测 4 小时无人发现）。
     *
     * 泵每 0.4 秒写一次 veh.txt，mtime 超过 5 秒未变 = 泵死了。
     */
    private static void ensurePump(Context ctx) {
        try {
            String pp = pumpFilePath();
            if (pp != null) {
                String out = AdbShell.exec(
                        "A=$(date +%s); B=$(stat -c %Y " + pp
                        + " 2>/dev/null || echo 0); echo $((A-B))", 3000);
                long age = -1;
                if (out != null) {
                    try { age = Long.parseLong(out.trim()); } catch (Throwable ignored) {}
                }
                if (age >= 0 && age < 5) return;   /* 5 秒内更新过，泵活着 */
                Log.w(TAG, "数据泵停了（文件 " + age + " 秒未更新），重启");
            }
            AdbShell.exec("pkill -9 -f dash-veh", 3000);
            Thread.sleep(300);
            java.io.InputStream is = ctx.getAssets().open("dash-veh.sh");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            is.close();
            String content = bo.toString("UTF-8");
            String b64 = android.util.Base64.encodeToString(
                    content.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            /* 步骤 1：写脚本（单独一条 exec，避免后台任务被会话回收） */
            String cp = "echo " + b64 + " | base64 -d > /data/local/tmp/dash-veh.sh"
                    + " && chmod 755 /data/local/tmp/dash-veh.sh";
            AdbShell.exec(cp, 8000);

            /*
             * 步骤 2：启动（必须独立一条 exec）
             *
             * 合成一条命令时，`setsid ... &` 的后台任务会在
             * AdbShell.exec 返回、shell 会话关闭时被回收 ——
             * 实测日志一直 "数据泵已重启" 但 ps 里始终没有进程。
             * 拆开后 adbd 在处理完启动命令时，setsid 已经把
             * 泵放进新会话，不再受影响。
             */
            /*
             * ★ 用 LongLivedShell 启动（2026-11-12 最终方案）
             *
             * 试过全部「普通 adb shell 起后台进程」的写法，在**App 的
             * AdbShell 会话**里全都活不过 exec 返回：
             *   nohup ... &        → 会话关闭被回收
             *   setsid ... &       → 同上
             *   sh -c 'setsid ...' & → 同上
             * （同样命令在 PC 的 adb shell 里能活，差异在会话管理）
             *
             * dcapd2 能一直活着，因为它走的是 LongLivedShell —— 
             * 那条通道在 spawn 后**长期持有进程句柄和 stdin/stdout**，
             * 不退会话，子进程自然不被回收。
             *
             * 泵现在也走同一条路。
             */
            if (pumpShell != null) {
                try { pumpShell.close(); } catch (Throwable ignored) {}
                pumpShell = null;
            }
            pumpShell = LongLivedShell.spawn("dash-veh",
                    "sh /data/local/tmp/dash-veh.sh");
            Thread.sleep(800);
            String chk = AdbShell.exec("pgrep -f \'dash-veh\\\\.sh\'", 2000);
            Log.i(TAG, "数据泵已重启" + ((chk != null && chk.trim().length() > 0)
                    ? "（已确认存活）" : "（未确认，下轮再查）"));
            Thread.sleep(500);
            Log.i(TAG, "数据泵已重启");
        } catch (Throwable t) {
            Log.w(TAG, "ensurePump: " + t);
        }
    }

    private static boolean initShell() {
        shellInited = AdbShell.available();
        Log.i(TAG, "shell data plane: " + (shellInited ? "ready" : "unavailable"));
        return shellInited;
    }

    private static void probeShell() {
        try {
            String dump = AdbShell.exec(
                    CMD_DUMP, 4000);
            Log.i(TAG, "SHELL probe: " + ((dump != null && dump.length() > 50)
                    ? (dump.split("\n").length + " lines OK") : "FAILED"));
        } catch (Throwable t) {
            Log.w(TAG, "probeShell: " + t);
        }
    }

    /*
     * 数据泵文件（root 写，App 读）。
     *
     * ★★ 2026-11-12 重大修复：路径必须运行时获取 ★★
     *
     * 原来硬编码 `/data/user/11/com.hermes.dashcast/files/veh.txt`。
     * 但 App 实际运行在 **user 0**（ps 显示 u0_a146），
     * 其私有目录是 /data/user/0/com.hermes.dashcast/。
     *
     * 结果：泵一直在往 user 11 写（那是另一个并存用户目录），
     * App 读 user 0 的路径 → 文件根本不存在 → readPumpFile 恒返回
     * null → 仪表所有数据（车速/电量/油量/灯光/胎压/档位）
     * 全是 HudData 的硬编码默认值。
     *
     * 这就是"所有数据都对不上"的最终根因。
     *
     * 现在用 ctx.getFilesDir() 拿真实路径，再用 adb 把同一路径告诉泵脚本。
     */
    private static String P_PUMP_FILE = null;

    private static String pumpFilePath() {
        if (P_PUMP_FILE == null && ctxRef != null) {
            P_PUMP_FILE = new java.io.File(ctxRef.getFilesDir(), "veh.txt")
                    .getAbsolutePath();
            Log.w(TAG, "泵文件真实路径 = " + P_PUMP_FILE);
        }
        return P_PUMP_FILE;
    }

    private static Context ctxRef;
    private static int pumpCheckTick = 0;
    private static LongLivedShell.Handle pumpShell;
    /** 单引号（Java 字符串里直接写 ' 容易和 PS 转义打架） */
    private static final String CH = "\u0027";

    private static void pollOnceShell(HudData data) {
        String dump = null;
        try {
            dump = readPumpFile();
        } catch (Throwable t) {
            Log.w(TAG, "read pump: " + t);
            return;
        }
        if (dump == null || dump.length() < 50) return;

        java.util.regex.Pattern pl = java.util.regex.Pattern.compile(
                "Property:(0x[0-9a-fA-F]+).*?floatValues: \\[(.*?)\\].*?int32Values: \\[(.*?)\\].*?bytes: \\[(.*?)\\]",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = pl.matcher(dump);

        float speed = -1, evRange = -1, evRange2 = -1, fuelRange2 = -1, fuelPct = -1, evCap = -1, fuelCap = -1, fuelL = -1,
                outT = -1, soc = -1, evPct = -1;
        int turn = -1, hazard = -1, lowBeam = -1, hiBeam = -1,
                gearId = -1, driveMode = -1;
        byte[] playBytes = null, lyricBytes = null, statusBytes = null;
        float[] tires = new float[4];

        while (m.find()) {
            int id = (int) Long.parseLong(m.group(1).substring(2), 16);
            float f0 = firstFloat(m.group(2));
            int i0 = firstInt(m.group(3));
            switch (id) {
                case P_SPEED:      speed = f0; break;
                case P_EV_RANGE:    evRange = f0; break;
                case P_EV_RANGE2:   evRange2 = f0; break;   /* 0x21607300 EV 续航 */
                case P_FUEL_RANGE2: fuelRange2 = f0; break; /* 0x216072ff 燃油续航 */
                case P_FUEL_PCT:    fuelPct = f0; break;    /* 0x2160733c 燃油% */
                case P_EV_CAP:     evCap = f0; break;
                case P_FUEL_CAP:   fuelCap = f0; break;
                case P_FUEL_LEVEL: fuelL = f0; break;
                case P_OUT_TEMP:   outT = f0; break;
                case P_EV_PCT:     evPct = f0; break;
                case P_BATT_SOC:   soc = f0; break;
                case P_GEAR:       gearId = i0; break;
                case P_DRIVE_MODE: driveMode = i0; break;
                case P_TURN_SIGNAL: turn = i0; break;
                case P_HAZARD:      hazard = i0; break;
                case P_HEADLIGHTS:  lowBeam = i0; break;
                case P_HIGH_BEAM:   hiBeam = i0; break;
                case P_TIRE_PRESS:
                    /* per-wheel lines share the id; take first 4 floats seen */
                    if (tires[0] < 0) tires[0] = f0;
                    else if (tires[1] < 0) tires[1] = f0;
                    else if (tires[2] < 0) tires[2] = f0;
                    else if (tires[3] < 0) tires[3] = f0;
                    break;
                case P_MEDIA_PLAY:  playBytes = parseBytes(m.group(4)); break;
                case P_MEDIA_LYRIC: lyricBytes = parseBytes(m.group(4)); break;
                case P_MEDIA_STATUS: statusBytes = parseBytes(m.group(4)); break;
            }
        }

        /*
         * 应用到 HudData（2026-11-12 重写：全部用真实属性，不推算）
         *
         * 之前靠推算：
         *   evCap  = evRange / (pct/100)    ← 满电续航是猜的
         *   fuelCap = 900                   ← 燃油满箱瞎估
         *   fuelRange = 900 * 油量比例      ← 全是算的
         *
         * 实测 VHAL 里本来就有独立属性（ECARX 私有段，CSV 有官方名称）：
         *   0x21607300 SENSOR_TYPE_ENDURANCE_MILEAGE_EV   纯电续航 km
         *   0x216072ff SENSOR_TYPE_ENDURANCE_MILEAGE_FUEL 燃油续航 km
         *   0x21607337 TYPE_EV_BATTERY_PERCENTAGE         电量 %
         *   0x2160733c TYPE_FUEL_PERCENTAGE               油量 %
         *
         * 用户实测对照：应显示 油量 72km/3%，VHAL 当前正是 72.0/3.0
         * → 直接使用，零误差。
         */
        if (speed >= 0f) data.speed = speed;
        if (outT > -50f && outT < 60f) data.outdoor = Math.round(outT) + "\u00b0";

        /* EV 续航：优先 0x21607300，缺失才用 RANGE_REMAINING */
        float evKm = (evRange2 > 0f) ? evRange2 : evRange;
        if (evKm > 0f) data.evRange = evKm;

        /* EV 满值：用「当前续航 ÷ 电量%」反推（精确） */
        if (evKm > 0f && evPct > 1f && evPct <= 100f) {
            float capKm = evKm / (evPct / 100f);
            if (capKm > 50f && capKm < 1000f) data.evCap = capKm;
        } else if (evCap > 0f && evPct > 1f && evPct <= 100f) {
            float capKm = evCap / 150f;
            if (capKm > 50f && capKm < 1000f) data.evCap = capKm;
        }

        /* 燃油续航：直接用 0x216072ff（实测 72 km） */
        if (fuelRange2 > 0f) data.fuelRange = fuelRange2;

        /* 燃油满值：用「续航 ÷ 油量%」反推，保证进度条 = 真实百分比 */
        if (fuelPct > 0f && fuelPct <= 100f && data.fuelRange > 0f) {
            float capKm = data.fuelRange / (fuelPct / 100f);
            if (capKm > 50f && capKm < 3000f) data.fuelCap = capKm;
        } else if (fuelL > 0f && fuelCap > 0f && data.fuelRange > 0f) {
            float liters = fuelL / 1000f;
            float capL = (fuelCap > 1000f) ? fuelCap / 1000f : fuelCap;
            if (capL > 0f && capL < 200f) {
                float r = liters / capL;
                if (r > 0f && r <= 1f) data.fuelCap = data.fuelRange / r;
            }
        }
        /*
         * 胎压单位换算（2026-11-12 修 "胎压对应不上"）
         *
         * VHAL 的 TIRE_PRESSURE（0x17600309）给的是 **kPa**：
         *   实测 262 / 253 / 255 / 253
         * 而 HudData.tireBars 期望 **bar**（默认值 2.4/2.4/2.5/2.1）。
         *
         * 原代码是：
         *   if (tires[i] > 0.5f && tires[i] < 5f) data.tireBars[i] = tires[i];
         * 条件是「值在 0.5~5 之间」——那是在假设单位已经是 bar。
         * 但实际传进来的是 262 —— 直接撞上 <5f 被过滤掉，
         * tireBars 永远停在 HudData 的硬编码默认值，
         * 所以仪表上胎压从头到尾没变过。
         *
         * 现在：先按量级判断单位，kPa 就除以 100 换成 bar，
         * 再用 bar 区间校验（1.0~4.5 bar 覆盖正常胎压范围）。
         */
        for (int i = 0; i < 4; i++) {
            float v = tires[i];
            if (v <= 0f) continue;
            /* kPa → bar：正常胎压在 180~350 kPa / 1.8~3.5 bar，
             * 用 10 做分界足够区分两个量级 */
            float bar = (v > 10f) ? (v / 100f) : v;
            if (bar > 1.0f && bar < 4.5f) {
                data.tireBars[i] = bar;
            }
        }
        if (turn >= 0) {
            data.lightLeft = (turn == 2);
            data.lightRight = (turn == 1);
        }
        if (hazard >= 0) data.lightHazard = (hazard == 1);
        if (lowBeam >= 0) data.lightLow = (lowBeam > 0);
        if (hiBeam >= 0) data.lightHigh = (hiBeam > 0);
        /*
         * 档位映射：GearLvrIndcn 枚举（来自 APVP 车厂信号表）
         *   0=P  1=R  2=N  3=D  4=M
         */
        if (gearId >= 0 && gearId <= 4) {
            String[] g = {"P", "R", "N", "D", "M"};
            data.gear = g[gearId];
        }
        if (driveMode >= 0 && driveMode <= 6) {
            /* vendor: 0=ECO 1=comfort 2=standard 3=sport 4=EV 5=hybrid 6=save */
            String[] names = {"\u589e\u7a0b", "\u667a\u80fd", "\u589e\u7a0b",
                    "\u6027\u80fd", "\u7eaf\u7535", "\u589e\u7a0b", "\u589e\u7a0b"};
            data.driveMode = names[driveMode];
        }
        applyMedia(data, playBytes, lyricBytes, statusBytes);
    }

    /** read the pump file (root writes it, app owns the dir). 0ms latency. */
    private static String readPumpFile() {
        String pp = pumpFilePath();
        if (pp == null) return null;
        java.io.File f = new java.io.File(pp);
        if (!f.exists() || f.length() < 50) return null;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r <= 0) break;
                off += r;
            }
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private static float firstFloat(String s) {
        if (s == null || s.isEmpty()) return -1f;
        try { return Float.parseFloat(s.split(",")[0].trim()); }
        catch (Throwable t) { return -1f; }
    }

    private static int firstInt(String s) {
        if (s == null || s.isEmpty()) return -1;
        try { return Integer.parseInt(s.split(",")[0].trim()); }
        catch (Throwable t) { return -1; }
    }

    private static byte[] parseBytes(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) {
            try { b[i] = (byte) Integer.parseInt(p[i].trim()); }
            catch (Throwable t) { return null; }
        }
        return b;
    }

    /** 解析 ECARX 厂商媒体属性（protobuf）
     *  实测结构：10 09 <歌名UTF8> 18 15 <歌手UTF8>
     *  tag=(field<<3)|wire  field1=LEN 歌名  field3=LEN 歌手 */
    private static void applyMedia(HudData data, byte[] play, byte[] lyric,
                                   byte[] status) {
        /*
         * STATUS 解码：实测 [8, 1, 16, 0, 24, 0]
         *   field1 (tag 0x08) varint = 1  → 播放状态：1=播放 2=暂停 0=停止
         *   field2 (tag 0x10) varint = 0  → 可能是进度
         *   field3 (tag 0x18) varint = 0
         */
        if (status != null && status.length >= 2) {
            int st = status[1] & 0xFF;   /* field1 的 varint 值（长度=1） */
            data.mediaPlaying = (st == 1);
        }
        if (play != null && play.length > 0) {
            if (!mediaDumped) {
                mediaDumped = true;
                Log.i(TAG, "MEDIA_PLAY len=" + play.length + " b64="
                        + android.util.Base64.encodeToString(
                            play, android.util.Base64.NO_WRAP));
            }
            parseMediaPb(play, data);
        }
        if (lyric != null && lyric.length > 0) {
            if (!lyricDumped) {
                lyricDumped = true;
                Log.i(TAG, "MEDIA_LYRIC len=" + lyric.length + " b64="
                        + android.util.Base64.encodeToString(
                            lyric, android.util.Base64.NO_WRAP));
            }
            applyLyrics(lyric, data);
        }
    }

    /** PLAY_INFO protobuf -> 歌名/歌手 */
    private static void parseMediaPb(byte[] b, HudData data) {
        int i = 0;
        while (i < b.length) {
            int tag = b[i] & 0xFF;
            i++;
            int field = tag >>> 3;
            int wire = tag & 7;
            if (wire == 2) {
                if (i >= b.length) break;
                int len = 0, shift = 0;
                while (i < b.length) {
                    int v = b[i] & 0xFF;
                    i++;
                    len |= (v & 0x7F) << shift;
                    shift += 7;
                    if ((v & 0x80) == 0) break;
                    if (shift > 21) { len = -1; break; }
                }
                if (len < 0 || i + len > b.length) break;
                if (field == 1 || field == 3) {
                    String s = utf8(b, i, len);
                    if (s != null && s.length() > 0) {
                        if (field == 1) data.mediaTitle = s;
                        else data.mediaArtist = s;
                    }
                }
                i += len;
            } else if (wire == 0) {
                while (i < b.length && (b[i] & 0x80) != 0) i++;
                i++;
            } else if (wire == 5) {
                i += 4;
            } else if (wire == 1) {
                i += 8;
            } else {
                break;
            }
        }
    }

    /** 歌词：protobuf 多段字符串，失败退化纯文本 */
    private static void applyLyrics(byte[] b, HudData data) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        boolean okPb = false;
        while (i + 1 < b.length) {
            int tag = b[i] & 0xFF;
            int wire = tag & 7;
            if (wire != 2) break;
            i++;
            int len = 0, shift = 0;
            while (i < b.length) {
                int v = b[i] & 0xFF;
                i++;
                len |= (v & 0x7F) << shift;
                shift += 7;
                if ((v & 0x80) == 0) break;
            }
            if (len <= 0 || i + len > b.length) break;
            String s = utf8(b, i, len);
            if (s == null) break;
            if (sb.length() > 0) sb.append("\n");
            sb.append(s);
            i += len;
            okPb = true;
        }
        String text = okPb ? sb.toString() : tryText(b);
        if (text == null || text.length() == 0) return;
        String[] ls = text.split("\\n");
        if (ls.length > 0) data.lyric0 = ls[0];
        if (ls.length > 1) data.lyric1 = ls[1];
        if (ls.length > 2) data.lyric2 = ls[2];
        if (ls.length > 3) data.lyric3 = ls[3];
    }

    /** 解 UTF-8，含替换字符视为非法 */
    private static String utf8(byte[] b, int off, int len) {
        try {
            String s = new String(b, off, len, "UTF-8");
            if (s.indexOf(0xFFFD) >= 0) return null;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }


    /** 字节转文本：可打印字符占比 >= 70% 才认 */
    private static String tryText(byte[] b) {
        if (b == null) return null;
        try {
            int printable = 0;
            int n = Math.min(b.length, 256);
            for (int i = 0; i < n; i++) {
                int c = b[i] & 0xFF;
                if (c == 9 || c == 10 || c == 13 || (c >= 32 && c != 127)) printable++;
            }
            if (printable * 10 / Math.max(n, 1) < 7) return null;
            return new String(b, "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        }
    }


    static synchronized void stop() {
        running = false;
        if (poller != null) poller.interrupt();
        poller = null;
        Log.i(TAG, "VehicleFeed stopped");
    }

}