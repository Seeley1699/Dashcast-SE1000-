/*
 * AdbShell.java — 走本机 adbd 的 root 通道执行 shell 命令
 *
 * 为什么需要：
 *   这台车机的 DisplayManager 允许普通 App 建 PUBLIC 虚拟屏（我们做到了），
 *   但把**别的 App** 的 Activity 启动到这块屏上时，ActivityStackSupervisor
 *   .isCallerAllowedToLaunchOnDisplay() 会把虚拟屏当成"私有屏"，
 *   要求调用方持有 INTERNAL_SYSTEM_WINDOW（protectionLevel=signature）。
 *   普通安装拿不到、priv-app 也拿不到（不是 signature|privileged），平台签名也没有。
 *
 *   但 adbd 在这台车上是 root 且 ro.adb.secure 为空（不需要 RSA 认证），
 *   监听在 :::5555。于是 App 直接连 127.0.0.1:5555 说 ADB 协议，
 *   用 `shell:am start --display N ...` 以 uid 0 身份启动 —— 一次都不碰 /system。
 *
 * 协议（无认证，adbd 侧 raw shell，不带 shell_v2 头）：
 *   CNXN  host::  →  CNXN
 *   OPEN  "shell:<cmd>\0"
 *   OKAY  → WRTE(输出)* → CLSE
 */
package com.hermes.dashcast;

