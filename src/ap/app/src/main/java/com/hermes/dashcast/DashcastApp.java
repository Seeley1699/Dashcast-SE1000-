package com.hermes.dashcast;

import android.app.Application;

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

    @Override
    public void onCreate() {
        super.onCreate();
        DashLog.init(this);
        DashLog.i("DashcastApp", "应用启动 pid=" + android.os.Process.myPid()
                + " 日志目录=" + LocalStore.dir(this).getAbsolutePath());
    }
}
