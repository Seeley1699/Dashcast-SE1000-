/*
 * LongLivedShell.java —— 为"要长活的进程"开一条专用 shell 通道
 *
 * 背景（2026-11-08 实测）：
 *   AdbShell.exec() 的 runCommand 收到 A_CLSE 就返回，exec() 随后
 *   closeQuietly() 或复用同一条 socket。adbd 在这条 shell 流关闭时，
 *   会把**该流创建的子进程组**一起回收 —— 于是 dcapd2 / hudcast-inject
 *   启动后几秒就消失。用手动 adb 命令行跑同样的命令却正常，因为
 *   adb 客户端不会立刻关流。
 *
 * 解法：
 *   开一条独立的 socket，发 OPEN("shell:<cmd>\0")，收到 A_OKAY 后
 *   **只读一小段确认命令已开始执行，然后把 socket 保存在静态字段里不关**。
 *   只要这条 socket 活着，adbd 就不会清理那个进程组。
 *
 *   App 进程退出 / 主动 release() 时 socket 才关 —— 这时进程组也一起没了，
 *   正好是我们要的语义（投屏会话结束 = 抓帧进程该退）。
 *
 * 为什么不用 exec: 服务：
 *   这台车的 adbd 对 exec: 支持不确定，且 exec: 不做 shell 解析（没有
 *   `&&`、重定向），我们的启动命令需要 shell。
 */

package com.hermes.dashcast;

