// 最小 stub：aidl/android/hardware/graphics/common/BlendMode.h
// 我的代码不直接用 BlendMode，只为满足 GraphicTypes.h 的 include。
// 实际类型定义来自 hardware/interfaces 的 AIDL 生成，这里给最小枚举。
#pragma once

#include <cstdint>

namespace aidl {
namespace android {
namespace hardware {
namespace graphics {
namespace common {

enum class BlendMode : int32_t {
    NONE = 0,
    PREMULTIPLIED = 1,
    COVERAGE = 2,
};

}  // namespace common
}  // namespace graphics
}  // namespace hardware
}  // namespace android
}  // namespace aidl
