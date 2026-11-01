// h264enc.c —— AP 侧 H.264 硬件编码 + RTP 打包发送
//
// 输入：dcapd2 已经合成好的 BGRA 画布（sw×sh，例如 1920×532）
// 输出：写进 dcapd2 已经建好的那条 TCP 控制连接，格式是
//       [u32be 长度][NAL 字节] 的简单流。
//
// 为什么不走 UDP/RTP：这台车机的 AP<->CP 链路会把每个 UDP 包送两份
//（实测 AP 发 67 包/s、零 sendto 失败，CP 收到 138 包/s，重复率 53%）。
// 重复包会把 FU-A 分片拼两遍，码流直接废掉。TCP 保证不重不丢不乱序，
// 点对点 USB 网络上也没有队头阻塞的实际代价。
//
// 编码参数按实测定的：源 2:1 下采样后的 960×266，CBR 2.5 Mbps、30fps、
// GOP 1s、baseline（无 B 帧，解码最省、延迟最低）。
//
// 运行时可调（不用重编，setprop 或 env 都行）：
//   DC_KBPS       码率 kbps，默认 2500
//   DC_UV_SWAP    置 1 = 色度 U/V 交换（设备是 NV21 时才需要）
//   DC_YUV_LAYOUT 1 = 平面 I420；0（默认）= 半平面 NV12（stride*sliceHeight 后交叠）
//   DC_H264_LOG   置 1 = 每 2 秒打一行统计

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
#include <sys/socket.h>
#include <poll.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/log.h>

#define TAG "dcapd2h264"
/* stderr 一定看得到（dcapd2 的日志就重定向到那里），logcat 在这个进程里不可靠 */
#define LOGI(...) do { fprintf(stderr, "[h264] " __VA_ARGS__); fprintf(stderr, "\n"); fflush(stderr); \
                       __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__); } while (0)
#define LOGE(...) do { fprintf(stderr, "[h264][E] " __VA_ARGS__); fprintf(stderr, "\n"); fflush(stderr); \
                       __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); } while (0)

#define COLOR_FMT_FLEXIBLE_YUV420 0x7F420888
#define COLOR_FMT_SEMI_PLANAR     21  /* OMX_COLOR_FormatYUV420SemiPlanar */

static AMediaCodec *g_enc = NULL;
static int   g_w = 0, g_h = 0;          /* 编码尺寸 */
static int   g_stride = 0;              /* 编码器要求的 Y 行距 */
static int   g_slice  = 0;              /* 编码器要求的 Y 行数 */
static int   g_uv_swap = 0;
static int   g_planar = 0;
static int   g_kbps = 2500;
static int   g_fps  = 30;
static int   g_verbose = 0;

static uint64_t g_pts = 0;

static uint8_t g_sps[256]; static int g_sps_len = 0;
static uint8_t g_pps[256]; static int g_pps_len = 0;

static int g_tcp = -1;                  /* dcapd2 那条控制连接，借来传码流 */

static long g_frames = 0, g_bytes = 0, g_fed = 0;
static long g_sent = 0, g_sfail = 0, g_pkts = 0;
static long g_stat_frames = 0, g_stat_bytes = 0;
static struct timespec g_t_last;

