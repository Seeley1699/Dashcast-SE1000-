package com.hermes.dashcast;

import android.app.Application;
import android.content.SharedPreferences;

/**
 * ══════════════════════════════════════════════════════════════════
 * Application —— 全局初始化
 * ══════════════════════════════════════════════════════════════════
 *
 * **为什么需要它（2026-11-05）**：
 *
 * DashLog 要把技术日志双写到 logcat + Download/Dashcast/dashcast.log，
 * 但写文件需要 Context。原来只在 MainActivity.onCreate 里 init，
 * 于是这些场景全都没有技术日志：
 *
 *   · KeepAliveService 启动（App 被系统从服务拉起，MainActivity 根本没跑）
 *   · DashcastSenderService 启动（同上）
 *   · DiagReceiver 收到命令（adb 广播直接打到 receiver）
 *
 * 出问题时恰恰是最需要日志的那些场景，日志却是空的。
 *
 * Application.onCreate 在**任何组件之前**执行，放这里一劳永逸。
 *
 * 注意：Application 里不能做重活 —— 它跑在主线程，卡住就是白屏。
 * 这里只做一次引用赋值，成本可忽略。
 */
public class DashcastApp extends Application {

    /** 上次运行时看到的 APK 安装时间（用于识别"是不是新部署"） */
    private static final String PREF_LAST_INSTALL = "last_install_time";

    @Override
    public void onCreate() {
        super.onCreate();

        /*
         * ★ **新部署时清空自己的日志**（2026-11-09）
         *
         * 用户要求：「App 部署时清空 Download/Dashcast/*.log」。
         *
         * 原来只有点 App 里的「部署」按钮才会清（MainActivity 里那条
         * LocalStore.clearLog）。但 `adb install -r` 装新包这条路径不清 ——
         * 新版本第一次启动时，日志里全是上个版本的记录，排查时
         * 根本分不清哪条是这次的。
         *
         * 判定方式：`PackageInfo.lastUpdateTime`
         *   · adb install -r      → lastUpdateTime 一定变  → 清空 ✓
         *   · 普通重启 App        → 不变                    → 不清 ✓
         *   · 车机重启            → 不变                    → 不清 ✓
         *   · 手动点「部署」按钮  → 不变（已有单独的清空）  → 不清 ✓
         *
         * 用 lastUpdateTime 而不是 firstInstallTime：
         * 后者只在**首次安装**时变，覆盖升级时不动，识别不到部署。
         *
         * 清空放在 DashLog.init() **之后**（要 Context 定位文件），
         * 但在任何业务日志之前 —— 这样"应用启动"那条就是新日志的第一行。
         */
        boolean newInstall = false;
        try {
            long installTime = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).lastUpdateTime;
            SharedPreferences sp = getSharedPreferences(
                    MainActivity.PREF, MODE_PRIVATE);
            long lastSeen = sp.getLong(PREF_LAST_INSTALL, 0);
            if (installTime != lastSeen) {
                newInstall = true;
                sp.edit().putLong(PREF_LAST_INSTALL, installTime).apply();
                if (lastSeen != 0) {
                    /* 不是首次安装（说明是覆盖升级）→ 清掉旧日志 */
                    LocalStore.clearLog(this);
                }
            }
        } catch (Throwable ignored) {
            /* 拿不到包信息就跳过 —— 不能因为清日志失败把 App 卡在启动 */
        }

        DashLog.init(this);
        DashLog.i("DashcastApp", "应用启动 pid=" + android.os.Process.myPid()
                + (newInstall ? "（新部署，日志已清空）" : "")
                + " 日志目录=" + LocalStore.dir(this).getAbsolutePath());
    }
}
