# Dashcast app 模块 —— 功能整合交接说明

> 生成方式：按 `tdd-workflow` skill 的 Step 0（探测测试运行器）+ Step 8（证据与交接报告）格式。
>
> **本文件不包含任何伪造的 PASS 结果。** 所有"未验证"项已明确标注。app 模块当前**零测试基础设施**，因此不存在可引用的 RED/GREEN 证据 —— 这一点本身是本报告最重要的结论。

---

## 1. 来源计划

无 `*.plan.md`。本报告的用户旅程由本次会话中实际发生的开发与排障推导得出：

- 2026-11-07 全项目审计（commit `67f0f2f`）
- 2026-11-07 UI 三批优化（commit `80b8526`）
- 2026-11-07 截取区视觉对齐 + 图层遮罩折叠（commit `eb03240`）
- 2026-11-07 按 HOME 进后台跟随（commit `0fb97c2`）
- 2026-11-07 预览区空白处不再拖动框（commit `eb0d7c6`）

---

## 2. 系统架构（交接必读）

### 2.1 三进程链路

```
dashcast APK (Android)
    │  adbd root 写入并拉起
    ▼
/data/local/tmp/dcapd2 (native C 抓帧器)
    │  TCP 8444
    ▼
CP 侧 dashcastd (Wayland layer 99)
    │
    ▼
仪表屏 (Display 1 / 800×480 或整屏)
```

**关键坐标**：

| 项 | 值 |
|---|---|
| CP 地址 | `198.18.34.2:8444`（按 SSH 通道 `198.18.34.2:22`，root/空密码） |
| dcapd2 路径 | `/data/local/tmp/dcapd2` |
| 控制通道 | dcapd2 监听 `127.0.0.1:8450` |
| 状态文件目录 | `/data/local/tmp/` |
| 图层配置 | `/data/local/tmp/dcapd2.layer`（key=value 文本） |

### 2.2 控制命令（小端 4 字节魔数）

| 命令 | 魔数 | 作用 |
|---|---|---|
| `DCBG` | `0x47424344` | 进入后台跟随（切抓虚拟屏） |
| `DCVP` | `0x50564344` | 设置虚拟屏帧来源 |
| `DCSR` | `0x52534344` | 换源 / 重载参数 |

帧数据魔数：`VDF1`。

### 2.3 状态文件

| 文件 | 含义 |
|---|---|
| `dcapd2.bg` | 后台跟随标志 |
| `dcapd2.pause` | 暂停标志（熄屏 / 迁移失败时置位） |
| `dc_codec` | 编码档：`0` = BGRA+LZ4，`2` = H.264 |
| `dcapd2.layer` | 图层几何参数 |
| `dcapd2.log` | native 侧日志（含 `STAT ...` 行） |
| `dc_debug_dump` | 存在时才写 PPM 调试帧（约 1.7MB/张） |

### 2.4 两种工作模式（核心概念，最容易搞错）

| 模式 | 抓取方式 | 何时用 |
|---|---|---|
| **主屏镜像** | dcapd2 直接 `captureScreen` 抓目标 App 在主屏上的矩形 | 目标 App 在前台 |
| **后台跟随** | 目标 App 搬进虚拟屏 → `VdMirror` 抓 ImageReader → 经 `127.0.0.1:8450` 喂给 dcapd2 | 目标 App 被 HOME 切走 |

> ⚠️ **历史陷阱（2026-11-07 修复）**：曾用 `hasTargetWindow()` 判断"窗口还在就不搬虚拟屏"。这个假设是错的 —— `captureScreen` 抓的是**合成器最终输出**，按 HOME 后桌面盖在最上层，抓到的就是桌面。**"窗口存在" ≠ "画面可见"。**

---

## 3. app 模块功能清单

### 3.1 代码规模

| 类 | 行数 | 职责 |
|---|---|---|
| `MainActivity` | 5271 | 主界面：左栏控制 + 右栏遮罩编辑器 |
| `MirrorController` | 2879 | 投屏编排：dcapd2 生命周期、HOME/熄屏事件、模式切换 |
| `LayerPreviewView` | 1600 | 可拖拽预览画布（遮罩框 / 截取框） |
| `VdMirror` | 1201 | 虚拟屏抓帧 → TCP 喂给 dcapd2 |
| `MapCastService` | 1090 | 地图 HUD 投屏服务 |
| `CpManager` | 983 | CP 侧操作：JSch SSH 推文件 / 启停 dashcastd |
| `LayerConfig` | 901 | 图层几何配置（**纯逻辑，最该先测**） |
| `EvccVehicleFeed` | 826 | 车况数据源（EVCC API） |
| `JavaDashEngine` | 766 | 仪表渲染引擎 |
| 其余 27 个类 | < 800 | 次级 Activity / 自绘控件 / 工具 |

### 3.2 已注册组件（AndroidManifest）