/* ---------- BGRA -> YUV（box 平均下采样） ---------- */
static void bgra_to_yuv(const uint8_t *src, int sw, int sh,
                        uint8_t *dst, int dw, int dh) {
    uint8_t *yp = dst;
    uint8_t *up, *vp;
    size_t ysz = (size_t) g_stride * g_slice;

    if (g_planar) {
        up = dst + ysz;
        vp = dst + ysz + ysz / 4;
    } else {
        up = dst + ysz;                /* 交织存放，下面按 2 字节步进 */
        vp = up + 1;
    }

    int bw = sw / dw;                  /* 源/目的 宽比，这里是 2 */
    int bh = sh / dh;
    if (bw < 1) bw = 1;
    if (bh < 1) bh = 1;

    for (int j = 0; j < dh; j++) {
        uint8_t *yrow = yp + (size_t) j * g_stride;
        uint8_t *urow = g_planar ? (up + (size_t) (j >> 1) * (g_stride / 2))
                                 : (up + (size_t) (j >> 1) * g_stride);
        uint8_t *vrow = g_planar ? (vp + (size_t) (j >> 1) * (g_stride / 2))
                                 : (vp + (size_t) (j >> 1) * g_stride);

        const uint8_t *srow0 = src + (size_t) (j * bh) * sw * 4;
        for (int i = 0; i < dw; i++) {
            const uint8_t *p = srow0 + (size_t) (i * bw) * 4;
            int r = 0, g = 0, b = 0, cnt = 0;
            for (int dy = 0; dy < bh; dy++) {
                const uint8_t *q = p + (size_t) dy * sw * 4;
                for (int dx = 0; dx < bw; dx++) {
                    const uint8_t *c = q + (size_t) dx * 4;
                    b += c[0]; g += c[1]; r += c[2]; cnt++;
                }
            }
            r /= cnt; g /= cnt; b /= cnt;

            /* BT.601 limited range */
            int Y = ( 77 * r + 150 * g +  29 * b + 128) >> 8;
            int U = ((-43 * r -  85 * g + 128 * b + 128) >> 8) + 128;
            int V = ((128 * r - 107 * g -  21 * b + 128) >> 8) + 128;
            if (Y < 16) Y = 16; else if (Y > 235) Y = 235;
            if (U < 16) U = 16; else if (U > 240) U = 240;
            if (V < 16) V = 16; else if (V > 240) V = 240;
            yrow[i] = (uint8_t) Y;

            if ((i & 1) == 0 && (j & 1) == 0) {
                if (g_planar) {
                    urow[i >> 1] = (uint8_t) U;
                    vrow[i >> 1] = (uint8_t) V;
                } else if (g_uv_swap) {          /* NV21: V,U */
                    urow[i]     = (uint8_t) V;
                    vrow[i]     = (uint8_t) U;
                } else {                          /* NV12: U,V */
                    urow[i]     = (uint8_t) U;
                    vrow[i]     = (uint8_t) V;
                }
            }
        }
    }
}

/* 全量写：TCP 是流，短写必须续 */
/*
 * 全量写：TCP 是流，短写必须续。
 *
 * **EAGAIN 必须等，不能当失败**（2026-10-31 修复）：
 * 之前 write() 返回任意负数就直接 return -1，日志打"写码流失路"。
 * 但 socket 是非阻塞的，CP 解码 1920x532（102 万像素软解）跟不上时
 * 发送缓冲会满 → write 返回 -1/EAGAIN → 被误判成"对端断了" →
 * 整个 H.264 会话 4 帧就崩，AP 侧表现为 喂=10 出帧=1。
 *
 * 实测证据（1920x532 全屏 H.264）：
 *   单帧 I 帧就有 252KB，CP 解码一帧要 30ms+，缓冲 200KB 一满就 EAGAIN。
 *   960x266 时帧小（60KB），缓冲够吃，所以以前没暴露这个问题。
 *
 * 正确做法：EAGAIN → poll 等可写，最多等 3 秒；真的等不到才认为断了。
 */
static int write_all(int fd, const void *buf, size_t n) {
    const uint8_t *p = (const uint8_t *) buf;
    size_t done = 0;
    int wait_ms = 0;
    while (done < n) {
        ssize_t w = write(fd, p + done, n - done);
        if (w < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                /* 发送缓冲满：等它可写。每次最多等 200ms，累计最多 3 秒。 */
                if (wait_ms >= 3000) {
                    LOGE("写码流等可写超时 3 秒（CP 处理不过来？）");
                    return -1;
                }
                struct pollfd pfd;
                pfd.fd = fd;
                pfd.events = POLLOUT;
                pfd.revents = 0;
                int pr = poll(&pfd, 1, 200);
                if (pr > 0) {
                    wait_ms += 0;      /* 有进展，不计入超时 */
                    continue;
                }
                if (pr < 0 && errno != EINTR) return -1;
                wait_ms += 200;
                continue;
            }
            return -1;
        }
        if (w == 0) return -1;
        done += (size_t) w;
    }
    return 0;
}

