// 最小 stub：android/hardware/graphics/common/1.1/types.h
#pragma once
#include <cstdint>

namespace android {
namespace hardware {
namespace graphics {
namespace common {
namespace V1_1 {

enum class RenderIntent : int32_t {
    COLORIMETRIC = 0,
    ENHANCE = 1,
    TONE_MAP_COLORIMETRIC = 2,
    TONE_MAP_ENHANCE = 3,
};

}  // namespace V1_1
}  // namespace common
}  // namespace graphics
}  // namespace hardware
}  // namespace android
