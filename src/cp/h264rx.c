// h264rx.c —— CP 侧 H.264 接收 + 软解 + 上屏
//
// 码流格式（AP 侧 h264enc.c 发）：
//     [u32be 长度][一个完整 NAL] 重复……
// 走的是握手那条 TCP 连接本身 —— 这台车机的 AP<->CP 链路会把每个 UDP 包
// 送两份（实测重复率 53%），重复包会把分片拼两遍、码流直接废掉，
// 所以不走 UDP/RTP，直接借 TCP 的"不重不丢不乱序"。
//
// 设计取舍：
//   · 不做 jitter buffer —— 点对点链路，TCP 已经保证顺序；解码出帧才提交，
//     没出帧就保持上一帧（合成器自己不会动）。
//   · SPS/PPS 由 AP 在每个 IDR 前补发，中途接入也能立刻解出来。
//   · 不用锁：只有本线程写 dst，commit 由调用方保证线程安全。

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdarg.h>
#include <time.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <stdlib.h>

extern void *h264_open(void);
extern int   h264_decode(void *, const unsigned char *, int,
                         unsigned char **, unsigned char **, unsigned char **,
                         int *, int *, int *, int *);
extern void  h264_reset(void *);
extern void  h264_close(void *);

#define MAX_NAL (4 * 1024 * 1024)

static uint8_t *g_nal = NULL;          /* NAL 缓冲，按需分配 */
static int      g_nal_cap = 0;

/* openh264 的 VIDEO_BITSTREAM_DEFAULT 认 Annex-B，必须带起始码；
 * 线上格式是裸 NAL（长度前缀已经定界了），喂之前补一个 00 00 00 01。 */
static uint8_t *g_in = NULL;
static int      g_in_cap = 0;

static long g_nals = 0, g_frames = 0, g_feed = 0, g_bytes = 0;
static long g_rv[8];                   /* h264_decode 返回值分布 */
static int  g_hist[32];                /* NAL 类型直方图 */
static int  g_dumped = 0;
static int g_restart_flag = 0;   /* 1 = 解码尺寸与画布不符，请断开让 AP 重连 */

static void yuv_to_argb(uint8_t *dst, const uint8_t *yp, const uint8_t *up, const uint8_t *vp,
                        int w, int h, int sy, int suv) {
    for (int j = 0; j < h; j++) {
        const uint8_t *y = yp + (size_t) j * sy;
        const uint8_t *u = up + (size_t) (j >> 1) * suv;
        const uint8_t *v = vp + (size_t) (j >> 1) * suv;
        uint32_t *row = (uint32_t *) (dst + (size_t) j * w * 4);
        for (int i = 0; i < w; i++) {
            int Y = y[i];
            int U = (int) u[i >> 1] - 128;
            int V = (int) v[i >> 1] - 128;
            int r = Y + ((91881 * V) >> 16);
            int g = Y - ((22554 * U + 46802 * V) >> 16);
            int b = Y + ((116130 * U) >> 16);
            if (r < 0) r = 0; else if (r > 255) r = 255;
            if (g < 0) g = 0; else if (g > 255) g = 255;
            if (b < 0) b = 0; else if (b > 255) b = 255;
            row[i] = 0xFF000000u | ((uint32_t) r << 16) | ((uint32_t) g << 8) | (uint32_t) b;
        }
    }
}

/* 读满 n 字节；1 = 成功，0 = 对端关了，-1 = 出错 */
static int read_full(int fd, uint8_t *buf, size_t n) {
    size_t done = 0;
    while (done < n) {
        ssize_t r = recv(fd, buf + done, n - done, 0);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) return 0;
        done += (size_t) r;
    }
    return 1;
}

static void log_line(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    fflush(stdout);
}

/* 调试用：DC_DUMP_FRAME=n 时把第 n 帧存成 PPM，全尺寸 + 1/6 缩略图，
 * 拉回宿主看一眼，确认码流和颜色（U/V 顺序）对不对。 */
static long g_dump_at = -1;
static void maybe_dump(const uint8_t *argb, int w, int h) {
    if (g_dump_at < 0) return;
    if (g_frames != g_dump_at) return;
    g_dump_at = -1;

    FILE *f = fopen("/run/dcfull.ppm", "wb");
    if (f) {
        fprintf(f, "P6\n%d %d\n255\n", w, h);
        for (int j = 0; j < h; j++) {
            const uint8_t *row = argb + (size_t) j * w * 4;
            for (int i = 0; i < w; i++) {
                /* 内存是 BGRA，PPM 要 RGB */
                const uint8_t px[3] = { row[i * 4 + 2], row[i * 4 + 1], row[i * 4] };
                fwrite(px, 1, 3, f);
            }
        }
        fclose(f);
    }
    const int sw = w / 6, sh = h / 6;
    FILE *g = fopen("/run/dcsmall.ppm", "wb");
    if (g) {
        fprintf(g, "P6\n%d %d\n255\n", sw, sh);
        for (int j = 0; j < sh; j++) {
            const uint8_t *row = argb + (size_t) (j * 6) * w * 4;
            for (int i = 0; i < sw; i++) {
                const uint8_t *p = row + (i * 6) * 4;
                const uint8_t px[3] = { p[2], p[1], p[0] };
                fwrite(px, 1, 3, g);
            }
        }
        fclose(g);
    }
    log_line("h264rx: 已落第 %ld 帧 -> /run/dcfull.ppm  /run/dcsmall.ppm", g_frames);
}