```
Application  .DashcastApp
Activity     .MainActivity              ← 主入口
Activity     .LayerEditorActivity       ← 独立图层编辑器（当前无入口引用）
Activity     .TestPatternActivity       ← 测试图样
Activity     .MiniWindowActivity        ← 迷你窗口
Activity     .DashPerfActivity          ← 性能测试
Activity     .WebViewPerfActivity       ← WebView 性能
Activity     .DashDumpActivity          ← dump 工具
Activity     .OverlayTestActivity       ← 悬浮窗测试
Activity     .NaviBindTestActivity      ← 导航绑定测试
Service      .KeepAliveService          ← 保活
Receiver     .DiagReceiver              ← 诊断广播
```

### 3.3 关键权限

```
INTERNET / FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC
FOREGROUND_SERVICE_MEDIA_PROJECTION / CAPTURE_VIDEO_OUTPUT
READ/WRITE_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE
QUERY_ALL_PACKAGES / MANAGE_ACTIVITY_TASKS / SYSTEM_ALERT_WINDOW
ecarx.oem.permission.OPENAPI_NAVI_PERMISSION
com.kooo.evcc.permission.ACCESS_EVCC_API
```

> `CAPTURE_VIDEO_OUTPUT` 与 `MANAGE_ACTIVITY_TASKS` 是系统级权限 —— 本 APK 必须预置到系统分区或平台签名，普通安装拿不到。

### 3.4 原生层

| 文件 | 行数 | 职责 |
|---|---|---|
| `dcapd2.c` | 2196 | 主循环：抓帧、编码、推流、控制命令处理 |
| `h264enc.c` | 503 | MediaCodec H.264 编码 |
| `layer_mask.c` | 506 | 遮罩位图生成（圆角/梯形/椭圆/羽化/边框） |
| `layer_mask.h` | 117 | 参数结构，与 `LayerConfig.java` 一一对应 |
| `h264stub.c` | 25 | 无编码器时的桩 |

**构建**：`src\ap-native\build-dcapd2.ps1` → 输出 `BUILD_OK <KB> md5=<md5>`

> ✅ **改 native 后只需同步 2 处**：`assets/dcapd2` 和 `assets/dcapd2.b64`。
>
> `MirrorController` 里的 MD5 已改为**运行时从 `assets/dcapd2.b64` 解码后现算**（字段 `sBinMd5`，见 MirrorController.java:57-70），**不再硬编码**。这消除了"改二进制忘记同步 MD5 常量"的整类事故。
>
> 但仍有坑：设备上 dcapd2 **正在运行时**替换二进制，`ensureBinary()` 会认为文件已存在而跳过推送。必须 `pkill -x dcapd2` 后重新开始投屏。

---

## 4. 编码档差异（影响 UI 可用性）

| 能力 | BGRA+LZ4 | H.264 |
|---|---|---|
| alpha 通道 | ✅ 有 | ❌ YUV420 无 alpha |
| 圆角矩形 / 梯形 / 椭圆 | ✅ | ❌ |
| 羽化 / 边框 | ✅ | ❌ |
| 纯矩形遮罩 + 铺满 | ✅ | ✅ |
| 带宽 | 高（tile-diff） | 低（RTP/UDP） |
| `STAT tiles=` 字段 | > 0 | **恒为 0** |

> ⚠️ 判断链路是否在推流：**不能只看 `fps`**（静止画面 fps=0 是正常的），要看 `kbps > 0 || tiles > 0`。H.264 档下 `tiles` 永远是 0。

---

## 5. 测试现状（Step 0 实测结果）

### 5.1 探测命令与输出

```
$ Get-ChildItem -Recurse -Include '*Test.java','*Test.kt'   → 仅 evccapi-* 模块命中
$ Test-Path src/ap/app/src/test                              → False
$ Get-Content src/ap/app/build.gradle | Select-String 'testImplementation'
                                                             → 无输出
```

### 5.2 结论

| 模块 | 测试基础设施 | 测试类数 |
|---|---|---|
| **app（Dashcast 本体）** | ❌ **完全没有** | **0** |
| evccapi-client | ✅ JUnit 4.13.2 | 5 |
| evccapi-contract | ✅ | 2 |

**`app` 模块没有 `src/test/` 目录，`build.gradle` 里没有任何 test 依赖。**

### 5.3 可测试性分级

| 等级 | 类 | 理由 |
|---|---|---|
| **高** | `LayerConfig`（901 行） | 30 个方法，几何计算是纯数学；仅 2 个 android import（`Context`/`SharedPreferences`） |
| 中 | `DashLog`（168）、`LocalStore`（249） | Android 依赖少 |
| 低 | `MirrorController`（2879）、`MainActivity`（5271） | 重度依赖 Android 框架 + adb + 网络 |
| 需 Robolectric | `LayerPreviewView`（1600） | 触摸事件需要 `MotionEvent` |

### 5.4 真实缺陷 vs 测试能力对照

本次会话修的两个 bug，本可以被测试抓到：

| Bug | 性质 | 可测性 |
|---|---|---|
| `hasTargetWindow()` 语义错误 | **纯逻辑判断** | ✅ 单元测试可覆盖（构造"窗口存在但被遮挡"的状态） |
| `hitsDraggableArea` 边界判断 | **几何逻辑** | ✅ 单元测试可覆盖（纯坐标计算） |
| `onTouchEvent` 无条件返回 true | 事件分发 | ⚠️ 需 Robolectric 或仪器测试 |

