/*
 * layer_mask.c — 图层形状遮罩实现
 *
 * alpha 计算思路：
 *   1) 先算每个像素到"形状边界"的有符号距离 d（内正外负）
 *   2) 基础 alpha：d >= 0 → 255，d < 0 → 0
 *   3) 羽化：在 [0, feather] 区间内把 alpha 线性压到 0
 *   4) 圆角用"到圆心的距离"判断，比逐个角算圆弧更简单也更快
 *
 * 抗锯齿：alpha 用 8bit 量化，圆角/斜边天然有一点锯齿，但在 1920×532 的
 * 车机面板上看不出来；用 float 距离场已经比硬边好很多。
 */
#include "layer_mask.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <math.h>

/* ============================ 配置 ============================ */

void dc_layer_defaults(dc_layer_cfg_t *cfg, int canvas_w, int canvas_h)
{
    memset(cfg, 0, sizeof(*cfg));
    cfg->version  = 1;
    cfg->enabled  = 0;              /* 默认关闭：不加遮罩时行为与旧版完全一致 */
    cfg->canvas_w = canvas_w;
    cfg->canvas_h = canvas_h;
    cfg->x = 0; cfg->y = 0;
    cfg->w = canvas_w; cfg->h = canvas_h;
    cfg->grid = 32;
    cfg->shape = LAYER_SHAPE_RECT;
    cfg->dpi = 160;
    cfg->border = 0;
}

static int kv_get_int(const char *text, const char *key, int *out)
{
    if (!text || !key || !out) return -1;
    size_t klen = strlen(key);
    const char *p = text;
    while (p && *p) {
        const char *line_end = strchr(p, '\n');
        size_t line_len = line_end ? (size_t)(line_end - p) : strlen(p);

        /* 跳过前导空白 */
        const char *q = p;
        while (q < p + line_len && (*q == ' ' || *q == '\t')) q++;

        if ((size_t)(p + line_len - q) > klen
                && strncmp(q, key, klen) == 0) {
            const char *eq = q + klen;
            /* key 后面允许空白，然后是 '=' */
            while (eq < p + line_len && (*eq == ' ' || *eq == '\t')) eq++;
            if (eq < p + line_len && *eq == '=') {
                *out = atoi(eq + 1);
                return 0;
            }
        }
        if (!line_end) break;
        p = line_end + 1;
    }
    return -1;
}

int dc_layer_parse(dc_layer_cfg_t *cfg, const char *text)
{
    if (!cfg || !text) return -1;
    int v;

    if (kv_get_int(text, "version",  &v) == 0) cfg->version  = v;
    if (kv_get_int(text, "enabled",  &v) == 0) cfg->enabled  = v ? 1 : 0;
    if (kv_get_int(text, "canvas_w", &v) == 0 && v > 0) cfg->canvas_w = v;
    if (kv_get_int(text, "canvas_h", &v) == 0 && v > 0) cfg->canvas_h = v;

    if (kv_get_int(text, "x", &v) == 0) cfg->x = v;
    if (kv_get_int(text, "y", &v) == 0) cfg->y = v;
    if (kv_get_int(text, "w", &v) == 0) cfg->w = v;
    if (kv_get_int(text, "h", &v) == 0) cfg->h = v;

    if (kv_get_int(text, "grid", &v) == 0 && v > 0) cfg->grid = v;
    if (kv_get_int(text, "shape", &v) == 0) cfg->shape = v;
    if (kv_get_int(text, "dpi", &v) == 0 && v > 0) cfg->dpi = v;
    if (kv_get_int(text, "border", &v) == 0) cfg->border = v;

    if (kv_get_int(text, "r_tl", &v) == 0) cfg->r_tl = v;
    if (kv_get_int(text, "r_tr", &v) == 0) cfg->r_tr = v;
    if (kv_get_int(text, "r_bl", &v) == 0) cfg->r_bl = v;
    if (kv_get_int(text, "r_br", &v) == 0) cfg->r_br = v;

    if (kv_get_int(text, "trap_top", &v) == 0) cfg->trap_top = v;
    if (kv_get_int(text, "trap_bot", &v) == 0) cfg->trap_bot = v;
    if (kv_get_int(text, "feather",  &v) == 0) cfg->feather  = v;

    cfg->fit = 0;                                  /* 默认铺满 */
    /*
     * fit 三档（2026-11-08 新增 2）：
     *   0 = 铺满遮罩（拉伸，比例不匹配时变形）
     *   1 = 遮罩内等比（留黑边）
     *   2 = cover 裁切（保持比例、放大填满、超出裁掉）
     *
     * **原来是 `cfg->fit = v ? 1 : 0`** —— 任何非 0 值都变 1，
     * 所以配置里写 fit=2 会被这里吃成 1。现在按 0..2 原样保留。
     */
    if (kv_get_int(text, "fit", &v) == 0) {
        if (v < 0) v = 0;
        if (v > 2) v = 2;
        cfg->fit = v;
    }

    return 0;
}

