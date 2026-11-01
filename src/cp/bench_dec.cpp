// bench_dec.c —— CP 侧 H.264 软解吞吐基准
//
// 目的：回答一个具体问题 —— 这台 CP（2 核）用 openh264 软解
//       704x576@25 的码流，到底能跑多少 fps、吃多少 CPU。
//       数字不够就不用写 H.264 通道了。
//
// 安全：这个程序只读文件、只在内存里解码、只 printf。
//       不打开任何显示设备、不碰 Weston / ivi / 图层 / render order。
//       跑在 /tmp，断电即消失。
//
// 编译（宿主，zig 交叉）：
//   zig c++ -target aarch64-linux-gnu -O2 bench_dec.cpp \
//       /path/libopenh264.so.2.2.0 -I headers -o bench_dec -lpthread

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <ctime>
#include <vector>

#include "codec_api.h"

static double now_sec() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

// Annex-B：按 00 00 01 / 00 00 00 01 切 NAL
static std::vector<std::pair<const uint8_t *, size_t>> split_nals(const std::vector<uint8_t> &buf) {
    std::vector<std::pair<const uint8_t *, size_t>> out;
    std::vector<size_t> starts;
    for (size_t i = 0; i + 3 < buf.size(); ++i) {
        if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) {
            starts.push_back(i);
            i += 2;
        }
    }
    for (size_t k = 0; k < starts.size(); ++k) {
        size_t s = starts[k];
        size_t e = (k + 1 < starts.size()) ? starts[k + 1] : buf.size();
        // 不要削尾随 0 —— 那段可能是 RBSP 的 cabac_zero_word，
        // 削掉会破坏码流（实测只解出 1/3 的帧）。
        (void) 0;
        out.push_back({buf.data() + s, e - s});
    }
    return out;
}

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "/tmp/dcbench/clip.h264";
    int loops = argc > 2 ? atoi(argv[2]) : 8;

    FILE *f = fopen(path, "rb");
    if (!f) { printf("open fail: %s\n", path); return 2; }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> data((size_t) n);
    if (fread(data.data(), 1, (size_t) n, f) != (size_t) n) { printf("read fail\n"); return 2; }
    fclose(f);

    auto nals = split_nals(data);

    ISVCDecoder *dec = nullptr;
    if (WelsCreateDecoder(&dec) != 0 || !dec) { printf("create decoder fail\n"); return 3; }
    SDecodingParam p{};
    p.sVideoProperty.eVideoBsType = VIDEO_BITSTREAM_DEFAULT;
    if (dec->Initialize(&p) != 0) { printf("init fail\n"); return 3; }

    // 先解码一遍（不计时），让 SPS/PPS 生效
    for (auto &nal : nals) {
        uint8_t *yuv[3] = {nullptr, nullptr, nullptr};
        SBufferInfo info;
        memset(&info, 0, sizeof(info));
        dec->DecodeFrameNoDelay(nal.first, (int) nal.second, yuv, &info);
    }

    // 计时开始
    struct timespec c0, c1;
    clock_gettime(CLOCK_MONOTONIC, &c0);
    double c0cpu = (double) clock() / CLOCKS_PER_SEC;

    long frames = 0;
    for (int l = 0; l < loops; ++l) {
        // 每轮重建解码器：片源是 B 帧流，循环回开头时参考帧已失效，
        // 不重置会一路 "Ref Picture for B-Slice is lost"。重建只要 1-2ms。
        if (l > 0) {
            dec->Uninitialize();
            dec->Initialize(&p);
        }
        for (auto &nal : nals) {
            uint8_t *yuv[3] = {nullptr, nullptr, nullptr};
            SBufferInfo info;
            memset(&info, 0, sizeof(info));
            int r = dec->DecodeFrameNoDelay(nal.first, (int) nal.second, yuv, &info);
            if (r == 0 && info.iBufferStatus == 1) ++frames;
        }
    }

    clock_gettime(CLOCK_MONOTONIC, &c1);
    double c1cpu = (double) clock() / CLOCKS_PER_SEC;

    double wall = (c1.tv_sec - c0.tv_sec) + (c1.tv_nsec - c0.tv_nsec) / 1e9;
    double cpu = c1cpu - c0cpu;

    int w = 0, h = 0;
    // 从解出来的最后一帧信息里拿分辨率
    {
        SBufferInfo info; memset(&info, 0, sizeof(info));
        uint8_t *yuv[3] = {nullptr, nullptr, nullptr};
        dec->DecodeFrameNoDelay(nals.empty() ? nullptr : nals[0].first, 0, yuv, &info);
        w = info.UsrData.sSystemBuffer.iWidth;
        h = info.UsrData.sSystemBuffer.iHeight;
    }

    double in_mb = (double) data.size() * loops / 1024.0 / 1024.0;
    printf("RESULT\n");
    printf("  clip        : %s\n", path);
    printf("  size        : %ldx? frames=%ld nals=%zu loops=%d\n", (long) (w ? w : 0), frames, nals.size(), loops);
    printf("  decode      : %.2f s wall, %.2f s cpu\n", wall, cpu);
    printf("  fps         : %.1f\n", frames / wall);
    printf("  ms/frame    : %.2f\n", wall * 1000.0 / (frames > 0 ? frames : 1));
    printf("  cpu/frame   : %.2f ms\n", cpu * 1000.0 / (frames > 0 ? frames : 1));
    printf("  cpu load    : %.0f %% of one core\n", 100.0 * cpu / wall);
    printf("  input rate  : %.1f MB/s\n", in_mb / wall);

    dec->Uninitialize();
    WelsDestroyDecoder(dec);
    return 0;
}
