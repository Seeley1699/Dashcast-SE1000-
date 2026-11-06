/*
 * dcapd2.c — AP 侧「单应用镜像」抓帧守护进程（root）
 *
 * 和 dcapd.c 的区别：
 *   dcapd.c  建一块虚拟屏，等 App 被搬进来（方案 4：主屏就空了）
 *   dcapd2.c 不碰 task，直接抓主屏上「目标 App 窗口」那一块像素（方案 1：镜像）
 *
 * 为什么要这样：方案 4 把 task 搬走，中控就摸不到那个 App 了。要「中控能操作 +
 * 仪表实时跟随」，App 必须留在主屏（display 0），我们只把它的像素复制一份送走。
 *
 * 权限：captureScreen 需要 CAPTURE_VIDEO_OUTPUT（signature）或 READ_FRAME_BUFFER。
 *      本进程由 adbd（root，uid 0）拉起，PermissionCache 对 uid 0 直接放行，
 *      所以不需要 MediaProjection、不弹授权框。
 *
 * 参数：
 *   dcapd2 <dispW> <dispH> <srcX> <srcY> <srcW> <srcH> <outW> <outH> <host> <port> [maxFps]
 *
 *   dispW/dispH  主屏逻辑尺寸（2560x1440）
 *   src*         目标 App 窗口在主屏上的矩形（dumpsys window 里那个 frame）
 *   out*         仪表画布尺寸（1920x532）
 *   maxFps       推流上限，默认 30
 *
 * 画布规则：等比缩放后居中，左右（或上下）补黑 —— 也就是「信箱化」。
 * 抓帧尺寸：固定取 1280 宽（按主屏比例算高），比整屏 2560 省一半以上回读带宽。
 *
 * 协议：与 src/dashcast_proto.h 完全一致，CP 端 dashcastd 不用改。
 */
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <vector>

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>

#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>

#include <ui/GraphicTypes.h>     /* ui::Dataspace / ui::PixelFormat（A11 由这里 using 进来） */
#include <ui/GraphicBuffer.h>
#include <ui/PixelFormat.h>
#include <ui/Rect.h>

#include <system/graphics.h>
#include <utils/Errors.h>
#include <utils/String8.h>

#include <android/log.h>

#include "dashcast_proto.h"
#include "layer_mask.h"

using namespace android;

#define DCAP_TAG "dcapd2"
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, DCAP_TAG, __VA_ARGS__); \
                       fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); fflush(stderr); } while (0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, DCAP_TAG, __VA_ARGS__); \
                       fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); fflush(stderr); } while (0)

#define TILE 64
#define CAPTURE_W 1280          /* 整屏回读宽度，按主屏比例推高度 */
#define PAUSE_FILE "/data/local/tmp/dcapd2.pause"
#define BG_FILE    "/data/local/tmp/dcapd2.bg"
#define PROXY_PORT 8450
#define VDFRAME_MAGIC 0x56444631u   /* 'VDF1'：App 侧 VD 帧的包头 */
#define CMD_BG        0x47424344u   /* 'DCBG'：4 字节控制命令 —— 立刻停抓主屏 */
#define CMD_VCROP     0x50564344u   /* 'DCVP'：后台跟随时的源裁剪框（16 字节） */
/*
 * 'DCSR'（2026-11-05 新增）：**主屏抓取区热更新**（16 字节 x,y,w,h）。
 *
 * 问题：改「截取 App 页面」或「按比例框选」时，Java 侧只能 pkill 再重启
 *      进程才能换抓取区 —— 每次重启 TCP 8444 断开、8450 帧源也断，
 *      日志里满屏 Broken pipe / ECONNREFUSED，画面还闪一下黑。
 *
 * 而 g_src_* 本来就是全局变量（captureScreen 每帧读它），
 * 所以只要给个命令能改这四个数，就完全不用重启进程。
 */
#define CMD_SRC       0x52534344u   /* 'DCSR'：主屏抓取区（16 字节） */

/*
 * 后台跟随（VD 源）时的裁剪框 —— 2026-11-03 修「按 HOME 回桌面后截取失效」。
 *
 * 现象：用户在「截取 App 页面」里框了一块，App 在前台时画面正常；
 *       一按 HOME 进后台跟随，整块窗口都上了仪表，截取框形同没有。
 *
 * 根因：主屏路径走 captureScreen(crop = srcX/Y/W/H)，裁剪在那条路上生效；
 *       而 VD 分支是从虚拟屏整块 ImageReader 读帧，直接
 *            blit(0,0,g_px_w,g_px_h → dst)
 *       ——压根没有"只取一块"的概念。
 *
 * 修法：App 把截取框换算成虚拟屏坐标，用 'DCVP' 命令下发；这里存起来，
 *       VD 分支按这个源矩形 blit（blit_bilinear 本来就支持源子矩形）。
 *       w/h = 0 表示取整块（老行为）。
 */
static int g_vd_crop_x = 0, g_vd_crop_y = 0, g_vd_crop_w = 0, g_vd_crop_h = 0;


static int g_proxy_active = 0;      /* 收到 BG 命令或帧之后才算真正接管 */

/* App 侧把目标 App 搬进虚拟屏后，从 ImageReader 读出的整帧 RGBA 走环回
 * TCP 送进来。为什么不让 App 直接连 CP：dcapd2 这条 TCP 是仪表图层的命根子，
 * 断了 dashcastd 就拆图层，仪表闪回原厂。让 App 把帧喂给 dcapd2，由它用
 * 同一条连接、同一套图块差量继续推 —— 模式切换仪表上零感知。 */
typedef struct {
    uint32_t magic;
    uint32_t w;
    uint32_t h;
    uint32_t stride_px;
} vd_frame_hdr_t;

static int read_full(int fd, void *buf, size_t len);   /* 定义在下面 socket 工具区 */
static long long now_ms(void);                          /* 同上 */

static int          g_listen_fd = -1;
static int          g_proxy_fd  = -1;
static uint8_t     *g_px_buf    = NULL;
static size_t       g_px_cap    = 0;
static int          g_px_w, g_px_h, g_px_stride;

/* H.264 档（argv[12]）：0=BGRA+LZ4（默认），2=H.264 over RTP */
extern int  h264_enc_open(int tcp_fd, int dw, int dh, int kbps, int fps);
extern int  h264_enc_feed(const uint8_t *bgra, int sw, int sh);
/* 带裁剪的版本：只取画布上 (cx,cy,cw,ch) 那块喂给编码器。
 * H.264 档必须用它 —— 传整块画布会让编码器错位取样，未覆盖区域留旧像素 → 重影。 */
extern int  h264_enc_feed_crop(const uint8_t *bgra, int sw, int sh,
                               int cx, int cy, int cw, int ch);
extern void h264_enc_drain(void);
extern void h264_enc_stats(long *frames, long *bytes);
extern void h264_enc_close(void);
static int g_codec = 0;
static int g_h264_ready = 0;
static int g_h264_tried = 0;      /* 只试开一次，失败就不再每帧刷日志 */
static long long g_ctrl_check = 0;
static int g_enc_w = 0, g_enc_h = 0;
static int g_last_enc_w = 0, g_last_enc_h = 0;   /* 上次算过的编码尺寸，避免重复打日志 */
static int g_enc_live_w = 0, g_enc_live_h = 0;   /* 编码器**实际**生效的尺寸，用于检测变化 */

/*
 * 抓哪块屏（argv[13]，可选）。
 *   <  0（默认）= 主屏（老行为，getInternalDisplayToken）
 *   >= 0        = 第 n 块物理屏（getPhysicalDisplayIds()[n]）
 *
 * 用途：把地图的 HudActivity 投到 Display 1（HDMI 800×480），
 * 抓那一块就能拿到地图的原生 HUD 渲染，比截主屏干净得多。
 */
static int g_target_display = -1;

/* —— 取证用：切模式那一下"仪表上到底是什么"无从远观，只能把帧拍下来 ——
 * 只在排查问题时落盘（每次切模式 3 张，几毫秒），平时不写。 */
static int          g_outW = 0, g_outH = 0;
static int          g_vd_dump   = 0;      /* 已落盘的 VD 帧数（最多 3） */
static long long    g_switch_ms = 0;      /* 收到 DCBG 的时刻 */
static int          g_after_dumped = 0;
/* —— 画面映射目标矩形（画布坐标）——
 * 遮罩启用时跟随遮罩矩形（内容铺满形状），没遮罩时是整块画布的信箱居中。
 * 由 layer_mask_reload() 更新，blit 时用它，这样编辑器改形状大小后
 * 画面立刻跟着重排，不再是固定死在中间的 1013x532。 */
static int          g_dst_x = 0, g_dst_y = 0, g_dst_w = 0, g_dst_h = 0;
static int          g_cap_w = 0, g_cap_h = 0;   /* 抓帧尺寸，重算映射用 */
static int          g_src_x = 0, g_src_y = 0, g_src_w = 0, g_src_h = 0;  /* 抓帧内源矩形 */

/*
 * **本次真正做 blit 的源宽高**（2026-11-05 新增）。
 *
 * 为什么还要单独一组：g_src_w/h 是抓帧缓冲的整块尺寸，
 * 而实际 blit 的源是「整块减去自动裁黑边」的 bsw×srcH2 ——
 * 两者可能不同（detect_crop_columns 会裁掉内容两侧的黑边）。
 *
 * recompute_dst() 里 fit=1（等比居中）要按**真实源比例**算，
 * 用 g_src_ 会偏，导致等比模式下画面被拉伸或留错边。
 * BGRA 档默认 fit=0 走铺满，看不出问题；fit=1 立刻暴露。
 */
static int          g_blit_src_w = 0, g_blit_src_h = 0;

/*
 * **主屏抓取区（屏幕坐标）** —— 'DCSR' 热更新改的就是这四个。
 *
 * 和 g_src_* 的区别（很容易搞混，两者语义不同）：
 *   g_src_*  = 抓帧缓冲里的坐标。因为 crop 已经把目标区域渲染成
 *              g_cap_w×g_cap_h，这块永远是 0,0,g_cap_w,g_cap_h。
 *   g_crop_* = **屏幕坐标**下"抓屏幕哪一块"，也就是传给
 *              captureScreen 的那个 Rect，和启动时 argv[3..6] 同一套。
 *
 * 原来没有后者，热更新把屏幕坐标写进 g_src_* 就全乱了：
 *   · recompute_dst 拿它当"抓帧内源矩形"算缩放 → 比例错
 *   · 主循环又不读它 → 改了等于没改
 * 所以拆成独立一组，职责清楚。
 */
static int          g_crop_x = 0, g_crop_y = 0, g_crop_w = 0, g_crop_h = 0;

/*
 * 画布指针 —— 取证用。
 *
 * 'DCBG'（App 按 HOME）那一刻把整块画布 dump 成 ppm，
 * 之后跟桌面/App 的截图一比，就知道漏上仪表的是谁。
 * 只在排查问题时写，平时是一次赋值，可忽略。
 */
static uint8_t     *g_canvas = NULL;
/*
 * 物理屏尺寸（= 命令行 argv[1] / argv[2]）。
 *
 * 原来是 main() 的局部 const int dispW/dispH，
 * 但 'DCSR' 热更新时要把新抓取区夹到这范围内，
 * 控制线程拿不到 main 的局部变量，所以提成全局。
 */
static int          g_scr_w = 0, g_scr_h = 0;

static int          g_vd_live   = 0;      /* 切到 VD 之后有没有出过"真画面" */
static int          g_vd_blank  = 0;      /* 连续丢掉的空白帧数 */

/* —— 图层形状遮罩（圆角 / 梯形 / 羽化 / 边框）——
 * 配置文件 /data/local/tmp/dcapd2.layer，App 侧可视化编辑器写这个文件；
 * dcapd2 每 500ms 查一次 mtime，改了立刻重建，不用重启。
 * 文件不存在或 enabled=0 → 行为与旧版完全一致（全不透明铺满整屏）。 */
#define LAYER_CFG_FILE "/data/local/tmp/dcapd2.layer"
static dc_layer_mask_t g_mask;
static int             g_mask_ready  = 0;    /* 1 = 遮罩已生成且启用 */
static long long       g_mask_stat_ms = 0;   /* 上次检查配置文件的时间 */
static long long       g_mask_mtime_ms = 0;  /* 上次看到的文件 mtime */

static void recompute_dst(void);      /* 前向声明 */
static void recompute_enc_size(int canvas_w, int canvas_h);

/*
 * 按遮罩比例重算 H.264 编码尺寸。
 *
 * 为什么需要：原来固定 `outW/2 × outH/2`（整块画布的一半，960×266，比例 3.61:1）。
 * 但遮罩可能是任意形状（比如 621×378，比例 1.64:1），把 960×266 的内容
 * 拉伸到 621×378 会横向压扁 2.2 倍 —— 抖音画面里的人脸会明显变宽变形。
 *
 * 正确做法：编码尺寸跟遮罩保持同比例，让合成器几乎不用拉伸。
 *   · 长边限制在 960 内（CP 软解 budget 约 19% 单核 @ 960×266）
 *   · 短边按比例算，YUV420 要求偶数
 *
 * 无遮罩时退回整屏一半（旧行为）。
 */
