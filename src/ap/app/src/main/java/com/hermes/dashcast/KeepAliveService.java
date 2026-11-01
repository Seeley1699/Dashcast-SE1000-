package com.hermes.dashcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * KeepAliveService —— 只做一件事：让进程留在前台。
 *
 * 为什么需要：镜像模式下用户大部分时间待在**别的 App**（比如影视仓）里，
 * 我们这个进程处在后台。一旦被系统判定为空进程回收，adbd 那边的 dcapd2
 * 还在跑，但没人管暂停/恢复了，前台守卫就失效。
 *
 * 一个前台服务就能把进程钉住，成本是一条通知。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "dashcast-mirror";
    private static final int NOTIF_ID = 2;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, KeepAliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }

        // intent == null ⇒ 系统把被杀的进程拉起来了（START_STICKY 重启）。
        // 用户之前在投屏（这个服务只会在点「开始投屏」时启动），所以把镜像
        // 按记住的目标自动续上 —— 不然进程一死投屏就断，还留下一堆孤儿
        // （旧 dcapd2 按旧目标推流、虚拟屏上挂着没人管的 task）。
        if (intent == null && !MirrorController.isMirroring()) {
            autoResume();
        }
        return START_STICKY;
    }

    /** 进程被杀后系统重启服务：按记住的目标把投屏续回来 */
    private void autoResume() {
        android.content.SharedPreferences p =
                getSharedPreferences(MainActivity.PREF, MODE_PRIVATE);
        final String pkg = p.getString("target_pkg", null);
        final String cls = p.getString("target_cls", null);
        if (pkg == null || cls == null) return;

        // 用户明明点了"停止投屏"，系统后来又把进程拉起来 —— 这种情况绝不能
        // 自作主张把画面再推上仪表，否则用户看到的就是"停了又自己回来了"。
        // casting_active 只在 start()/stop() 里翻转，是唯一的权威状态。
        if (!p.getBoolean("casting_active", false)) {
            Log.i("DashcastMirror", "进程被系统重启，但用户已停止投屏 —— 不自动恢复");
            return;
        }

        Log.i("DashcastMirror", "进程被系统重启，自动恢复投屏: " + pkg);
        // MirrorController 单例还没建（没有 Activity），这里建一个无界面的
        final MirrorController c = new MirrorController(
                getApplicationContext(), new CpManager(getApplicationContext()));
        // 工作线程跑：start() 里全是 adbd/SSH 网络调用
        new Thread(() -> {
            try {
                c.start(pkg, pkg + "/" + cls, null);
            } catch (Throwable t) {
                Log.w("DashcastMirror", "自动恢复投屏失败: " + t);
            }
        }, "mirror-resume").start();
    }

    private Notification build() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "仪表投屏", NotificationManager.IMPORTANCE_LOW));
            return new Notification.Builder(this, CHANNEL)
                    .setContentTitle("仪表投屏运行中")
                    .setContentText("单应用镜像 · 中控可正常操作")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("仪表投屏运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
