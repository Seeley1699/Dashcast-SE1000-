/* h264stub.c - BGRA+LZ4 链路不需要 H.264 编码器。
 * 给链接期提供空实现（真实编码见 h264enc.c，依赖 NDK media 头）。
 * 全部返回失败/0，dcapd2 的 H.264 分支会优雅降级到 BGRA。 */
#include <stddef.h>
#include <stdint.h>

int h264_enc_open(int tcp_fd, int dw, int dh, int kbps, int fps) {
    (void)tcp_fd; (void)dw; (void)dh; (void)kbps; (void)fps;
    return -1;
}
int h264_enc_feed(const uint8_t *bgra, int sw, int sh) {
    (void)bgra; (void)sw; (void)sh;
    return -1;
}
int h264_enc_feed_crop(const uint8_t *bgra, int sw, int sh,
                       int cx, int cy, int cw, int ch) {
    (void)bgra; (void)sw; (void)sh; (void)cx; (void)cy; (void)cw; (void)ch;
    return -1;
}
int h264_enc_drain(void) { return 0; }
void h264_enc_stats(long *frames, long *bytes) {
    if (frames) *frames = 0;
    if (bytes) *bytes = 0;
}
void h264_enc_close(void) {}