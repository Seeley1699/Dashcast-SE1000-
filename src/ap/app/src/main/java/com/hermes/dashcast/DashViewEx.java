package com.hermes.dashcast;

import android.content.Context;
import android.graphics.Canvas;

import com.hud.dash.DashView;

/**
 * DashViewEx —— onDraw 完成后把 JavaDashEngine 的地图帧画进 MAP 洞。
 *
 * 渲染顺序：
 *   1. super.onDraw → DashRenderer 全量（含示意路网）
 *   2. drawMapLayer → 真地图 drawBitmap 盖住 MAP 矩形 + 浮层重画
 *
 * 引擎未起或暂无地图帧时，自然回落为 renderer 自带的示意路网。
 */
public class DashViewEx extends DashView {

    private JavaDashEngine engine;

    public DashViewEx(Context c) {
        super(c);
    }

    public void attachEngine(JavaDashEngine e) { engine = e; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (engine != null) {
            /* DashView.t 是包私有，用系统时间近似（浮层呼吸点精度够用） */
            float t = System.nanoTime() / 1_000_000_000f;
            DashViewHook.drawMapLayer(canvas, engine.snapshotMap(), t);
        }
    }
}
