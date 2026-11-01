// 最小 stub：android/hardware/graphics/common/1.2/types.h
// 我的代码只用 Dataspace::V0_SRGB，这里给最小枚举定义。
#pragma once

#include <cstdint>

namespace android {
namespace hardware {
namespace graphics {
namespace common {
namespace V1_2 {

// Dataspace 枚举（完整版有几十个值，这里只保留我需要的）
enum class Dataspace : int32_t {
    UNKNOWN = 0,
    V0_SRGB = 142671872,  // STANDARD_BT709 | TRANSFER_SRGB | RANGE_FULL
    DISPLAY_P3 = 143261696,
};

// Hdr 类型（占位）
enum class Hdr : int32_t {
    DOLBY_VISION = 1,
    HDR10 = 2,
    HLG = 3,
};

// ColorMode 类型（占位）
enum class ColorMode : int32_t {
    NATIVE = 0,
    SRGB = 7,
};

// PixelFormat 类型（占位，实际用 ui::PixelFormat，这里不用）
enum class PixelFormat : int32_t {
    RGBA_8888 = 1,
    BGRA_8888 = 5,
};

}  // namespace V1_2

}  // namespace common
}  // namespace graphics
}  // namespace hardware
}  // namespace android