/* 把一个完整 NAL 喂给解码器；出帧就转换 + 提交 */
static void feed_nal(void *dec, const uint8_t *nal, int len,
                     uint8_t *dst, int w, int h, void (*commit)(void)) {
    unsigned char *py = NULL, *pu = NULL, *pv = NULL;
    int sy = 0, suv = 0, dw = 0, dh = 0;
    if (len <= 0) return;
    g_hist[nal[0] & 0x1f]++;

    if (len + 4 > g_in_cap) {
        uint8_t *ni = (uint8_t *) realloc(g_in, (size_t) len + 4);
        if (!ni) return;
        g_in = ni;
        g_in_cap = len + 4;
    }
    g_in[0] = 0; g_in[1] = 0; g_in[2] = 0; g_in[3] = 1;
    memcpy(g_in + 4, nal, (size_t) len);

    int r = h264_decode(dec, g_in, len + 4, &py, &pu, &pv, &sy, &suv, &dw, &dh);
    g_feed++;
    if (r >= 0 && r < 8) g_rv[r]++;
    if (r != 1) return;
    if (dw != w || dh != h) {
        /* 分辨率变了（AP 改档）：这一帧丢掉，等下次握手重建 */
        return;
    }
    yuv_to_argb(dst, py, pu, pv, w, h, sy, suv);
    g_frames++;
    maybe_dump(dst, w, h);
    commit();
}

/*
 * 从 TCP 上收码流并解码上屏。返回 0 = 正常结束（AP 断开）。
 * fd 就是握手那条控制连接；调用方（dashcastd）负责关它和拆图层。
 */
int h264_rx_run(int fd, uint8_t *dst, int w, int h,
                void (*commit)(void), volatile int *running) {
    log_line("h264rx: 开始收码流（TCP fd=%d，解码尺寸 %dx%d）", fd, w, h);

    void *dec = h264_open();
    if (!dec) { log_line("h264rx: 解码器创建失败"); return -1; }

    const char *dn = getenv("DC_DUMP_FRAME");
    if (dn && *dn) g_dump_at = atol(dn);

    uint8_t hdr[4];
    time_t last_report = time(NULL);
    int rc = 0;

    while (*running) {
        int rr = read_full(fd, hdr, 4);
        if (rr <= 0) {
            log_line("h264rx: 连接结束（rr=%d）", rr);
            rc = 0;
            break;
        }
        uint32_t len = ((uint32_t) hdr[0] << 24) | ((uint32_t) hdr[1] << 16)
                     | ((uint32_t) hdr[2] << 8) | (uint32_t) hdr[3];
        if (len == 0 || len > MAX_NAL) {
            log_line("h264rx: 非法 NAL 长度 %u，断开", len);
            rc = -1;
            break;
        }
        if (len > (uint32_t) g_nal_cap) {
            uint8_t *nn = (uint8_t *) realloc(g_nal, (size_t) len);
            if (!nn) { log_line("h264rx: 内存不足"); rc = -1; break; }
            g_nal = nn;
            g_nal_cap = (int) len;
        }
        rr = read_full(fd, g_nal, (size_t) len);
        if (rr <= 0) { rc = 0; break; }

        g_nals++;
        g_bytes += len;
        if (g_dumped < 3) {
            g_dumped++;
            log_line("h264rx: 首个 NAL len=%u 头=%02x %02x %02x %02x",
                     len, g_nal[0], g_nal[1], g_nal[2], g_nal[3]);
        }
        feed_nal(dec, g_nal, (int) len, dst, w, h, commit);

        time_t now = time(NULL);
        if (now - last_report >= 5) {
            char hist[256];
            int hp = 0;
            for (int t = 0; t < 32; t++) {
                if (g_hist[t])
                    hp += snprintf(hist + hp, sizeof(hist) - hp, "%d:%d ", t, g_hist[t]);
            }
            log_line("h264rx: 帧=%ld NAL=%ld 喂=%ld %.0f KB/s | rv0=%ld rv1=%ld rv2=%ld rv3=%ld | NAL[%s]",
                     g_frames, g_nals, g_feed, (double) g_bytes / 1024.0,
                     g_rv[0], g_rv[1], g_rv[2], g_rv[3], hist);
            g_bytes = 0;
            last_report = now;
        }
    }

    h264_close(dec);
    /* 尺寸不符导致的退出要区分出来：上层据此决定"断开让 AP 重连" */
    if (g_restart_flag) {
        rc = -2;
        g_restart_flag = 0;
    }
    free(g_nal);
    log_line("h264rx: 退出，共 %ld 个 NAL / %ld 帧", g_nals, g_frames);
    return rc;
}
