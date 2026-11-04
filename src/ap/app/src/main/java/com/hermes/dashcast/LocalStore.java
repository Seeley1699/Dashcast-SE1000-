package com.hermes.dashcast;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本地存储（Download/Dashcast/）。
 *
 * 2026-10-31 新增：预设配置和运行日志都落在手机本地 Download 目录，
 * **不放 CP**（CP 的 /run 是 tmpfs，重启即丢，而且车规要求 CP 不留痕）。
 *
 * 目录结构：
 *   /sdcard/Download/Dashcast/
 *       ├── presets.txt        三个预设槽（纯文本，可直接编辑/拷走）
 *       └── dashcast.log       运行日志（追加写，超过 2MB 自动轮转一次）
 */
public final class LocalStore {

    private static final String DIR_NAME = "Dashcast";
    /** 技术日志：DashLog 写入，相当于落盘的 logcat */
    private static final String LOG_FILE = "dashcast.log";
    /** 操作事件：界面上「运行日志」卡片显示的内容 */

    /** 日志轮转阈值：2MB（车机空间有限，别无限长） */
    private static final long LOG_MAX_BYTES = 2L * 1024 * 1024;

    private LocalStore() {}

    /**
     * 取 Download/Dashcast 目录。
     *
     * 车机 ROM 把 MANAGE_EXTERNAL_STORAGE 拒了（appops 显示 rejectTime），
     * 所以普通 mkdir 建不出公网 Download。三级兜底：
     *   ① 直接建 /sdcard/Download/Dashcast（有权限时最快）
     *   ② 用 su 建并 chmod（本 App 有 root 通道）
     *   ③ 退回 App 私有目录（保证功能可用）
     *
     * **结果必须缓存**：这台 ROM 上 ① 永远失败、② 是一次 adbd 往返
     * （超时 8000ms）。appendLog() 是从 setStatus() 的 mainHandler.post 里
     * 调进来的（主线程），每次状态更新都重跑一遍三级兜底 = 主线程被 root
     * shell 拖死，冷启动连发几个状态就直接 ANR。目录一旦确定不会再变，
     * 缓存到进程结束即可。
     */
    private static volatile File sDir;

    public static File dir(Context ctx) {
        File cached = sDir;
        if (cached != null && cached.exists()) return cached;

        synchronized (LocalStore.class) {
            if (sDir != null && sDir.exists()) return sDir;

            File pub = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
            if (pub.exists() || pub.mkdirs()) {
                sDir = pub;
                return pub;
            }

            /* ② 走 root 建目录（adb/su 通道）—— 只在首次失败时走一次 */
            try {
                AdbShell.exec("mkdir -p " + pub.getAbsolutePath()
                        + " && chmod 777 " + pub.getAbsolutePath(), 8000);
                if (pub.exists()) {
                    sDir = pub;
                    return pub;
                }
            } catch (Throwable ignored) {
            }

            /* ③ 私有目录兜底 */
            File prv = new File(ctx.getApplicationContext().getFilesDir(), DIR_NAME);
            if (!prv.exists()) prv.mkdirs();
            sDir = prv;
            return prv;
        }
    }

    /** 技术日志文件（dashcast.log）—— **唯一落盘的日志** */
    public static File logFile(Context ctx) {
        return new File(dir(ctx), LOG_FILE);
    }