static void recompute_enc_size(int canvas_w, int canvas_h)
{
    int bw, bh;
    /*
     * **方案 A：编码尺寸 = 遮罩矩形尺寸**（H.264 和 BGRA 都是）。
     *
     * H.264 没有 alpha，要实现"只投遮罩那块、旁边露出原厂仪表"，
     * 只能靠两步配合：
     *   ① CP 端 LayerManagerControl set layer 99 destination region <遮罩 x/y/w/h>
     *      → 图层本身只有这么大，旁边自然透出原厂界面
     *   ② 编码尺寸 = 遮罩尺寸
     *      → 编码分辨率和图层分辨率 1:1，合成器零缩放，不变形不糊
     *
     * 两者必须一致。少了①，图层铺满整屏遮住原厂；少了②，内容被拉伸。
     *
     * BGRA 有 alpha，理论上整屏编码靠透明挖洞也行，但让编码尺寸跟着
     * 遮罩走能省大量带宽/CPU（遮罩小 → 编码小），所以统一成一条路径。
     */
    if (g_mask_ready && g_mask.cfg.w > 0 && g_mask.cfg.h > 0) {
        bw = g_mask.cfg.w;
        bh = g_mask.cfg.h;
    } else {
        bw = canvas_w;          /* 没遮罩 = 整块仪表画布 1920x532 */
        bh = canvas_h;
    }
    if (bw < 2 || bh < 2) return;

    /*
     * **1:1 像素映射** —— 编码尺寸直接等于遮罩尺寸，不做长边压缩。
     *
     * 历史：早先限制长边 ≤960（因为担心 CP 软解扛不住），但那样
     * 960x172 要拉伸到 1443x259，等于二次缩放 —— 画面糊，而且
     * 遮罩比例和编码比例不一致时还会露出黑边。
     *
     * 现在改成：编码 = 遮罩（偶数对齐），destination region = 遮罩，
     * 合成器 1:1 贴上去，**不缩放、不变形、无黑边**。
     *
     * 代价：全屏遮罩时编码尺寸 = 1920x532（102 万像素），CP 软解压力
     * 明显大于 960x266。实测 2 核 ARM 跑 OpenH264 解码 1920x532
     * 大约占 1.3 核 —— 能跑，但余量小。真掉帧就把遮罩调小，或者
     * 把帧率降下来（帧率现在是可配置的）。
     */
    /*
     * **16 对齐**（不是 2）。
     *
     * 实测：只对齐到偶数时，1036x518 这种尺寸会让 MediaCodec 硬件编码器
     * configure 失败（日志 "H.264 编码器启动失败，回退到 BGRA 档"），
     * 于是投屏看起来"没生效"—— 画面在动但走的是 BGRA，不是 H.264。
     *
     * 原因：高通/联发科的硬编对宽高有 16 对齐要求（宏块是 16x16）。
     * 1920x532 能工作是因为 1920%16=0，532 恰好也过；
     * 1442x258 能工作大概也是碰巧（或者走了软编兜底）。
     *
     * 取 16 的**向下**对齐：宁可少 1~15 像素，也不要编码器起不来。
     * 这部分差异由合成器拉伸补上（destination region 仍是原遮罩尺寸），
     * 肉眼看不出来。
     */
    #define ENC_ALIGN 16
    g_enc_w = (bw / ENC_ALIGN) * ENC_ALIGN;
    g_enc_h = (bh / ENC_ALIGN) * ENC_ALIGN;
    if (g_enc_w < ENC_ALIGN) g_enc_w = ENC_ALIGN;
    if (g_enc_h < ENC_ALIGN) g_enc_h = ENC_ALIGN;
    #undef ENC_ALIGN

    /* 这条日志只在 H.264 档有意义（BGRA 不走编码器）。
     * 之前不分档位都打，看日志会以为 BGRA 也在算编码尺寸。 */
    if (g_codec == DASHCAST_CODEC_H264
            && (g_enc_w != g_last_enc_w || g_enc_h != g_last_enc_h)) {
        LOGI("H.264 编码尺寸 → %dx%d（= 遮罩 %dx%d，1:1 无缩放）",
             g_enc_w, g_enc_h, bw, bh);
        g_last_enc_w = g_enc_w;
        g_last_enc_h = g_enc_h;
    }
}

/**
 * 重新读遮罩配置并生成遮罩。返回 1 = 遮罩生效。
 * 遮罩变化后立刻重算画面映射（recompute_dst），这样编辑器一改形状大小，
 * 画面就跟着铺满/缩小，不用重启抓帧进程。
 */
static int layer_mask_reload(int canvas_w, int canvas_h)
{
    dc_layer_cfg_t cfg;
    dc_layer_defaults(&cfg, canvas_w, canvas_h);

    if (dc_layer_load_file(&cfg, LAYER_CFG_FILE) != 0) {
        dc_layer_free(&g_mask);
        g_mask_ready = 0;
        recompute_dst();
        recompute_enc_size(g_outW, g_outH);
        return 0;
    }
    if (!cfg.enabled) {
        dc_layer_free(&g_mask);
        g_mask_ready = 0;
        LOGI("图层遮罩未启用（enabled=0）");
        recompute_dst();
        recompute_enc_size(g_outW, g_outH);
        return 0;
    }

    cfg.canvas_w = canvas_w;
    cfg.canvas_h = canvas_h;

    /*
     * **H.264 档强制矩形遮罩**（方案 A）。
     *
     * H.264 是 4:2:0 YUV，**物理上没有 alpha 通道**。CP 端解码出来每个像素
     * 都是不透明的（h264rx.c: row = 0xFF000000 | rgb）。
     * 所以圆角/梯形/椭圆的"透明区域"到了仪表上就是黑色 —— 用户看到的就是黑边。
     *
     * BGRA 档有 alpha，形状遮罩能正常挖洞；H.264 档只能按矩形。
     * 这是格式限制，不是实现缺陷。
     *
     * 用户选 H.264 是为了带宽（45KB/s vs BGRA 的 2543KB/s，快 56 倍），
     * 用直角换流畅，这个取舍是值得的。
     */
    if (g_codec == DASHCAST_CODEC_H264 && cfg.shape != LAYER_SHAPE_RECT) {
        LOGI("H.264 档不支持形状遮罩（无 alpha 通道），强制按矩形处理（原 shape=%d）",
             cfg.shape);
        cfg.shape = LAYER_SHAPE_RECT;
        cfg.r_tl = cfg.r_tr = cfg.r_bl = cfg.r_br = 0;
        cfg.trap_top = cfg.trap_bot = 0;
        cfg.feather = 0;
    }

    if (dc_layer_build(&g_mask, &cfg) != 0) {
        LOGE("遮罩生成失败（内存不足？）");
        g_mask_ready = 0;
        recompute_dst();
        recompute_enc_size(g_outW, g_outH);
        return 0;
    }
    g_mask_ready = 1;
    recompute_dst();
    recompute_enc_size(g_outW, g_outH);   /* 遮罩变了 → 编码尺寸跟着变，避免拉伸变形 */

    /* 打印 g_mask.cfg —— sanitize 之后的最终值。
     * 用原始 cfg 会误导排查（日志显示圆角 999，实际渲染用的是夹好的 100）。
     * 注意参数个数必须与格式串一致，否则会读到错位的栈值、输出乱码。 */
    LOGI("图层遮罩生效: shape=%d 矩形=%d,%d %dx%d 圆角=%d/%d/%d/%d 梯形=%d/%d 羽化=%d 边框=%d fit=%d",
         g_mask.cfg.shape,
         g_mask.cfg.x, g_mask.cfg.y, g_mask.cfg.w, g_mask.cfg.h,
         g_mask.cfg.r_tl, g_mask.cfg.r_tr, g_mask.cfg.r_bl, g_mask.cfg.r_br,
         g_mask.cfg.trap_top, g_mask.cfg.trap_bot,
         g_mask.cfg.feather, g_mask.cfg.border, g_mask.cfg.fit);
    return 1;
}

/**
 * 重算「抓帧源矩形 → 画布目标矩形」的映射。
 *
 * 规则：
 *   有遮罩 + fit=0（默认） → 拉伸铺满遮罩矩形，**不留任何黑边**
 *   有遮罩 + fit=1        → 等比缩放，遮罩矩形内居中
 *   无遮罩                → 等比居中铺在整块画布（旧行为）
 *
 * 为什么默认铺满：用户要的是"仪表上这块区域全是投屏内容"，形状内出现
 * 黑边就浪费了可用面积（截图里那圈黑框就是等比缩放导致的）。
 * 遮罩矩形之外的像素由 dc_layer_clear_outside() 清成全透明，
 * 合成器会把原厂仪表透出来 —— 这才是"黑边的地方恢复原有的样子"。
 */
static void recompute_dst(void)
{
    const int outW = g_outW, outH = g_outH;
    if (outW <= 0 || outH <= 0 || g_src_w <= 0 || g_src_h <= 0) return;

    int boxX, boxY, boxW, boxH;

    /*
     * 遮罩矩形 = 图层占屏的范围（这是 BGRA 和 H.264 共同的基础）。
     *
     * 「地图模式」要的就是这个效果：把遮罩调成红圈那块（车辆动画区），
     * 图层就只占那一块，周围保留原厂仪表（速度/电量/音乐卡片）。
     *
     * H.264 没有 alpha，靠 **setLayerRect 把 ivi 图层本身缩到这块矩形**
     * 来实现"挖洞"（CP 端 LayerManagerControl set layer 99 destination region），
     * 所以这里必须返回遮罩的 x/y/w/h，不能是整块画布 ——
     * 否则图层铺满，仪表全被盖住，原厂界面就没了。
     */
    if (g_mask_ready) {
        boxX = g_mask.cfg.x;  boxY = g_mask.cfg.y;
        boxW = g_mask.cfg.w;  boxH = g_mask.cfg.h;
    } else {
        boxX = 0; boxY = 0; boxW = outW; boxH = outH;
    }
    /* 极端窄条（w 或 h = 1）也要处理：clamp 到至少 1 像素继续算，
     * 不能直接 return —— 否则画面映射会停留在上一次的旧值，
     * 表现为"遮罩改小了但画面还是大地铺着"。 */
    if (boxW < 1) boxW = 1;
    if (boxH < 1) boxH = 1;

    int dw, dh;

    if (g_codec == DASHCAST_CODEC_H264 || (g_mask_ready && !g_mask.cfg.fit)) {
        /* H.264 永远铺满（等比会留边，等于黑边）；BGRA 尊重用户的 fit 选择 */
        dw = boxW;
        dh = boxH;
    } else {
        /*
         * 等比缩放 —— 按**真实 blit 源尺寸**算（不是抓帧缓冲整块）。
         *
         * g_blit_src_w/h 在每帧 blit 前被更新为「整块减去自动裁黑边」后的
         * 实际宽高；拿不到时（首帧之前）退回 g_src_。
         * 用错的话 fit=1（等比居中）会按偏大的源算比例，
         * 画面被压扁/留边错位。
         */
        int sw_ref = g_blit_src_w > 0 ? g_blit_src_w : g_src_w;
        int sh_ref = g_blit_src_h > 0 ? g_blit_src_h : g_src_h;
        if (sw_ref <= 0 || sh_ref <= 0) { sw_ref = g_src_w; sh_ref = g_src_h; }
        double sc = (double) boxW / (double) sw_ref;
        double sc2 = (double) boxH / (double) sh_ref;
        if (sc2 < sc) sc = sc2;
        dw = (int) (sw_ref * sc);
        dh = (int) (sh_ref * sc);
        if (dw > boxW) dw = boxW;
        if (dh > boxH) dh = boxH;
    }
    if (dw < 1) dw = 1;
    if (dh < 1) dh = 1;

    int dx = boxX + (boxW - dw) / 2;
    int dy = boxY + (boxH - dh) / 2;

    if (dx != g_dst_x || dy != g_dst_y || dw != g_dst_w || dh != g_dst_h) {
        LOGI("画面映射: 源 %dx%d → 画布 %d,%d %dx%d（%s）",
             g_blit_src_w > 0 ? g_blit_src_w : g_src_w,
             g_blit_src_h > 0 ? g_blit_src_h : g_src_h,
             dx, dy, dw, dh,
             g_mask_ready ? (g_mask.cfg.fit ? "遮罩内等比" : "铺满遮罩")
                          : "画布居中");
    }
    g_dst_x = dx; g_dst_y = dy; g_dst_w = dw; g_dst_h = dh;
}

/* 运行时切换编码档：轮询 /data/local/tmp/dc_codec。
/*
 * 运行时切换编码档：轮询 /data/local/tmp/dc_codec。
 *
 * 为什么要轮询而不是重启 dcapd2：
 *   每次重启都会让 TCP 断开 → CP 的 dashcastd 收到 EOF 自己退出 →
 *   layer 99 被销毁 → 仪表闪一下、图层消失、要等几秒才恢复。
 *   用户在 App 里只是点了一下「切 H.264」，不该付出这么大代价。
 *
 * 现在改成：App 只写一个数字文件，dcapd2 每 500ms 读一次，
 * 变了就关掉旧编码器、开新的，TCP 连接全程不断。
 */

