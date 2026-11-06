package com.hermes.dashcast;

import android.content.Context;
import android.util.Log;

/**
 * ══════════════════════════════════════════════════════════════════
 * 双写日志：同时进 logcat 和 dashcast.log
 * ══════════════════════════════════════════════════════════════════
 *
 * **用户要求（2026-11-05）**：
 *   · dashcast.log      → 存 **logcat 技术日志**（开发者排查用）
 *   · events 日志       → 存 **用户可见的操作事件**（界面上「运行日志」显示）
 *
 * 原来 dashcast.log 里存的是操作事件（「部署完成」「已应用预设 1」这种），
 * 真正的技术细节（「虚拟屏 12 无帧」「HUD bounds=0x0」）只在 logcat 里，
 * 一重启就没了 —— 出了问题没法复盘。
 *
 * 现在拆成两个文件：
 *
 *   Download/Dashcast/
 *       ├── dashcast.log     技术日志（本类写入，带 级别/标签）
 *       └── events.log       操作事件（LocalStore.log 写入，界面显示）
 *
 * 用法：把项目里的 `Log.i(TAG, "…")` 换成 `DashLog.i(TAG, "…")` 即可，
 * 签名完全一致，不需要改调用方逻辑。
 *
 * 注意：**不能反过来依赖 logcat 命令**（App 内跑 logcat 要权限、要长驻进程、
 * 还要处理流式输出），所以直接在写 logcat 的同时写文件，是成本最低的做法。
 */
public final class DashLog {

    /** 技术日志文件名（和 events.log 区分开） */
    private static final String FILE = "dashcast.log";

    /** 写文件用的 Context，由 MainActivity/Application 注入一次即可 */
    private static volatile Context sCtx;

    /**
     * 关闭文件写入，只走 logcat。
     *
     * 某些场景（例如 App 未初始化、或用户只想清静）可以关掉。
     * 默认开启 —— 用户要的就是"重启后还能看到技术日志"。
     */
    private static volatile boolean sFileEnabled = true;

    private DashLog() {}

    /** 注入 Context（幂等，重复调用无副作用） */
    public static void init(Context ctx) {
        if (ctx != null) sCtx = ctx.getApplicationContext();
    }

    public static void setFileEnabled(boolean on) {
        sFileEnabled = on;
    }

    // ────────────────────────── 各级别 ──────────────────────────

    public static void v(String tag, String msg) {
        Log.v(tag, msg);
        toFile('V', tag, msg, null);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        toFile('D', tag, msg, null);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        toFile('I', tag, msg, null);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        toFile('W', tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable t) {
        Log.w(tag, msg, t);
        toFile('W', tag, msg, t);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        toFile('E', tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        toFile('E', tag, msg, t);
    }

    // ────────────────────────── 落盘 ──────────────────────────

    /**
     * 追加一行到技术日志。
     *
     * 走和 LocalStore.log 同一个单线程队列 —— 日志 IO 绝不能碰主线程。
     * 格式：`MM-dd HH:mm:ss.SSS  I/TAG  msg`
     * 和 logcat 的默认格式接近，习惯看 logcat 的人一眼能读懂。
     */
    private static void toFile(char level, String tag, String msg, Throwable t) {
        if (!sFileEnabled) return;
        Context ctx = sCtx;
        if (ctx == null) return;   /* 还没 init，静默跳过 */

        /*
         * **V/D 级不落文件**（2026-11-05 修复"日志还是太长"）。
         *
         * 原来所有级别都写文件，于是把 pkt 逐包日志降成 V 级之后
         * 依然写进了 dashcast.log —— 实测 8412 行里 7908 行还是 V 级噪音。
         *
         * 现在定规矩：
         *   V/D  → 只进 logcat（开发调试用，不落盘）
         *   I/W/E → 同时落盘（运维排查用）
         *
         * I 级就够了：业务关键节点（部署/投屏/切换/失败）全是 I；
         * W/E 是异常；D 是"每个 ADB 包、每次切换"这种过程细节，
         * 一个正常会话能刷几百行。
         */
        if (level == 'V' || level == 'D') return;

        final String line = buildLine(level, tag, msg, t);
        LocalStore.logTech(ctx, line);
    }

    private static String buildLine(char level, String tag, String msg, Throwable t) {
        String head = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                java.util.Locale.US).format(new java.util.Date());
        StringBuilder sb = new StringBuilder(160);
        sb.append(head).append("  ").append(level).append('/').append(tag)
          .append("  ").append(msg == null ? "" : msg);
        if (t != null) {
            sb.append('\n').append(t.toString());
            /* 只打印前几层，整车机的 z 栈太长了 */
            StackTraceElement[] st = t.getStackTrace();
            int n = Math.min(st == null ? 0 : st.length, 6);
            for (int i = 0; i < n; i++) {
                sb.append("\n    at ").append(st[i].toString());
            }
        }
        return sb.toString();
    }
}