/* ---------- 发送：4 字节大端长度 + 一个 NAL ---------- */
static int send_nal(int fd, const uint8_t *nal, int len) {
    uint8_t hdr[4];
    hdr[0] = (uint8_t) (len >> 24);
    hdr[1] = (uint8_t) (len >> 16);
    hdr[2] = (uint8_t) (len >> 8);
    hdr[3] = (uint8_t) len;
    if (write_all(fd, hdr, 4) != 0) return -1;
    if (write_all(fd, nal, (size_t) len) != 0) return -1;
    g_pkts++;
    g_sent  += 4 + len;
    g_bytes += 4 + len;
    return 0;
}

static void send_sps_pps(void) {
    if (g_sps_len) send_nal(g_tcp, g_sps, g_sps_len);
    if (g_pps_len) send_nal(g_tcp, g_pps, g_pps_len);
}

/*
 * 把一个访问单元（编码器一次吐出来的可能含多个 NAL）拆成 NAL 逐个发。
 * 起始码可能是 3 字节也可能是 4 字节，逐个记下"载荷起点"，
 * 不去削尾随 0（那是 cabac_zero_word，削了会破坏码流）。
 */
static int send_access_unit(const uint8_t *buf, int len) {
    static int start[256], pay[256];
    int ns = 0;
    for (int i = 0; i + 2 < len && ns < 256; i++) {
        if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) {
            start[ns] = i;
            pay[ns]   = i + 3;
            ns++;
            i += 2;
        }
    }
    if (ns == 0) return send_nal(g_tcp, buf, len);
    for (int k = 0; k < ns; k++) {
        const int s = pay[k];
        const int e = (k + 1 < ns) ? start[k + 1] : len;
        if (e <= s) continue;
        /* 每个 IDR 前补发 SPS/PPS：中途接入也能立刻解出来 */
        if ((buf[s] & 0x1f) == 5) send_sps_pps();
        if (send_nal(g_tcp, buf + s, e - s) != 0) return -1;
    }
    return 0;
}

/* 从 codec-config 里抠出 SPS/PPS 存起来 */
static void save_sps_pps(const uint8_t *p, int n) {
    int i = 0;
    while (i + 3 < n) {
        if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1) {
            const int s = i + 3;
            const int type = p[s] & 0x1f;
            int e = n;
            for (int k = s; k + 2 < n; k++) {
                if (p[k] == 0 && p[k + 1] == 0 && p[k + 2] == 1) { e = k; break; }
            }
            const int l = e - s;
            if (type == 7 && l < (int) sizeof(g_sps)) { memcpy(g_sps, p + s, (size_t) l); g_sps_len = l; }
            if (type == 8 && l < (int) sizeof(g_pps)) { memcpy(g_pps, p + s, (size_t) l); g_pps_len = l; }
            i = e;
        } else i++;
    }
}

static int env_int(const char *k, int defv) {
    const char *v = getenv(k);
    if (!v || !*v) return defv;
    return atoi(v);
}

