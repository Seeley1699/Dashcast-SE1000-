/*
 * DcapNative.java — libdcap.so 的 Java 门面
 *
 * 只做转发，不含逻辑。所有热路径（拆 stride / 图块比对 / RGBA→BGRA / LZ4 / 组帧 /
 * TCP 发送）都在 native，Java 每帧只跨一次 JNI 边界。
 */
package com.hermes.dashcast;

import java.nio.ByteBuffer;

public final class DcapNative {

    static {
        System.loadLibrary("dcap");
    }

    private DcapNative() {}

    /** 建抓帧上下文（预分配 prev / tile / comp / hdr / pay 五块缓冲）。返回 0 = 失败 */
    public static native long create(int w, int h);

    /** 连 CP 并完成 28B 握手 + 8B ACK。会清空 prev，之后第一帧自动整帧 */
    public static native boolean connect(long handle, String host, int port);

    /** 断开，保留上下文（prev 清零，重连后首帧整帧） */
    public static native void disconnect(long handle);

    /**
     * 抓一帧、打包、发送。
     *
     * @param buffer       ImageReader 的 direct ByteBuffer（RGBA_8888）
     * @param rowStride    Image.Plane.getRowStride()，通常 > width*4（有 padding）
     * @param pixelStride  Image.Plane.getPixelStride()，必须是 4
     * @param forceFull    true = 不比对，整帧重发（首帧 / 重连 / 定期兜底）
     * @return 本帧图块数；0 = 画面没变（零字节）；<0 = 链路错误，需要 disconnect 重连
     */
    public static native int process(long handle, ByteBuffer buffer, int rowStride,
                                     int pixelStride, boolean forceFull);

    /** out = [tiles, bytesLast, seq, bytesTotal, connected] */
    public static native void stats(long handle, long[] out);

    public static native void destroy(long handle);

    /** LZ4 编码器 ↔ CP 解码器 往返自检。0 = 通过 */
    public static native int selfTest();
}