#define CODEC_FILE "/data/local/tmp/dc_codec"

static int codec_poll(void)
{
    static long long last_ms = 0;
    long long now = now_ms();
    if (now - last_ms < 500) return 0;
    last_ms = now;

    int want = -1;
    FILE *f = fopen(CODEC_FILE, "r");
    if (f) {
        if (fscanf(f, "%d", &want) != 1) want = -1;
        fclose(f);
    }

    if (want != DASHCAST_CODEC_BGRA && want != DASHCAST_CODEC_H264) return 0;
    if (want == g_codec) return 0;

    LOGI("编码档运行时切换：%d → %d（不重启进程）", g_codec, want);

    if (g_codec == DASHCAST_CODEC_H264) {
        h264_enc_close();
        g_h264_ready = 0;
        g_h264_tried = 0;
    }

    g_codec = want;

    /*
     * 切档后必须重建遮罩：H.264 档要强制矩形（无 alpha），
     * BGRA 档要恢复用户设的形状（圆角/梯形/椭圆）。
     * 不重建的话，切回 BGRA 时形状还是被压成矩形的那个遮罩。
     */
    {
        int ccw = g_mask.cfg.canvas_w > 0 ? g_mask.cfg.canvas_w : g_outW;
        int cch = g_mask.cfg.canvas_h > 0 ? g_mask.cfg.canvas_h : g_outH;
        layer_mask_reload(ccw, cch);
    }

    recompute_enc_size(g_outW, g_outH);
    return 1;   /* 让主循环断开重连，用新档位重新握手 */
}

static void layer_mask_poll(int canvas_w, int canvas_h)
{
    long long now = now_ms();
    if (now - g_mask_stat_ms < 500) return;
    g_mask_stat_ms = now;

    struct stat st;
    long long mt = 0, sz = 0;
    if (stat(LAYER_CFG_FILE, &st) == 0) {
        /* mtime 秒级精度在快速连续改动时会撞车，加上纳秒和文件大小一起比对。
         * 实测：adb push 两次覆盖配置，mtime 相同但内容不同，只比 mtime 会漏掉后一次。 */
        mt = (long long) st.st_mtime * 1000L + (long long)(st.st_mtim.tv_nsec / 1000000L);
        sz = (long long) st.st_size;
    }
    long long sig = mt * 1000000LL + sz;
    if (sig != g_mask_mtime_ms) {
        g_mask_mtime_ms = sig;
        layer_mask_reload(canvas_w, canvas_h);
    }
}

/** 整帧接近全黑 = App 还没画出来（刚搬进虚拟屏、正在重建 UI）。
 *  这种帧推上仪表就是一闪黑，必须丢掉 —— 抽样判定，几十微秒。 */
static int frame_is_blank(const uint8_t *buf, int w, int h, int stride)
{
    if (!buf || w <= 0 || h <= 0) return 1;
    int lit = 0;
    for (int y = 0; y < h; y += 8) {
        const uint8_t *row = buf + (size_t) y * stride;
        for (int x = 0; x < w; x += 8) {
            const uint8_t *p = row + (size_t) x * 4;
            if (p[0] > 8 || p[1] > 8 || p[2] > 8) {
                if (++lit > 24) return 0;
            }
        }
    }
    return 1;
}

/** 缩成 480 宽的 PPM 落盘（PIL 可直接读） */
static void dump_ppm(const char *path, const uint8_t *buf, int w, int h, int stride)
{
    if (!buf || w <= 0 || h <= 0 || stride < w * 4) return;
    int dw = 480;
    if (dw > w) dw = w;
    int dh = (int) ((long long) h * dw / w);
    if (dh < 1) dh = 1;
    FILE *f = fopen(path, "wb");
    if (!f) return;
    fprintf(f, "P6\n%d %d\n255\n", dw, dh);
    uint8_t row[480 * 3];
    for (int y = 0; y < dh; y++) {
        const uint8_t *src = buf + (size_t) (y * h / dh) * stride;
        for (int x = 0; x < dw; x++) {
            const uint8_t *p = src + (size_t) (x * w / dw) * 4;
            row[x * 3 + 0] = p[0];
            row[x * 3 + 1] = p[1];
            row[x * 3 + 2] = p[2];
        }
        fwrite(row, 1, (size_t) dw * 3, f);
    }
    fclose(f);
    LOGI("取证帧: %s (%dx%d)", path, dw, dh);
}

/** 调试用：把画布的 alpha 通道导成灰度 PPM（验证遮罩是否真的生效）。
 *  只在 DC_DUMP_ALPHA 环境变量存在时调用，平时零开销。 */
static void dump_alpha(const char *path, const uint8_t *buf, int w, int h, int stride)
{
    if (!buf || w <= 0 || h <= 0) return;
    int dw = 480;
    if (dw > w) dw = w;
    int dh = (int)((long long)h * dw / w);
    if (dh < 1) dh = 1;
    FILE *f = fopen(path, "wb");
    if (!f) return;
    fprintf(f, "P6\n%d %d\n255\n", dw, dh);
    uint8_t row[480 * 3];
    for (int y = 0; y < dh; y++) {
        const uint8_t *src = buf + (size_t)(y * h / dh) * stride;
        for (int x = 0; x < dw; x++) {
            uint8_t a = src[(size_t)(x * w / dw) * 4 + 3];
            row[x*3+0] = a; row[x*3+1] = a; row[x*3+2] = a;
        }
        fwrite(row, 1, (size_t)dw * 3, f);
    }
    fclose(f);
    LOGI("alpha 转储: %s (%dx%d)", path, dw, dh);
}

/** 调试用：把画布导成「RGB + Alpha 双文件」PPM（480 宽缩略图）。
 *  宿主机上按 alpha 合成到模拟底图上，就能还原"仪表上看到的样子"。
 *  只在 DC_DUMP_ALPHA 存在时调用。 */
static void dump_rgba(const char *rgb_path, const char *a_path,
                      const uint8_t *buf, int w, int h, int stride)
{
    if (!buf || w <= 0 || h <= 0) return;
    int dw = 480;
    if (dw > w) dw = w;
    int dh = (int)((long long)h * dw / w);
    if (dh < 1) dh = 1;

    FILE *f = fopen(rgb_path, "wb");
    FILE *g = fopen(a_path, "wb");
    if (!f || !g) { if (f) fclose(f); if (g) fclose(g); return; }
    fprintf(f, "P6\n%d %d\n255\n", dw, dh);
    fprintf(g, "P6\n%d %d\n255\n", dw, dh);

    uint8_t row[480 * 3];
    for (int y = 0; y < dh; y++) {
        const uint8_t *src = buf + (size_t)(y * h / dh) * stride;
        for (int x = 0; x < dw; x++) {
            const uint8_t *p = src + (size_t)(x * w / dw) * 4;
            row[x*3+0] = p[2]; row[x*3+1] = p[1]; row[x*3+2] = p[0];   /* BGRA→RGB */
        }
        fwrite(row, 1, (size_t)dw * 3, f);
        for (int x = 0; x < dw; x++) {
            uint8_t a = src[(size_t)(x * w / dw) * 4 + 3];
            row[x*3+0] = a; row[x*3+1] = a; row[x*3+2] = a;
        }
        fwrite(row, 1, (size_t)dw * 3, g);
    }
    fclose(f);
    fclose(g);
    LOGI("画布转储: %s + %s (%dx%d)", rgb_path, a_path, dw, dh);
}

static void set_nonblock(int fd, int on)
{
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl < 0) return;
    fcntl(fd, F_SETFL, on ? (fl | O_NONBLOCK) : (fl & ~O_NONBLOCK));
}

static int make_listen(int port)
{
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    sa.sin_port = htons((uint16_t) port);
    if (bind(fd, (struct sockaddr *) &sa, sizeof(sa)) != 0 ||
        listen(fd, 2) != 0) {
        close(fd);
        return -1;
    }
    set_nonblock(fd, 1);
    return fd;
}

static void try_accept_proxy(void)
{
    if (g_listen_fd < 0 || g_proxy_fd >= 0) return;
    int fd = accept(g_listen_fd, NULL, NULL);
    if (fd < 0) return;
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    set_nonblock(fd, 0);
    g_proxy_fd = fd;
    LOGI("控制通道已接入（App 会一直挂着这条连接，按 HOME 时让 dcapd2 立刻停抓主屏）");
}

/* 读一条消息。1 = 收到一帧，2 = 只收到控制命令，0 = 对端断了，-1 = 数据非法
 *
 * 消息两种形态（都以 4 字节 magic 开头，所以不会混淆）：
 *   'DCBG'                        —— 立刻停抓主屏（App 按 HOME 时先发这个）
 *   'VDF1' + w,h,stride_px + 数据 —— 一帧 VD 画面
 */
static int proxy_read_msg(void)
{
    uint32_t w4;
    if (read_full(g_proxy_fd, &w4, 4) != 0) return 0;

    if (w4 == CMD_BG) {
        g_proxy_active = 1;      /* 只是切模式：这一轮起不再抓主屏 */
        g_switch_ms = now_ms();
        g_vd_dump = 0;
        g_after_dumped = 0;
        g_vd_live = 0;
        g_vd_blank = 0;
        /* 取证：把"停抓主屏那一刻仪表上的画面"拍下来 ——
         * 之后跟桌面/App 的截图一比，就知道漏的是谁。 */
        dump_ppm("/data/local/tmp/dc_canvas.ppm", g_canvas, g_outW, g_outH, g_outW * 4);
        return 2;
    }

    /*
     * 'DCVP' + x,y,w,h（16 字节，虚拟屏坐标）—— 后台跟随时的源裁剪框。
     *
     * 见 g_vd_crop_* 的注释：主屏路径的 captureScreen 带 crop，
     * 但 VD 分支原来整块 blit，所以"按 HOME 之后截取失效"。
     */
    if (w4 == CMD_VCROP) {
        uint32_t c[4];
        if (read_full(g_proxy_fd, c, sizeof(c)) != 0) return 0;
        g_vd_crop_x = (int) c[0];
        g_vd_crop_y = (int) c[1];
        g_vd_crop_w = (int) c[2];
        g_vd_crop_h = (int) c[3];
        if (g_vd_crop_x < 0) g_vd_crop_x = 0;
        if (g_vd_crop_y < 0) g_vd_crop_y = 0;
        LOGI("VD 截取区 → %d,%d %d×%d（虚拟屏帧 %dx%d）",
             g_vd_crop_x, g_vd_crop_y, g_vd_crop_w, g_vd_crop_h,
             g_px_w, g_px_h);
        /*
         * **不要重置 g_vd_live / g_vd_blank**（2026-11-05 修复「改截取框后卡几秒」）。
         *
         * 原来这里写成 `g_vd_live = 0; g_vd_blank = 0;` ——
         * 注释的本意是"换了裁剪框，重新等一帧真画面"。
         * 但空屏判定只在 `!g_vd_live` 时才生效，把它清零等于：
         *   · 下一帧若恰好偏暗（App 是深色主题、刚切后台还在重绘）
         *   · 就进 frame_is_blank 分支 → 丢帧 → 最多要等 150 帧（约 5 秒）
         * 用户体感"改完截取框，仪表黑/卡好几秒才出画面"。
         *
         * 而换裁剪框**根本没有换帧源** —— 虚拟屏还是那块、
         * ImageReader 还在出同一张图，没有"等首帧"的必要。
         * 所以这里保持 live 状态不动，下一帧照常推。
         */
        return 2;
    }
    /*
     * 'DCSR' + x,y,w,h（16 字节，**主屏屏幕坐标**）—— 主屏抓取区热更新。
     *
     * 见 CMD_SRC 宏处注释：改了截取框不用重启进程，
     * g_src_* 是全局变量，captureScreen 每帧都读它。
     *
     * 还要顺带重算两样东西：
     *   · g_cap_w/h —— 抓帧尺寸，做画面映射时要除以它
     *   · 图层遮罩尺寸 —— 新抓取区的比例和遮罩不匹配时要重排
     * 所以置 g_mask_stat_ms = 0 强制下一轮重新加载遮罩配置。
     */
    if (w4 == CMD_SRC) {
        uint32_t c[4];
        if (read_full(g_proxy_fd, c, sizeof(c)) != 0) return 0;
        int nx = (int) c[0], ny = (int) c[1];
        int nw = (int) c[2], nh = (int) c[3];

        /* 夹到屏幕内，避免越界抓帧 */
        if (nx < 0) nx = 0;
        if (ny < 0) ny = 0;
        if (nw < 16) nw = 16;
        if (nh < 16) nh = 16;
        if (g_scr_w > 0 && nx + nw > g_scr_w) nw = g_scr_w - nx;
        if (g_scr_h > 0 && ny + nh > g_scr_h) nh = g_scr_h - ny;
        if (nw < 16) nw = 16;
        if (nh < 16) nh = 16;

        if (nx == g_crop_x && ny == g_crop_y && nw == g_crop_w && nh == g_crop_h) {
            LOGI("主屏抓取区未变（%d,%d %dx%d），跳过", nx, ny, nw, nh);
            return 2;
        }

        /*
         * **只改「抓屏幕哪一块」和「抓帧尺寸」**（2026-11-05 修画面错位）。
         *
         * g_src_* 是「抓帧缓冲里的源矩形」，语义是 0,0,g_cap_w,g_cap_h，
         * 不能塞屏幕坐标进去 —— 那会让 recompute_dst 的缩放比全错。
         */
        g_crop_x = nx; g_crop_y = ny; g_crop_w = nw; g_crop_h = nh;
        {
            /* 和启动时同一套算法：抓帧宽 = min(CAPTURE_W, 源宽) */
            int cw = CAPTURE_W;
            if (cw > nw) cw = nw;
            int chh = (int) ((long long) nh * cw / nw);
            if (chh < 2) chh = 2;
            chh &= ~1;
            g_cap_w = cw;
            g_cap_h = chh;
            /* 抓帧内源矩形恒为整块（crop 已经把目标区域渲染成 cap 尺寸） */
            g_src_x = 0; g_src_y = 0;
            g_src_w = cw; g_src_h = chh;
            /*
             * **真实 blit 源尺寸也要重置**（2026-11-05）。
             *
             * recompute_dst() 的等比分支优先用 g_blit_src_w/h，
             * 那是「上一帧实际 blit 的源」（可能还被自动裁黑边减小过）。
             * 换抓取区后它和新源完全无关，不重置就等于
             * 拿旧尺寸算新比例 —— fit=1 时画面必然偏。
             * 置 0 让 recompute_dst 退回用 g_src_（刚更新的新值）；
             * 下一帧 blit 前会再按真实值覆盖。
             */
            g_blit_src_w = 0;
            g_blit_src_h = 0;
        }
        /*
         * 强制下一轮重新加载遮罩（尺寸可能变了），并且**重算编码尺寸**。
         *
         * 原来只调了 recompute_dst()，漏了 recompute_enc_size() ——
         * H.264 档的编码尺寸是按遮罩算的，遮罩没变时它会停在上一个值；
         * 而抓帧尺寸变了之后，h264_enc_feed_crop 用的采样比就和新画面
         * 对不上，表现为"改完截取框，H.264 下画面被拉伸/错位"。
         * 顺手一起算，成本可以忽略。
         */
        g_mask_stat_ms = 0;
        recompute_dst();
        recompute_enc_size(g_outW, g_outH);
        LOGI("主屏抓取区热更新 → %d,%d %dx%d（屏幕 %dx%d，抓帧 %dx%d）",
             g_crop_x, g_crop_y, g_crop_w, g_crop_h, g_scr_w, g_scr_h,
             g_cap_w, g_cap_h);
        return 2;
    }

    if (w4 != VDFRAME_MAGIC) return -1;

    uint32_t rest[3];
    if (read_full(g_proxy_fd, rest, sizeof(rest)) != 0) return 0;
    g_proxy_active = 1;

    const uint32_t w = rest[0], h = rest[1], stride_px = rest[2];
    if (w < 16 || w > 4096 || h < 16 || h > 4096 || stride_px < w || stride_px > 8192)
        return -1;

    size_t need = (size_t) stride_px * h * 4;
    if (need > g_px_cap) {
        uint8_t *nb = (uint8_t *) realloc(g_px_buf, need);
        if (!nb) return -1;
        g_px_buf = nb;
        g_px_cap = need;
    }
    if (read_full(g_proxy_fd, g_px_buf, need) != 0) return 0;
    g_px_w = (int) w;
    g_px_h = (int) h;
    g_px_stride = (int) stride_px;
    return 1;
}