int dc_layer_load_file(dc_layer_cfg_t *cfg, const char *path)
{
    if (!cfg || !path) return -1;
    FILE *f = fopen(path, "rb");
    if (!f) return -1;
    char buf[4096];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    buf[n] = '\0';
    return dc_layer_parse(cfg, buf);
}

/* ============================ 遮罩生成 ============================ */

/* 把配置夹到合法范围（防越界、防负数） */
static void sanitize(dc_layer_cfg_t *c)
{
    if (c->canvas_w < 1) c->canvas_w = 1;
    if (c->canvas_h < 1) c->canvas_h = 1;

    if (c->w < 1) c->w = 1;
    if (c->h < 1) c->h = 1;
    if (c->x < 0) c->x = 0;
    if (c->y < 0) c->y = 0;
    if (c->x > c->canvas_w - 1) c->x = c->canvas_w - 1;
    if (c->y > c->canvas_h - 1) c->y = c->canvas_h - 1;
    if (c->x + c->w > c->canvas_w) c->w = c->canvas_w - c->x;
    if (c->y + c->h > c->canvas_h) c->h = c->canvas_h - c->y;

    if (c->feather < 0) c->feather = 0;

    /* 羽化必须留出实心区：如果 feather >= 短边一半，整个形状会被羽化削成
     * 半透明（实测 feather=999 / 200x200 的矩形，中心 alpha 只剩 ~25，
     * 上屏几乎全黑）。上限取短边一半，保证中心始终是不透明的。 */
    {
        int half = (c->w < c->h ? c->w : c->h) / 2;
        if (half < 1) half = 1;
        if (c->feather > half) c->feather = half;
    }
    if (c->border < 0) c->border = 0;
    if (c->trap_top < 0) c->trap_top = 0;
    if (c->trap_bot < 0) c->trap_bot = 0;

    /* 圆角半径夹到短边一半，否则圆弧会互相吃掉 */
    int rmax = (c->w < c->h ? c->w : c->h) / 2;
    if (c->r_tl > rmax) c->r_tl = rmax;
    if (c->r_tr > rmax) c->r_tr = rmax;
    if (c->r_bl > rmax) c->r_bl = rmax;
    if (c->r_br > rmax) c->r_br = rmax;
    if (c->r_tl < 0) c->r_tl = 0;
    if (c->r_tr < 0) c->r_tr = 0;
    if (c->r_bl < 0) c->r_bl = 0;
    if (c->r_br < 0) c->r_br = 0;

    /* 梯形内缩不能超过一半宽，否则自交 */
    if (c->trap_top > c->w / 2) c->trap_top = c->w / 2;
    if (c->trap_bot > c->w / 2) c->trap_bot = c->w / 2;

    if (c->shape < LAYER_SHAPE_RECT || c->shape > LAYER_SHAPE_ELLIPSE)
        c->shape = LAYER_SHAPE_RECT;
}

/*
 * 有符号距离场：返回该点"离形状边界有多远"，内部为正。
 * 用它统一处理 4 种形状 + 羽化。
 */