/* ---------- 对外接口 ---------- */
int h264_enc_open(int tcp_fd, int dw, int dh, int kbps, int fps) {
    g_w = dw & ~1; g_h = dh & ~1;              /* YUV420 要求偶数 */
    g_kbps = kbps > 0 ? kbps : 2500;
    g_fps  = fps  > 0 ? fps  : 30;
    /* 默认值先置 -1，等拿到编码器真正报的 color-format 再定；
     * DC_UV_SWAP / DC_YUV_LAYOUT 显式设了就以环境变量为准。 */
    const int env_swap   = env_int("DC_UV_SWAP", -1);
    const int env_planar = env_int("DC_YUV_LAYOUT", -1);
    g_verbose  = env_int("DC_H264_LOG", 0);
    const int k = env_int("DC_KBPS", 0);
    if (k > 0) g_kbps = k;

    if (tcp_fd < 0) { LOGE("没有控制连接"); return -1; }
    g_tcp = tcp_fd;
    int one = 1;
    setsockopt(g_tcp, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    int snd = 1 * 1024 * 1024;
    setsockopt(g_tcp, SOL_SOCKET, SO_SNDBUF, &snd, sizeof(snd));

    AMediaFormat *fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, g_w);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, g_h);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, COLOR_FMT_FLEXIBLE_YUV420);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BIT_RATE, g_kbps * 1000);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, g_fps);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);

    g_enc = AMediaCodec_createEncoderByType("video/avc");
    if (!g_enc) { LOGE("找不到 AVC 编码器"); AMediaFormat_delete(fmt); return -1; }

    media_status_t st = AMediaCodec_configure(g_enc, fmt, NULL, NULL,
                                              AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(fmt);
    if (st != AMEDIA_OK) { LOGE("encoder configure 失败 %d", (int) st); return -1; }
    if (AMediaCodec_start(g_enc) != AMEDIA_OK) { LOGE("encoder start 失败"); return -1; }

    /* 编码器会把它真正接受的 stride / slice-height / color-format 报回来，
     * 必须按它给的写，不能想当然用 w*h。 */
    AMediaFormat *ifmt = AMediaCodec_getInputFormat(g_enc);
    int cf = 0, stride = 0, slice = 0;
    if (ifmt) {
        if (!AMediaFormat_getInt32(ifmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, &cf))    cf = 0;
        if (!AMediaFormat_getInt32(ifmt, AMEDIAFORMAT_KEY_STRIDE, &stride))      stride = 0;
        if (!AMediaFormat_getInt32(ifmt, AMEDIAFORMAT_KEY_SLICE_HEIGHT, &slice)) slice = 0;
        AMediaFormat_delete(ifmt);
    }
    g_stride = stride > 0 ? stride : ((g_w + 15) & ~15);
    g_slice  = slice  > 0 ? slice  : ((g_h + 15) & ~15);

    /*
     * 色度平面的排布。Android 的 OMX_COLOR_FormatYUV420SemiPlanar(21) 名义上是
     * NV12（U,V 交织），但高通/大部分 SoC 实际上按 NV21（V,U）吃 —— 实测这台
     * AP 报 cf=0x15，按 NV12 写会整体 R/B 互换（青绿主色变成橄榄色）。
     * 所以半平面一律按 NV21 写，要覆盖就设 DC_UV_SWAP。
     */
    int def_planar = 0, def_swap = 0;
    switch (cf) {
        case 19:                    /* COLOR_FormatYUV420Planar  I420 */
            def_planar = 1; break;
        case 21:                    /* COLOR_FormatYUV420SemiPlanar —— 实测是 NV21 */
        case 39:                    /* COLOR_FormatYUV420PackedSemiPlanar（NV21 明确版） */
            def_swap = 1; break;
        default:
            if (cf == COLOR_FMT_FLEXIBLE_YUV420) def_swap = 1;   /* 报回来通常也是半平面 */
            break;
    }
    g_planar  = (env_planar >= 0) ? (env_planar ? 1 : 0) : def_planar;
    g_uv_swap = (env_swap   >= 0) ? (env_swap   ? 1 : 0) : def_swap;

    clock_gettime(CLOCK_MONOTONIC, &g_t_last);
    LOGI("H.264 就绪 %dx%d @%dfps %dkbps (TCP fd=%d) | stride=%d slice=%d cf=0x%x uvswap=%d planar=%d",
         g_w, g_h, g_fps, g_kbps, tcp_fd, g_stride, g_slice, cf, g_uv_swap, g_planar);
    return 0;
}

