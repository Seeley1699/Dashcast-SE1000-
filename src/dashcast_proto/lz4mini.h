/*
 * lz4mini.h — 极简 LZ4 block 解码器（仅解码，无压缩依赖）
 *
 * 为什么要自实现：
 *   CP（e04-p171）是弱 SoC，解压要快；且 CP rootfs 不一定带 liblz4。
 *   这里只做解码，代码量小、无依赖，够 dashcast 用。
 *
 * 格式：LZ4 block 格式（无 4 字节原始长度前缀、无字典）。
 *   与 AP 侧编码器（dcap_jni.c / dcapd2.c）约定一致 —— 编码也只写 token+
 *   literal+offset+match 的裸序列，不追加任何长度头。
 *
 * 健壮性：所有长度判定改用「剩余字节数」比较，避免 op+len / ip+len 这类
 *   会先越界再比较的指针运算（UB）；扩展字节循环也带边界检查。畸形输入
 *   一律返回 -1，绝不越界读写。
 */
#ifndef LZ4MINI_H
#define LZ4MINI_H

#include <stdint.h>
#include <stddef.h>

/*
 * 解码一个 LZ4 block。
 *   src / src_size : 压缩数据
 *   dst / dst_cap  : 输出缓冲（容量必须 >= 解压后长度）
 *   expected_size  : 预期解压后长度（用于校验，0 表示不校验）
 * 返回：解压后字节数；失败返回 -1。
 */
static inline int lz4_decompress_block(const uint8_t *src, size_t src_size,
                                       uint8_t *dst, size_t dst_cap,
                                       size_t expected_size)
{
    const uint8_t *ip = src;
    const uint8_t *const iend = src + src_size;
    uint8_t *op = dst;
    uint8_t *const oend = dst + dst_cap;

    while (ip < iend) {
        /* token: 高 4 位 = literal 长度，低 4 位 = match 长度 - 4 */
        const uint8_t token = *ip++;
        size_t lit_len = token >> 4;
        size_t match_len = (token & 0x0F) + 4;

        /* 扩展 literal 长度（15 → 继续累加，直到遇到非 255 字节） */
        if (lit_len == 15) {
            uint8_t b;
            do {
                if (ip >= iend) return -1;
                b = *ip++;
                lit_len += b;
            } while (b == 255);
        }

        /* 拷贝 literal：先判剩余，再逐字节（不用 op+len / ip+len 的指针加法） */
        if ((size_t)(oend - op) < lit_len) return -1;
        if ((size_t)(iend - ip) < lit_len) return -1;
        for (size_t i = 0; i < lit_len; i++) *op++ = *ip++;

        /* match 需要 2 字节 offset（低位在前） */
        if (ip == iend) {
            /* 末尾只有 literal，没有 match —— 合法 */
            break;
        }
        if ((size_t)(iend - ip) < 2) return -1;

        const size_t offset = (size_t)ip[0] | ((size_t)ip[1] << 8);
        ip += 2;
        if (offset == 0 || offset > (size_t)(op - dst)) return -1;

        /* 扩展 match 长度（19 → 继续累加） */
        if (match_len == 19) {
            uint8_t b;
            do {
                if (ip >= iend) return -1;
                b = *ip++;
                match_len += b;
            } while (b == 255);
        }

        if ((size_t)(oend - op) < match_len) return -1;

        /* 拷贝 match（允许重叠，逐字节） */
        const uint8_t *m = op - offset;
        for (size_t i = 0; i < match_len; i++) {
            *op++ = *m++;
        }
    }

    const size_t out = (size_t)(op - dst);
    if (expected_size != 0 && out != expected_size) return -1;
    return (int)out;
}

#endif /* LZ4MINI_H */