static float shape_distance(const dc_layer_cfg_t *c, float px, float py)
{
    const float w = (float)c->w, h = (float)c->h;

    switch (c->shape) {
    case LAYER_SHAPE_ELLIPSE: {
        /* 椭圆近似：把坐标归一化后按圆算，再用缩放近似修正 */
        const float cx = w * 0.5f, cy = h * 0.5f;
        const float dx = (px - cx) / cx, dy = (py - cy) / cy;
        const float r  = sqrtf(dx * dx + dy * dy);
        /* r=1 是边界；用短半轴换算成像素距离近似 */
        const float k = (cx < cy ? cx : cy);
        return (1.0f - r) * k;
    }

    case LAYER_SHAPE_TRAP: {
        /* 梯形：按 y 线性插值出该行的内缩量（左右对称内缩） */
        const float t = (h > 1.0f) ? (py / h) : 0.0f;
        const float inset = (float)c->trap_top
                          + ((float)c->trap_bot - (float)c->trap_top) * t;

        /* 水平余量：距左边界 与 距右边界 取小（两侧都是 inset） */
        const float dL = px - inset;
        const float dR = (w - inset) - px;
        const float dx = (dL < dR) ? dL : dR;

        /* 垂直余量 */
        const float dy = (py < h - py) ? py : (h - py);

        /*
         * 斜边的真实距离 = 水平余量 / sqrt(1 + slope^2)，
         * slope = 每像素的横向内缩变化量。斜得越厉害，同样水平余量
         * 对应的垂直距离越小。
         */
        const float slope = (h > 1.0f)
            ? ((float)c->trap_bot - (float)c->trap_top) / h : 0.0f;
        const float norm = sqrtf(1.0f + slope * slope);
        const float dxn = dx / norm;
        return (dxn < dy) ? dxn : dy;
    }

    case LAYER_SHAPE_ROUND: {
        /* 圆角矩形：先按普通矩形算，再对四个角做圆弧修正 */
        const float dx = (px < w - px) ? px : (w - px);
        const float dy = (py < h - py) ? py : (h - py);

        /* 判断落在哪个角 */
        int tl = (px < (float)c->r_tl && py < (float)c->r_tl);
        int tr = (px > w - (float)c->r_tr && py < (float)c->r_tr);
        int bl = (px < (float)c->r_bl && py > h - (float)c->r_bl);
        int br = (px > w - (float)c->r_br && py > h - (float)c->r_br);

        if (tl) {
            const float r = (float)c->r_tl;
            const float ddx = r - px, ddy = r - py;
            return r - sqrtf(ddx * ddx + ddy * ddy);
        }
        if (tr) {
            const float r = (float)c->r_tr;
            const float ddx = px - (w - r), ddy = r - py;
            return r - sqrtf(ddx * ddx + ddy * ddy);
        }
        if (bl) {
            const float r = (float)c->r_bl;
            const float ddx = r - px, ddy = py - (h - r);
            return r - sqrtf(ddx * ddx + ddy * ddy);
        }
        if (br) {
            const float r = (float)c->r_br;
            const float ddx = px - (w - r), ddy = py - (h - r);
            return r - sqrtf(ddx * ddx + ddy * ddy);
        }
        return (dx < dy) ? dx : dy;
    }

    default: {  /* 直角矩形 */
        const float dx = (px < w - px) ? px : (w - px);
        const float dy = (py < h - py) ? py : (h - py);
        return (dx < dy) ? dx : dy;
    }
    }
}

int dc_layer_build(dc_layer_mask_t *m, const dc_layer_cfg_t *cfg)
{
    if (!m) return -1;
    /*
     * **先把旧 alpha 释放掉再 memset**（2026-11-07 修内存泄漏）。
     *
     * 原来直接 memset(m, 0, sizeof(*m)) 会把 m->alpha 指针清零 ——
     * 旧缓冲既没 free、指针也丢了，成永久泄漏。
     *
     * 调用方 dcapd2.c:379 在**正常重载路径**上直接调本函数，
     * 只有两条错误分支（load_file 失败 / enabled=0）会先调 dc_layer_free。
     * 也就是「用户每改一次遮罩、每切一次编码档」都漏一块 w×h 字节。
     *
     * 遮罩典型 528×416 ≈ 215KB，改十次就是 2MB，长时间跑会累积。
     * 函数自己负责释放旧缓冲，语义更安全：调用方不用记得先 free。
     */
    free(m->alpha);
    memset(m, 0, sizeof(*m));
    if (!cfg) return -1;

    dc_layer_cfg_t c = *cfg;
    sanitize(&c);
    m->cfg = c;
    m->w = c.w;
    m->h = c.h;

    size_t n = (size_t)m->w * (size_t)m->h;
    m->alpha = (uint8_t *)malloc(n ? n : 1);
    if (!m->alpha) { m->w = m->h = 0; return -1; }
    memset(m->alpha, 0, n);

    if (!c.enabled) {
        /* 关闭时整块都视为"形状内"（沿用旧的不透明行为，由调用方刷 0xFF） */
        memset(m->alpha, 255, n);
        m->valid = 1;
        return 0;
    }

    const float feather = (float)c.feather;
    const float fden = (feather > 0.0f) ? feather : 1.0f;

    for (int j = 0; j < m->h; j++) {
        const float py = (float)j + 0.5f;
        uint8_t *row = m->alpha + (size_t)j * (size_t)m->w;

        for (int i = 0; i < m->w; i++) {
            const float px = (float)i + 0.5f;
            const float d = shape_distance(&c, px, py);

            uint8_t a;
            if (d <= 0.0f) {
                a = 0;                                  /* 形状外：全透明 */
            } else if (feather > 0.0f && d < feather) {
                a = (uint8_t)((d / fden) * 255.0f + 0.5f);   /* 羽化渐变 */
            } else if (c.border > 0 && d < (float)c.border) {
                a = 254;                                /* 边框标记（apply 压暗） */
            } else {
                a = 255;
            }
            row[i] = a;
        }
    }

    m->valid = 1;
    return 0;
}

