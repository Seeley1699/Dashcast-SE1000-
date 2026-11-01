/*
 * layer_mask.h — 图层形状遮罩（圆角矩形 / 梯形 / 椭圆 + 羽化）
 *
 * 为什么需要：EVCC 原理要求"叠加一层可消失的透明膜"，而不是铺满整屏的不透明图。
 * 圆角、梯形、羽化、边框全部建立在 alpha 上 —— 形状外 alpha=0，合成器就把
 * 原厂仪表透出来；形状内 alpha=255，盖住它。羽化则是边缘 alpha 由 0 渐变到 255。
 *
 * 关键：Wayland 的 WL_SHM_FORMAT_ARGB8888 是 **premultiplied**（预乘）。
 * 若只改 alpha 不改 RGB，羽化边缘会出现亮边/彩边。所以必须 rgb *= a / 255。
 *
 * 配置格式（key=value 文本，Java 侧 LayerConfig 写同一份）：
 *   version / enabled / canvas_w / canvas_h
 *   x / y / w / h                 图层矩形（画布坐标）
 *   grid                          网格步长（仅 UI 吸附用，C 侧不参与计算）
 *   shape                         0=矩形 1=圆角矩形 2=梯形 3=椭圆
 *   dpi                           仅 UI 元数据
 *   border                        边框宽度 px（0=无）
 *   r_tl / r_tr / r_bl / r_br     四角圆角半径
 *   trap_top / trap_bot           梯形上/下沿内缩
 *   feather                       羽化宽度 px（向内渐变）
 */
#ifndef LAYER_MASK_H
#define LAYER_MASK_H

#include <stdint.h>

#define LAYER_SHAPE_RECT     0
#define LAYER_SHAPE_ROUND    1
#define LAYER_SHAPE_TRAP     2
#define LAYER_SHAPE_ELLIPSE  3

typedef struct {
    int version;
    int enabled;
    int canvas_w, canvas_h;

    int x, y, w, h;          /* 图层矩形（画布坐标） */
    int grid;                /* 网格步长（UI 用） */
    int shape;
    int dpi;                 /* UI 元数据 */
    int border;
    int r_tl, r_tr, r_bl, r_br;
    int trap_top, trap_bot;
    int feather;

    /* 内容填充方式（0 = 铺满遮罩矩形，1 = 等比居中）
     *
     * fit=0（默认）：内容拉伸填满遮罩矩形，**不留任何黑边**。视频会有
     *   轻微变形，但用户要的是"仪表上这块区域全是投屏内容"。
     * fit=1：保持原始比例，遮罩矩形内居中，比例不符的两侧留透明。
     *
     * 注意：无论哪种模式，遮罩矩形之外的像素一律 alpha=0（透明），
     * 合成器会把原厂仪表透出来 —— 这就是"黑边的地方恢复原有的样子"。 */
    int fit;
} dc_layer_cfg_t;

/* 遮罩上下文：w×h 的 8bit alpha + 预乘查表 */
typedef struct {
    dc_layer_cfg_t cfg;
    uint8_t *alpha;          /* w*h，0..255 */
    int      w, h;
    int      valid;
} dc_layer_mask_t;

/* 填默认值（全屏矩形、无圆角、无羽化） */
void dc_layer_defaults(dc_layer_cfg_t *cfg, int canvas_w, int canvas_h);

/* 从 key=value 文本解析；返回 0 成功，-1 失败（保留默认值） */
int dc_layer_parse(dc_layer_cfg_t *cfg, const char *text);

/* 从文件读；文件不存在/解析失败返回 -1 */
int dc_layer_load_file(dc_layer_cfg_t *cfg, const char *path);

/* 按 cfg 生成遮罩。返回 0 成功 */
int dc_layer_build(dc_layer_mask_t *m, const dc_layer_cfg_t *cfg);

/* 释放遮罩内存 */
void dc_layer_free(dc_layer_mask_t *m);

/*
 * 把遮罩应用到一块 BGRA 图（premultiply）。
 *   px / stride_px : 目标缓冲，行距按像素算（stride_px*4 字节）
 *   (off_x, off_y) : 遮罩左上角在缓冲里的位置
 * 只处理遮罩覆盖的区域。enabled=0 时原样保留（全不透明由调用方负责）。
 */
void dc_layer_apply(const dc_layer_mask_t *m, uint8_t *px, int stride_px,
                    int off_x, int off_y);

/* 把整个画布刷成"全透明"（形状外必须是 alpha=0，才能透出原厂仪表） */
void dc_layer_clear_canvas(uint8_t *px, int w, int h);

/*
 * 只清「图层矩形 (x,y,w,h) 之外」的部分为全透明（alpha=0 且 RGB=0）。
 *
 * 为什么每帧都要调：blit 只把画面画进矩形内，矩形外留着的是上一帧的旧像素。
 * 遮罩一缩小，外面就残留一圈旧画面（看着像黑边/脏边）。清掉之后合成器
 * 才会把原厂仪表透出来 —— 这就是"黑边的地方恢复原有的样子"。
 *
 * 实现效率：矩形上方/下方是连续整行，直接 memset；矩形内左右两侧是小段 memset。
 * 全程只做清零，不读像素，1920×532 约 0.3ms。
 */
void dc_layer_clear_outside(uint8_t *px, int w, int h,
                            int x, int y, int rw, int rh);

/*
 * 把「遮罩矩形之外」强制清成全透明。
 *
 * 必须在 dc_layer_clear_outside 之后调用：后者为了等比模式的画面边条
 * 会把大片区域填成不透明黑，其中落在遮罩外的部分必须再清透明，
 * 否则合成器会把原厂仪表整个盖住。
 *
 * 调用顺序：blit → clear_outside → **mask_outside** → apply
 */
void dc_layer_mask_outside(uint8_t *px, int w, int h,
                           int x, int y, int rw, int rh);

#endif /* LAYER_MASK_H */