// stub: hidl/HybridInterface.h
// 提供 DECLARE_HYBRID_META_INTERFACE 宏，转发到标准 binder DECLARE_META_INTERFACE。
// dcapd 不需要 HIDL 跨进程转换能力，因此 HIDL 转换方法省略。
#pragma once

#include <binder/IInterface.h>

#define DECLARE_HYBRID_META_INTERFACE(INTERFACE, V1_0, V2_0) \
    DECLARE_META_INTERFACE(INTERFACE)