/* ============================ LZ4 block 编码器 ============================ */
/* 与 dcap_jni.c 同一份实现（已在车上跑过 2.4GB 流量，CP 端能解） */

#define LZ4_HASH_LOG   12
#define LZ4_HASH_SIZE  (1 << LZ4_HASH_LOG)
#define LZ4_MINMATCH   4
#define LZ4_MFLIMIT    12
#define LZ4_LASTLIT    5

static inline uint32_t rd32(const uint8_t *p) {
    uint32_t v;
    memcpy(&v, p, 4);
    return v;
}

static inline uint32_t lz4_hash(uint32_t v) {
    return (v * 2654435761u) >> (32 - LZ4_HASH_LOG);
}

/*
 * LZ4 块压缩 —— 哈希表用**代际标记**，免掉每块 16KB 的 memset（2026-10-31 优化）。
 *
 * 原写法每压一块就 memset(table, 0xFF, 16KB)。
 * 实测代价：遮罩 1887×444 = 210 块/帧 → 3.3MB/帧 → 30fps 时 100MB/s
 * 纯内存清零，占了编码时间相当一块。
 *
 * 现在 table 存 {pos, gen}，gen 每块递增。命中条件「gen == 当前 gen」，
 * 不匹配就当空槽 —— 完全不需要清零。
 * 压缩结果与原来逐字节一致（只是"空槽"的表示方式变了）。
 */
typedef struct { int pos; uint32_t gen; } lz4_slot;
static uint32_t g_lz4_gen = 0;

static int lz4_compress_block(const uint8_t *src, int n, uint8_t *dst,
                              int dst_cap, lz4_slot *table)
{
    if (n <= 0 || dst_cap < n + 16) return 0;

    if (++g_lz4_gen == 0) {                 /* 溢出（约 3 年一次）才真清 */
        memset(table, 0, LZ4_HASH_SIZE * sizeof(lz4_slot));
        g_lz4_gen = 1;
    }
    const uint32_t gen = g_lz4_gen;

    int ip = 0, anchor = 0, op = 0;
    const int mflimit = n - LZ4_MFLIMIT;
    const int matchlimit = n - LZ4_LASTLIT;

    while (ip < mflimit) {
        uint32_t seq = rd32(src + ip);
        uint32_t h = lz4_hash(seq);
        lz4_slot *slot = &table[h];
        int match = (slot->gen == gen) ? slot->pos : -1;
        slot->gen = gen;
        slot->pos = ip;

        if (match >= 0 && (ip - match) < 65535 && (match + 3) < n
                && rd32(src + match) == seq) {
            int max_len = matchlimit - ip;
            if (max_len < LZ4_MINMATCH) break;

            int len = LZ4_MINMATCH;
            while (len < max_len && src[match + len] == src[ip + len]) len++;

            int lit_len = ip - anchor;
            if (op + 1 + lit_len + 2 + 10 > dst_cap) return 0;

            int token_pos = op++;
            int lit = lit_len;
            if (lit >= 15) {
                dst[token_pos] = (uint8_t) 0xF0;
                lit -= 15;
                while (lit >= 255) { dst[op++] = 255; lit -= 255; }
                dst[op++] = (uint8_t) lit;
            } else {
                dst[token_pos] = (uint8_t) (lit << 4);
            }

            memcpy(dst + op, src + anchor, (size_t) lit_len);
            op += lit_len;

            int off = ip - match;
            dst[op++] = (uint8_t) (off & 0xFF);
            dst[op++] = (uint8_t) ((off >> 8) & 0xFF);

            int ml = len - LZ4_MINMATCH;
            if (ml >= 15) {
                dst[token_pos] |= 0x0F;
                ml -= 15;
                while (ml >= 255) { dst[op++] = 255; ml -= 255; }
                dst[op++] = (uint8_t) ml;
            } else {
                dst[token_pos] |= (uint8_t) ml;
            }

            ip += len;
            anchor = ip;
            if (ip < mflimit) {
                uint32_t h2 = lz4_hash(rd32(src + ip - 2));
                table[h2].gen = gen;
                table[h2].pos = ip - 2;
            }
            continue;
        }
        ip++;
    }

    int lit_len = n - anchor;
    if (op + 1 + lit_len + 10 > dst_cap) return 0;

    int token_pos = op++;
    int lit = lit_len;
    if (lit >= 15) {
        dst[token_pos] = (uint8_t) 0xF0;
        lit -= 15;
        while (lit >= 255) { dst[op++] = 255; lit -= 255; }
        dst[op++] = (uint8_t) lit;
    } else {
        dst[token_pos] = (uint8_t) (lit << 4);
    }
    memcpy(dst + op, src + anchor, (size_t) lit_len);
    op += lit_len;
    return op;
}

/* ============================== socket 工具 ============================== */

static int write_all(int fd, const void *buf, size_t len)
{
    const uint8_t *p = (const uint8_t *) buf;
    while (len > 0) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        p += n;
        len -= (size_t) n;
    }
    return 0;
}

static int read_full(int fd, void *buf, size_t len)
{
    uint8_t *p = (uint8_t *) buf;
    size_t got = 0;
    while (got < len) {
        ssize_t n = recv(fd, p + got, len - got, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        got += (size_t) n;
    }
    return 0;
}

static long long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ================================ 编码上下文 ================================ */

typedef struct {
    int w, h;
    int cols, rows, ntiles;

    uint8_t *prev;
    size_t   prev_cap;

    uint8_t *tile;
    uint8_t *comp;
    uint8_t *hdr;
    uint8_t *pay;
    lz4_slot *htab;

    int     fd;
    int     seq;
    int     last_tiles;
    int     last_bytes;
    long long bytes_total;
} enc_t;

static void enc_free(enc_t *e)
{
    if (!e) return;
    if (e->fd >= 0) close(e->fd);
    free(e->prev); free(e->tile); free(e->comp);
    free(e->hdr);  free(e->pay);  free(e->htab);
    free(e);
}

static enc_t *enc_new(int w, int h)
{
    enc_t *e = (enc_t *) calloc(1, sizeof(enc_t));
    if (!e) return NULL;

    e->w = w;
    e->h = h;
    e->cols = (w + TILE - 1) / TILE;
    e->rows = (h + TILE - 1) / TILE;
    e->ntiles = e->cols * e->rows;
    e->fd = -1;

    e->prev_cap = (size_t) w * h * 4;
    size_t tile_cap = TILE * TILE * 4;
    size_t comp_cap = tile_cap + tile_cap / 255 + 64;
    size_t hdr_cap = sizeof(dashcast_frame_header_t)
                   + (size_t) e->ntiles * sizeof(dashcast_tile_t);
    size_t pay_cap = e->prev_cap + 4096;

    e->prev = (uint8_t *) calloc(1, e->prev_cap);
    e->tile = (uint8_t *) malloc(tile_cap);
    e->comp = (uint8_t *) malloc(comp_cap);
    e->hdr  = (uint8_t *) malloc(hdr_cap);
    e->pay  = (uint8_t *) malloc(pay_cap);
        e->htab = (lz4_slot *) malloc(LZ4_HASH_SIZE * sizeof(lz4_slot));

    if (!e->prev || !e->tile || !e->comp || !e->hdr || !e->pay || !e->htab) {
        enc_free(e);
        return NULL;
    }
    return e;
}

static int enc_connect(enc_t *e, const char *host, int port)
{
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons((uint16_t) port);
    if (inet_pton(AF_INET, host, &sa.sin_addr) != 1) {
        LOGE("host 不是 IPv4: %s", host);
        return -1;
    }

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));

    struct timeval tv = { 3, 0 };
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    /* 握手 ACK 之前 CP 可能还在建缓冲池/图层，给足 10 秒 */
    struct timeval rv = { 10, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rv, sizeof(rv));

    if (connect(fd, (struct sockaddr *) &sa, sizeof(sa)) != 0) {
        LOGE("connect %s:%d 失败: %d", host, port, errno);
        close(fd);
        return -1;
    }

    dashcast_handshake_t hs;
    hs.magic   = DASHCAST_MAGIC;
    hs.version = DASHCAST_VERSION;
    hs.width   = (uint32_t) e->w;
    hs.height  = (uint32_t) e->h;
    hs.codec   = (uint32_t) g_codec;
    hs.flags   = (g_codec == DASHCAST_CODEC_H264) ? DASHCAST_FLAGS(g_enc_w, g_enc_h) : 0u;
    hs.rsvd    = 0;

    if (write_all(fd, &hs, sizeof(hs)) != 0) { close(fd); return -1; }

    dashcast_handshake_ack_t ack;
    if (read_full(fd, &ack, sizeof(ack)) != 0) {
        LOGE("握手 ACK 读取失败（CP 上 dashcastd 没起？）");
        close(fd);
        return -1;
    }
    if (ack.magic != DASHCAST_MAGIC || ack.status != 0) {
        LOGE("握手被拒: magic=%08x status=%d", ack.magic, ack.status);
        close(fd);
        return -1;
    }

    memset(&tv, 0, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    /*
     * 握完手立刻发"图层矩形"：CP 用它把 ivi 图层缩到遮罩那块区域。
     *
     * 这样 H.264 档（无 alpha、遮罩外会变黑块）也能做到"图层外露原厂仪表"，
     * 因为方式换成了**几何约束**而不是透明通道 —— 图层外面合成器根本不绘制。
     * BGRA 档同样受益：不用再靠 alpha 挖洞，边界更干净。
     * 没有遮罩时发整屏矩形，行为与旧版完全一致。
     */
    if (getenv("DC_SEND_LAYER_RECT") && g_mask_ready) {   /* 默认不发：兼容 CP 上旧版 dashcastd */
        dashcast_layer_rect_t lr;
        lr.magic = DASHCAST_MAGIC;
        if (g_mask_ready) {
            lr.x = (uint32_t) g_mask.cfg.x;
            lr.y = (uint32_t) g_mask.cfg.y;
            lr.w = (uint32_t) g_mask.cfg.w;
            lr.h = (uint32_t) g_mask.cfg.h;
        } else {
            lr.x = 0; lr.y = 0;
            lr.w = (uint32_t) e->w;
            lr.h = (uint32_t) e->h;
        }
        if (write_all(fd, &lr, sizeof(lr)) != 0) {
            LOGE("发送图层矩形失败");
            close(fd);
            return -1;
        }
        LOGI("图层矩形已发送: %u,%u %ux%u", lr.x, lr.y, lr.w, lr.h);
    }

    e->fd = fd;
    e->seq = 0;
    memset(e->prev, 0, e->prev_cap);
    LOGI("已连 CP %s:%d（握手 OK，%dx%d）", host, port, e->w, e->h);
    return 0;
}

