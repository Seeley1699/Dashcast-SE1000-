package com.hermes.dashcast;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.View;
import android.view.WindowManager;

/**
 * 仅供联调：一块 60fps 的确定内容源。
 *
 * 用途：把这块 Activity 丢到虚拟屏上，就能验证"虚拟屏 → 抓帧 → CP"整条链路的实时性。
 * 它每帧都在动，帧率、图块数、带宽都是可预期的；如果这里也只有 1fps，问题一定在链路，
 * 如果这里能跑到 30fps，那说明之前画面不动是**内容源自己没在刷新**。
 *
 * 没有 launcher 入口，只能 am start 拉起：
 *   adb shell am start -n com.hermes.dashcast/.TestPatternActivity --display N
 */
public class TestPatternActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(new PatternView(this));
    }

    private static class PatternView extends View implements Choreographer.FrameCallback {

        private final Paint paint = new Paint();
        private long frames;
        private long startNs;

        PatternView(android.content.Context c) {
            super(c);
            paint.setAntiAlias(false);
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (startNs == 0) startNs = frameTimeNanos;
            frames++;
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        protected void onDraw(Canvas c) {
            long t = frames;   // 帧号即时间轴，不依赖系统时钟
            int w = getWidth();
            int h = getHeight();

            // 底色每秒翻转一次，保证图块一直在变
            c.drawColor(((t / 60) & 1) == 0 ? Color.BLACK : Color.rgb(8, 8, 24));

            // 竖直滚动彩条：每帧位移 6px
            int barW = Math.max(8, w / 48);
            int offset = (int) ((t * 6) % (barW * 6));
            for (int x = -offset, i = 0; x < w + barW * 6; x += barW * 6, i++) {
                paint.setColor(Color.HSVToColor(new float[]{ (i * 23 + t / 2) % 360, 0.85f, 1f }));
                c.drawRect(x, 0, x + barW * 2, h, paint);
            }

            // 横向扫过的亮块：慢速、大面积，便于看"是否在动"
            int sweep = (int) ((t * 9) % (w + 300)) - 300;
            paint.setColor(Color.WHITE);
            c.drawRect(sweep, 0, sweep + 300, h, paint);

            // 中央计数（文字边缘对压缩也敏感，能看出图块是否在变）
            paint.setColor(Color.BLACK);
            paint.setTextSize(h * 0.42f);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.valueOf(frames), w / 2f, h * 0.68f, paint);
        }
    }
}