import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class LongLivedShell {

    private static final String TAG = "DashcastLongShell";

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int A_VERSION = 0x01000001;
    private static final int A_MAXDATA = 256 * 1024;

    /** 每条长活通道：socket + 流。不关它，子进程就活着 */
    public static final class Handle {
        final Socket sock;
        final DataInputStream in;
        final OutputStream out;
        final int localId;
        volatile int remoteId;
        volatile boolean alive = true;

        Handle(Socket s, DataInputStream i, OutputStream o, int lid) {
            sock = s; in = i; out = o; localId = lid;
        }

        public boolean isAlive() { return alive && sock != null && !sock.isClosed(); }

        public void close() {
            alive = false;
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    private static Handle sDcapd2;
    private static Handle sInjector;

    private LongLivedShell() {}

    /**
     * 在独立 shell 通道上启动一条命令，并**持有该通道**。
     *
     * @param tag      日志用标识
     * @param command  shell 命令（不要自己加结尾的 &）
     * @return Handle（持有即保活），失败返回 null
     */
    public static synchronized Handle spawn(String tag, String command) {
        try {
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(HOST, PORT), 5000);
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(8000);

            DataInputStream in = new DataInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();

            /* 握手
             *
             * **A_VERSION / A_MAXDATA 必须填真值**（2026-11-08 踩坑）。
             * 最初写的是 send(..., 0, 0, "host::features=...")，
             * adbd 回 CNXN 之后对后续 OPEN 完全不响应（等到 SoTimeout），
             * 因为 arg0/arg1 是协议版本和最大包长，填 0 等于声明了一个
             * 它不认识的协议版本。照抄 AdbShell 的写法即可。
             */
            send(out, A_CNXN, A_VERSION, A_MAXDATA, "host::".getBytes(StandardCharsets.UTF_8));
            Message m = read(in);
            if (m.cmd == A_AUTH) {
                Log.w(TAG, tag + ": adbd 要求 RSA 认证，本机不该出现");
                sock.close();
                return null;
            }
            if (m.cmd != A_CNXN) {
                Log.w(TAG, tag + ": 握手失败 cmd=" + Integer.toHexString(m.cmd));
                sock.close();
                return null;
            }

            /* 开 shell 流 */
            final int localId = 1;
            send(out, A_OPEN, localId, 0,
                    ("shell:" + command + "\u0000").getBytes(StandardCharsets.UTF_8));

            /* 等 OKAY（拿到 remoteId）
             *
             * **超时不能关 socket**（2026-11-08 实测教训）：
             *   命令已经随 OPEN 发出去、进程已经起来了（实测 pidof 能查到），
             *   只是某些 adbd 版本不回 OKAY（或回得比 8 秒还晚）。
             *   原来这里超时就 sock.close() —— adbd 立刻回收该流的进程组，
             *   于是"进程起来了又被我自己关掉"，表现为 spawn 失败且没有进程。
             *
             *   现在：超时也照样返回 Handle，让 socket 保持打开。
             *   父进程（App）持有它就等于给子进程续命。
             */
            Handle h = new Handle(sock, in, out, localId);
            int guard = 0;
            while (guard++ < 40) {
                Message mm;
                try {
                    mm = read(in);
                } catch (java.net.SocketTimeoutException te) {
                    Log.w(TAG, tag + ": 等 OKAY 超时，但命令已发出 —— 保留通道");
                    h.remoteId = 0;
                    return h;
                }
                Log.i(TAG, tag + ": 收到 pkt cmd=" + Integer.toHexString(mm.cmd)
                        + " a0=" + mm.arg0 + " a1=" + mm.arg1
                        + " len=" + (mm.data == null ? 0 : mm.data.length)
                        + (mm.data != null ? " data=" + new String(mm.data, StandardCharsets.UTF_8).trim() : ""));
                if (mm.cmd == A_OKAY) {
                    h.remoteId = mm.arg0;
                    send(out, A_OKAY, localId, h.remoteId, null);
                    Log.i(TAG, tag + ": 通道已建立 remoteId=" + h.remoteId);
                    return h;
                }
                if (mm.cmd == A_CLSE) {
                    Log.w(TAG, tag + ": 命令流被立刻关闭（命令语法可能有问题）");
                    sock.close();
                    return null;
                }
                if (mm.cmd == A_WRTE) {
                    h.remoteId = mm.arg1;
                    send(out, A_OKAY, localId, h.remoteId, null);
                    Log.i(TAG, tag + ": 通道已建立（WRTE 路径）");
                    return h;
                }
            }
            Log.w(TAG, tag + ": 等 OKAY 轮次用尽，保留通道");
            return h;
        } catch (Throwable t) {
            Log.e(TAG, tag + ": spawn 失败 " + t);
            return null;
        }
    }

    /** 启动 dcapd2（幂等：已有活的就复用） */
    public static synchronized Handle spawnDcapd2(String cmd) {
        if (sDcapd2 != null && sDcapd2.isAlive()) {
            Log.i(TAG, "dcapd2 通道仍活，复用");
            return sDcapd2;
        }
        if (sDcapd2 != null) sDcapd2.close();
        sDcapd2 = spawn("dcapd2", cmd);
        return sDcapd2;
    }

    /** 启动 hudcast-inject（每次注入新建，旧的先关） */
    public static synchronized Handle spawnInjector(String cmd) {
        if (sInjector != null) sInjector.close();
        sInjector = spawn("injector", cmd);
        return sInjector;
    }

    /** 释放全部长活通道（进程组随之被 adbd 回收 —— 正是我们要的） */
    public static synchronized void releaseAll() {
        if (sDcapd2 != null) { sDcapd2.close(); sDcapd2 = null; }
        if (sInjector != null) { sInjector.close(); sInjector = null; }
        Log.i(TAG, "已释放所有长活通道");
    }

    public static synchronized boolean hasDcapd2() {
        return sDcapd2 != null && sDcapd2.isAlive();
    }

    public static synchronized boolean hasInjector() {
        return sInjector != null && sInjector.isAlive();
    }

    /** 检查通道是否被对端关闭（读 steer 数据会失败） */
    public static synchronized void reapIfClosed() {
        if (sDcapd2 != null && !sDcapd2.isAlive()) { sDcapd2 = null; }
        if (sInjector != null && !sInjector.isAlive()) { sInjector = null; }
    }

    /* ─────────────── ADB 协议底层 ─────────────── */

    private static final class Message {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int a0, int a1, byte[] data)
            throws IOException {
        int len = data == null ? 0 : data.length;
        java.nio.ByteBuffer hdr = java.nio.ByteBuffer.allocate(24);
        int sum = 0;
        if (data != null) for (byte b : data) sum += (b & 0xFF);
        hdr.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum)
           .putInt(cmd ^ 0xFFFFFFFF);
        out.write(hdr.array());
        if (data != null && len > 0) out.write(data);
        out.flush();
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(h)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = bb.getInt();
        m.arg0 = bb.getInt();
        m.arg1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt();           /* checksum */
        bb.getInt();           /* magic */
        if (len > 0 && len <= A_MAXDATA) {
            m.data = new byte[len];
            in.readFully(m.data);
        }
        return m;
    }
}
