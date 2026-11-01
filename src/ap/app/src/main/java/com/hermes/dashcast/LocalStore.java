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
    private static final String LOG_FILE = "dashcast.log";
    private static final String PRESET_FILE = "presets.txt";

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

    public static File logFile(Context ctx) {
        return new File(dir(ctx), LOG_FILE);
    }

    public static File presetFile(Context ctx) {
        return new File(dir(ctx), PRESET_FILE);
    }

    /**
     * 追加一行日志（带时间戳）。失败静默 —— 日志写不进去不该影响主流程。
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

    public static void log(Context ctx, final String msg) {
        if (msg == null) return;
        final Context app = ctx.getApplicationContext();
        try {
            LOG_EXEC.execute(() -> writeNow(app, msg));
        } catch (Throwable t) {
            /* 队列满了/已关闭：宁可丢这条日志，也绝不阻塞调用方 */
        }
    }

    /** 同步写盘。只被后台线程调用（readLog/测试需要时也可直接调）。 */
    private static void writeNow(Context ctx, String msg) {
        try {
            File f = logFile(ctx);
            rotateIfNeeded(f);
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            FileWriter w = new FileWriter(f, true);
            w.write(ts + "  " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 超过阈值就改名成 dashcast.log.1（保留一代，旧的直接覆盖） */
    private static void rotateIfNeeded(File f) {
        if (!f.exists() || f.length() < LOG_MAX_BYTES) return;
        File bak = new File(f.getParentFile(), LOG_FILE + ".1");
        if (bak.exists()) bak.delete();
        f.renameTo(bak);
    }

    /** 读整个日志文件（供 UI 显示历史）。不存在返回空串。 */
    public static String readLog(Context ctx, int maxChars) {
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
            /* 从第一行完整处开始，避免半行 */
            int nl = s.indexOf('\n');
            if (nl > 0 && skip > 0) s = s.substring(nl + 1);
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 清空日志文件 */
    public static void clearLog(Context ctx) {
        try {
            FileWriter w = new FileWriter(logFile(ctx), false);
            w.write("");
            w.close();
        } catch (IOException ignored) {
        }
    }
}