/*
 * 一帧画布 → 图块差量 → LZ4 → 组帧 → TCP
 * 返回发出的图块数；0 = 没变化；<0 = 链路错误
 */
static int enc_push(enc_t *e, const uint8_t *src, int row_stride, int force_full)
{
    if (e->fd < 0) return -1;

    size_t hdr_len = sizeof(dashcast_frame_header_t);
    size_t pay_len = 0;
    int n = 0;

    for (int ty = 0; ty < e->rows; ty++) {
        int y0 = ty * TILE;
        int th = e->h - y0; if (th > TILE) th = TILE;

        for (int tx = 0; tx < e->cols; tx++) {
            int x0 = tx * TILE;
            int tw = e->w - x0; if (tw > TILE) tw = TILE;

            const size_t row_bytes = (size_t) tw * 4;
            const uint8_t *s = src + (size_t) y0 * (size_t) row_stride + (size_t) x0 * 4;
            uint8_t *p = e->prev + ((size_t) y0 * e->w + x0) * 4;

            int changed = force_full ? 1 : 0;
            if (!changed) {
                for (int j = 0; j < th; j++) {
                    if (memcmp(s + (size_t) j * (size_t) row_stride,
                               p + (size_t) j * (size_t) e->w * 4,
                               row_bytes) != 0) {
                        changed = 1;
                        break;
                    }
                }
            }
            if (!changed) continue;

            /* 抽块 + RGBA→BGRA（wl_shm ARGB8888 的小端序），顺手写回 prev */
            for (int j = 0; j < th; j++) {
                const uint8_t *sr = s + (size_t) j * (size_t) row_stride;
                uint8_t *pr = p + (size_t) j * (size_t) e->w * 4;
                uint8_t *dr = e->tile + (size_t) j * row_bytes;

                memcpy(pr, sr, row_bytes);

                for (size_t i = 0; i < row_bytes; i += 4) {
                    dr[i + 0] = sr[i + 2];
                    dr[i + 1] = sr[i + 1];
                    dr[i + 2] = sr[i + 0];
                    dr[i + 3] = sr[i + 3];
                }
            }

            const int raw_len = tw * th * 4;
            uint8_t *payload = e->tile;
            int data_len = raw_len;
            uint16_t mode = DASHCAST_CODEC_BGRA;

            int comp_len = lz4_compress_block(e->tile, raw_len, e->comp,
                                              (int) (raw_len + raw_len / 255 + 64), e->htab);
            if (comp_len > 0 && comp_len < raw_len) {
                payload = e->comp;
                data_len = comp_len;
                mode = DASHCAST_CODEC_RLE;
            }

            if (pay_len + (size_t) data_len > e->prev_cap + 4096) return -1;

            dashcast_tile_t tile;
            tile.x = (uint16_t) x0;
            tile.y = (uint16_t) y0;
            tile.w = (uint16_t) tw;
            tile.h = (uint16_t) th;
            tile.mode = mode;
            tile.rsvd = 0;
            tile.raw_len = (uint32_t) raw_len;
            tile.data_len = (uint32_t) data_len;

            memcpy(e->hdr + hdr_len, &tile, sizeof(tile));
            hdr_len += sizeof(tile);
            memcpy(e->pay + pay_len, payload, (size_t) data_len);
            pay_len += (size_t) data_len;
            n++;
        }
    }

    e->last_tiles = n;
    e->last_bytes = 0;
    if (n == 0) return 0;

    dashcast_frame_header_t fh;
    fh.magic = DASHCAST_MAGIC;
    fh.seq = (uint32_t) e->seq++;
    fh.tile_count = (uint32_t) n;
    fh.payload_len = (uint32_t) pay_len;
    memcpy(e->hdr, &fh, sizeof(fh));

    if (write_all(e->fd, e->hdr, hdr_len) != 0) return -1;
    if (write_all(e->fd, e->pay, pay_len) != 0) return -1;

    e->bytes_total += (long long) (hdr_len + pay_len);
    e->last_bytes = (int) (hdr_len + pay_len);
    return n;
}

/* ============================ 自动裁掉内容黑边 ============================ */

/*
 * 检测抓帧缓冲里 src 区域的有效内容边界，返回需要砍掉的左右黑边像素数。
 *
 * 为什么需要：目标 App（比如抖音车机版）的窗口里可能自带宽边 —— 系统标题栏、
 * 导航栏、或 Activity 本身的 padding。把整块窗口直接缩放投上仪表时，
 * 那些黑边会跟着一起显示，看着就像"投屏区里有一条黑边"。
 *
 * 判定：从左右两侧逐列扫描，只要该列有足够的亮像素（>8）就算内容列；
 * 找到第一列/最后一列亮列，砍掉外面的部分。
 *
 * 保守约束：
 *   · 最多砍掉总宽度的 15%（防止把全黑画面误判成"整屏黑边"）
 *   · 砍完宽度不得小于原来的 60%
 *   · 只在内容明显偏窄时启用
 */
static void detect_crop_columns(const uint8_t *buf, int stride_px,
                                int x, int y, int w, int h,
                                int *out_left, int *out_right)
{
    *out_left = 0;
    *out_right = 0;
    if (!buf || w < 8 || h < 8) return;

    /* 竖采样 12 行，够判断一列有没有内容 */
    const int STEP = h / 12 > 1 ? h / 12 : 1;

    /*
     * 判定标准：**列的亮度极差（max-min）**，不是绝对亮度。
     *
     * 为什么换：一开始用"有亮像素(>8)就算内容"，但实测抖音左侧是
     * 深色背景 (22,24,35) 而非纯黑，阈值 8 直接判成内容，黑边裁不掉。
     * 深色 UI 背景和真黑边亮度接近，靠绝对亮度分不开。
     *
     * 但极差差得很远：
     *   · 黑边：整列几乎同一个值 → 极差≈0
     *   · 内容：有图标/文字/画面 → 每行都不一样 → 极差大
     */
    const int VAR_TH = 12;   /* 经验值：真黑边极差通常 <6，内容 >20 */

    /* 从左边扫：第一列极差超过阈值的就是内容起点 */
    int left = 0;
    for (int i = 0; i < w * 15 / 100; i++) {
        int mn = 255, mx = 0;
        for (int j = 0; j < h; j += STEP) {
            const uint8_t *p = buf + (size_t)(y + j) * stride_px * 4
                             + (size_t)(x + i) * 4;
            int lum = (p[0] * 2 + p[1] * 3 + p[2]) / 6;
            if (lum < mn) mn = lum;
            if (lum > mx) mx = lum;
        }
        if (mx - mn > VAR_TH) { left = i; break; }
    }

    /* 从右边扫 */
    int right = 0;
    for (int i = 0; i < w * 15 / 100; i++) {
        int mn = 255, mx = 0;
        for (int j = 0; j < h; j += STEP) {
            const uint8_t *p = buf + (size_t)(y + j) * stride_px * 4
                             + (size_t)(x + w - 1 - i) * 4;
            int lum = (p[0] * 2 + p[1] * 3 + p[2]) / 6;
            if (lum < mn) mn = lum;
            if (lum > mx) mx = lum;
        }
        if (mx - mn > VAR_TH) { right = i; break; }
    }

    /* 保守约束：砍完不能窄于 60% */
    if (left + right > w * 40 / 100) return;

    *out_left = left;
    *out_right = right;
}

/* ================================ 缩放 ================================ */

/*
 * 把抓帧缓冲里的 src 区域，等比缩放到 out 的 dst 矩形里（双线性）。
 * 目标矩形之外保持原样（画布初始化时已经是黑色）。
 */
/*
 * 双线性缩放 —— 整数定点 + 预计算 x 索引（2026-10-31 优化）。
 *
 * 原实现全 float，内层循环每像素要算 u/x0/wx，再做 4 采样 12 次浮点乘加。
 * 1920×532 = 102 万像素，是每帧 77ms 里相当一块。
 *
 * 改法：① x 索引每帧只算一遍（原来每行都重算，532 倍浪费）
 *       ② 浮点加权改 16 位定点（ARM 整数乘加快于浮点，且省 float→int 转换）
 * 精度 1/65536，肉眼不可见。
 */
static void blit_bilinear(const uint8_t *cap, int cap_stride_px, int cap_w, int cap_h,
                          int sx, int sy, int sw, int sh,
                          uint8_t *out, int out_w, int out_h,
                          int dx, int dy, int dw, int dh)
{
    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return;
    if (cap_w <= 0 || cap_h <= 0 || out_w <= 0 || out_h <= 0) return;

    /*
     * ══════════════════════════════════════════════════════════════
     * **入口统一夹紧：源矩形和输出矩形都必须在缓冲内**（2026-11-05）
     * ══════════════════════════════════════════════════════════════
     *
     * 原来只夹了 x1/y1（`if (x1 >= cap_w) x1 = cap_w - 1`），
     * 没夹 x0/y0 —— 一旦 sx+sw 超出 cap_w，x0 就可能 >= cap_w，
     * 下面 `r0 + cx0 * 4` 直接越界读（读到别的内存，画面花屏甚至崩）。
     *
     * 输出侧同样没夹：dy+y >= out_h 或 dx+dw > out_w 时，
     * `orow + x*4` 会写穿画布尾部 —— 破坏堆内存。
     *
     * 平时参数一致时走不到越界分支，但**热更新（DCSR/DCVP）会改尺寸**
     * 而画布不变，参数一时不一致就会踩到，所以必须在这里兜住。
     * 代价是几次整数比较，可忽略。
     */
    if (sx < 0) { sw += sx; sx = 0; }
    if (sy < 0) { sh += sy; sy = 0; }
    if (sx + sw > cap_w) sw = cap_w - sx;
    if (sy + sh > cap_h) sh = cap_h - sy;
    if (sw <= 0 || sh <= 0) return;

    if (dx < 0) { dw += dx; dx = 0; }
    if (dy < 0) { dh += dy; dy = 0; }
    if (dx + dw > out_w) dw = out_w - dx;
    if (dy + dh > out_h) dh = out_h - dy;
    if (dw <= 0 || dh <= 0) return;

    static int *x0_tab = NULL, *x1_tab = NULL;
    static unsigned *wx_tab = NULL;
    static int tab_cap = 0;
    if (dw > tab_cap) {
        free(x0_tab); free(x1_tab); free(wx_tab);
        x0_tab = (int *) malloc(sizeof(int) * dw);
        x1_tab = (int *) malloc(sizeof(int) * dw);
        wx_tab = (unsigned *) malloc(sizeof(unsigned) * dw);
        if (!x0_tab || !x1_tab || !wx_tab) {
            free(x0_tab); free(x1_tab); free(wx_tab);
            x0_tab = x1_tab = NULL; wx_tab = NULL; tab_cap = 0;
            return;
        }
        tab_cap = dw;
    }

    const float fx = (float) sw / (float) dw;
    for (int x = 0; x < dw; x++) {
        float u = (x + 0.5f) * fx + (float) sx - 0.5f;
        int x0 = (int) u;
        float wx = u - (float) x0;
        if (x0 < 0) { x0 = 0; wx = 0; }
        int x1 = x0 + 1; if (x1 >= cap_w) x1 = cap_w - 1;
        x0_tab[x] = x0;
        x1_tab[x] = x1;
        wx_tab[x] = (unsigned) (wx * 65536.0f + 0.5f);
    }

    const float fy = (float) sh / (float) dh;
    for (int y = 0; y < dh; y++) {
        float v = (y + 0.5f) * fy + (float) sy - 0.5f;
        int y0 = (int) v;
        float wyf = v - (float) y0;
        if (y0 < 0) { y0 = 0; wyf = 0; }
        int y1 = y0 + 1; if (y1 >= cap_h) y1 = cap_h - 1;
        const unsigned wy = (unsigned) (wyf * 65536.0f + 0.5f);
        const unsigned iwy = 65536u - wy;

        const uint8_t *r0 = cap + (size_t) y0 * cap_stride_px * 4;
        const uint8_t *r1 = cap + (size_t) y1 * cap_stride_px * 4;
        uint8_t *orow = out + (size_t) (dy + y) * out_w * 4 + (size_t) dx * 4;

        for (int x = 0; x < dw; x++) {
            const int cx0 = x0_tab[x], cx1 = x1_tab[x];
            const unsigned wx = wx_tab[x], iwx = 65536u - wx;
            const uint8_t *p00 = r0 + (size_t) cx0 * 4;
            const uint8_t *p01 = r0 + (size_t) cx1 * 4;
            const uint8_t *p10 = r1 + (size_t) cx0 * 4;
            const uint8_t *p11 = r1 + (size_t) cx1 * 4;
            uint8_t *o = orow + (size_t) x * 4;
            for (int c = 0; c < 3; c++) {
                unsigned a = (unsigned) p00[c] * iwx + (unsigned) p01[c] * wx;
                unsigned b = (unsigned) p10[c] * iwx + (unsigned) p11[c] * wx;
                unsigned val = ((a >> 16) * iwy + (b >> 16) * wy) >> 16;
                o[c] = (uint8_t) val;
            }
            o[3] = 0xFF;
        }
    }
}

