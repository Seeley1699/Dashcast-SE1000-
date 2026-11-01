/*
 * dashcast_proto.h — AP/CP 两端共用的投屏协议定义
 *
 * 布局：小端序（车机 aarch64 / x86 均为 LE）
 * 所有字段均为定长，方便两端直接 memcpy。
 *
 * 握手： AP -> CP 发送 DASHCAST_HANDSHAKE，CP 回 DASHCAST_HANDSHAKE_ACK
 * 帧流： AP -> CP 发送 DASHCAST_FRAME_HEADER + 若干 DASHCAST_TILE + 像素数据
 */
#ifndef DASHCAST_PROTO_H
#define DASHCAST_PROTO_H

#include <stdint.h>

/* 魔数：'DCST' */
#define DASHCAST_MAGIC 0x54534344UL

/* 协议版本 */
#define DASHCAST_VERSION 1

/* 图块尺寸（EVCC 原理 §二 的 64×64 经验值） */
#define DASHCAST_TILE_SIZE 64

/* flags 位域（仅 H.264 档使用，BGRA 档恒为 0，向后兼容） */
#define DASHCAST_FLAG_ENC_W(f)   ((int)((f) & 0xffff))
#define DASHCAST_FLAG_ENC_H(f)   ((int)(((f) >> 16) & 0xffff))
#define DASHCAST_FLAGS(ew, eh)   ((uint32_t)(((eh) & 0xffffu) << 16 | ((ew) & 0xffffu)))

/* codec 字段取值 */
#define DASHCAST_CODEC_BGRA 0   /* 原始 BGRA，带 alpha */
#define DASHCAST_CODEC_RLE  1   /* 像素 RLE */
#define DASHCAST_CODEC_H264 2   /* H.264 over RTP/UDP（无 alpha） */

/* 握手包（AP -> CP） */
typedef struct __attribute__((packed)) {
    uint32_t magic;      /* DASHCAST_MAGIC */
    uint32_t version;    /* DASHCAST_VERSION */
    uint32_t width;      /* 画面宽（1920） */
    uint32_t height;     /* 画面高（532） */
    uint32_t codec;      /* DASHCAST_CODEC_* */
    uint32_t flags;      /* H.264: 低16位=编码宽, 高16位=编码高; BGRA: 0 */
    uint32_t rsvd;       /* 保留 */
} dashcast_handshake_t;

/* 握手应答（CP -> AP） */
typedef struct __attribute__((packed)) {
    uint32_t magic;      /* DASHCAST_MAGIC */
    uint32_t status;     /* 0 = OK，非 0 = 拒绝 */
} dashcast_handshake_ack_t;

/* 帧头（AP -> CP） */
typedef struct __attribute__((packed)) {
    uint32_t magic;      /* DASHCAST_MAGIC */
    uint32_t seq;        /* 帧序号，自增 */
    uint32_t tile_count; /* 本帧包含的图块数 */
    uint32_t payload_len;/* 本帧所有像素数据总字节数（不含头） */
} dashcast_frame_header_t;

/* 图块头（AP -> CP），紧跟 frame header 之后，连续 tile_count 个 */
typedef struct __attribute__((packed)) {
    uint16_t x;          /* 图块左上角 X */
    uint16_t y;          /* 图块左上角 Y */
    uint16_t w;          /* 图块宽（<=64） */
    uint16_t h;          /* 图块高（<=64） */
    uint16_t mode;       /* 0=原始 BGRA, 1=像素 RLE */
    uint16_t rsvd;
    uint32_t raw_len;    /* 未压缩像素字节数 = w*h*4 */
    uint32_t data_len;   /* 压缩后字节数（<=raw_len） */
} dashcast_tile_t;

/* 结构体字节数静态校验（编译期锁死，防两端不一致） */
/*
 * 图层矩形（v2.9.1）：AP 在握手 ACK 之后再发一个 20 字节的"图层矩形"，
 * CP 用它设置 ivi 图层的 destination region。
 *
 * 为什么需要：H.264 档没有 alpha（4:2:0），遮罩外的透明区会变成黑块。
 * 改成"把图层本身缩到遮罩那块区域"后 —— 图层外合成器根本不绘制，
 * 原厂仪表自然露出来。两条链路（BGRA+LZ4 / H.264）走同一套逻辑，与 alpha 无关。
 *
 * 发送时机：收到握手 ACK 之后、第一帧之前。握手结构保持 28 字节不变，
 * 旧版 CP 仍能读懂握手；CP 若没收到矩形（超时/旧 AP），按整屏处理=旧行为。
 */
typedef struct __attribute__((packed)) {
    uint32_t magic;      /* DASHCAST_MAGIC */
    uint32_t x;          /* 图层目的矩形（画布坐标） */
    uint32_t y;
    uint32_t w;
    uint32_t h;
} dashcast_layer_rect_t;

_Static_assert(sizeof(dashcast_handshake_t) == 28, "handshake must be 28 bytes");
_Static_assert(sizeof(dashcast_handshake_ack_t) == 8, "handshake_ack must be 8 bytes");
_Static_assert(sizeof(dashcast_frame_header_t) == 16, "frame_header must be 16 bytes");
_Static_assert(sizeof(dashcast_tile_t) == 20, "tile must be 20 bytes");
_Static_assert(sizeof(dashcast_layer_rect_t) == 20, "layer_rect must be 20 bytes");

#endif /* DASHCAST_PROTO_H */
