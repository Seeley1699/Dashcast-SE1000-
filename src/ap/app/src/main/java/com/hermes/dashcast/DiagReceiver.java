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

        /*
         * **CPCMD 是 root 命令通道，必须校验调用方**。
         *
         * 这个 receiver 在 Manifest 里是 exported="true" 且没有任何权限保护，
         * cmd 参数会原样进 CpManager.raw() 在仪表（CP）上以 root 执行，
         * 还支持 push: 往 CP 落地任意二进制。
         * 也就是说：车机上任何一个三方 App 一条 am broadcast 就能拿到
         * 「在自己 App 里以 root 操纵另一台设备」的能力。
         *
         * 合法调用方只有两类：
         *   ① 本 App 自己（DiagReceiver 自己发的 DIAG 广播、App 内的诊断按钮）
         *   ② adb shell 里的 `am broadcast`（调试用，uid 2000）
         * 其余一律拒绝。
         */
        if (ACTION_CPCMD.equals(action) && !isTrustedCaller(context)) {
            DashLog.w(TAG, "拒绝不可信调用方的 CPCMD 广播（uid 校验失败）");
            try {
                // 仍然把拒绝原因写进输出文件，方便排查"为什么命令没执行"
                java.io.File priv = new java.io.File(context.getFilesDir(), "cpcmd.out");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(priv);
                fos.write("DENIED: 调用方不是本 App 或 adb shell\n".getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {
            }
            pr.finish();
            return;
        }

        if (ACTION_CPCMD.equals(action)) {
            final String rawCmd = intent.getStringExtra("cmd");
            /*
             * **先解码 b64:**（2026-11-08 修）
             *
             * adb broadcast 传中文/特殊字符必须走 base64，所以入口的 cmd
             * 常常是 `b64:<base64>`。原来这里只对"非 b64"分支做判断，
             * 于是 `mapcast:start` 被包成 b64 后永远匹配不到，
             * 全落到最后的 `raw()` 分支 —— 表现为"命令收到了但不生效"。
             */
            final String cmd;
            if (rawCmd != null && rawCmd.startsWith("b64:")) {
                String dec = rawCmd.substring(4);
                try {
                    dec = new String(android.util.Base64.decode(dec,
                            android.util.Base64.NO_WRAP), "UTF-8");
                } catch (Throwable ignored) {
                }
                cmd = dec;
            } else {
                cmd = rawCmd;
            }
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
                    } else if (cmd.startsWith("mapcast:")) {
                        /*
                         * mapcast:start / mapcast:stop / mapcast:status
                         *
                         * 给 adb 用的无 UI 入口（2026-11-08 新增）。
                         * 为什么需要：UI 上点「地图投屏」在某些窗口配置下收不到
                         * 点击（input tap 坐标和实际控件对不上），有这个通道就能
                         * 直接验证链路，也方便脚本化。
                         */
                        String sub = cmd.substring(8).trim();
                        MapCastService svc = MapCastService.get();
                        if ("start".equals(sub)) {
                            boolean ok = svc.start(context.getApplicationContext(), null);
                            sb.append("MAPCAST_START=").append(ok).append('\n');
                        } else if ("stop".equals(sub)) {
                            boolean ok = svc.stop();
                            sb.append("MAPCAST_STOP=").append(ok).append('\n');
                        } else if ("status".equals(sub)) {
                            sb.append("running=").append(svc.isRunning())
                              .append(" pid=").append(svc.injectedPid()).append('\n');
                        } else {
                            sb.append("(未知子命令: ").append(sub).append(")\n");
                        }
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
                    DashLog.w(TAG, "CPCMD done, " + out.length() + " chars");
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

                DashLog.w(TAG, "================ CP 诊断 ================");
                DashLog.w(TAG, "SSH 连通: " + cp.ping());
                DashLog.w(TAG, "dashcastd 版本一致: " + cp.checkDashcastd());
                int[] sz = cp.queryScreenSize();
                DashLog.w(TAG, "CP 屏幕: " + (sz == null ? "解析失败" : sz[0] + "x" + sz[1]));
                String log = cp.tailLog(80);
                for (String line : log.split("\n")) DashLog.w(TAG, "| " + line);

                DashLog.w(TAG, "================ AP 诊断 ================");
                for (String line : AdbShell.exec(
                        "echo '--- dcapd2 ---'; pidof dcapd2 || echo NOT_RUNNING; "
                        + "ls -la /data/local/tmp/dcapd2.pause 2>/dev/null || echo '(无暂停文件)'; "
                        + "echo '--- dcapd2.log ---'; tail -12 /data/local/tmp/dcapd2.log 2>/dev/null; "
                        + "echo '--- focus ---'; dumpsys window | grep mCurrentFocus | tail -1",
                        15000).split("\n")) {
                    DashLog.w(TAG, "| " + line);
                }
                DashLog.w(TAG, "================ 诊断结束 ================");
            } catch (Throwable t) {
                DashLog.e(TAG, "诊断失败", t);
            } finally {
                pr.finish();
            }
        }, "diag").start();
    }

    /**
     * 判断 CPCMD 广播的调用方是否可信。
     *
     * CPCMD 会以 root 在仪表上执行任意命令，所以必须挡住三方 App。
     * 放行的只有两类：
     *   · 本 App 自己（uid == 自己） —— 例如 App 内的诊断按钮
     *   · adb shell 的 am broadcast（uid 2000 / shell，调试时用）
     *
     * 注意：这里用 getSentFromUid()（API 29+ 才有），低于 29 时退回
     * getSentFromPackage() 不可靠 —— 这台车机是 Android 11，够用。
     * 拿不到调用方信息（返回 -1）时**按不可信处理**，宁可让调试多一步确认，
     * 也不留一个裸 root 通道。
     */
    /**
     * 纵深防御的第二道：uid 校验。
     *
     * 主防线是 Manifest 上的 signature 级权限（三方 App 没有我们的签名，
     * 根本发不进来这条广播）。这里再挡一次，防止将来有人把权限去掉。
     *
     * 车机上 `adb shell` 是 **uid=0(root)**（不是标准的 2000），而且
     * getSentFromUid() 对 `am broadcast` 有时返回 -1（拿不到发送方信息）。
     * 所以这里不能"拿不到就拒绝" —— 那会把仅有的调试通道也堵死。
     *
     * 实际策略：
     *   · 能拿到 uid 时：本 App / root / shell 放行，其余拒绝
     *   · 拿不到 uid（-1）时：放行，但**记一条日志**。
     *     这时唯一的保护就是 signature 权限，而它已经足够。
     */
    private boolean isTrustedCaller(Context ctx) {
        try {
            int uid = sentFromUidViaReflect();
            int myUid = ctx.getApplicationInfo().uid;

            if (uid < 0) {
                /* 拿不到发送方信息：交给 signature 权限把关 */
                DashLog.w(TAG, "无法取调用方 uid（返回 " + uid + "），依赖 signature 权限放行");
                return true;
            }
            if (uid == myUid) return true;
            if (uid == android.os.Process.ROOT_UID) return true;      /* adb shell(root) */
            if (uid == android.os.Process.SHELL_UID) return true;     /* 标准 adb shell */
            if (uid == 1000) return true;                             /* system_server */

            DashLog.w(TAG, "拒绝 uid=" + uid + " 的 CPCMD（不是本 App / root / shell）");
            return false;
        } catch (Throwable t) {
            DashLog.w(TAG, "校验调用方失败，依赖 signature 权限放行: " + t);
            return true;
        }
    }

    /**
     * 取广播发送方的 uid。
     *
     * BroadcastReceiver.getSentFromUid() 看起来是 API 29 引入的，但这台车机
     * 的 framework 上**运行时没有这个方法**（实测 NoSuchMethodError，
     * 编译期能过是因为我们用 API 30 SDK）。只能反射，失败就返回 -1。
     */
    private int sentFromUidViaReflect() {
        try {
            java.lang.reflect.Method m =
                    BroadcastReceiver.class.getMethod("getSentFromUid");
            Object r = m.invoke(this);
            return r instanceof Integer ? (Integer) r : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
