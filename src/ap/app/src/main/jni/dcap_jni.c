/*
 * dcap_jni.c — AP 侧 native 抓帧热路径（libdcap.so）
 *
 * 分工（方案 A：真 APK 拿 appToken，native 只做像素搬运）：
 *   Java  : DisplayManager.createVirtualDisplay + ImageReader  → 拥有 appToken，能承载别的 App
 *   native: stride 拆解 → 图块比对 → RGBA→BGRA → LZ4 → 组帧 → TCP 发送
 *
 * 为什么这些必须落在 native：
 *   1920×532 = 1,021,440 像素/帧。Java 里逐像素 ByteBuffer.get() 是 400 万次 JNI 边界
 *   调用，单帧 40–60 ms，帧率直接死。native 一遍 memcmp + 一遍向量化拷贝，全帧 < 20 ms，
 *   静态画面 0 开销（acquireLatestImage 返回 null，连比对都不做）。
 *
 * 协议：与 ../../../../../src/dashcast_proto.h 完全一致，CP 端 dashcastd 不改一行。
 *   握手 28B / 帧头 16B / 图块头 20B / 像素 BGRA（= wl_shm ARGB8888 的小端字节序）
 *
 * 线程约定：ctx 只允许被同一个线程使用（Java 侧单线程 senderLoop）。
 */
#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include "dashcast_proto.h"
#include "lz4mini.h"   /* 只用它的解码器做自检，验证编码输出能被 CP 解开 */

#define LOG_TAG "DcapJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define TILE 64

/* ============================ LZ4 block 编码器 ============================ */
/*
 * 标准 LZ4 block 格式，无 4 字节长度前缀（与 CP 端 lz4mini.h 的解码器约定一致）。
 * 语义与 lz4 官方 LZ4_compress_default 相同，只是实现更短：
 *   token(1B) | [litLen ext] | literals | offset(2B LE) | [matchLen ext]
 */
#define LZ4_HASH_LOG   12
#define LZ4_HASH_SIZE  (1 << LZ4_HASH_LOG)
#define LZ4_MINMATCH   4
#define LZ4_MFLIMIT    12
#define LZ4_LASTLIT    5

static inline uint32_t rd32(const uint8_t *p) {
    uint32_t v;
    memcpy(&v, p, 4);
    return v;
}

static inline uint32_t lz4_hash(uint32_t v) {
    return (v * 2654435761u) >> (32 - LZ4_HASH_LOG);
}

/* 返回压缩字节数；0 表示压不动（调用方回退原始像素） */
static int lz4_compress_block(const uint8_t *src, int n, uint8_t *dst,
                              int dst_cap, int *table)
{
    if (n <= 0 || dst_cap < n + 16) return 0;

    memset(table, 0xFF, LZ4_HASH_SIZE * sizeof(int));

    int ip = 0, anchor = 0, op = 0;
    const int mflimit   = n - LZ4_MFLIMIT;
    const int matchlimit = n - LZ4_LASTLIT;

    while (ip < mflimit) {
        uint32_t seq = rd32(src + ip);
        uint32_t h = lz4_hash(seq);
        int match = table[h];
        table[h] = ip;

        if (match >= 0 && (ip - match) < 65535 && (match + 3) < n
                && rd32(src + match) == seq) {
            int max_len = matchlimit - ip;
            if (max_len < LZ4_MINMATCH) break;

            int len = LZ4_MINMATCH;
            while (len < max_len && src[match + len] == src[ip + len]) len++;

            int lit_len = ip - anchor;
            if (op + 1 + lit_len + 2 + 10 > dst_cap) return 0;

            int token_pos = op++;
            int lit = lit_len;
            if (lit >= 15) {
                dst[token_pos] = (uint8_t) 0xF0;
                lit -= 15;
                while (lit >= 255) { dst[op++] = 255; lit -= 255; }
                dst[op++] = (uint8_t) lit;
            } else {
                dst[token_pos] = (uint8_t) (lit << 4);
            }

            memcpy(dst + op, src + anchor, (size_t) lit_len);
            op += lit_len;

            int off = ip - match;
            dst[op++] = (uint8_t) (off & 0xFF);
            dst[op++] = (uint8_t) ((off >> 8) & 0xFF);

            int ml = len - LZ4_MINMATCH;
            if (ml >= 15) {
                dst[token_pos] |= 0x0F;
                ml -= 15;
                while (ml >= 255) { dst[op++] = 255; ml -= 255; }
                dst[op++] = (uint8_t) ml;
            } else {
                dst[token_pos] |= (uint8_t) ml;
            }

            ip += len;
            anchor = ip;

            /* 补一格哈希，长匹配之后的命中率明显更好 */
            if (ip < mflimit) table[lz4_hash(rd32(src + ip - 2))] = ip - 2;
            continue;
        }
        ip++;
    }

    /* 末尾纯 literal（LZ4 规范要求至少 5 字节，无需 offset） */
    int lit_len = n - anchor;
    if (op + 1 + lit_len + 10 > dst_cap) return 0;

    int token_pos = op++;
    int lit = lit_len;
    if (lit >= 15) {
        dst[token_pos] = (uint8_t) 0xF0;
        lit -= 15;
        while (lit >= 255) { dst[op++] = 255; lit -= 255; }
        dst[op++] = (uint8_t) lit;
    } else {
        dst[token_pos] = (uint8_t) (lit << 4);
    }
    memcpy(dst + op, src + anchor, (size_t) lit_len);
    op += lit_len;
    return op;
}

