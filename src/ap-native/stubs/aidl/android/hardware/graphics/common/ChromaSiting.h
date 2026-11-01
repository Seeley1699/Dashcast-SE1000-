// stub: ChromaSiting.h
#pragma once
#include <cstdint>
namespace aidl { namespace android { namespace hardware { namespace graphics { namespace common {
enum class ChromaSiting : int32_t {
    NONE = 0,
    UNKNOWN = 0,
    SITED_INTERSTITIAL = 1,
    COSITED_HORIZONTAL = 2,
    COSITED_VERTICAL = 3,
    COSITED = 4,
};
}}}}}
