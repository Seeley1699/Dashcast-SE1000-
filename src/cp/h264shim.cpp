// h264shim.cpp —— 给 C 代码用的 openh264 薄封装
//
// openh264 的 codec_api.h 在 C++ 下是类，在纯 C 下是裸虚表指针，
// 直接 `dec->Foo()` 在 C 里编译不过。与其在 dashcastd.c 里玩函数指针，
// 不如单独编译一个 C++ 翻译单元，对外只暴露 3 个 C 函数。

#include <string.h>
#include <stdint.h>
#include "codec_api.h"

extern "C" {

void *h264_open(void) {
    ISVCDecoder *dec = nullptr;
    if (WelsCreateDecoder(&dec) != 0 || !dec) return nullptr;
    SDecodingParam p;
    memset(&p, 0, sizeof(p));
    p.sVideoProperty.eVideoBsType = VIDEO_BITSTREAM_DEFAULT;
    if (dec->Initialize(&p) != 0) { WelsDestroyDecoder(dec); return nullptr; }
    return dec;
}

/* 返回 1 = 出一帧；0 = 没帧；-1 = 出错 */
int h264_decode(void *h, const unsigned char *buf, int len,
                unsigned char **py, unsigned char **pu, unsigned char **pv,
                int *sy, int *suv, int *w, int *hh) {
    ISVCDecoder *dec = (ISVCDecoder *) h;
    if (!dec) return -1;
    uint8_t *yuv[3] = {nullptr, nullptr, nullptr};
    SBufferInfo info;
    memset(&info, 0, sizeof(info));
    int r = dec->DecodeFrameNoDelay(buf, len, yuv, &info);
    if (r != 0 || info.iBufferStatus != 1) return 0;
    *py = yuv[0]; *pu = yuv[1]; *pv = yuv[2];
    *sy = info.UsrData.sSystemBuffer.iStride[0];
    *suv = info.UsrData.sSystemBuffer.iStride[1];
    *w = info.UsrData.sSystemBuffer.iWidth;
    *hh = info.UsrData.sSystemBuffer.iHeight;
    return 1;
}

/* 循环回开头时重置参考帧 */
void h264_reset(void *h) {
    ISVCDecoder *dec = (ISVCDecoder *) h;
    if (!dec) return;
    SDecodingParam p;
    memset(&p, 0, sizeof(p));
    p.sVideoProperty.eVideoBsType = VIDEO_BITSTREAM_DEFAULT;
    dec->Uninitialize();
    dec->Initialize(&p);
}

void h264_close(void *h) {
    ISVCDecoder *dec = (ISVCDecoder *) h;
    if (!dec) return;
    dec->Uninitialize();
    WelsDestroyDecoder(dec);
}

}  // extern "C"