/* 喂一帧 BGRA（源尺寸是编码尺寸的 bw×bh 倍） */
int h264_enc_feed(const uint8_t *bgra, int sw, int sh) {
    if (!g_enc) return -1;

    ssize_t idx = AMediaCodec_dequeueInputBuffer(g_enc, 2000);
    if (idx < 0) return 0;                       /* 编码器忙，丢这一帧 */

    size_t cap = 0;
    uint8_t *buf = AMediaCodec_getInputBuffer(g_enc, idx, &cap);
    if (!buf) return -1;
    const size_t need = (size_t) g_stride * g_slice * 3 / 2;
    if (cap < need) { LOGE("输入缓冲太小 %zu < %zu", cap, need); return -1; }

    bgra_to_yuv(bgra, sw, sh, buf, g_w, g_h);

    g_pts += 1000000ULL / (uint64_t) g_fps;
    if (AMediaCodec_queueInputBuffer(g_enc, idx, 0, need, g_pts, 0) != AMEDIA_OK) {
        LOGE("queueInputBuffer 失败");
        return -1;
    }
    g_fed++;
    return 0;
}

/* 把编码器里已经好了的帧全部取出来发走 */
void h264_enc_drain(void) {
    if (!g_enc) return;
    for (;;) {
        AMediaCodecBufferInfo info;
        ssize_t oi = AMediaCodec_dequeueOutputBuffer(g_enc, &info, 0);
        if (oi == AMEDIACODEC_INFO_TRY_AGAIN_LATER) break;
        if (oi == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *of = AMediaCodec_getOutputFormat(g_enc);
            int w = 0, h = 0;
            if (of) {
                AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_WIDTH, &w);
                AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_HEIGHT, &h);
                AMediaFormat_delete(of);
            }
            LOGI("输出格式变化：%dx%d", w, h);
            continue;
        }
        if (oi == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
        if (oi < 0) break;

        size_t osz = 0;
        uint8_t *ob = AMediaCodec_getOutputBuffer(g_enc, oi, &osz);
        if (ob && info.size > 0) {
            const uint8_t *p = ob + info.offset;
            const int n = (int) info.size;
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
                save_sps_pps(p, n);
            } else {
                if (send_access_unit(p, n) != 0) {
                    LOGE("写码流失路（CP 断了？）");
                    return;
                }
                g_frames++;
                g_stat_frames++;
            }
        }
        AMediaCodec_releaseOutputBuffer(g_enc, oi, false);
    }

    if (g_verbose) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        double dt = (double) (now.tv_sec - g_t_last.tv_sec) +
                    (double) (now.tv_nsec - g_t_last.tv_nsec) / 1e9;
        if (dt >= 2.0) {
            LOGI("H.264 喂=%ld 出=%ld %.1ffps %.0f KB/s | NAL=%ld 发字节=%ld 失败=%ld",
                 g_fed, g_frames, (double) g_stat_frames / dt,
                 (double) (g_bytes - g_stat_bytes) / dt / 1024.0,
                 g_pkts, g_sent, g_sfail);
            g_t_last = now;
            g_stat_frames = 0;
            g_stat_bytes = g_bytes;
        }
    }
}

void h264_enc_stats(long *frames, long *bytes) {
    if (frames) *frames = g_frames;
    if (bytes)  *bytes  = g_bytes;
}

void h264_enc_close(void) {
    if (g_enc) {
        AMediaCodec_stop(g_enc);
        AMediaCodec_delete(g_enc);
        g_enc = NULL;
    }
    g_tcp = -1;   /* fd 归 dcapd2 管，这里不关 */
    LOGI("H.264 关闭：喂=%ld 出帧=%ld 发字节=%ld", g_fed, g_frames, g_bytes);
}
