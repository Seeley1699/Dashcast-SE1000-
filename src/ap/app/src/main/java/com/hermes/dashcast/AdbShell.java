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
     */
    public static String exec(String command, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), 2500);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);

            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(out, A_CNXN, A_VERSION, A_MAXDATA, "host::".getBytes(StandardCharsets.UTF_8));

            // 等 CNXN（免认证时直接来；要认证就会先来 AUTH）
            while (true) {
                Msg m = read(in);
                if (m.cmd == A_CNXN) break;
                if (m.cmd == A_AUTH) {
                    throw new IOException("adbd 要求 RSA 认证（ro.adb.secure=1），本通道不可用");
                }
            }

            final int localId = 1;
            send(out, A_OPEN, localId, 0,
                    ("shell:" + command + "\u0000").getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            int remoteId = 0;
            boolean open = false;

            while (true) {
                Msg m = read(in);
                Log.d(TAG, "pkt cmd=" + Integer.toHexString(m.cmd)
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
                    send(out, A_OKAY, localId, remoteId, null);
                    continue;
                }
                if (m.cmd == A_CLSE) {
                    send(out, A_CLSE, localId, remoteId, null);
                    break;
                }
                // 其它（PONG/DENT…）忽略
            }

            if (!open) throw new IOException("OPEN 未被接受（shell 服务不可用？）");
            return sb.toString();
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
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
            int s = 0;
            for (byte b : m.data) s += (b & 0xFF);
            if ((s & 0xFFFFFFFF) != (sum & 0xFFFFFFFF)) {
                Log.w(TAG, "ADB 校验和不符（继续）");
            }
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
