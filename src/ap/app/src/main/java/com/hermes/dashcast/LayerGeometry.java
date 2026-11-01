package com.hermes.dashcast;

/**
 * 图层坐标系策略（2026-11-01 重影根因修复）。
 *
 * ══════════════════════════════════════════════════════════════
 * 问题的本质：AP 端画布和 CP 端图层用了两套不同的坐标映射，
 * 叠加起来就变成"两个画面"。
 * ══════════════════════════════════════════════════════════════
 *
 * AP 端（dcapd2）的行为：
 *   · 画布固定 1920×532（= 仪表全屏尺寸）
 *   · blit 把抓到的画面画进**遮罩矩形**（比如 928,192 480×340）
 *   · 画布其余位置 memset 成全透明 (0,0,0,0)
 *
 * CP 端（dashcastd）的行为由 App 通过 LayerManagerControl 设定：
 *   `set layer 99 destination region <x> <y> <w> <h>`
 *   合成器把整块 source 画布**缩放**到 destination 矩形里。
 *
 * ══════════════════════════════════════════════════════════════
 * 错误的配法（之前就是这样，导致重影）：
 *     AP:  内容画在画布 928,192 480×340
 *     CP:  destination = 928,192 480×340, source = 0,0 1920×532
 *     → 合成器把**整块** 1920×532 压到 480×340
 *     → 画布上那个 480×340 的正常画面被压成 120×85，跑到 destination 一角
 *     → 用户看到"正常画面 + 缩小副本"叠在一起 = 重影
 *
 * ══════════════════════════════════════════════════════════════
 * 两种正确的配法：
 *
 *   A) destination = 0,0 1920×532（整屏），AP 画在遮罩坐标
 *      → 画布 1:1 映射到屏幕，零缩放。遮罩外的透明像素透出原厂仪表。
 *      → **BGRA 档用这个**（有 alpha，能挖洞）
 *
 *   B) destination = 遮罩矩形，AP 画在画布 0,0
 *      → 图层本身只占那块，旁边露出原厂仪表。
 *      → **H.264 档必须用这个**（4:2:0 无 alpha，挖不了洞）
 *
 * 注意 B 要求 AP 端把内容画在画布左上角（0,0）而不是遮罩坐标 ——
 * 现在的 dcapd2 在 H.264 档下就是这么做的（编码尺寸 = 遮罩尺寸，
 * 直接喂遮罩那块像素）。
 */
final class LayerGeometry {

    private LayerGeometry() {}

    /**
     * 计算应该下发给 CP 的 destination region。
     *
     * @param codec      当前编码档（MirrorController.CODEC_BGRA / CODEC_H264）
     * @param mask       当前遮罩矩形（画布坐标）
     * @param canvasW    画布宽（1920）
     * @param canvasH    画布高（532）
     * @return {x, y, w, h} 要下发给 setLayerRect 的值
     */
    static int[] destinationFor(int codec, int[] mask, int canvasW, int canvasH) {
        /*
         * H.264：没有 alpha，只能在"图层本身"上做文章 ——
         * 把图层缩到遮罩矩形，图层外自然露出原厂仪表。
         * 此时 AP 端编码的正是遮罩那块像素（dcapd2 的 recompute_enc_size
         * 已经把编码尺寸设成遮罩尺寸），合成器 1:1 不缩放。
         */
        if (codec == MirrorController.CODEC_H264) {
            if (mask == null || mask.length < 4) return new int[]{0, 0, canvasW, canvasH};
            int x = mask[0], y = mask[1], w = mask[2], h = mask[3];
            if (w <= 0 || h <= 0) return new int[]{0, 0, canvasW, canvasH};
            return new int[]{x, y, w, h};
        }

        /*
         * BGRA+LZ4：有 alpha，画布遮罩外已经全透明。
         * 图层铺满整屏 + 1:1 映射，合成器不做任何缩放 ——
         * 这是唯一不会产生"缩小副本"的配法。
         *
         * 注意：不要在这里传遮罩矩形！那会让合成器把整块画布压缩，
         * 画布上正常位置的画面会被缩成一小块叠上去（重影的根源）。
         */
        return new int[]{0, 0, canvasW, canvasH};
    }
}
