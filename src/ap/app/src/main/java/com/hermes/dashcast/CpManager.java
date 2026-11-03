/*
 * CpManager.java — CP 侧操作封装（推文件 / 启动 / 停止 dashcastd）
 *
 * 通道：JSch（标准 SSH 客户端库）直连 CP dropbear（198.18.34.2:22，root/空密码）。
 *
 * 推文件策略：把 dashcastd 二进制 base64 编码后，通过 SSH exec 通道
 *   base64 -d > /run/dashcastd 落盘，再 chmod +x。避开 sftp/scp 子协议。
 *
 * 启动/停止/状态也都走 exec 通道。
 */
package com.hermes.dashcast;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

public class CpManager {

    private static final String TAG = "DashcastCp";
    private static final String HOST = "198.18.34.2";
    private static final int PORT = 22;
    private static final String USER = "root";

    private static final String CP_PATH = "/run/dashcastd";
    private static final String CP_B64_PATH = "/run/dashcastd.b64";

    private static final String CP_GZ_PATH = CP_PATH + ".gz";   /* 临时压缩包 */

    /** 上次「图层不存在」时的矩形，用于抑制重复日志（避免保活循环刷屏） */
    private static volatile String sLastNoLayerRect = "";
    /** 投屏用的 ivi 图层号（须与 dashcastd.c 的 LAYER_ID 一致） */
    private static final int LAYER_ID_CAST = 99;

    private final Context context;

    /** 本地 dashcastd 二进制的 MD5（构造时算一次，全程缓存，避免每次 check 都解 base64） */
    private final String localMd5;

    /** 推送/启停互斥锁，防止多线程并发触发重复推送或重复启动 */
    private final Object pushLock = new Object();

    /** 最近一次推送失败的原因（联调用，写进 cpcmd.out 方便看） */
    public volatile String lastPushError = "";

    public CpManager(Context context) {
        this.context = context;
        this.localMd5 = computeLocalMd5();
    }

    /** 算本地 assets/dashcastd.b64 解出的二进制 MD5，失败返回 null */
    /**
     * 本地 dashcastd 的 MD5，**进程级缓存**。
     *
     * 为什么要缓存（实测）：这个函数要读 715KB 的 assets/dashcastd.b64、
     * base64 解码、再算 MD5，单次约 1.4 秒。而 CpManager 在 MainActivity、
     * LayerEditorActivity、KeepAliveService、DiagReceiver 里都会被 new，
     * 构造函数里就调它 —— 一次投屏实测触发 8 次，白等 11 秒。
     *
     * 资产随 APK 打包，进程存活期内不会变，静态缓存是安全的。
     */
    private static volatile String sLocalMd5 = null;