/* ============================== socket 工具 ============================== */

static int write_all(int fd, const void *buf, size_t len)
{
    const uint8_t *p = (const uint8_t *) buf;
    while (len > 0) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        p += n;
        len -= (size_t) n;
    }
    return 0;
}

static int read_full(int fd, void *buf, size_t len)
{
    uint8_t *p = (uint8_t *) buf;
    size_t got = 0;
    while (got < len) {
        ssize_t n = recv(fd, p + got, len - got, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        got += (size_t) n;
    }
    return 0;
}

/* ================================ 上下文 ================================ */

typedef struct {
    int w, h;
    int cols, rows;
    int ntiles;

    uint8_t *prev;        /* 上一帧，已去掉 stride 的 RGBA 紧密排列 */
    size_t   prev_cap;

    uint8_t *tile;        /* 单块原始像素（已转 BGRA） */
    size_t   tile_cap;

    uint8_t *comp;        /* LZ4 输出 */
    size_t   comp_cap;

    uint8_t *hdr;         /* 帧头 + 所有图块头 */
    size_t   hdr_cap;

    uint8_t *pay;         /* 本帧所有像素数据 */
    size_t   pay_cap;

    int     *htab;        /* LZ4 哈希表 */

    int     fd;
    int     seq;
    int     last_tiles;
    int     last_bytes;
    long long bytes_total;
} dcap_t;

static void ctx_free(dcap_t *d)
{
    if (!d) return;
    if (d->fd >= 0) { close(d->fd); d->fd = -1; }
    free(d->prev);
    free(d->tile);
    free(d->comp);
    free(d->hdr);
    free(d->pay);
    free(d->htab);
    free(d);
}

/* ================================ JNI 导出 ================================ */

JNIEXPORT jlong JNICALL
Java_com_hermes_dashcast_DcapNative_create(JNIEnv *env, jclass clazz,
                                                 jint w, jint h)
{
    (void) env; (void) clazz;
    if (w <= 0 || h <= 0) return 0;

    dcap_t *d = (dcap_t *) calloc(1, sizeof(dcap_t));
    if (!d) return 0;

    d->w = w;
    d->h = h;
    d->cols = (w + TILE - 1) / TILE;
    d->rows = (h + TILE - 1) / TILE;
    d->ntiles = d->cols * d->rows;
    d->fd = -1;

    d->prev_cap = (size_t) w * h * 4;
    d->tile_cap = TILE * TILE * 4;
    d->comp_cap = d->tile_cap + d->tile_cap / 255 + 64;
    d->hdr_cap  = sizeof(dashcast_frame_header_t)
                + (size_t) d->ntiles * sizeof(dashcast_tile_t);
    d->pay_cap  = d->prev_cap + 4096;

    d->prev = (uint8_t *) calloc(1, d->prev_cap);
    d->tile = (uint8_t *) malloc(d->tile_cap);
    d->comp = (uint8_t *) malloc(d->comp_cap);
    d->hdr  = (uint8_t *) malloc(d->hdr_cap);
    d->pay  = (uint8_t *) malloc(d->pay_cap);
    d->htab = (int *) malloc(LZ4_HASH_SIZE * sizeof(int));

    if (!d->prev || !d->tile || !d->comp || !d->hdr || !d->pay || !d->htab) {
        LOGE("分配失败");
        ctx_free(d);
        return 0;
    }

    LOGI("ctx %dx%d, %d 图块, prev %.1f MB",
         w, h, d->ntiles, d->prev_cap / 1048576.0);
    return (jlong) (intptr_t) d;
}

JNIEXPORT jboolean JNICALL
Java_com_hermes_dashcast_DcapNative_connect(JNIEnv *env, jclass clazz,
                                                  jlong handle, jstring host,
                                                  jint port)
{
    (void) clazz;
    dcap_t *d = (dcap_t *) (intptr_t) handle;
    if (!d) return JNI_FALSE;

    if (d->fd >= 0) { close(d->fd); d->fd = -1; }

    const char *host_chars = (*env)->GetStringUTFChars(env, host, NULL);
    if (!host_chars) return JNI_FALSE;

    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons((uint16_t) port);
    int ok = inet_pton(AF_INET, host_chars, &sa.sin_addr) == 1;
    if (!ok) LOGE("host 非 IPv4: %s", host_chars);
    (*env)->ReleaseStringUTFChars(env, host, host_chars);
    if (!ok) return JNI_FALSE;

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { LOGE("socket 失败: %d", errno); return JNI_FALSE; }

    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));

    /* 连接 + 握手阶段要有超时，否则 CP 不在时会卡死发送线程 */
    struct timeval tv = { 3, 0 };
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    struct timeval rv = { 3, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rv, sizeof(rv));

    if (connect(fd, (struct sockaddr *) &sa, sizeof(sa)) != 0) {
        LOGE("connect %s:%d 失败: %d", "", port, errno);
        close(fd);
        return JNI_FALSE;
    }

    dashcast_handshake_t hs;
    hs.magic   = DASHCAST_MAGIC;
    hs.version = DASHCAST_VERSION;
    hs.width   = (uint32_t) d->w;
    hs.height  = (uint32_t) d->h;
    hs.codec   = DASHCAST_CODEC_BGRA;
    hs.flags   = 0;
    hs.rsvd    = 0;

    if (write_all(fd, &hs, sizeof(hs)) != 0) {
        LOGE("握手发送失败: %d", errno);
        close(fd);
        return JNI_FALSE;
    }

    dashcast_handshake_ack_t ack;
    if (read_full(fd, &ack, sizeof(ack)) != 0) {
        LOGE("握手 ACK 读取失败（CP 端 dashcastd 未起？）: %d", errno);
        close(fd);
        return JNI_FALSE;
    }
    if (ack.magic != DASHCAST_MAGIC || ack.status != 0) {
        LOGE("握手被拒: magic=%08x status=%d", ack.magic, ack.status);
        close(fd);
        return JNI_FALSE;
    }

    /* 握手完成后不再等读超时：这条连接单向，只有我们在写 */
    memset(&rv, 0, sizeof(rv));
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rv, sizeof(rv));

    d->fd = fd;
    d->seq = 0;
    memset(d->prev, 0, d->prev_cap);

    LOGI("已连 %d，握手 OK %dx%d", port, d->w, d->h);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_hermes_dashcast_DcapNative_disconnect(JNIEnv *env, jclass clazz,
                                                     jlong handle)
{
    (void) env; (void) clazz;
    dcap_t *d = (dcap_t *) (intptr_t) handle;
    if (!d) return;
    if (d->fd >= 0) { close(d->fd); d->fd = -1; }
    memset(d->prev, 0, d->prev_cap);   /* 下次连上必发整帧 */
}