---

## 6. 建议的 TDD 落地路径

### 阶段 A：建测试骨架（不违背 RED 门禁）

```gradle
// src/ap/app/build.gradle
dependencies {
    testImplementation 'junit:junit:4.13.2'
    // 触摸事件测试才需要：
    // testImplementation 'org.robolectric:robolectric:4.11.1'
}
```

### 阶段 B：特征测试（characterization test）

先给 **已正确** 的代码写测试，锁定当前行为：

| 目标方法 | 保证什么 |
|---|---|
| `LayerConfig.sanitize()` | 负值/超界值被钳制到合法范围 |
| `LayerConfig.snap()` | 网格吸附结果落在 grid 倍数上 |
| `LayerConfig.toText()` | 输出格式与 `dc_layer_parse` 对齐 |
| `LayerConfig.parse()` | 畸形输入不崩溃，回退到默认值 |
| `LayerPreviewView.hitsDraggableArea()` | 框外返回 false，框内/手柄返回 true |

> 这些测试**一开始就应该全绿** —— 它们不是 TDD 的 RED，是回归保护网。

### 阶段 C：此后严格 RED → GREEN

改任何新功能前，先写失败测试。

---

## 7. 验证状态（诚实记录）

| 会话内改动 | 编译 | 真机验证 |
|---|---|---|
| 全项目审计（`67f0f2f`） | ✅ | ✅ 02:55–02:56 |
| UI 三批优化（`80b8526`） | ✅ | ✅ 03:46–03:48 |
| 截取区对齐 + 折叠（`eb03240`） | ✅ | ✅ 用户手动确认 |
| 按 HOME 后台跟随（`0fb97c2`） | ✅ | ✅ 用户确认 |
| 空白区不拖框（`eb0d7c6`） | ✅ | ✅ 用户确认 |
| 导航栏不隐藏 | ✅ | ✅ 07:34 截图 |
| 尺寸/字号网格化 | ✅ | ✅ 08:05 截图 |
| 预览区空白滚动 | ✅ | ✅ 08:18 截图 |

**没有任何一项是通过自动化测试验证的** —— 全部依赖编译 + 真机截图 + 人工确认。

---

## 8. 交接注意事项

### 8.1 环境坑（都踩过）

| 坑 | 后果 | 正确做法 |
|---|---|---|
| 车机 logcat 被系统日志填满 | 看不到 Dashcast 日志 | 复现前先 `logcat -c` |
| App 日志在 user-11 作用域 | adb shell 读不到 | 用 `/storage/emulated/11/Download/Dashcast` |
| `uiautomator dump` 抓错屏 | 拿到 Display 1 (HUD) | 多次重试或改用截图判断 |
| `input tap` 坐标偏移 | 点不中 | 靠截图估算，别信 dump 坐标 |
| dcapd2 运行中替换二进制 | 设备仍跑旧 md5 | 先 `pkill -x dcapd2` |
| `git checkout` 冲刷未提交改动 | 丢失整批工作 | 改动前后确认 `git status` |
| PowerShell `String.Replace` 3 参数 | 报错 | 用 2 参数版本 |
| 单元素数组被 PowerShell 展平 | 逐字符迭代，文件损坏 | 用 `,` 前缀或 `@()` 包住 |

### 8.2 已知未解决问题

| 问题 | 根因 | 状态 |
|---|---|---|
| 系统级 App（carplay）按 HOME 后可能仍投桌面 | `captureScreen` 抓不到被遮挡内容；`captureLayers` 在 AOSP 11 跨进程不可达 | **已知，需专门显示位才能根治** |
| `buildAppCropSection` 648 行 | 巨型方法，未拆分（两套坐标职责交叉） | 技术债 |
| `MainActivity` 5271 行 | 拆类需改 182 处调用点的 Context 传递 | 技术债 |
| `LayerEditorActivity` 无入口引用 | 文件保留但不再被引用 | 死代码候选 |

### 8.3 刻意不改的设计

| 项 | 理由 |
|---|---|
| `blit_bilinear` 3 个静态表（~6KB） | 永久缓存，设计如此 |
| VD 分支 `usleep(10ms)` | 余量小但工作正常 |
| `TestPatternActivity.onDraw` 每帧 `new float[]` | 仅测试页面 |
| 图标尺寸 `dp(18)` 偏离 4px 网格 | 20dp 会过大，是图标专用值 |

---

## 9. 合并证据

若后续 squash 提交，把本文件一起带上 —— 它记录了：

- 架构与协议的权威描述（第 2 节）
- 编码档差异陷阱（第 4 节）
- 测试现状 = 零（第 5 节）
- 每条改动的验证方式与局限（第 7 节）
- 环境坑清单（第 8.1 节）

---

**核心结论**：app 模块**没有任何自动化测试**。所有质量保证依赖「编译通过 + 真机截图 + 人工确认」。落地 TDD 的第一步不是写功能，而是**先建测试骨架 + 给 `LayerConfig` 写特征测试**。