    /**
     * 追加一行日志。失败静默 —— 日志写不进去不该影响主流程。
     *
     * **必须在后台写**：本方法由 appendLog() 主线程调用链进来，
     * 而 FileWriter 打开文件 + rotateIfNeeded 的 renameTo 都是磁盘 IO，
     * 车机的 eMMC 抖动时一次能吃掉几十到几百毫秒。
     * 用一条常驻线程 + 无界队列，主线程只做一个 offer。
     */
    private static final java.util.concurrent.ExecutorService LOG_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "local-log");
                t.setDaemon(true);
                return t;
            });

    /* ══════════════════════════════════════════════════════════════
     * **操作事件 → SharedPreferences**（2026-11-05 方案 B）
     * ══════════════════════════════════════════════════════════════
     *
     * 为什么不写 events.log 文件：
     *
     * 车机 ROM 的 FUSE 策略要求文件必须先在 MediaProvider 的
     * content://media/external/file 表里登记，App 才能通过
     * /storage/emulated/N/ 写入。用 root/shell 手工创建的文件绕过了登记，
     * 于是：
     *   · 属主对了（u11_a141）
     *   · 权限对了（600）
     *   · SELinux 标签也补对了（s0:c141,c256,c523,c768）
     *   · 但每次写还是报 `E MediaProvider: Couldn't find file: .../events.log`
     *   → FileWriter 打开失败，事件全丢。
     *
     * dashcast.log 能写，唯一差别就是它是 App 自己创建的，MediaProvider 里有记录。
     *
     * 而「事件」本来就只是界面上那 12 条环形缓冲，不需要文件持久化 ——
     * 放 prefs 最省事：永远写得进去、不需要权限、不产生文件。
     */

    /** 事件环形缓冲在 prefs 里的键前缀 */
    private static final String EV_KEY = "event_line_";
    /** 环形缓冲条数（和界面 LOG_MAX 一致） */
    private static final int EV_MAX = 12;

    /** 事件计数（用来定位下一个要覆盖的槽） */
    private static final String EV_SEQ = "event_seq";

    /**
     * 写一条**操作事件**（界面上「运行日志」显示的内容）。
     *
     * 只记「做了什么、结果如何」，例如「部署完成」「已应用预设 1」。
     * 技术细节（协议包、像素统计、异常栈）不写这里 —— 那是 DashLog 的活。
     */
    public static void log(Context ctx, final String msg) {
        if (msg == null || msg.isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        try {
            LOG_EXEC.execute(() -> writeEvent(app, msg));
        } catch (Throwable t) {
            /* 队列满了/已关闭：宁可丢这条日志，也绝不阻塞调用方 */
        }
    }

    private static void writeEvent(Context ctx, String msg) {
        try {
            android.content.SharedPreferences sp = ctx
                    .getSharedPreferences("dashcast", Context.MODE_PRIVATE);
            int seq = sp.getInt(EV_SEQ, 0);
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            sp.edit()
              .putString(EV_KEY + (seq % EV_MAX), ts + "  " + msg)
              .putInt(EV_SEQ, seq + 1)
              .apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读回最近的事件（按写入顺序，最旧的在前）。
     *
     * 界面启动时用这个填充「运行日志」卡片，让 App 重启后还能看到上次的操作。
     */
    public static java.util.List<String> readEvents(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            android.content.SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences("dashcast", Context.MODE_PRIVATE);
            int seq = sp.getInt(EV_SEQ, 0);
            int n = Math.min(seq, EV_MAX);
            for (int i = 0; i < n; i++) {
                int slot = (seq - n + i) % EV_MAX;
                String line = sp.getString(EV_KEY + slot, null);
                if (line != null) out.add(line);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 写一条**技术日志**（dashcast.log，相当于落盘的 logcat）。
     *
     * 由 DashLog 调用，line 已经自带时间戳和级别/标签，这里原样追加。
     */
    public static void logTech(Context ctx, final String line) {
        if (line == null || line.isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        try {
            LOG_EXEC.execute(() -> writeTech(app, line));
        } catch (Throwable ignored) {
        }
    }

    private static void writeTech(Context ctx, String line) {
        try {
            File f = logFile(ctx);
            rotateIfNeeded(f);
            FileWriter w = new FileWriter(f, true);
            w.write(line + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 超过阈值就改名成 dashcast.log.1（保留一代，旧的直接覆盖） */
    private static void rotateIfNeeded(File f) {
        if (!f.exists() || f.length() < LOG_MAX_BYTES) return;
        File bak = new File(f.getParentFile(), f.getName() + ".1");
        if (bak.exists()) bak.delete();
        f.renameTo(bak);
    }

    /** 读技术日志的尾部（导出排查时用）。 */
    public static String readTechLog(Context ctx, int maxChars) {
        File f = logFile(ctx);
        if (!f.exists()) return "";
        try {
            long len = f.length();
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long skip = Math.max(0, len - maxChars);
            raf.seek(skip);
            byte[] buf = new byte[(int) (len - skip)];
            raf.readFully(buf);
            raf.close();
            String s = new String(buf, "UTF-8");
            int nl = s.indexOf('\n');
            if (nl > 0 && skip > 0) s = s.substring(nl + 1);
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 清空日志。
     *
     * 「部署」按钮调用 —— 用户要求每次部署从干净状态开始记录：
     *   · dashcast.log 清空，之后写的都是本次部署之后的技术日志
     *   · 事件环形缓冲清空
     */
    public static void clearLog(Context ctx) {
        /* ① 清空技术日志文件 */
        try {
            FileWriter w = new FileWriter(logFile(ctx), false);
            w.write("");
            w.close();
        } catch (IOException ignored) {
        }
        /* ② 清空事件环形缓冲 */
        try {
            android.content.SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences("dashcast", Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor e = sp.edit();
            for (int i = 0; i < EV_MAX; i++) e.remove(EV_KEY + i);
            e.remove(EV_SEQ);
            e.apply();
        } catch (Throwable ignored) {
        }
    }
}