import android.os.SystemClock;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class AdbShell {

    private static final String TAG = "DashcastAdb";

    // ADB 报文命令字：线上是 4 个 ASCII 字节，小端读回来就是下面这些值
    private static final int A_CNXN = 0x4e584e43;  // "CNXN"
    private static final int A_AUTH = 0x48545541;  // "AUTH"
    private static final int A_OPEN = 0x4e45504f;  // "OPEN"
    private static final int A_OKAY = 0x59414b4f;  // "OKAY"
    private static final int A_CLSE = 0x45534c43;  // "CLSE"
    private static final int A_WRTE = 0x45545257;  // "WRTE"

    private static final int A_VERSION = 0x01000001;
    private static final int A_MAXDATA = 256 * 1024;

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;

    /*
     * ══════════════════════════════════════════════════════════════════
     * **连接复用**（2026-11-05）
     * ══════════════════════════════════════════════════════════════════
     *
     * 原来每次 exec() 都 new Socket + connect + CNXN 协商 + close。
     * MirrorController 的周期任务合计约 2 次/秒，一场投屏 10 分钟
     * 就是上千次握手；而命令本身（pidof / tail -3 / dumpsys）只有几毫秒。
     * 开销全在协议上，不在活儿上。
     *
     * 设计取舍（有意做得很保守）：
     *   · **只保留一条**连接。ADB 的 shell 流用固定 localId=1，
     *     同一条 socket 上并发 OPEN 会互相踩 —— 一条 + 串行最安全。
     *   · 串行靠 synchronized，锁粒度 = 一次 exec 的完整生命周期。
     *     调用方本来也都在等结果，串行不会带来额外等待。
     *   · **任何异常都丢弃这条连接**，下次重新建。
     *     不复用"可能已脏"的连接，避免半包/错位这种难查问题。
     *   · 空闲超过 IDLE_CLOSE_MS 就主动关掉，不留长连接占 adbd 资源。
     *
     * 为什么不做成连接池（多个）：adbd 那边每个连接都要单独协商，
     * 多开只是在 App 侧并行，服务端依旧是排队；并发还要处理
     * "哪条忙哪条闲"，复杂度和收益不成正比。
     */
    private static final long IDLE_CLOSE_MS = 60_000;

    /** 复用的连接与它的流；null 表示当前没有可用连接 */
    private static Socket sSocket;
    private static DataInputStream sIn;
    private static OutputStream sOut;
    private static long sLastUsedAt;

    /** 串行化所有 exec：同一条连接上的 OPEN 必须一个一个来 */
    private static final Object sLock = new Object();

    /**
     * **本机 adbd 是否支持连接复用**（2026-11-06 实测修正）。
     *
     * 日志实证（dashcast-4.log）：8 分钟内出现 374 次
     * 「复用连接不可用，已降级为新连接」，约 47 次/分钟。
     *
     * 根因：降级重连成功后 sSocket 又变成非空，下一次 exec 又走复用路径、
     * 又失败、又降级 —— 每次命令都要「先失败一次再重连」，
     * 比原来"每次新建"还慢。
     *
     * 所以改成：**失败一次就认定这台机器不支持复用**，
     * 之后永久走"每次新建"。只在进程重启后重新试探。
     * 代价是失去复用收益，但至少不再付失败的开销。
     */
    private static volatile boolean sReuseBroken = false;

    private AdbShell() {}

    /** 本机 adbd 是否可用（root + 免认证） */
    public static boolean available() {
        try {
            exec("id", 2000);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 执行一条 shell 命令，返回合并后的 stdout。
     * 注意：adbd 在这台车上是 root，所以命令以 uid 0 执行。
     *
     * 连接复用逻辑见 sLock 上方的注释：整条 exec 串行，
     * 成功则保留连接给下次用，任何异常都丢弃连接（下次重建）。
     */
    public static String exec(String command, int timeoutMs) throws IOException {
        synchronized (sLock) {
            /*
             * 空闲太久先关掉 —— 不养长连接。
             * adbd 某些版本会回收空闲连接，与其等它断、下次用时报错，
             * 不如自己主动关。
             */
            if (sSocket != null && sLastUsedAt > 0
                    && SystemClock.elapsedRealtime() - sLastUsedAt > IDLE_CLOSE_MS) {
                closeQuietly();
            }

            if (sReuseBroken) closeQuietly();
            boolean needConnect = sSocket == null;
            try {
                if (needConnect) {
                    connectAndHandshake();
                }
                String result = runCommand(command, timeoutMs);
                sLastUsedAt = SystemClock.elapsedRealtime();
                sLastUsedAt = SystemClock.elapsedRealtime();
                if (sReuseBroken) closeQuietly();
                return result;
            } catch (Throwable t) {
                /*
                 * **任何失败都丢掉连接**。绝不复用"可能已经脏"的 socket：
                 * 半包、错位的响应会让下一条命令读到别人的输出，
                 * 那种 bug 很难从日志里看出来。
                 */
                closeQuietly();
                /*
                 * **刚才用的是复用连接 → 立刻用新连接重试一次**（2026-11-05）。
                 *
                 * 为什么需要：ADB 协议里 shell 流用 localId 标识，
                 * 同一条 socket 上"旧流 CLSE 之后复用同一个 localId 再 OPEN"，
                 * 不同 adbd 版本的处理不一样 —— 有的接受，有的直接关连接。
                 * 我没法在所有车机上验证，所以不赌：
                 *   · 复用失败 = 可能是这个原因
                 *   · 重建一条新连接再试（新连接上 localId=1 一定干净）
                 *   · 如果新连接也失败，才真的抛出去
                 *
                 * 这条降级路径只在"复用不被支持"的机型上走，
                 * 且只在第一次失败时走一次，不会造成重试风暴。
                 */
                if (!needConnect) {
                    try {
                        connectAndHandshake();
                        String result2 = runCommand(command, timeoutMs);
                        sLastUsedAt = SystemClock.elapsedRealtime();
                        if (!sReuseBroken) {
                            sReuseBroken = true;
                            DashLog.w(TAG, "本机 adbd 不支持连接复用，已永久切换为每次新建（只报一次）");
                        }
                        closeQuietly();
                        return result2;
                    } catch (Throwable t2) {
                        closeQuietly();
                        throw t2;
                    }
                }
                throw t;
            }
        }
    }

    /** 建连 + CNXN 协商，结果存进 sSocket/sIn/sOut */
    private static void connectAndHandshake() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), 2500);
            socket.setTcpNoDelay(true);

            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(out, A_CNXN, A_VERSION, A_MAXDATA, "host::".getBytes(StandardCharsets.UTF_8));

            while (true) {
                Msg m = read(in);
                if (m.cmd == A_CNXN) break;
                if (m.cmd == A_AUTH) {
                    throw new IOException("adbd 要求 RSA 认证（ro.adb.secure=1），本通道不可用");
                }
            }

            sSocket = socket;
            sIn = in;
            sOut = out;
        } catch (Throwable t) {
            try { socket.close(); } catch (IOException ignored) {}
            throw t;
        }
    }

    /** 在已建好的连接上跑一条 shell 命令 */
    private static String runCommand(String command, int timeoutMs) throws IOException {
        sSocket.setSoTimeout(timeoutMs);

        final int localId = 1;
        send(sOut, A_OPEN, localId, 0,
                ("shell:" + command + "\u0000").getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        int remoteId = 0;
        boolean open = false;

        while (true) {
            Msg m = read(sIn);
            /*
             * **pkt 逐包日志改成 V 级**（2026-11-05）。
             *
             * 实测一个 logcat 文件 5644 行里 5550 行是这种 pkt 噪音
             * （98.3%），真正有用的业务日志只有 94 行。
             * 用户把日志发过来，第一眼看到的就是一片 pkt。
             *
             * 降到 V 级：默认 logcat 过滤掉，
             * 排查协议问题时开 `adb logcat -v *:V` 还能看到。
             */
            DashLog.v(TAG, "pkt cmd=" + Integer.toHexString(m.cmd)
                    + " a0=" + m.arg0 + " a1=" + m.arg1
                    + " len=" + (m.data == null ? 0 : m.data.length));
            if (m.cmd == A_OKAY) {
                open = true;
                remoteId = m.arg0;       // adbd 那边的流 id
                continue;
            }
            if (m.cmd == A_WRTE) {
                if (m.data != null && m.data.length > 0) {
                    sb.append(new String(m.data, StandardCharsets.UTF_8));
                }
                // 回 ACK，否则 adbd 不会再发下一包
                send(sOut, A_OKAY, localId, remoteId, null);
                continue;
            }
            if (m.cmd == A_CLSE) {
                send(sOut, A_CLSE, localId, remoteId, null);
                break;
            }
            // 其它（PONG/DENT…）忽略
        }

        if (!open) throw new IOException("OPEN 未被接受（shell 服务不可用？）");
        return sb.toString();
    }

    /** 关掉复用连接（异常安全） */
    private static void closeQuietly() {
        try { if (sIn != null) sIn.close(); } catch (IOException ignored) {}
        try { if (sOut != null) sOut.close(); } catch (IOException ignored) {}
        try { if (sSocket != null) sSocket.close(); } catch (IOException ignored) {}
        sIn = null;
        sOut = null;
        sSocket = null;
        sLastUsedAt = 0;
    }

    // ============================ 报文编解码 ============================

    private static final class Msg {
        int cmd;
        int arg0;
        int arg1;
        byte[] data;
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);

        Msg m = new Msg();
        m.cmd = le32(h, 0);
        m.arg0 = le32(h, 4);
        m.arg1 = le32(h, 8);
        int len = le32(h, 12);
        int sum = le32(h, 16);
        int magic = le32(h, 20);

        if ((m.cmd ^ 0xFFFFFFFF) != magic) {
            throw new IOException("ADB 报文 magic 不匹配: " + Integer.toHexString(m.cmd));
        }
        if (len < 0 || len > 4 * 1024 * 1024) {
            throw new IOException("ADB 报文长度非法: " + len);
        }

        if (len > 0) {
            m.data = new byte[len];
            in.readFully(m.data);
            /*
             * **不再校验 checksum**（2026-11-05）。
             *
             * ADB 协议 v1 之后 checksum 字段已经废弃，adbd 发出来的
             * 值经常是 0 或不匹配 —— 实测每 1.5 秒报一次
             * 「ADB 校验和不符（继续）」，一场投屏报了 2344 次。
             *
             * 我们本来就只是"警告然后继续"，既然不影响功能，
             * 干脆连算都不算 —— 省掉每包一次逐字节求和。
             */
        }

        return m;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data)
            throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) {
            for (byte b : data) sum += (b & 0xFF);
        }
        byte[] h = new byte[24];
        put32(h, 0, cmd);
        put32(h, 4, arg0);
        put32(h, 8, arg1);
        put32(h, 12, len);
        put32(h, 16, sum);
        put32(h, 20, cmd ^ 0xFFFFFFFF);
        out.write(h);
        if (len > 0) out.write(data);
        out.flush();
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }
}