/*
 * 抓一帧 → 打包 → 发送。
 * 返回：本帧发出的图块数；0 = 画面没变（一个字节都不发）；<0 = 链路错误。
 */
JNIEXPORT jint JNICALL
Java_com_hermes_dashcast_DcapNative_process(JNIEnv *env, jclass clazz,
                                                  jlong handle, jobject buffer,
                                                  jint row_stride, jint pixel_stride,
                                                  jboolean force_full)
{
    (void) clazz;
    dcap_t *d = (dcap_t *) (intptr_t) handle;
    if (!d || d->fd < 0) return -1;

    uint8_t *src = (uint8_t *) (*env)->GetDirectBufferAddress(env, buffer);
    if (!src) { LOGE("frame buffer 不是 direct buffer"); return -1; }

    if (pixel_stride != 4) {
        LOGE("pixelStride=%d 不支持（RGBA_8888 应为 4）", pixel_stride);
        return -1;
    }
    if (row_stride <= 0) row_stride = d->w * 4;

    size_t hdr_len = sizeof(dashcast_frame_header_t);
    size_t pay_len = 0;
    int n = 0;

    for (int ty = 0; ty < d->rows; ty++) {
        int y0 = ty * TILE;
        int th = d->h - y0; if (th > TILE) th = TILE;

        for (int tx = 0; tx < d->cols; tx++) {
            int x0 = tx * TILE;
            int tw = d->w - x0; if (tw > TILE) tw = TILE;

            const size_t row_bytes = (size_t) tw * 4;
            uint8_t *s = src + (size_t) y0 * (size_t) row_stride + (size_t) x0 * 4;
            uint8_t *p = d->prev + ((size_t) y0 * d->w + x0) * 4;

            int changed = force_full ? 1 : 0;
            if (!changed) {
                for (int j = 0; j < th; j++) {
                    if (memcmp(s + (size_t) j * (size_t) row_stride,
                               p + (size_t) j * (size_t) d->w * 4,
                               row_bytes) != 0) {
                        changed = 1;
                        break;
                    }
                }
            }
            if (!changed) continue;

            /* 抽出图块：顺手把 RGBA 换成 BGRA（wl_shm ARGB8888 的小端字节序），
             * 同时把这块像素写回 prev，省掉整帧拷贝 */
            for (int j = 0; j < th; j++) {
                const uint8_t *sr = s + (size_t) j * (size_t) row_stride;
                uint8_t *pr = p + (size_t) j * (size_t) d->w * 4;
                uint8_t *dr = d->tile + (size_t) j * row_bytes;

                memcpy(pr, sr, row_bytes);

                if ((((uintptr_t) sr) | ((uintptr_t) dr)) & 3u) {
                    for (size_t i = 0; i < row_bytes; i += 4) {
                        dr[i + 0] = sr[i + 2];
                        dr[i + 1] = sr[i + 1];
                        dr[i + 2] = sr[i + 0];
                        dr[i + 3] = sr[i + 3];
                    }
                } else {
                    const uint32_t *in = (const uint32_t *) sr;
                    uint32_t *out = (uint32_t *) dr;
                    for (int i = 0; i < tw; i++) {
                        uint32_t v = in[i];
                        out[i] = (v & 0xFF00FF00u)
                               | ((v & 0x00FF0000u) >> 16)
                               | ((v & 0x000000FFu) << 16);
                    }
                }
            }

            const int raw_len = tw * th * 4;
            uint8_t *payload = d->tile;
            int data_len = raw_len;
            uint16_t mode = DASHCAST_CODEC_BGRA;

            int comp_len = lz4_compress_block(d->tile, raw_len, d->comp,
                                              (int) d->comp_cap, d->htab);
            if (comp_len > 0 && comp_len < raw_len) {
                payload = d->comp;
                data_len = comp_len;
                mode = DASHCAST_CODEC_RLE;
            }

            if (pay_len + (size_t) data_len > d->pay_cap) {
                LOGE("payload 溢出（%zu + %d > %zu）", pay_len, data_len, d->pay_cap);
                return -1;
            }

            dashcast_tile_t tile;
            tile.x = (uint16_t) x0;
            tile.y = (uint16_t) y0;
            tile.w = (uint16_t) tw;
            tile.h = (uint16_t) th;
            tile.mode = mode;
            tile.rsvd = 0;
            tile.raw_len = (uint32_t) raw_len;
            tile.data_len = (uint32_t) data_len;

            memcpy(d->hdr + hdr_len, &tile, sizeof(tile));
            hdr_len += sizeof(tile);

            memcpy(d->pay + pay_len, payload, (size_t) data_len);
            pay_len += (size_t) data_len;
            n++;
        }
    }

    d->last_tiles = n;
    d->last_bytes = 0;

    if (n == 0) return 0;   /* 画面没变：零带宽，连帧头都不发 */

    dashcast_frame_header_t fh;
    fh.magic = DASHCAST_MAGIC;
    fh.seq = (uint32_t) d->seq++;
    fh.tile_count = (uint32_t) n;
    fh.payload_len = (uint32_t) pay_len;
    memcpy(d->hdr, &fh, sizeof(fh));

    if (write_all(d->fd, d->hdr, hdr_len) != 0) return -1;
    if (write_all(d->fd, d->pay, pay_len) != 0) return -1;

    size_t sent = hdr_len + pay_len;
    d->bytes_total += (long long) sent;
    d->last_bytes = (int) sent;
    return n;
}