    private String computeLocalMd5() {
        String cached = sLocalMd5;
        if (cached != null) return cached;

        synchronized (CpManager.class) {
            if (sLocalMd5 != null) return sLocalMd5;
            try {
                String b64 = readAssetText("dashcastd.b64");
                if (b64 == null || b64.length() == 0) return null;
                byte[] bin = Base64.decode(b64.replaceAll("\\s+", ""), Base64.DEFAULT);
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(bin);
                StringBuilder sb = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                    sb.append(Character.forDigit(b & 0xF, 16));
                }
                sLocalMd5 = sb.toString();
                Log.i(TAG, "本地 dashcastd MD5 = " + sLocalMd5 + " (" + bin.length
                        + " 字节，已缓存，后续复用)");
                return sLocalMd5;
            } catch (Exception e) {
                Log.w(TAG, "计算本地 MD5 失败: " + e.getMessage());
                return null;
            }
        }
    }

    /** 建一条 SSH session（root/空密码，不校验 host key） */
    /*
     * SSH 连接复用池（2026-10-31）。
     *
     * 实测问题：原来每个操作都 acquireSession()，一次投屏流程触发了 13 次建连，
     * 日志里全是 "SSH 已连 198.18.34.2:22"，每条耗时 ~0.6 秒（dropbear
     * 建连 + 认证），而且多个线程会并发建连，看着像"疯狂重连"。
     *
     * JSch 的 Session 本身支持在一条 TCP 上开多个 Channel（并发或串行），
     * 所以正确的做法是**复用 Session**，每次只开 Channel：
     *   · 建连成本从 0.6 秒降到 0（Channel 开销约 5~10ms）
     *   · 并发不再重复建连，日志干净
     *
     * 失效处理：Session 可能因 CP 重启/网络抖动断开，用 isConnected() 检查，
     * 断了就重建。重建时清空旧引用，避免用到坏 Session。
     */
    private Session sSharedSession = null;

    /** 拿一条可用的 Session：优先复用，断了才重建 */
    private synchronized Session acquireSession() throws Exception {
        if (sSharedSession != null && sSharedSession.isConnected()) {
            return sSharedSession;
        }
        /* 旧的可能已经处于半死状态，先彻底断开 */
        if (sSharedSession != null) {
            try { sSharedSession.disconnect(); } catch (Throwable ignored) {}
            sSharedSession = null;
        }
        JSch jsch = new JSch();
        Session session = jsch.getSession(USER, HOST, PORT);
        session.setPassword("");
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.setTimeout(20000);
        session.connect(20000);
        sSharedSession = session;
        Log.i(TAG, "SSH 已连 " + HOST + ":" + PORT + "（建立共享 Session，后续复用）");
        return session;
    }

    /** 关闭共享 Session（投屏结束/停止服务时调用，释放连接） */
    public synchronized void closeSession() {
        if (sSharedSession != null) {
            try { sSharedSession.disconnect(); } catch (Throwable ignored) {}
            sSharedSession = null;
            Log.i(TAG, "SSH 共享 Session 已关闭");
        }
    }

    /** 在已有 session 上执行命令（复用连接） */
    private String execOnSession(Session session, String command) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        InputStream in = channel.getInputStream();
        channel.connect(20000);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (true) {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            if (channel.isClosed()) {
                if (in.available() > 0) continue;
                break;
            }
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        channel.disconnect();
        return out.toString("UTF-8");
    }

    /**
     * 推 dashcastd 二进制到 CP。
     *
     * 走 SSH **stdin 管道**：channel 建好后把整份 base64 写进 stdin，
     * 远端 `base64 -d > /run/dashcastd` 直接从 stdin 读。
     *
     * 为什么不用"分片 exec 追加"（旧实现）：dropbear 每条 exec 命令都有长度上限，
     * 700KB 的二进制要 179 条命令，任何一条被截断/超时都会静默产出残缺文件，
     * 而远端 md5sum 照样有输出 —— 结果就是"提示推送成功，CP 上还是旧版本"。
     * 改 stdin 管道后只有一次命令，无截断风险，速度快几十倍。
     */
    /**
     * 推 dashcastd 二进制到 CP。
     *
     * 走**分片 exec 追加**（不是 stdin 管道）。
     *
     * 为什么放弃 stdin 管道：JSch 的 ChannelExec 不支持写 stdin，
     * 每次 getOutputStream().write() 都抛 "channel is broken"。
     * 试过调换 connect 顺序、去掉 setInputStream，都不行 —— 这是 JSch
     * exec 通道的固有限制（它只给 stdout/stderr，不给 stdin）。
     *
     * 分片方案的关键（之前栽过的地方）：
     *   · 每片写完立刻回读文件大小，对不上就停 —— 不能闷头推完再看
     *   · 分片降到 4000 字符（6000 偶发被 dropbear 截断）
     *   · 最后整体 md5 比对，不一致直接报失败
     */
    public boolean pushDashcastd() {
        synchronized (pushLock) {
            Session session = null;
            try {
                String b64 = readAssetText("dashcastd.b64");
                if (b64 == null || b64.length() == 0) {
                    Log.e(TAG, "assets/dashcastd.b64 读取失败");
                    return false;
                }
                b64 = b64.replaceAll("\\s+", "");
                byte[] bin = Base64.decode(b64, Base64.DEFAULT);
                String wantMd5 = md5Hex(bin);

                /*
                 * **gzip 压缩后再传**。
                 *
                 * 实测：dashcastd 535536 字节，gzip -6 后 246388 字节（46%）。
                 * 传输量减半 → base64 后 328520 字符（原 714048）→
                 * 分片数 83（原 179）→ 批量命令 42 条（原 89）→
                 * 推送耗时 3.6 秒（原 7.6 秒）。
                 *
                 * CP 侧有 /bin/gzip，用 `gzip -dc` 解到 stdout（比 gzip -d 安全，
                 * 不依赖 .gz 后缀）。
                 *
                 * md5 校验对象仍是**解压后的二进制**，所以 wantMd5 不变。
                 */
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(bin.length / 2 + 1024);
                java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos, 8192);
                gz.write(bin);
                gz.close();
                byte[] gzBytes = bos.toByteArray();
                bos.close();

                String gzB64 = Base64.encodeToString(gzBytes, Base64.NO_WRAP);
                Log.i(TAG, "dashcastd " + bin.length + " 字节 → gzip " + gzBytes.length
                        + " 字节（" + (gzBytes.length * 100 / bin.length) + "%），base64 "
                        + gzB64.length() + " 字符 (md5=" + wantMd5 + ")，开始推送");

                session = acquireSession();
                execOnSession(session, "rm -f " + CP_PATH + " " + CP_B64_PATH + " " + CP_GZ_PATH);

                /* 分片追加，每片 4000 字符 */
                /*
                 * 批量分片：**把多片合并成一条 SSH 命令**。
                 *
                 * 实测瓶颈：一次 SSH 往返固定 ~0.6 秒，与数据量无关：
                 *   空命令 0.647s / 追加 4000 字符 0.609s / wc -c 0.593s
                 * 所以耗时 = 命令条数 × 0.6s。原来 120 片 + 12 次校验 ≈ 130 条
                 * 命令 → 约 78 秒（实测 25 秒，部分被合并了）。
                 *
                 * 改法：每 4 片拼成一条 "printf A >> f; printf B >> f; ..." 命令，
                 * 一条命令内多片并发写入，往返次数直接降到 1/4。
                 *
                 * 为什么用 4 而不是更大：dropbear 单条 exec 命令长度有上限，
                 * 4×4000=16000 字符的命令约 16KB，是实测安全区（曾经 6000×1
                 * 也就是 6KB 都偶发被截，16KB 反而没问题 —— 上限在 32KB 附近）。
                 * 保守取 4 片，兼顾速度与可靠。
                 */
                int CH = 4000;
                int PIECES_PER_CMD = 2;
                long expect = 0;
                int sentCmds = 0;

                StringBuilder cmdBuf = new StringBuilder(20000);
                int piecesInCmd = 0;

                for (int i = 0; i < gzB64.length(); i += CH) {
                    String piece = gzB64.substring(i, Math.min(gzB64.length(), i + CH));
                    cmdBuf.append("printf %s \'").append(piece).append("\' >> ")
                          .append(CP_B64_PATH).append("; ");
                    expect += piece.length();
                    piecesInCmd++;

                    /* 凑够 4 片就发一条命令 */
                    if (piecesInCmd >= PIECES_PER_CMD) {
                        execOnSession(session, cmdBuf.toString());
                        cmdBuf.setLength(0);
                        piecesInCmd = 0;
                        sentCmds++;

                        /* 每 30 批（120 片）校验一次大小 */
                        if (sentCmds % 30 == 0) {
                            String sz = execOnSession(session, "wc -c " + CP_B64_PATH).trim();
                            long got = 0;
                            try { got = Long.parseLong(sz.split("\\s+")[0]); } catch (Throwable ignored) {}
                            if (got < expect * 9 / 10) {
                                Log.e(TAG, "分片丢失: 期望 " + expect + " 实际 " + got + "，中止");
                                lastPushError = "分片丢失 @" + got + "/" + expect;
                                return false;
                            }
                            expect = got;
                        }
                    }
                }
                /* 收尾：最后不满 4 片的部分 */
                if (cmdBuf.length() > 0) {
                    execOnSession(session, cmdBuf.toString());
                    cmdBuf.setLength(0);
                }

                /* 解码 + 校验合并成一条命令，少一次往返 */
                /* 收尾：base64 解码 → gzip 解压 → chmod → md5 校验，一条命令走完 */
                String tail = "base64 -d " + CP_B64_PATH + " > " + CP_GZ_PATH
                        + " && gzip -dc " + CP_GZ_PATH + " > " + CP_PATH
                        + " && chmod 755 " + CP_PATH
                        + " && rm -f " + CP_B64_PATH + " " + CP_GZ_PATH
                        + " && md5sum " + CP_PATH + " 2>/dev/null";
                String res = execOnSession(session, tail).trim();
                String remoteMd5 = res.isEmpty() ? "" : res.split("\\s+")[0];
                boolean ok = remoteMd5.equalsIgnoreCase(wantMd5);
                Log.i(TAG, "落盘校验: 远端 " + remoteMd5 + " vs 本地 " + wantMd5
                        + (ok ? " (一致)" : " (不一致!)")
                        + "（本次共 " + sentCmds + " 条批量命令）");
                if (!ok) lastPushError = "md5 不一致: " + remoteMd5 + " / " + wantMd5;
                return ok;
            } catch (Exception e) {
                Log.e(TAG, "推送 dashcastd 失败: " + e.getMessage(), e);
                lastPushError = String.valueOf(e);
                return false;
            } finally {
                /* 共享 Session 不断开，交给 closeSession() 统一管理 */
            }
        }
    }

    /** 启动 dashcastd（带环境变量，后台运行；先 killall 防多实例；用 pidof 确认真的起来了） */
    /**
     * CP 侧 dashcastd 是否**正在运行**（与 checkDashcastd 不同：那个只看文件）。
     *
     * 为什么要单独一个：文件在 /run（tmpfs）上，只要 CP 没重启文件就一直在，
     * 但进程可能被杀（用户点了「停止服务」、OOM、崩溃）。这时 checkDashcastd
     * 返回 true 却没人服务 8444，App 会一直以为在投屏，图层矩形永远设不上。
     */
    public boolean isDashcastdRunning() {
        try {
            String out = execOnSession(acquireSession(), "pidof dashcastd 2>/dev/null");
            return out != null && !out.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean startDashcastd() {
        synchronized (pushLock) {
            try {
                // 没有二进制就别自欺欺人地回 STARTED（以前 echo STARTED 无条件执行，
                // 文件被「停止服务」删掉后仍然报成功，结果车机一直连不上 CP）
                String cmd = "if [ ! -x " + CP_PATH + " ]; then echo NOBIN; else "
                        // 先按 pid 强杀：以前只用 killall，偶尔杀不掉（进程名/权限），
                        // 结果旧进程继续占着 8444 和 ivi surface 9001，
                        // 新进程报 'Address already in use' + 'surface_id is already assigned'，
                        // 而旧进程是老版本，握手后直接断开 —— 表现就是"握手成功但一帧都发不出去"。
                        + "OLD=$(pidof dashcastd); "
                        + "if [ -n \"$OLD\" ]; then kill -9 $OLD 2>/dev/null; sleep 0.6; fi; "
                        + "export XDG_RUNTIME_DIR=/etc/weston/weston_xdg_runtime_dir; "
                        + "export WAYLAND_DISPLAY=wayland-1; "
                        + "nohup " + CP_PATH + " >/run/dashcastd.log 2>&1 & "
                        + "sleep 1.2; "
                        + "NEW=$(pidof dashcastd); "
                        + "if [ -n \"$NEW\" ]; then echo STARTED,$NEW; else echo FAILED; fi; "
                        + "fi";
                Session session = acquireSession();
                try {
                    String out = execOnSession(session, cmd);
                    Log.i(TAG, "启动结果: " + out.trim());
                    return out.contains("STARTED");
                } finally {
                    /* 共享 Session：不断开，由 closeSession() 管理 */
                }
            } catch (Exception e) {
                Log.e(TAG, "启动 dashcastd 失败: " + e.getMessage(), e);
                return false;
            }
        }
    }

    /**
     * 确保 CP 上有一份可执行的 dashcastd。
     *
     * v2.9.1 起策略调整：**md5 不一致不再阻断**。
     *
     * 原因：图层矩形（destination region）已改由 App 从外部用
     * LayerManagerControl 设置（见 setLayerRect），不再依赖 CP 侧二进制。
     * 而 CP 上的 dashcastd 只有车机内网可达，App 侧大文件 SSH 推送不稳
     * （dropbear exec 长度限制，700KB 分片易被截断）。
     *
     * 所以：能推就推（拿最新版），推不动只要 CP 上已有可执行文件就继续用，
     * 只是记一条警告 —— 宁可跑旧版也不让投屏起不来。
     */
    public boolean ensureDashcastd() {
        if (checkDashcastd()) return true;              /* 版本一致，直接用 */

        Log.w(TAG, "CP 上 dashcastd 缺失或版本不一致，尝试推送");
        if (pushDashcastd()) return true;

        /* 推送失败：只要 CP 上有可执行文件就继续（旧版也能投屏，图层矩形走外部命令） */
        try {
            Session session = acquireSession();
            try {
                String out = execOnSession(session, "test -x " + CP_PATH + " && echo HAVE || echo NONE");
                if (out != null && out.contains("HAVE")) {
                    Log.w(TAG, "推送失败，但 CP 上已有可执行的 dashcastd —— 继续使用旧版"
                            + "（图层矩形由 setLayerRect 外部设置，功能不受影响）");
                    return true;
                }
                Log.e(TAG, "CP 上既没有可执行文件、推送也失败");
                return false;
            } finally {
                /* 共享 Session：不断开，由 closeSession() 管理 */
            }
        } catch (Throwable t) {
            Log.e(TAG, "检查 CP 二进制失败: " + t);
            return false;
        }
    }

    /** 停止 dashcastd 并删除推送的文件，恢复 CP 原样（图层随进程退出自动消失） */
    public boolean stopDashcastd() {
        synchronized (pushLock) {
            try {
                Session session = acquireSession();
                try {
                    String out = execOnSession(session,
                            "killall dashcastd 2>/dev/null; "
                            + "rm -f " + CP_PATH + " " + CP_B64_PATH + " /run/dashcastd.log; "
                            + "echo DONE");
                    Log.i(TAG, "停止结果: " + out.trim() + "（已删除推送文件）");
                    return out.contains("DONE");
                } finally {
                    /* 共享 Session：不断开，由 closeSession() 管理 */
                }
            } catch (Exception e) {
                Log.e(TAG, "停止 dashcastd 失败: " + e.getMessage(), e);
                return false;
            }
        }
    }

    /**
     * 强制把仪表还原：render order 里摘掉 99，再销毁 layer 99，带验证重试。
     *
     * 为什么不能只靠 dashcastd 自己的 cleanup：它还原的是「自己启动时存下来的
     * 那张表」。那张表一旦被污染（上一次没清干净，表里已经带 99），之后每次
     * "还原"都会把 99 又写回去 —— 表现就是点停止投屏，仪表一直停在最后一帧。
     *
     * @return 验证通过（render order 里已经没有 99）
     */
    public boolean restoreDashboard() {
        // WAYLAND_DISPLAY 千万别硬编码：猜错一个号，整套 LayerManagerControl
        // 都打在一个不存在的合成器上 —— set/destroy 全部静默失败，
        // 而 get 还能返回一份看起来正常的输出，"验证=干净"纯属自我安慰。
        // 先到 Weston 的 runtime 目录里把真实 socket 名抄出来。
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Session session = acquireSession();
                try {
                    String sock = execOnSession(session,
                            "ls /etc/weston/weston_xdg_runtime_dir/ 2>/dev/null"
                            + " | grep -oE 'wayland-[0-9]+' | head -1").trim();
                    if (sock.isEmpty()) sock = "wayland-1";
                    final String env = "export XDG_RUNTIME_DIR=/etc/weston/weston_xdg_runtime_dir; "
                            + "export WAYLAND_DISPLAY=" + sock + "; ";
                    String dump = execOnSession(session,
                            env + "LayerManagerControl get screen 0 2>&1");
                    int[] ids = parseRenderOrder(dump);

                    boolean has99 = false;
                    StringBuilder order = new StringBuilder();
                    for (int id : ids) {
                        if (id == LAYER_ID_CAST) { has99 = true; continue; }
                        if (order.length() > 0) order.append(' ');
                        order.append(id);
                    }
                    if (order.length() == 0) order.append("10");

                    // 先摘表：即使 destroy 失败，图层不在 render order 里就不会被合成，
                    // 画面立刻回到原厂仪表
                    execOnSession(session,
                            env + "LayerManagerControl set screen 0 render order " + order);
                    execOnSession(session,
                            env + "LayerManagerControl destroy layer " + LAYER_ID_CAST);

                    String after = execOnSession(session,
                            env + "LayerManagerControl get screen 0 2>&1");
                    int[] ids2 = parseRenderOrder(after);
                    boolean clean = true;
                    for (int id : ids2) if (id == LAYER_ID_CAST) clean = false;

                    Log.i(TAG, "还原仪表（第 " + (attempt + 1) + " 次）: 原表含99=" + has99
                            + " → 设为 [" + order + "] 验证=" + (clean ? "干净" : "仍含99"));
                    if (clean) return true;
                } finally {
                    /* 共享 Session：不断开，由 closeSession() 管理 */
                }
            } catch (Throwable t) {
                Log.w(TAG, "还原仪表失败（第 " + (attempt + 1) + " 次）: " + t);
            }
        }
        return false;
    }

    /** /run/dashcastd.log 的行数；读不到（文件不存在等）返回 -1 */
    public int dashcastdLogLines() {
        try {
            Session session = acquireSession();
            try {
                String out = execOnSession(session,
                        "wc -l < /run/dashcastd.log 2>/dev/null").trim();
                return Integer.parseInt(out);
            } finally {
                /* 共享 Session：不断开，由 closeSession() 管理 */
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 杀掉 dcapd2 之后，dashcastd **必须**在自己的日志里留下"会话结束"——
     * 那是图层真正被清理的唯一权威证据。LayerManagerControl 在 App 的 SSH
     * 会话里读不到动态建的层（这台 ROM 只列启动时就存在的层），
     * 靠它验证等于没验 —— 这就是"点了停止、画面还留在仪表上"的盲区。
     *
     * @param linesBefore 杀进程前的日志行数
     * @return true = 看到了新的"会话结束"
     */
    public boolean verifySessionEnded(int linesBefore, long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                Session session = acquireSession();
                String out;
                try {
                    out = execOnSession(session,
                            "tail -n +" + (linesBefore + 1) + " /run/dashcastd.log 2>/dev/null"
                            + " | grep -c '会话结束'").trim();
                } finally {
                    /* 共享 Session：不断开，由 closeSession() 管理 */
                }
                if (out != null && out.length() > 0 && Integer.parseInt(out.trim()) > 0) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }
        return false;
    }

    /** 从 `LayerManagerControl get screen 0` 输出里抠出 render order 的层号 */    private static int[] parseRenderOrder(String dump) {
        if (dump == null) return new int[0];
        int k = dump.indexOf("render order");
        if (k < 0) k = dump.indexOf("render_order");
        if (k < 0) return new int[0];
        int eol = dump.indexOf('\n', k);
        String line = eol > 0 ? dump.substring(k, eol) : dump.substring(k);

        // 形如 "10(0xa), 99(0x63)," → 取每个 N(0x 里的 N
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*\\(0x").matcher(line);
        int[] tmp = new int[32];
        int n = 0;
        while (m.find() && n < tmp.length) {
            try { tmp[n++] = Integer.parseInt(m.group(1)); } catch (Throwable ignored) {}
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /**
     * 校验 CP 上的 dashcastd 是否**可执行且 MD5 与本地一致**。
     *
     * 注意：这个函数只判「文件就绪」，不判「进程在跑」。
     * 进程启动由 startDashcastd() 负责，两步都在 MainActivity 里串联调用。
     */
    public boolean checkDashcastd() {
        try {
            String out = execOnSession(acquireSession(),
                    "test -x " + CP_PATH + " && md5sum " + CP_PATH
                    + " || echo NOEXEC");
            out = out == null ? "" : out.trim();

            if (out.contains("NOEXEC") || out.isEmpty()) {
                Log.i(TAG, "dashcastd 不存在或不可执行，需推送");
                return false;
            }

            String remoteMd5 = out.split("\\s+")[0];
            boolean match = localMd5 != null && remoteMd5.equalsIgnoreCase(localMd5);
            Log.i(TAG, "dashcastd 可执行, 远端 MD5 " + remoteMd5 + " vs 本地 "
                    + localMd5 + (match ? " (一致)" : " (不一致，需重推)"));
            return match;
        } catch (Exception e) {
            Log.w(TAG, "校验 dashcastd 失败（可能未推过）: " + e.getMessage());
            return false;
        }
    }

    /**
     * 读 CP 上 dashcastd 的日志尾部 + 进程状态。
     * 推流断了的时候，AP 侧只能看到 ECONNREFUSED，真正原因在这里。
     */
    public String tailLog(int lines) {
        try {
            Session session = acquireSession();
            try {
                return execOnSession(session,
                        "echo '--- dashcastd.log (tail " + lines + ") ---'; "
                        + "tail -n " + lines + " /run/dashcastd.log 2>/dev/null; "
                        + "echo '--- pidof ---'; "
                        + "pidof dashcastd || echo NOT_RUNNING; "
                        // LayerManagerControl 要连 Weston，得带上 dashcastd 同款环境变量，
                        // 否则 SSH exec 里跑它只会输出空 —— 看着像"查不到"，其实是没连上
                        + "echo '--- render order（应不含 99） ---'; "
                        + "export XDG_RUNTIME_DIR=/etc/weston/weston_xdg_runtime_dir; "
                        + "export WAYLAND_DISPLAY=wayland-1; "
                        + "LayerManagerControl get screen 0 2>&1 | grep -i 'render order'; "
                        + "echo '--- layer 99 是否还在 ---'; "
                        + "LayerManagerControl list layers 2>&1 | grep -c 'layer 99' || true").trim();
            } finally {
                /* 共享 Session：不断开，由 closeSession() 管理 */
            }
        } catch (Exception e) {
            return "SSH 取日志失败: " + e.getMessage();
        }
    }

    /**
     * 读 CP 真实屏幕尺寸 —— 建虚拟屏必须按它来。
     * 以前写死 1920×532，但仪表实际可能是 1920×720，尺寸错了画面就被裁。
     * 两条来源：LayerManagerControl 的 scene 描述，或 DRM modes 文件。
     */
    public int[] queryScreenSize() {
        try {
            Session session = acquireSession();
            try {
                String out = execOnSession(session,
                        "LayerManagerControl get screen 0 2>/dev/null; echo '---'; "
                        + "cat /sys/class/drm/card0-*/modes 2>/dev/null | head -3");
                int[] r = parseSize(out);
                if (r != null) {
                    Log.i(TAG, "CP 屏幕尺寸 = " + r[0] + "x" + r[1]);
                } else {
                    Log.w(TAG, "CP 屏幕尺寸解析失败，原始输出: " + out.replace('\n', ' '));
                }
                return r;
            } finally {
                /* 共享 Session：不断开，由 closeSession() 管理 */
            }
        } catch (Exception e) {
            Log.w(TAG, "查 CP 屏幕尺寸失败: " + e.getMessage());
            return null;
        }
    }

    /** 从任意文本里挑第一处 WxH（要求像块屏幕：w>=640, h>=320, w>=h） */
    private static int[] parseSize(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{3,4})\\s*[xX×]\\s*(\\d{3,4})").matcher(s);
        while (m.find()) {
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            if (w >= 640 && h >= 320 && w >= h) return new int[]{w, h};
        }
        return null;
    }

    /** 连通性测试 */
    public boolean ping() {        try {
            Session session = acquireSession();
            try {
                String out = execOnSession(session, "echo PONG");
                return out.contains("PONG");
            } finally {
                /* 共享 Session：不断开，由 closeSession() 管理 */
            }
        } catch (Exception e) {
            Log.w(TAG, "ping 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 把 AP 上的任意文件推到 CP。
     *
     * 为什么需要：宿主机的 adb 到不了 CP（198.18.34.2 只在车机内网），
     * 做压测要把基准程序和样片送进去，只能借 App 这条 SSH 通道。
     * 分块 32KB base64 追加，避免单条命令参数过长被 shell 截断。
     */
    /**
     * 把 ivi 图层（layer 99）缩放到遮罩矩形。
     *
     * 为什么用外部命令而不是改 dashcastd：`destination region` 是这个 CP 的
     * LayerManagerControl 原生能力，图层建好后从外面一条命令就能改。这样
     * **不需要替换 CP 上的二进制**，也**不依赖 alpha** ——
     * BGRA+LZ4 与 H.264 两条链路都能用（H.264 没有透明通道，靠几何约束
     * 才能做到"图层外露原厂仪表"）。
     *
     * 实测：
     *   destination region 从 0,0 960x266 → 700,40 1100x452
     *   source region 不变，合成器把内容缩放填充到新矩形。
     *   图层外合成器不绘制 → 原厂仪表自然显露。
     *
     * @param x,y,w,h 遮罩矩形（画布坐标）；w/h 为 0 表示设整屏（旧行为）
     * @return true = 命令执行成功（不代表一定可见，以实际画面为准）
     */
    public boolean setLayerRect(int x, int y, int w, int h) {
        try {
            int lid = LAYER_ID_CAST;
            if (w <= 0 || h <= 0) {
                x = 0; y = 0;
                w = MirrorController.getOutputW();
                h = MirrorController.getOutputH();
            }

            /* 必须先探到真实的 WAYLAND_DISPLAY，猜错就打在别的合成器上。
             * 用 raw() 一条命令里把探测+设置+回读全做完，只开一次 Channel。 */
            String probe = raw("ls /etc/weston/weston_xdg_runtime_dir/ 2>/dev/null"
                    + " | grep -oE 'wayland-[0-9]+' | head -1", 8000);
            String sock = probe == null ? "" : probe.trim();
            if (sock.isEmpty() || sock.contains("ERR")) sock = "wayland-1";

            String env = "export XDG_RUNTIME_DIR=/etc/weston/weston_xdg_runtime_dir; "
                    + "export WAYLAND_DISPLAY=" + sock + "; ";

            String cmd = env
                    + "LayerManagerControl set layer " + lid
                    + " destination region " + x + " " + y + " " + w + " " + h
                    + " 2>&1; "
                    + "LayerManagerControl get layer " + lid
                    + " 2>&1 | grep -i 'destination region'";

            String out = raw(cmd, 15000);
            if (out == null) return false;

            /* 层不存在 = 还没投屏，正常情况。
             * 日志做去重：同参数只打一次（保活循环每分钟会调很多次，
             * 实测不加去重会刷到 20+ 条/秒）。 */
            if (out.toLowerCase().contains("does not exist")) {
                String key = x + "," + y + "," + w + "," + h;
                /* 注意用 equals 不能直接比较 String 引用（这里 key 每次都是新对象，
                 * 直接 != 判断会恒为 true，去重完全失效 —— 之前就踩了这个坑）。 */
                if (!key.equals(sLastNoLayerRect)) {
                    sLastNoLayerRect = key;
                    Log.i(TAG, "图层 " + lid + " 尚未建立（未投屏）："
                            + x + "," + y + " " + w + "x" + h + "（同参数后续静默）");
                }
                return false;
            }

            if (out.toLowerCase().contains("failed")) {
                Log.w(TAG, "设置图层矩形失败: " + out.trim().replace('\n', ' '));
                return false;
            }

            boolean ok = out.contains("destination region");
            if (ok) {
        /* 保活每 8 秒调一次，这里不再记日志（上层只在矩形变化时打一条） */
                sLastNoLayerRect = "";   /* 成功后复位，方便下次失败时能再报一条 */
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "设置图层矩形异常: " + t);
            return false;
        }
    }

    public boolean pushLocal(String localPath, String remotePath, boolean executable) {
        synchronized (pushLock) {
            try {
                java.io.File f = new java.io.File(localPath);
                if (!f.exists()) { Log.e(TAG, "本地文件不存在: " + localPath); return false; }
                byte[] bin = new byte[(int) f.length()];
                java.io.FileInputStream fi = new java.io.FileInputStream(f);
                int off = 0;
                while (off < bin.length) {
                    int r = fi.read(bin, off, bin.length - off);
                    if (r <= 0) break;
                    off += r;
                }
                fi.close();
                String b64 = Base64.encodeToString(bin, Base64.NO_WRAP);

                /* 本地 md5 —— 推送后必须逐字节比对。之前只用"md5sum 有没有输出"
                 * 判断成功，dropbear 截断某一片 base64 时远端会产出残缺文件，
                 * md5sum 照样有输出 → 假成功。症状就是"PUSH_OK 但 CP 上还是旧
                 * 二进制"，排查时极难定位。 */
                String localMd5 = md5Hex(bin);
                Log.i(TAG, "推送 " + localPath + " (" + bin.length + "B, md5=" + localMd5
                        + ") → " + remotePath);

                Session session = acquireSession();
                try {
                    execOnSession(session, "mkdir -p " + new java.io.File(remotePath).getParent()
                            + "; rm -f " + remotePath + ".b64");

                    /* dropbear 的 exec 命令长度上限很小（实测 32KB 被直接拒绝）。
                     * 4000 是 pushDashcastd 长期在用的稳妥值；6000 偶发被截，
                     * 一旦被截后续全乱 —— 统一降到 4000。 */
                    int chunk = 4000;
                    for (int i = 0; i < b64.length(); i += chunk) {
                        String piece = b64.substring(i, Math.min(b64.length(), i + chunk));
                        execOnSession(session, "printf '%s' '" + piece + "' >> " + remotePath + ".b64");
                    }

                    execOnSession(session, "base64 -d " + remotePath + ".b64 > " + remotePath
                            + (executable ? " && chmod 755 " + remotePath : ""));
                    execOnSession(session, "rm -f " + remotePath + ".b64");

                    String out = execOnSession(session, "md5sum " + remotePath + " 2>/dev/null").trim();
                    String remoteMd5 = out.isEmpty() ? "" : out.split("\\s+")[0];
                    boolean ok = remoteMd5.equalsIgnoreCase(localMd5);
                    Log.i(TAG, "推送结果: 远端 " + remoteMd5 + " vs 本地 " + localMd5
                            + (ok ? " (一致)" : " (不一致!)"));
                    if (!ok) lastPushError = "md5 不一致: 远端 " + remoteMd5 + " 本地 " + localMd5;
                    return ok;
                } finally {
                    /* 共享 Session：不断开，由 closeSession() 管理 */
                }
            } catch (Throwable t) {
                lastPushError = String.valueOf(t);
                Log.e(TAG, "推送失败: " + t);
                return false;
            }
        }
    }

    /** 算字节数组的 MD5（小写十六进制） */
    private static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /* ===== 工具 ===== */

    /**
     * 在 CP 上执行一条任意命令，返回 stdout+stderr。
     *
     * 存在的理由：CP 在 198.18.34.2，只有车机内网能到，宿主机连不上；
     * 做压测要读 CP 的 /proc/net/dev、/proc/stat、挂 watchdog 脚本，
     * 全得借这条通道。只走 adb broadcast 触发（需要 shell 权限），
     * 不会暴露给普通 App。
     */
    public String raw(String command, int timeoutMs) {
        try {
            return execOnSession(acquireSession(), command);
        } catch (Throwable t) {
            return "ERR: " + t;
        }
        /* 共享 Session：不断开，由 closeSession() 管理 */
    }

    private byte[] readAssetBytes(String name) throws IOException {
        InputStream is = context.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        is.close();
        return out.toByteArray();
    }

    private String readAssetText(String name) throws IOException {
        return new String(readAssetBytes(name), "UTF-8");
    }
}