void dc_layer_free(dc_layer_mask_t *m)
{
    if (!m) return;
    free(m->alpha);
    m->alpha = NULL;
    m->w = m->h = 0;
    m->valid = 0;
}

/* ============================ 应用到画布 ============================ */

void dc_layer_clear_canvas(uint8_t *px, int w, int h)
{
    if (!px || w <= 0 || h <= 0) return;
    /* 全 0 = alpha 0 的透明像素（BGRA 顺序，预乘后 rgb 也必须是 0） */
    memset(px, 0, (size_t)w * (size_t)h * 4);
}

void dc_layer_clear_outside(uint8_t *px, int w, int h,
                            int x, int y, int rw, int rh)
{
    if (!px || w <= 0 || h <= 0) return;

    /* 夹到画布内 */
    if (x < 0) { rw += x; x = 0; }
    if (y < 0) { rh += y; y = 0; }
    if (rw < 0) rw = 0;
    if (rh < 0) rh = 0;
    if (x + rw > w) rw = w - x;
    if (y + rh > h) rh = h - y;

    const size_t row_bytes = (size_t)w * 4;

    /* 画面矩形为空 → 整屏透明（没内容可投） */
    if (rw <= 0 || rh <= 0) {
        memset(px, 0, (size_t)w * (size_t)h * 4);
        return;
    }

    /*
     * 画面矩形之外：清成「不透明黑」(0,0,0,255)，不是透明。
     *
     * 为什么是不透明黑而不是透明：
     *   · 铺满模式：画面矩形 == 遮罩矩形，矩形外本来就会被 dc_layer_apply
     *     压成透明（alpha=0），所以填什么都不影响可见结果；
     *   · 等比模式：画面比遮罩小，画面与遮罩之间的边条留在这里。
     *     如果填透明，原厂仪表会从边条漏出来，看着像"画面缺一块"；
     *     填不透明黑才是正常的信箱边（画面居中、两侧黑边）。
     *
     * 之后 dc_layer_apply 会按遮罩 alpha 把遮罩外的部分再压成透明，
     * 所以"遮罩外露原厂仪表"这个核心行为不受影响。
     */
    {
        /* 上方整块 */
        if (y > 0) {
            size_t n = (size_t)y * row_bytes;
            memset(px, 0, n);
            for (size_t k = 3; k < n; k += 4) px[k] = 255;
        }
        /* 下方整块 */
        if (y + rh < h) {
            uint8_t *base = px + (size_t)(y + rh) * row_bytes;
            size_t n = (size_t)(h - y - rh) * row_bytes;
            memset(base, 0, n);
            for (size_t k = 3; k < n; k += 4) base[k] = 255;
        }
        /* 中间每行：左边一段 + 右边一段 */
        for (int j = 0; j < rh; j++) {
            uint8_t *row = px + (size_t)(y + j) * row_bytes;
            if (x > 0) {
                memset(row, 0, (size_t)x * 4);
                for (int k = 0; k < x; k++) row[k * 4 + 3] = 255;
            }
            if (x + rw < w) {
                uint8_t *r2 = row + (size_t)(x + rw) * 4;
                int npx = w - x - rw;
                memset(r2, 0, (size_t)npx * 4);
                for (int k = 0; k < npx; k++) r2[k * 4 + 3] = 255;
            }
        }
    }
}