JNIEXPORT void JNICALL
Java_com_hermes_dashcast_DcapNative_stats(JNIEnv *env, jclass clazz,
                                                jlong handle, jlongArray out)
{
    (void) clazz;
    dcap_t *d = (dcap_t *) (intptr_t) handle;
    if (!d || !out || (*env)->GetArrayLength(env, out) < 5) return;

    jlong v[5];
    v[0] = d->last_tiles;
    v[1] = d->last_bytes;
    v[2] = d->seq;
    v[3] = d->bytes_total;
    v[4] = (d->fd >= 0) ? 1 : 0;
    (*env)->SetLongArrayRegion(env, out, 0, 5, v);
}

JNIEXPORT void JNICALL
Java_com_hermes_dashcast_DcapNative_destroy(JNIEnv *env, jclass clazz,
                                                  jlong handle)
{
    (void) env; (void) clazz;
    ctx_free((dcap_t *) (intptr_t) handle);
}

/*
 * 自检：造 4 种典型画面（纯色 / 渐变 / 随机噪声 / 文字状条纹），
 * 走一遍编码器，再用 CP 端同一份解码器（lz4mini.h）解回来做 memcmp。
 * 返回 0 = 全通过，非 0 = 第几个用例挂了。
 */
JNIEXPORT jint JNICALL
Java_com_hermes_dashcast_DcapNative_selfTest(JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;

    enum { N = TILE * TILE * 4 };
    uint8_t *src = (uint8_t *) malloc(N);
    uint8_t *cmp = (uint8_t *) malloc(N + N / 255 + 64);
    uint8_t *dec = (uint8_t *) malloc(N);
    int *tab = (int *) malloc(LZ4_HASH_SIZE * sizeof(int));
    if (!src || !cmp || !dec || !tab) {
        free(src); free(cmp); free(dec); free(tab);
        return 99;
    }

    int fails = 0;
    for (int c = 0; c < 4; c++) {
        for (int i = 0; i < N; i++) {
            switch (c) {
                case 0: src[i] = (uint8_t) (i & 3 ? 0x20 : 0xFF); break;
                case 1: src[i] = (uint8_t) (i / 4 + i % 64); break;
                case 2: src[i] = (uint8_t) ((i * 2654435761u) >> 13); break;
                default: src[i] = (uint8_t) ((i % 251 < 40) ? 0xF0 : 0x10); break;
            }
        }
        int cl = lz4_compress_block(src, N, cmp, N + N / 255 + 64, tab);
        if (cl <= 0) { fails++; LOGE("自检 %d: 未压缩", c); continue; }
        int dl = lz4_decompress_block(cmp, (size_t) cl, dec, (size_t) N, (size_t) N);
        if (dl != N || memcmp(src, dec, N) != 0) {
            fails++;
            LOGE("自检 %d: 往返不一致 (cl=%d dl=%d)", c, cl, dl);
        } else {
            LOGI("自检 %d: OK  %d -> %d 字节 (%.1f%%)", c, N, cl,
                 100.0 * cl / N);
        }
    }

    free(src); free(cmp); free(dec); free(tab);
    if (fails == 0) LOGI("LZ4 自检全部通过");
    return fails;
}
