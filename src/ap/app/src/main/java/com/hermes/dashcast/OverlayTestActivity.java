package com.hermes.dashcast;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 浮窗测试页 —— 只为验证 MiniOverlay 能否真的浮在桌面之上。
 *
 * 用法：
 *   adb shell am start -n com.hermes.dashcast/.OverlayTestActivity
 *   点「显示浮窗」→ 按 HOME → 浮窗应仍留在屏幕上
 *
 * 判定标准（无需肉眼）：
 *   adb shell dumpsys window windows | grep DashcastOverlay
 *   有窗口记录 = 浮窗真的挂上 WindowManager 了
 */
public class OverlayTestActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(getColor(R.color.surface_root));

        TextView title = new TextView(this);
        title.setText("浮窗测试");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 24, 0, 24);
        root.addView(status);

        Button grant = new Button(this);
        grant.setText("1. 申请悬浮窗权限");
        grant.setAllCaps(false);
        grant.setOnClickListener(v -> {
            MiniOverlay.requestOverlayPermission(this);
            refresh();
        });
        root.addView(grant);

        Button showBtn = new Button(this);
        showBtn.setText("2. 显示浮窗");
        showBtn.setAllCaps(false);
        showBtn.setOnClickListener(v -> {
            boolean ok = MiniOverlay.get(this).show();
            status.setText(ok ? "浮窗已显示 ✓\n按 HOME 验证它是否还在"
                    : "浮窗显示失败（先授权）");
            DashLog.i("DashcastOverlay", "TEST show() = " + ok);
        });
        root.addView(showBtn);

        Button hideBtn = new Button(this);
        hideBtn.setText("3. 隐藏浮窗");
        hideBtn.setAllCaps(false);
        hideBtn.setOnClickListener(v -> {
            MiniOverlay.get(this).hide();
            status.setText("浮窗已隐藏");
            refresh();
        });
        root.addView(hideBtn);

        Button target = new Button(this);
        target.setText("4. 内容源设为地图");
        target.setAllCaps(false);
        target.setOnClickListener(v -> {
            MiniOverlay.get(this).setTarget("com.geely.map", "地图");
            status.setText("内容源已设为 com.geely.map");
        });
        root.addView(target);

        Button miniBtn = new Button(this);
        miniBtn.setText("5. launch() 起小窗（带 start_windowmode）");
        miniBtn.setAllCaps(false);
        miniBtn.setOnClickListener(v -> {
            MiniWindowActivity.launch(this);
            status.setText("已调用 launch()，观察是否进系统小窗");
            DashLog.i("DashcastOverlay", "TEST launch() called");
        });
        root.addView(miniBtn);

        setContentView(root);
        refresh();
    }

    private void refresh() {
        boolean can = MiniOverlay.canDrawOverlay(this);
        boolean showing = MiniOverlay.get(this).isShowing();
        status.setText("悬浮窗权限：" + (can ? "已授予 ✓" : "未授予 ✗")
                + "\n浮窗状态：" + (showing ? "显示中" : "已隐藏"));
    }
}