/* ================================== main ================================== */

static volatile sig_atomic_t g_stop = 0;

static void on_signal(int sig)
{
    (void) sig;
    g_stop = 1;
}

static void usage(const char *p)
{
    fprintf(stderr,
        "用法: %s <dispW> <dispH> <srcX> <srcY> <srcW> <srcH> <outW> <outH> <host> <port> [maxFps] [codec] [display]\n"
        "  codec:   0=BGRA+LZ4（默认）  2=H.264\n"
        "  display: 抓第几块物理屏；省略或 -1 = 主屏\n"
        "  例（主屏）: %s 2560 1440 0 96 2560 1344 1920 532 198.18.34.2 8444 30 2\n"
        "  例（HUD） : %s 800 480 0 0 800 480 1920 532 198.18.34.2 8444 30 2 1\n"
        "            （先 am start --display 1 -n com.geely.map/...hud.HudActivity）\n",
        p, p, p);
}

int main(int argc, char **argv)
{
    if (argc < 11) { usage(argv[0]); return 2; }

    const int dispW = atoi(argv[1]);
    const int dispH = atoi(argv[2]);
    const int srcX  = atoi(argv[3]);
    const int srcY  = atoi(argv[4]);
    const int srcW  = atoi(argv[5]);
    const int srcH  = atoi(argv[6]);
    const int outW  = atoi(argv[7]);
    const int outH  = atoi(argv[8]);
    const char *host = argv[9];
    const int port   = atoi(argv[10]);
    int maxFps = (argc > 11) ? atoi(argv[11]) : 30;
    g_codec    = (argc > 12) ? atoi(argv[12]) : 0;
    /*
     * argv[13]：抓哪块屏。省略或负数 = 主屏（老行为）。
     *
     * 配合 HudActivity 用：把地图的 HUD 页面用
     *   am start --display 1 -n com.geely.map/...hud.HudActivity
     * 投到 Display 1（HDMI 800×480），再让 dcapd2 抓那一块，
     * 就能拿到地图的**原生 HUD 渲染**（浅底高对比、粗路线、居中车标）。
     */
    g_target_display = (argc > 13) ? atoi(argv[13]) : -1;
    g_enc_w    = (outW / 2) & ~1;          /* YUV420 要求偶数 */
    g_enc_h    = (outH / 2) & ~1;
    if (maxFps <= 0) maxFps = 30;

    if (dispW <= 0 || dispH <= 0 || srcW <= 0 || srcH <= 0 || outW <= 0 || outH <= 0) {
        LOGE("参数不合法");
        usage(argv[0]);
        return 2;
    }
    /*
     * 把屏幕尺寸提到全局 —— 'DCSR' 热更新时要把新抓取区夹进这范围。
     */
    g_scr_w = dispW;
    g_scr_h = dispH;


    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    /*
     * 抓帧尺寸（2026-10-31 优化：按**源矩形**算，不再按整屏算）。
     *
     * 原做法：capW 固定 1280（整屏等比），crop 也传整屏 —— 于是每帧都要
     *   captureScreen(整屏 2560×1344) → 缩到 1280×672 → 再从里面切 src 矩形
     * 遮罩只占屏幕一部分时，80% 的像素抓了又扔。
     *
     * 现在：crop 就是源矩形本身，capW 按源矩形宽度等比 ——
     *   captureScreen 直接把 srcW×srcH 那块渲染成 capW×capH，
     *   抓的量、缩放的工作量都跟着遮罩走。
     *
     * 上限仍然 1280（CP 那边解码/传输吃不下更大的）。
     */
    int capW = CAPTURE_W;
    if (capW > srcW) capW = srcW;          /* 源比 1280 窄就不用放大 */
    int capH = (int) ((long long) srcH * capW / srcW);
    if (capH < 2) capH = 2;
    capH &= ~1;

    LOGI("dcapd2 启动: 主屏 %dx%d, 抓帧 %dx%d（源 %d,%d %dx%d → 只抓这块）, 画布 %dx%d, 上限 %dfps",
         dispW, dispH, capW, capH, srcX, srcY, srcW, srcH, outW, outH, maxFps);

    /*
     * **清掉上一轮残留的状态文件**。
     *
     * /data/local/tmp/dcapd2.bg 是"我现在在后台跟随模式（抓虚拟屏）"的标记。
     * 主循环靠 access(BG_FILE) 判断走哪条路：
     *     有 bg 文件 → 只处理 VD 帧，**完全不抓主屏**
     *
     * 问题是 App 端如果没能清干净（停止流程被抢占、forceCleanup 失败、
     * 进程被 kill -9 来不及清），这个文件就会残留。下次启动时
     * bg_mode=true、可虚拟屏根本没建 → 主屏不抓、VD 也没帧 →
     *     STAT fps=0 capture=0 tiles=0 totalKB=0
     * 看起来就是"投屏启动了但仪表一动不动"，实测踩过（2026-11-01）。
     *
     * 新进程一定是主屏模式起步（后台跟随要等 App 收到 DCBG 才切），
     * 所以这里无条件清掉是对的。
     */
    unlink(BG_FILE);
    unlink(PAUSE_FILE);

    /*
     * 源矩形在抓帧缓冲里的坐标 —— 现在**恒为整块**（0,0,capW,capH）。
     *
     * 因为 crop 已经把 srcW×srcH 那块渲染成 capW×capH 了，
     * 抓回来的整张图就是源区域本身，不再需要从里面切。
     * 再按 srcX/dispW 换算会切错（那套公式是"抓帧=整屏缩略"时代的产物）。
     */
    int sx = 0, sy = 0, sw = capW, sh = capH;
    if (sw <= 0 || sh <= 0) { LOGE("源矩形换算后为空"); return 2; }

    /* 记录源矩形与抓帧尺寸：目标矩形改由 recompute_dst() 动态算，
     * 遮罩一改大小画面就跟着重排（见该函数注释）。
     * 这里不再写死 dx/dy/dw/dh —— 那是"画面永远停在中间一块"的根因。 */
    g_cap_w = capW; g_cap_h = capH;
    g_src_x = sx; g_src_y = sy; g_src_w = sw; g_src_h = sh;
    /* 屏幕坐标的抓取区（argv[3..6]），'DCSR' 热更新改的就是它 */
    g_crop_x = srcX; g_crop_y = srcY; g_crop_w = srcW; g_crop_h = srcH;
    g_outW = outW; g_outH = outH;
    recompute_dst();          /* 先按"无遮罩"算出画布居中的默认值 */

    ProcessState::self()->startThreadPool();

    sp<IBinder> display;

    /*
     * **抓哪块屏**（2026-11-02 新增）。
     *
     * 原来写死 getInternalDisplayToken() = 永远抓主屏（2560×1600）。
     *
     * 现在车机上还有一块 Display 1（HDMI 800×480，FLAG_PRESENTATION）。
     * 实测把地图的 HudActivity 用 `am start --display 1` 投过去之后，
     * 那块屏上就是**原生 HUD 渲染**：浅底、高对比、粗路线、居中车标，
     * 无 POI 无地名 —— 比截屏主屏上的地图干净得多，正是开车该看的东西。
     *
     * 所以加一个参数 g_target_display：
     *   >= 0 → 抓第 n 块物理屏
     *   <  0 → 保持老行为，抓主屏
     *
     * 用 getPhysicalDisplayIds() 而不是硬编码编号：物理屏 id 是 64 位的
     * PhysicalDisplayId，和 DisplayManager 里那个小整数 displayId
     * 不是一回事，不能直接互转。
     */
    if (g_target_display >= 0) {
        std::vector<PhysicalDisplayId> ids =
                SurfaceComposerClient::getPhysicalDisplayIds();
        if (g_target_display < (int) ids.size()) {
            display = SurfaceComposerClient::getPhysicalDisplayToken(
                    ids[g_target_display]);
            LOGI("抓帧目标：物理屏 #%d（共 %d 块）",
                 g_target_display, (int) ids.size());
        } else {
            LOGE("指定的物理屏 #%d 不存在（共 %d 块），回落主屏",
                 g_target_display, (int) ids.size());
            display = SurfaceComposerClient::getInternalDisplayToken();
        }
    } else {
        display = SurfaceComposerClient::getInternalDisplayToken();
    }

    if (display == nullptr) {
        LOGE("拿不到 display token（目标=%d）", g_target_display);
        return 3;
    }

    sp<ISurfaceComposer> composer = interface_cast<ISurfaceComposer>(
            defaultServiceManager()->getService(String16("SurfaceFlinger")));
    if (composer == nullptr) {
        LOGE("连不上 SurfaceFlinger");
        return 3;
    }
    LOGI("SurfaceFlinger 已连，开始抓帧");

    enc_t *enc = enc_new(outW, outH);
    if (!enc) { LOGE("编码上下文分配失败"); return 4; }

    /* 画布：默认黑底不透明（旧行为）。
     * 若启用了图层遮罩，形状外必须是全透明（alpha=0）——这样 CP 合成器
     * 才会把底下的原厂仪表透出来，才叫"叠加一层可消失的透明膜"。
     * 注意 WGSL/ARGB8888 是预乘格式：透明像素的 RGB 也必须是 0，
     * 否则羽化边缘会发亮/串色。 */
    uint8_t *canvas = (uint8_t *) malloc((size_t) outW * outH * 4);
    if (!canvas) { LOGE("画布分配失败"); enc_free(enc); return 4; }
    memset(canvas, 0, (size_t) outW * outH * 4);

    g_canvas = canvas;          /* 取证用：DCBG 那一刻把这块画布拍下来 */
    g_outW = outW;
    g_outH = outH;

    /* 初始化遮罩：有配置就按形状透明，没配置就全不透明（旧行为） */
    layer_mask_reload(outW, outH);
    if (!g_mask_ready) {
        /* 旧行为：整块画布不透明（信箱黑边也是不透明黑，不会透出原厂仪表） */
        for (int i = 3; i < outW * outH * 4; i += 4) canvas[i] = 0xFF;
    }
    /*
     * 有遮罩时不预填不透明 —— 保持 memset 的全 0（全透明）状态。
     * 之后每帧：blit 把内容画进遮罩矩形 → clear_outside 清掉矩形外 →
     * apply 处理圆角/羽化/边框。矩形外的透明像素让合成器透出原厂仪表，
     * 这就是"黑边的地方恢复原有样子"的实现方式。
     */
    if (g_mask_ready) {
        LOGI("画布按遮罩初始化：矩形外全透明（%dx%d 画布，图层 %d,%d %dx%d，%s）",
             outW, outH, g_mask.cfg.x, g_mask.cfg.y, g_mask.cfg.w, g_mask.cfg.h,
             g_mask.cfg.fit ? "遮罩内等比" : "铺满遮罩");
    }

    if (enc_connect(enc, host, port) == 0) {
        /* 连上了 */
    } else {
        /* CP 没起也不退出：继续抓帧，只统计不发送，2 秒后重试。
         * 这样 CP 重启、断网都不会把我们弄死，也让"只测抓帧帧率"成为可能。 */
        LOGE("首连 CP 失败，先空转抓帧，稍后重试");
    }

    const long long min_interval = 1000 / maxFps;
    long long last_push = 0;
    long long win_start = now_ms();
    long long bytes_mark = 0;
    int frames = 0;
    int applied = 0;         /* 成功抓到并合成的帧数（含无变化） */
    int win_tiles = 0;       /* 这一秒里累计发出去的图块数 */
    int force_full = 1;
    int connected = (enc->fd >= 0);
    long long last_try = now_ms();
    int was_paused = 0;

    /* 后台跟随模式的帧源入口。建不出来只影响"回桌面继续跟"，主流程照跑 */
    g_listen_fd = make_listen(PROXY_PORT);
    if (g_listen_fd < 0) {
        LOGE("监听 127.0.0.1:%d 失败，后台跟随模式不可用", PROXY_PORT);
    } else {
        LOGI("VD 帧源监听 127.0.0.1:%d", PROXY_PORT);
    }

    while (!g_stop) {
        long long now = now_ms();
        int do_push = 0;

        /* CP 掉线不退出：每 2 秒重试一次，期间照常抓帧（只统计不发送） */
        if (!connected && now - last_try >= 2000) {
            last_try = now;
            if (enc_connect(enc, host, port) == 0) {
                connected = 1;
                force_full = 1;    /* 重连后 CP 图层可能是新画的，全量补一帧 */
            }
        }

        if (g_listen_fd >= 0 && g_proxy_fd < 0) try_accept_proxy();

        const int paused = (access(PAUSE_FILE, F_OK) == 0);
        const int bg_mode = (access(BG_FILE, F_OK) == 0);
        if (paused) was_paused = 1;

        if (g_proxy_fd >= 0) {
            /* —— 控制/帧通道（App 投屏期间一直挂着这条连接）—— */
            struct pollfd pf = { g_proxy_fd, POLLIN, 0 };
            /* 未接管时别在这儿等：赶紧回去抓主屏，一帧都不能落下 */
            int pr = poll(&pf, 1, g_proxy_active ? 20 : 0);
            if (pr > 0) {
                int r = proxy_read_msg();
                if (r <= 0) {
                    LOGI("控制通道断开(r=%d)，回到主屏抓帧", r);
                    close(g_proxy_fd);
                    g_proxy_fd = -1;
                    g_proxy_active = 0;
                    force_full = 1;
                } else if (r == 1) {
                    /* 收到一帧 VD 画面 */
                    if (g_vd_dump < 3) {
                        char vp[80];
                        g_vd_dump++;
                        snprintf(vp, sizeof vp, "/data/local/tmp/dc_vd%d.ppm", g_vd_dump);
                        const uint8_t *q = g_px_buf + (size_t) (g_px_h / 2) * g_px_stride * 4
                                           + (size_t) (g_px_w / 2) * 4;
                        LOGI("VD 帧%d: %dx%d stride=%d 中心像素=%d,%d,%d 空屏=%d live=%d blank=%d",
                             g_vd_dump, g_px_w, g_px_h, g_px_stride,
                             q[0], q[1], q[2],
                             frame_is_blank(g_px_buf, g_px_w, g_px_h, g_px_stride * 4),
                             g_vd_live, g_vd_blank);
                        dump_ppm(vp, g_px_buf, g_px_w, g_px_h, g_px_stride * 4);
                    }
                    if (paused) {
                        /* 熄屏：帧照吃，不推 —— 仪表停在最后一帧 */
                    } else if (!g_vd_live && g_vd_blank < 150 &&
                               frame_is_blank(g_px_buf, g_px_w, g_px_h, g_px_stride * 4)) {
                        /* 刚搬进虚拟屏那几百毫秒，App 还没画出来，整帧是全黑的。
                         * 这种帧一旦推上去，仪表就是"App → 黑 → App"闪一下 ——
                         * 所以一个字都不发，等第一帧真画面（最多等 150 帧 ≈ 5 秒）。 */
                        g_vd_blank++;
                        if (g_vd_blank == 1) {
                            LOGI("VD 首帧是空屏 —— 先不推，等 App 画出来（仪表保持上一帧）");
                        }
                    } else if (now - last_push < min_interval) {
                        /* 限帧：丢掉，下一帧再说 */
                    } else {
                        if (!g_vd_live) {
                            LOGI("VD 出真画面了（前面跳过 %d 帧空屏）", g_vd_blank);
                        }
                        g_vd_live = 1;
                        last_push = now_ms();
                        /*
                         * **按 App 下发的裁剪框取源**（2026-11-03）。
                         *
                         * 原来固定 0,0,g_px_w,g_px_h = 整块虚拟屏，
                         * 于是"设了截取框，一按 HOME 回桌面就失效"。
                         * g_vd_crop_w/h 为 0 时保持老行为（整块）。
                         */
                        int vx = 0, vy = 0, vw = g_px_w, vh = g_px_h;
                        if (g_vd_crop_w > 0 && g_vd_crop_h > 0) {
                            vx = g_vd_crop_x;
                            vy = g_vd_crop_y;
                            vw = g_vd_crop_w;
                            vh = g_vd_crop_h;
                            if (vx + vw > g_px_w) vw = g_px_w - vx;
                            if (vy + vh > g_px_h) vh = g_px_h - vy;
                            if (vw < 4 || vh < 4) { vx = 0; vy = 0; vw = g_px_w; vh = g_px_h; }
                        }
                        /* 同上：记录真实源尺寸，等比模式才按对的来源算比例 */
                        if (vw != g_blit_src_w || vh != g_blit_src_h) {
                            g_blit_src_w = vw;
                            g_blit_src_h = vh;
                            recompute_dst();
                        }
                        blit_bilinear(g_px_buf, g_px_stride, g_px_w, g_px_h,
                                      vx, vy, vw, vh, canvas, outW, outH,
                                      g_dst_x, g_dst_y, g_dst_w, g_dst_h);
                        applied++;
                        do_push = 1;
                    }
                }
                /* r == 2：只收到 'DCBG'，模式已经切了，这一轮起不再抓主屏 */
            }
        }

        if (g_proxy_active) {
            /* 已被接管：帧在上面处理过了；没有新帧就歇一下 */
            if (!do_push) usleep(10 * 1000);
        } else if (!bg_mode && !paused) {
            /* —— 主屏镜像：App 在中控上，抓它的窗口区域 —— */

            if (now - last_push < min_interval) {
                usleep((useconds_t) ((min_interval - (now - last_push)) * 1000));
            } else {
                last_push = now;

                sp<GraphicBuffer> gb;
                /*
                 * **只抓目标区域，不抓整屏**（2026-10-31 优化）。
                 *
                 * 原来 crop 传整屏 (0,0 dispW×dispH)，再从 2560×1344 里
                 * 缩放到目标尺寸 —— 遮罩只占屏幕一部分时，绝大多数像素
                 * 抓了又扔，缩放也是白算。
                 *
                 * 现在把 crop 收缩到「源矩形对应的屏幕区域」：
                 *   源矩形在抓帧坐标里是 g_src_x/y/w/h，
                 *   换算回屏幕坐标就是 srcX..srcX+srcW 那一段。
                 * 抓帧尺寸也按这个区域等比缩放，像素量直接降 60~80%。
                 *
                 * 注意：captureScreen 的 crop 是屏幕坐标（dispW×dispH），
                 * 而 g_src_* 是抓帧坐标（capW×capH），所以这里统一用
                 * srcX/srcY/srcW/srcH（它们本来就是屏幕坐标）。
                 */
                /*
                 * ══════════════════════════════════════════════════════
                 * **每帧都从全局变量取值，不读启动时的局部量**（2026-11-05）
                 * ══════════════════════════════════════════════════════
                 *
                 * 原来这里写的是 `srcX, srcY, srcW, srcH` 加 `capW, capH`
                 * —— 全是 main() 开头的**局部常量**。
                 * 所以 'DCSR' 热更新虽然改了全局量，
                 * 抓帧循环压根不看，**改了等于没改**：
                 *   · 日志显示"主屏抓取区热更新 → 2016×1440 @(318,0)"
                 *   · 实际 captureScreen 还是旧矩形
                 *   用户看到的就是"截取框改了但画面没变"。
                 *
                 * 现在统一用 g_crop_ 一组（屏幕坐标）和 g_cap_ 一组（抓帧尺寸），
                 * 启动时它们已经被初始化成和原来局部量相同的值，
                 * 行为不变；热更新之后才真的生效。
                 *
                 * 注意：注释里别写 "g_src_*舍 g_cap_*" 这种带星号斜杠的组合，
                 * 里面的 "星号+斜杠" 会提前结束块注释 —— 后面整段中文
                 * 就变成代码了，编译器报一堆 "unexpected character <U+FF0C>"。
                 * 之前就是这样把编译搞挂的。
                 */
                int cropX = g_crop_x, cropY = g_crop_y;
                int cropW = g_crop_w, cropH = g_crop_h;
                if (cropW < 16 || cropH < 16) {
                    /* 兜底：热更新前的极端值，退回启动时参数 */
                    cropX = srcX; cropY = srcY; cropW = srcW; cropH = srcH;
                }
                int frameW = g_cap_w > 0 ? g_cap_w : capW;
                int frameH = g_cap_h > 0 ? g_cap_h : capH;
                Rect crop(cropX, cropY, cropX + cropW, cropY + cropH);
                status_t err = composer->captureScreen(display, &gb, crop,
                                                      (uint32_t) frameW, (uint32_t) frameH,
                                                      false, ui::ROTATION_0);
                if (err != NO_ERROR || gb == nullptr) {
                    LOGE("captureScreen 失败: %d (%s)", err, strerror(-err));
                    usleep(200 * 1000);
                } else {
                    void *pixels = nullptr;
                    err = gb->lock(GRALLOC_USAGE_SW_READ_OFTEN, &pixels);
                    if (err != NO_ERROR || pixels == nullptr) {
                        LOGE("GraphicBuffer lock 失败: %d", err);
                        usleep(100 * 1000);
                    } else {
                        /*
                         * 自动裁掉内容自带的左右黑边（抖音车机版窗口里常见）。
                         *
                         * **但只在没有遮罩时才做**（2026-11-01 重影修复）。
                         *
                         * 有遮罩的时候，用户已经在编辑器里精确框出了想要的区域，
                         * 再叠一层"每帧现算的自动裁剪"就是两套规则打架：
                         *   · 这个检测没有时间平滑，每帧结果都会变 ——
                         *     实测同一画面连续几帧报 29/29、54/12、150/12、129/12…
                         *   · 于是 blit 的源矩形每帧都在变宽/变窄，
                         *     画面在遮罩里"呼吸"式伸缩，前后两帧错位叠加 →
                         *     用户看到的就是重影 + 抖动。
                         *
                         * 有遮罩时直接用 sx/sy/sw/sh，画面绝对稳定。
                         */
                        int cropL = 0, cropR = 0;
                        /*
                         * **HUD 模式必须禁用自动裁黑边**（2026-11-02 实测踩到）。
                         *
                         * 抓 HUD 屏时不走遮罩（g_mask_ready=0），于是这里会
                         * 跑 detect_crop_columns。而 HUD 画面本身就是
                         * 「黑底 + 中间一坨内容」—— 左右大片纯黑。
                         *
                         * 自动裁黑边的判定逻辑把那些黑当成"内容黑边"，
                         * 实测报「内容黑边 56/58 px」，把 722 宽裁成 608。
                         * 结果：
                         *   · 左边的「P」被切掉一半
                         *   · 右边的「164米进入」贴边
                         *   · 整体横向被拉宽 19%
                         *
                         * 用户的抓取区是我们按内容边界精确量出来的
                         * （HUD_SRC_X/Y/W/H），任何"每帧现算"的二次裁剪
                         * 都是在破坏这个标定。
                         *
                         * 所以：抓非主屏（g_target_display >= 0）时一律不裁。
                         */
                        /*
                         * 源矩形也取**动态**的：抓帧缓冲恒为整块，跟着 frameW/frameH。
                         *
                         * 原来用的是启动时的局部 sx/sy/sw/sh（= 0,0,capW,capH），
                         * 热更新把抓帧尺寸改小/改大后，这里还指着旧尺寸 ——
                         * 配合下面的 blit 就是"画面只取到一块旧区域"。
                         */
                        int srcX2 = 0, srcY2 = 0, srcW2 = frameW, srcH2 = frameH;
                        if (!g_mask_ready && g_target_display < 0) {
                            detect_crop_columns((const uint8_t *) pixels, gb->getStride(),
                                                srcX2, srcY2, srcW2, srcH2, &cropL, &cropR);
                        }
                        int bsx = srcX2 + cropL, bsw = srcW2 - cropL - cropR;
                        if (bsw < 4) { bsx = srcX2; bsw = srcW2; }
                        if (!g_mask_ready && (cropL || cropR)) {
                            static long s_lastcrop = -1;
                            if (s_lastcrop != (long)(cropL * 1000 + cropR)) {
                                s_lastcrop = cropL * 1000 + cropR;
                                LOGI("内容黑边 %d/%d px，实际用 %d×%d（原 %d×%d）",
                                     cropL, cropR, bsw, srcH2, srcW2, srcH2);
                            }
                        }
                        /*
                         * **blit 的抓帧尺寸也要用动态的 frameW/frameH**
                         * （2026-11-05 修「改完截取框画面错位/拉伸」）。
                         *
                         * 上面 captureScreen 已经按 frameW×frameH 渲染，
                         * 这里如果还用 main() 的局部 capW/capH，
                         * blit_bilinear 的 cap_w/cap_h 就和真实缓冲尺寸不符 ——
                         * 采样比按旧尺寸算，画面必错。
                         *
                         * 这是同一个"局部量 vs 全局量"坑的最后一个漏点：
                         * DCSR 改了 g_cap_ 之后，captureScreen 用对了、
                         * 但 blit 还在用错的，表现就是画面拉伸/错位。
                         */
                        /*
                         * blit 前把**真实源尺寸**记到全局，供 recompute_dst 的
                         * 等比分支使用（见 g_blit_src_w 的注释）。
                         * 尺寸变了才重算 —— 免得每帧都算一遍。
                         */
                        if (bsw != g_blit_src_w || srcH2 != g_blit_src_h) {
                            g_blit_src_w = bsw;
                            g_blit_src_h = srcH2;
                            recompute_dst();
                        }
                        blit_bilinear((const uint8_t *) pixels, gb->getStride(),
                                      frameW, frameH,
                                      bsx, srcY2, bsw, srcH2, canvas, outW, outH,
                                      g_dst_x, g_dst_y, g_dst_w, g_dst_h);
                        applied++;

                        if (was_paused) {
                            /*
                            * 刚从暂停恢复（DCBG 之后第一次抓到合法画面）。
                            *
                            * **必须强制全量**。原来这里只抄 prev 不发送，指望下一帧
                            * 靠增量补齐 —— 但增量只发"与 prev 不同的块"。恢复后第一帧
                            * 是桌面，第二帧是 App；两者相同的那些块（比如纯黑背景）
                            * 永远不发，**CP 上那些块还留着暂停前的旧像素**。
                            * 用户看到的就是"App 画面 + 之前残留的仪表内容"重影。
                            *
                            * 改成：这一帧照常送，并且 force_full=1 —— CP 收到整屏描述，
                            * 把上一轮的残影全部覆盖掉。多花一帧带宽（约 3MB），
                            * 但换来的是一次干净的重画。
                            */
                            was_paused = 0;
                            force_full = 1;   /* 全量重画，清掉残留 */
                            do_push = 1;
                            LOGI("暂停恢复：强制全量重画一帧（清残留）");
                        } else {
                            do_push = 1;
                        }
                    }
                }
            }
        } else {
            /* 后台模式等 App 接入 / 暂停中：绝不抓主屏 ——
             * 这时候主屏上是桌面，抓了推过去仪表就闪桌面了 */
            usleep(20 * 1000);
        }

        /*
         * 图层形状遮罩：每帧把形状外的 alpha 压 0、羽化边缘预乘、边框压暗。
         *
         * 时机很关键 —— 必须在画布内容画完之后、推送之前做，且每个推送
         * 分支都要经过这里。do_push 的两种 codec（BGRA / H.264）都吃这块画布。
         *
         * 开销：只在 alpha != 255 的像素上动，全不透明区直接 continue。
         * 有遮罩时典型只处理边缘一圈（羽化 24px × 周长 ≈ 几万像素），
         * 相对 4MB memcpy 可忽略。
         */
        /* 运行时切编码档：需要重连重握手。*/
        /* 整屏重画请求：App 写 /data/local/tmp/dc_force，这里消费一次。
         * 用在 CP 重建会话后 —— 图层被清空过，增量补不回来，必须全量推一帧。 */
        {
            static long long force_ms = 0;
            long long fn = now_ms();
            if (fn - force_ms >= 500) {
                force_ms = fn;
                struct stat fst;
                if (stat("/data/local/tmp/dc_force", &fst) == 0) {
                    unlink("/data/local/tmp/dc_force");
                    force_full = 1;
                    LOGI("收到整屏重画请求，下一帧全量发送");
                }
            }
        }

        int recodec = codec_poll();
        if (recodec) {
            LOGI("编码档切换完成，重建连接以重新握手");
            if (enc) {
                if (g_codec == DASHCAST_CODEC_H264) { h264_enc_close(); g_h264_ready = 0; g_h264_tried = 0; }
                close(enc->fd);
                enc->fd = -1;
                connected = 0;
                force_full = 1;
            }
            continue;
        }

        layer_mask_poll(outW, outH);
        if (do_push && g_mask_ready) {
            /*
             * 三段式渲染，顺序不能变：
             *
             * ① 画面矩形（g_dst_*）之外填「不透明黑」
             *    blit 只画进 dst 矩形，之外是上一帧旧像素。铺满时 dst==遮罩看不出问题；
             *    **切到等比时画面比遮罩小，两者之间的边条会糊着旧内容**。
             *    这里填不透明黑（而不是透明），是因为等比模式下那些边条属于
             *    "信箱边"，应该显示为黑，不能让原厂仪表漏进来。
             *
             * ② 遮罩矩形之外清「全透明」(0,0,0,0)
             *    ① 填的不透明黑有相当一部分落在遮罩外，必须再清一次，
             *    否则合成器不透明黑会把原厂仪表整个盖住（实测整屏全白）。
             *
             * ③ 遮罩矩形内按 alpha 处理圆角/羽化/边框
             *    alpha 表尺寸=遮罩矩形，只覆盖内部，不会碰外面。
             */
            dc_layer_clear_outside(canvas, outW, outH,
                                   g_dst_x, g_dst_y, g_dst_w, g_dst_h);
            dc_layer_mask_outside(canvas, outW, outH,
                                  g_mask.cfg.x, g_mask.cfg.y,
                                  g_mask.cfg.w, g_mask.cfg.h);
            dc_layer_apply(&g_mask, canvas, outW, g_mask.cfg.x, g_mask.cfg.y);

            /* 调试：DC_DUMP_ALPHA 存在时落一次 alpha 图，验证遮罩真的写进画布 */
            static int alpha_dumped = 0;
            if (!alpha_dumped && getenv("DC_DUMP_ALPHA")) {
                alpha_dumped = 1;
                dump_rgba("/data/local/tmp/dc_rgb.ppm", "/data/local/tmp/dc_alpha.ppm",
                          canvas, outW, outH, outW * 4);
            }
        }

        if (do_push && connected && g_codec == DASHCAST_CODEC_H264) {
            /* H.264 档：画布照常合成，但像素走"硬件编码 + RTP/UDP"，
             * 不再走图块差量。编码尺寸 = 画布的一半（960×266）。 */
            /* H.264 档不往 TCP 上写东西，控制通道断了没人发现 ——
             * CP 重启（或 dashcastd 被换掉）之后就再也连不回来。
             * 每秒探一次：读到 0 字节 = 对端关了。 */
            if (now - g_ctrl_check >= 1000) {
                g_ctrl_check = now;
                char probe[16];
                ssize_t pr = recv(enc->fd, probe, sizeof(probe), MSG_DONTWAIT);
                if (pr == 0 || (pr < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) {
                    LOGI("控制通道已断（CP 重启？），重连");
                    h264_enc_close();
                    g_h264_ready = 0;
                    g_h264_tried = 0;
                    close(enc->fd);
                    enc->fd = -1;
                    connected = 0;
                    force_full = 1;
                    continue;
                }
            }
            /*
             * 编码尺寸变了（遮罩被改过）→ 必须重建编码器。
             * MediaCodec 的尺寸在 configure 时就固定了，不能中途改；
             * 不重建的话画面会被拉伸到新比例（实测 960×384 → 1443×259，
             * 横向拉 2.2 倍，人脸明显变形）。
             */
            if (g_h264_ready && (g_enc_w != g_enc_live_w || g_enc_h != g_enc_live_h)) {
                LOGI("编码尺寸变化 %dx%d → %dx%d，重建编码器",
                     g_enc_live_w, g_enc_live_h, g_enc_w, g_enc_h);
                h264_enc_close();
                g_h264_ready = 0;
                g_h264_tried = 0;
            }
            if (!g_h264_ready && !g_h264_tried) {
                g_h264_tried = 1;
                /* 码率按像素数自适应：1:1 映射后编码尺寸可能就是 1920x532，
                 * 需要比 960x266 高得多的码率才不糊。按面积等比，夹在 1500~9000。
                 * 上限从 4000 提到 9000：1920x532(0.85x 全屏) 时约 8500kbps，
                 * 车机内网千兆 + CP 直连，带宽不是瓶颈。 */
                int kbps = (int)(2500.0 * ((double)g_enc_w * g_enc_h) / (960.0 * 266.0));
                if (kbps < 1500) kbps = 1500;
                if (kbps > 9000) kbps = 9000;
                if (h264_enc_open(enc->fd, g_enc_w, g_enc_h, kbps, maxFps) == 0) {
                    g_h264_ready = 1;
                    g_enc_live_w = g_enc_w;   /* 记住实际生效的尺寸 */
                    g_enc_live_h = g_enc_h;
                } else {
                    LOGE("H.264 编码器启动失败，回退到 BGRA 档");
                }
            }
            if (g_h264_ready) {
                /*
                 * **必须用带裁剪的版本**。
                 *
                 * 原来传整块画布 (outW, outH) = 1920×532，而编码尺寸是遮罩
                 * 尺寸（比如 672×400）。bgra_to_yuv 里用整数除法算缩放比：
                 *   1920/672 = 2  （真值 2.857）
                 *    532/400 = 1  （真值 1.33）
                 * 横向少采 576 像素、纵向少采 132 行 —— 画面错位，没被覆盖的
                 * 区域留着上一帧的旧像素，就是仪表上的重影。
                 *
                 * 改成只取遮罩矩形那块，等比缩放后正好铺满编码帧。
                 * 无遮罩时回落到整块（cw/ch <= 0 走旧路径）。
                 */
                if (g_mask_ready && g_mask.cfg.w > 0 && g_mask.cfg.h > 0) {
                    h264_enc_feed_crop(canvas, outW, outH,
                                       g_mask.cfg.x, g_mask.cfg.y,
                                       g_mask.cfg.w, g_mask.cfg.h);
                } else {
                    h264_enc_feed(canvas, outW, outH);
                }
                h264_enc_drain();
                frames++;
            }
        } else if (do_push && connected) {
            int tiles = enc_push(enc, canvas, outW * 4, force_full);
            if (tiles < 0) {
                LOGE("发送失败，断开后重连");
                enc->fd = -1;
                connected = 0;
                force_full = 1;
            } else if (tiles > 0) {
                /* 整屏级大帧（整屏约 270 图块）单独记一笔 —— 这是"桌面漏上仪表"
                 * 这类事故的照妖镜：HOME 前后如果冒出 src=SCREEN 的大帧，
                 * 就是主屏那一路没停干净，桌面被推上去了。src=VD 的大帧属于
                 * App 换屏后的重新排版，正常。 */
                if (tiles >= 150) {
                    LOGI("大帧 %d 图块 src=%s", tiles, g_proxy_active ? "VD" : "SCREEN");
                }
                force_full = 0;
                frames++;
                win_tiles += tiles;
            }
        }

        long long t = now_ms();

        /* 取证：切换 1.5 秒后再拍一张 —— 落定的画面到底是谁 */
        if (g_proxy_active && !g_after_dumped && g_switch_ms > 0 && t - g_switch_ms > 1500) {
            g_after_dumped = 1;
            dump_ppm("/data/local/tmp/dc_after.ppm", canvas, outW, outH, outW * 4);
        }

        if (t - win_start >= 1000) {
            long long dt = t - win_start;
            int fps = (int) (frames * 1000 / (dt > 0 ? dt : 1));
            /* H.264 档的字节数记在编码器里（图块通道根本没走），
             * 这里合进来，App 上的"码率"才不会恒为 0。 */
            long h264_bytes = 0;
            if (g_codec == DASHCAST_CODEC_H264) h264_enc_stats(NULL, &h264_bytes);
            const long long stat_bytes = (g_codec == DASHCAST_CODEC_H264)
                                       ? h264_bytes : enc->bytes_total;
            int kbps = 0;
            if (dt > 0) {
                kbps = (int) ((stat_bytes - bytes_mark) * 1000 / dt / 1024);
            }
            bytes_mark = stat_bytes;
            /* 注意：这里报的是「这一秒累计发出去的图块数」，不是最后一帧的图块数。
             * 用 last_tiles 会骗人 —— 一秒内 30 次推帧，最后一次常常是无变化的 0，
             * 于是 UI 上永远显示"图块 0"，看着像死了，其实前面 29 次一直在发。 */
            LOGI("STAT fps=%d capture=%d tiles=%d kbps=%d totalKB=%lld%s",
                 fps, applied, win_tiles, kbps, stat_bytes / 1024,
                 g_proxy_active ? " src=VD" : " src=SCREEN");
            frames = 0;
            applied = 0;
            win_tiles = 0;
            win_start = t;
        }
    }

    LOGI("dcapd2 退出（累计 %lld KB）", enc->bytes_total / 1024);
    if (g_codec == DASHCAST_CODEC_H264) h264_enc_close();
    if (g_proxy_fd >= 0) close(g_proxy_fd);
    if (g_listen_fd >= 0) close(g_listen_fd);
    free(g_px_buf);
    free(canvas);
    enc_free(enc);
    return 0;
}