/*
 * 把「遮罩矩形之外」强制清成全透明（0,0,0,0）。
 *
 * 为什么单独一个函数：dc_layer_apply 的 alpha 表尺寸 = 遮罩矩形，
 * 遮罩外的像素它遍历不到。而 clear_outside 为了等比模式的画面边条
 * 会把大片区域填成不透明黑 —— 那些在遮罩外的部分必须再清透明，
 * 否则合成器会把原厂仪表整个盖住（实测：整屏全白 alpha）。
 *
 * 调用顺序：blit 画内容 → clear_outside 填边条 → **本函数**清遮罩外 → apply 处理遮罩内边缘
 */
void dc_layer_mask_outside(uint8_t *px, int w, int h,
                           int x, int y, int rw, int rh)
{
    if (!px || w <= 0 || h <= 0) return;
    if (x < 0) { rw += x; x = 0; }
    if (y < 0) { rh += y; y = 0; }
    if (rw < 0) rw = 0;
    if (rh < 0) rh = 0;
    if (x + rw > w) rw = w - x;
    if (y + rh > h) rh = h - y;

    const size_t row_bytes = (size_t)w * 4;

    if (rw <= 0 || rh <= 0) {
        memset(px, 0, (size_t)w * (size_t)h * 4);
        return;
    }
    if (y > 0) memset(px, 0, (size_t)y * row_bytes);
    if (y + rh < h) {
        memset(px + (size_t)(y + rh) * row_bytes, 0,
               (size_t)(h - y - rh) * row_bytes);
    }
    for (int j = 0; j < rh; j++) {
        uint8_t *row = px + (size_t)(y + j) * row_bytes;
        if (x > 0) memset(row, 0, (size_t)x * 4);
        if (x + rw < w) {
            memset(row + (size_t)(x + rw) * 4, 0, (size_t)(w - x - rw) * 4);
        }
    }
}

void dc_layer_apply(const dc_layer_mask_t *m, uint8_t *px, int stride_px,
                    int off_x, int off_y)
{
    if (!m || !m->valid || !m->alpha || !px) return;
    if (stride_px <= 0) return;

    const int border = (m->cfg.enabled ? m->cfg.border : 0);

    /*
     * 处理顺序与语义（2026-10-31 修正）：
     *
     *   a == 0        → 遮罩外：**强制写成全 0**（预乘透明）。
     *                   不论 rgb 原先是什么（clear_outside 可能填过不透明黑，
     *                   那是给等比模式的画面边条用的），这里必须清掉，
     *                   否则合成器不透明黑会把原厂仪表整个盖住。
     *   a == 255      → 遮罩内且非边框：原样保留（画面内容 / 不透明黑边条）
     *   a == 254      → 边框标记：压暗成实线，保持不透明
     *   0 < a < 254   → 羽化边缘：premultiply，rgb 与 alpha 一起乘
     */
    for (int j = 0; j < m->h; j++) {
        uint8_t *dst = px + ((size_t)(off_y + j) * (size_t)stride_px
                             + (size_t)off_x) * 4;
        const uint8_t *arow = m->alpha + (size_t)j * (size_t)m->w;

        for (int i = 0; i < m->w; i++) {
            const uint8_t a = arow[i];
            uint8_t *p = dst + (size_t)i * 4;

            if (a == 255) continue;          /* 遮罩内不透明区，不动 */

            if (a == 0) {
                /* 遮罩外：强制全透明（覆盖 clear_outside 可能填的不透明黑） */
                p[0] = p[1] = p[2] = p[3] = 0;
                continue;
            }

            if (border > 0 && a == 254) {
                p[0] = (uint8_t)((int)p[0] * 2 / 5);
                p[1] = (uint8_t)((int)p[1] * 2 / 5);
                p[2] = (uint8_t)((int)p[2] * 2 / 5);
                p[3] = 255;
                continue;
            }

            /* 羽化边缘 */
            p[0] = (uint8_t)(((int)p[0] * (int)a) / 255);
            p[1] = (uint8_t)(((int)p[1] * (int)a) / 255);
            p[2] = (uint8_t)(((int)p[2] * (int)a) / 255);
            p[3] = a;
        }
    }
}
