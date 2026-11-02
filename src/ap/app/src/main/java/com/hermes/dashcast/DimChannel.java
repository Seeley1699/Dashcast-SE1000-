package com.hermes.dashcast;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * DimChannel —— 仪表盘地图的「原生通道」。
 *
 * ══════════════════════════════════════════════════════════════
 * 这是什么
 * ══════════════════════════════════════════════════════════════
 * 和现有的「截屏投送」是**两条完全不同的路**：
 *
 *   截屏投送（现有）：
 *     dcapd2 截屏 → 编码 → TCP → CP 的 dashcastd → Weston layer 99
 *     几千行代码，49 KB/s 带宽，~100ms 延迟
 *
 *   原生通道（本类）：
 *     往 Settings 写一个值 → 地图 App 自己把导航画面画到仪表屏
 *     几十行代码，零带宽，零延迟，原生画质
 *
 * ══════════════════════════════════════════════════════════════
 * 原理（从 com.geely.map 的 Map.apk 反编译挖出来的）
 * ══════════════════════════════════════════════════════════════
 *   ecarx.navi.engine.feature.dim.DimControlManager
 *     · 监听 content://ecarx.settings/map/display_on_dashboard
 *     · 读 Settings.System 的 "display_on_dashboard"
 *     · 经 DimXcSettingsManager.transformXcSettingEnumToNaviEnum() 映射：
 *         "no"     → NAVI_DIM_DISPLAY_MODULE_SIMPLE
 *         "normal" → NAVI_DIM_DISPLAY_MODULE_FULL     ← 完整地图
 *         "ar"     → NAVI_DIM_DISPLAY_MODULE_AR       ← AR 实景导航
 *
 *   然后地图引擎对 SURFACE_VIEW_ID_DIM (=3) 建渲染目标：
 *     com.ecarx.navi.multiscreen.dim.DimPresentation
 *       extends android.app.Presentation   ← 标准 Android 多屏 API
 *       构造时 ExternalService.getExternalService(3) 取 DIMService
 *       内部 SurfaceView 交给 DIMControl.addSurface(sv, isNight, w, h)
 *       → 导航画面直接画到仪表那块物理 Display
 *
 * ══════════════════════════════════════════════════════════════
 * 硬性约束对照
 * ══════════════════════════════════════════════════════════════
 *   · 不碰车机系统：只写一个可随时改回的 Settings 值，
 *     不装证书、不改 APK、不替换 so、不需要 Frida
 *   · CP 零残留：这条路**根本不碰 CP**，仪表是车机自己渲染的
 *   · 不乱删东西：只改值，不删任何文件
 *   · 重启可恢复：见 restore()，App 启动时主动写回 "no"
 *
 * ══════════════════════════════════════════════════════════════
 * 需要什么权限
 * ══════════════════════════════════════════════════════════════
 *   Settings.System 的写操作需要 WRITE_SETTINGS（普通可授权权限）。
 *
 *   如果 dashcast 没被授予 WRITE_SETTINGS，会自动回落到
 *   `adb shell settings put system ...`（root 已有，一定能成功）。
 *
 *   两条路都失败才算真失败 —— 会明确打日志区分。
 */
public final class DimChannel {

    private static final String TAG = "DashcastDim";

    /** Settings.System 里的键名（地图 App 读的就是这个） */
    public static final String KEY = "display_on_dashboard";

    /* 三个合法值（大小写敏感，地图那边是 str.equals 精确比较） */
    public static final String VAL_OFF    = "no";       /* 不在仪表显示 */
    public static final String VAL_FULL   = "normal";   /* 完整地图 */
    public static final String VAL_AR     = "ar";       /* AR 实景导航 */

    private DimChannel() {}

    /**
     * 打开仪表盘地图。
     *
     * @param ctx  上下文
     * @param ar   true = "ar"（AR 实景），false = "normal"（完整地图）
     * @return 是否写成功
     */
    public static boolean open(Context ctx, boolean ar) {
        return put(ctx, ar ? VAL_AR : VAL_FULL);
    }

    /**
     * 关闭仪表盘地图 —— 恢复原厂行为。
     *
     * 这个必须能被随时调用，且**不能失败**：
     * 关不掉的话仪表会一直显示地图，用户的"重启恢复"硬性条件就破了。
     * 所以先试 Settings API，失败再走 adb。
     */
    public static boolean close(Context ctx) {
        return put(ctx, VAL_OFF);
    }

    /** 读当前值（只读，用于回显和排查） */
    public static String current(Context ctx) {
        try {
            String v = Settings.System.getString(ctx.getContentResolver(), KEY);
            return v == null ? "(未设置)" : v;
        } catch (Throwable t) {
            Log.w(TAG, "读 Settings 失败: " + t);
        }
        /* API 读不到就走 adb（read 不需要权限） */
        try {
            String out = AdbShell.exec("settings get system " + KEY, 3000);
            if (out == null) return "(读取失败)";
            String s = out.trim();
            if (s.isEmpty() || "null".equals(s)) return "(未设置)";
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "adb 读 Settings 失败: " + t);
            return "(读取失败)";
        }
    }

    /**
     * 写值：先试 Settings.System API，失败回落 adb。
     *
     * 为什么两条都要：
     *   · Settings API 干净、即时、不依赖 adbd 在线 —— 首选
     *   · 但需要 WRITE_SETTINGS，dashcast 未必有这个权限
     *   · adb 走 root（车机的 adbd 是 uid=0），一定能写
     */
    private static boolean put(Context ctx, String value) {
        /* ① 先试原生 API */
        try {
            boolean ok = Settings.System.putString(
                    ctx.getContentResolver(), KEY, value);
            if (ok) {
                Log.i(TAG, "Settings API 写入成功: " + KEY + " = " + value);
                return true;
            }
            Log.w(TAG, "Settings API 返回 false（多半缺 WRITE_SETTINGS），回落 adb");
        } catch (Throwable t) {
            Log.w(TAG, "Settings API 异常: " + t + "，回落 adb");
        }

        /* ② 回落 adb（root） */
        try {
            AdbShell.exec("settings put system " + KEY + " " + value, 4000);
            String now = current(ctx);
            boolean ok = now != null && now.trim().equals(value);
            Log.i(TAG, "adb 写入" + (ok ? "成功" : "后回读不符")
                    + ": " + KEY + " = " + value + "（当前 " + now + "）");
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "adb 写入也失败: " + t);
            return false;
        }
    }

    /**
     * 「逃生」恢复 —— 供 App 启动时无条件调用一次。
     *
     * 为什么启动要恢复：
     *   如果上次投屏异常退出（进程被杀、崩溃），Settings 可能还停在
     *   "normal"，仪表就会一直显示地图。用户的硬性条件是
     *   「重启就能恢复」，所以我们每次启动都主动写回 "no"。
     *
     * 注意：只在**没有正在投屏**时调用。否则会把用户刚开的
     * 原生仪表地图给关掉。调用方负责判断。
     */
    public static void restoreOnStartup(Context ctx) {
        String cur = current(ctx);
        if (VAL_OFF.equals(cur)) {
            Log.i(TAG, "启动检查：已是 " + VAL_OFF + "，无需恢复");
            return;
        }
        Log.i(TAG, "启动检查：发现残留值 " + cur + "，恢复为 " + VAL_OFF);
        close(ctx);
    }
}
