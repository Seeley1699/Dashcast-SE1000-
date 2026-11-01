package com.hermes.dashcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * DiagReceiver —— 联调用的「一键把两边状态吐到 logcat」。
 *
 * 为什么需要：仪表（CP）在 198.18.34.2 上，只有车机内部网能到，电脑连不上去。
 * 一旦画面不对，AP 侧只能看到"握手 OK、抓帧 30/s"，真正的错误（surface 分配失败、
 * dashcastd 是旧版本、图层被抢）全在 CP 的 /run/dashcastd.log 里，看不见就只能靠猜。
 *
 * 用法：
 *   adb shell am broadcast -a com.hermes.dashcast.DIAG
 *   adb logcat -s DashcastDiag
 */
public class DiagReceiver extends BroadcastReceiver {

    private static final String TAG = "DashcastDiag";

    /** 用法：am broadcast -a com.hermes.dashcast.CPCMD --es cmd "..." */
    private static final String ACTION_CPCMD = "com.hermes.dashcast.CPCMD";

    /** CP 命令的输出落这里，避开某些 ROM 吞第三方日志的问题 */
    private static final String CPCMD_OUT = "/data/local/tmp/cpcmd.out";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final PendingResult pr = goAsync();
        final String action = intent == null ? null : intent.getAction();

        if (ACTION_CPCMD.equals(action)) {
            final String cmd = intent.getStringExtra("cmd");
            new Thread(() -> {
                try {
                    // 先把"我收到了"写下来 —— 否则出问题时无法区分
                    // "广播没到" 和 "到了但命令为空/执行失败"。
                    StringBuilder sb = new StringBuilder();
                    sb.append("ACTION=").append(action).append('\n');
                    sb.append("CMD=").append(cmd == null ? "(null)" : cmd).append('\n');
                    sb.append("--- output ---\n");

                    if (cmd == null || cmd.trim().isEmpty()) {
                        sb.append("(空命令)\n");
                    } else if (cmd.startsWith("push:")) {
                        // push:<AP路径>:<CP路径>[:exec]
                        String[] p = cmd.split(":");
                        if (p.length < 3) {
                            sb.append("(push 参数不足)\n");
                        } else {
                            boolean ex = p.length > 3 && "exec".equals(p[3]);
                            CpManager cpm = new CpManager(context.getApplicationContext());
                            boolean ok = cpm.pushLocal(p[1], p[2], ex);
                            sb.append(ok ? "PUSH_OK " + p[2] + "\n"
                                    : "PUSH_FAIL " + p[1] + " :: " + cpm.lastPushError + "\n");
                        }
                    } else {
                        String run = cmd.startsWith("b64:")
                                ? "echo '" + cmd.substring(4) + "' | base64 -d | sh"
                                : cmd;
                        try {
                            sb.append(new CpManager(context.getApplicationContext()).raw(run, 20000));
                        } catch (Throwable t) {
                            sb.append("EXC: ").append(t).append('\n');
                        }
                    }

                    String out = sb.toString();
                    // 落盘顺序有讲究：App 私有目录是唯一**一定**写得进去的地方
                    // （/data/local/tmp 被 SELinux 拦、外部存储可能没挂载）。
                    // 读法：adb shell run-as com.hermes.dashcast cat files/cpcmd.out
                    try {
                        java.io.File priv = new java.io.File(context.getFilesDir(), "cpcmd.out");
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(priv);
                        fo.write(out.getBytes("UTF-8"));
                        fo.close();
                    } catch (Throwable ignored) {
                    }
                    try {
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(CPCMD_OUT);
                        fo.write(out.getBytes("UTF-8"));
                        fo.close();
                    } catch (Throwable ignored) {
                    }
                    Log.w(TAG, "CPCMD done, " + out.length() + " chars");
                } finally {
                    pr.finish();
                }
            }, "cpcmd").start();
            return;
        }

        new Thread(() -> {
            try {
                Context app = context.getApplicationContext();
                CpManager cp = new CpManager(app);

                Log.w(TAG, "================ CP 诊断 ================");
                Log.w(TAG, "SSH 连通: " + cp.ping());
                Log.w(TAG, "dashcastd 版本一致: " + cp.checkDashcastd());
                int[] sz = cp.queryScreenSize();
                Log.w(TAG, "CP 屏幕: " + (sz == null ? "解析失败" : sz[0] + "x" + sz[1]));
                String log = cp.tailLog(80);
                for (String line : log.split("\n")) Log.w(TAG, "| " + line);

                Log.w(TAG, "================ AP 诊断 ================");
                for (String line : AdbShell.exec(
                        "echo '--- dcapd2 ---'; pidof dcapd2 || echo NOT_RUNNING; "
                        + "ls -la /data/local/tmp/dcapd2.pause 2>/dev/null || echo '(无暂停文件)'; "
                        + "echo '--- dcapd2.log ---'; tail -12 /data/local/tmp/dcapd2.log 2>/dev/null; "
                        + "echo '--- focus ---'; dumpsys window | grep mCurrentFocus | tail -1",
                        15000).split("\n")) {
                    Log.w(TAG, "| " + line);
                }
                Log.w(TAG, "================ 诊断结束 ================");
            } catch (Throwable t) {
                Log.e(TAG, "诊断失败", t);
            } finally {
                pr.finish();
            }
        }, "diag").start();
    }
}
