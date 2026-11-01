/*
 * dcapd.c — 车机侧（AP）native 抓帧进程
 *
 * 第一阶段：验证「root 下 native 能否建虚拟屏 + 抓帧」
 *   - 连 SurfaceFlinger（SurfaceComposerClient）
 *   - createDisplay 建虚拟屏（VIRTUAL，1920x532）
 *   - 挂 BufferQueue producer（setDisplaySurface）
 *   - acquireBuffer 抓一帧，验证能拿到像素
 *   - 输出诊断信息后退出
 *
 * 编译：NDK 26 的 aarch64-linux-android30-clang
 * 链接：-lgui -lbinder -lui -lutils -lcutils -llog -lnativewindow
 *
 * 关键：libgui 的 SurfaceComposerClient::createDisplay / Transaction 是
 *   hidden API，用 AOSP 11 头文件（frameworks/native + system/core）编译。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>

#include <utils/RefBase.h>
#include <utils/Errors.h>

#include <gui/SurfaceComposerClient.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceControl.h>

#include <ui/PixelFormat.h>
#include <ui/Rect.h>
#include <ui/GraphicBuffer.h>
#include <ui/Fence.h>
#include <ui/DisplayState.h>

#include <binder/ProcessState.h>
#include <binder/IPCThreadState.h>

#include <gui/BufferQueue.h>
#include <gui/IGraphicBufferProducer.h>
#include <gui/IGraphicBufferConsumer.h>
#include <gui/BufferItem.h>

#include <system/graphics.h>

#include <android/log.h>

#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, "dcapd", __VA_ARGS__); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, "dcapd", __VA_ARGS__); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)

#define VIRT_W 1920
#define VIRT_H 532
#define VIRT_DPI 160

using namespace android;

int main(int argc, char** argv) {
    fprintf(stderr, "=== dcapd 启动：验证建虚拟屏 ===\n");
    LOGI("=== dcapd 启动：验证建虚拟屏 ===");

    // 初始化 binder（SurfaceFlinger 走 binder 通信）
    fprintf(stderr, ">> 初始化 ProcessState...\n");
    ProcessState::self()->startThreadPool();
    fprintf(stderr, ">> ProcessState OK\n");

    // 1. 连 SurfaceFlinger
    fprintf(stderr, ">> new SurfaceComposerClient...\n");
    sp<SurfaceComposerClient> client = new SurfaceComposerClient();
    fprintf(stderr, ">> SurfaceComposerClient 构造完成，initCheck...\n");
    status_t err = client->initCheck();
    if (err != NO_ERROR) {
        LOGE("SurfaceComposerClient initCheck 失败: %d (%s)", err, strerror(-err));
        return 1;
    }
    LOGI("SurfaceComposerClient 连接 OK");

    // 2. 建虚拟屏（createDisplay(name, isSecure)）
    String8 name("dcapd-virtual");
    sp<IBinder> display = client->createDisplay(name, false /* isSecure */);
    if (display == nullptr) {
        LOGE("createDisplay 返回 null（建虚拟屏失败）");
        return 2;
    }
    LOGI("createDisplay 成功，token=%p", display.get());

    // 3. 建 BufferQueue，拿 producer + consumer
    sp<IGraphicBufferProducer> producer;
    sp<IGraphicBufferConsumer> consumer;
    BufferQueue::createBufferQueue(&producer, &consumer);
    if (producer == nullptr || consumer == nullptr) {
        LOGE("createBufferQueue 失败");
        return 3;
    }
    LOGI("BufferQueue 创建 OK");

    // 设置 consumer 的默认 buffer 尺寸 + 格式 + dataspace
    consumer->setDefaultBufferSize(VIRT_W, VIRT_H);
    consumer->setDefaultBufferFormat(HAL_PIXEL_FORMAT_RGBA_8888);
    consumer->setDefaultBufferDataSpace(HAL_DATASPACE_V0_SRGB);

    // 4. 挂 producer 到虚拟屏（setDisplaySurface）
    SurfaceComposerClient::Transaction t;
    t.setDisplaySurface(display, producer);
    // layerStack 用独立值（避开 0=主屏 1=HDMI 2=launcher），默认 42
    uint32_t layerStack = 42;
    if (argc > 2) layerStack = (uint32_t)atoi(argv[2]);
    t.setDisplayLayerStack(display, layerStack);
    LOGI("虚拟屏 layerStack 设为 %u", layerStack);
    // 投影：整屏
    Rect displayRect(VIRT_W, VIRT_H);
    t.setDisplayProjection(display, ui::ROTATION_0, displayRect, displayRect);
    err = t.apply();
    if (err != NO_ERROR) {
        LOGE("Transaction apply 失败: %d (%s)", err, strerror(-err));
        return 4;
    }
    LOGI("虚拟屏 surface 已挂接（1920x532）");

    // 查虚拟屏的 layerStack（layerStack == displayId，用于 am start --display）
    ui::DisplayState ds;
    if (SurfaceComposerClient::getDisplayState(display, &ds) == NO_ERROR) {
        LOGI("虚拟屏 layerStack=%u（= displayId，am start --display 用这个）", ds.layerStack);
    }

    // 5. 循环抓帧（保持进程存活，期间外部 am start --display 搬 App 进来）
    //    -1 或参数 = 持续秒数；默认 30 秒
    int seconds = 30;
    if (argc > 1) seconds = atoi(argv[1]);

    LOGI("开始循环抓帧 %d 秒（期间可 am start --display %u 搬 App 进来）...", seconds,
         ds.layerStack);

    int frames = 0;
    int nonBlackFrames = 0;
    for (int i = 0; i < seconds * 10; i++) {  // 每 100ms 试一次
        BufferItem item;
        err = consumer->acquireBuffer(&item, 0);
        if (err != NO_ERROR) {
            usleep(100 * 1000);  // 100ms 后重试
            continue;
        }
        frames++;

        if (item.mFence != nullptr) {
            item.mFence->wait(300);  // 最多等 300ms
        }

        sp<GraphicBuffer> buf = item.mGraphicBuffer;
        if (buf == nullptr) {
            consumer->releaseHelper(item.mSlot, item.mFrameNumber, item.mFence);
            continue;
        }

        // 采样判断是否非黑（有内容）
        uint8_t* pixels = nullptr;
        err = buf->lock(GRALLOC_USAGE_SW_READ_OFTEN, reinterpret_cast<void**>(&pixels));
        if (err == NO_ERROR && pixels != nullptr) {
            int nonBlack = 0;
            int stridePx = buf->getStride();
            int w = buf->getWidth(), h = buf->getHeight();
            for (int y = 0; y < h; y += 16) {
                for (int x = 0; x < w; x += 16) {
                    int off = (y * stridePx + x) * 4;
                    if (pixels[off] != 0 || pixels[off+1] != 0 || pixels[off+2] != 0) {
                        nonBlack++;
                    }
                }
            }
            if (nonBlack > 0) nonBlackFrames++;
            if (frames <= 3 || nonBlack > 0) {
                LOGI("[帧 %d] %dx%d stride=%d fmt=%d 非黑采样=%d", frames, w, h,
                     stridePx, buf->getPixelFormat(), nonBlack);
            }
            buf->unlock();
        }

        consumer->releaseHelper(item.mSlot, item.mFrameNumber, item.mFence);
    }

    LOGI("=== 抓帧结束：共 %d 帧，非黑 %d 帧 ===", frames, nonBlackFrames);
    LOGI(nonBlackFrames > 0 ? "✓ 虚拟屏抓到内容（App 已搬入）"
                            : "✗ 虚拟屏无内容（App 未搬入或渲染失败）");

    // 清理
    t.setDisplaySurface(display, nullptr);
    t.apply();
    client->destroyDisplay(display);

    LOGI("dcapd 退出");
    return 0;
}
