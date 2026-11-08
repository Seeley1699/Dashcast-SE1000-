package com.hermes.dashcast;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * NaviService OpenAPI 绑定验证。
 *
 * ══════════════════════════════════════════════════════════════════
 * 验证目标（最小）
 * ══════════════════════════════════════════════════════════════════
 *
 * 1. 声明 ecarx.oem.permission.OPENAPI_NAVI_PERMISSION（normal 级，无需 root）
 *    能否成功 bind 到 ecarx.naviservice/.service.NaviService
 * 2. onServiceConnected 拿到 IBinder 后，它的 interface descriptor 是什么
 * 3. 能否用 Binder.transact 调 TRANSACTION_registerNaviObserver
 *
 * 为什么这一步不需要完整 AIDL：
 *   bind 成功 + 拿到 descriptor 就已经证明了「连接可用」。
 *   具体怎么调、返回什么数据是下一步的事。
 *
 * ══════════════════════════════════════════════════════════════════
 * 已知信息（从 XSFNaviService dex 提取）
 * ══════════════════════════════════════════════════════════════════
 *
 *   服务:      ecarx.naviservice/.service.NaviService
 *   权限:      ecarx.oem.permission.OPENAPI_NAVI_PERMISSION (normal)
 *   接口:      ecarx.naviservice.INaviServer
 *   事务码:    TRANSACTION_registerNaviObserver
 *              TRANSACTION_unregisterNaviObserver
 *              TRANSACTION_setNavigationContextOwner
 *   数据:      getTurnId / laneInfos / lightCountDown / ISpeedLimitInfoBean
 *              getRemainTime / trafficLightDistance
 */
public class NaviBindTestActivity extends Activity {

    private static final String TAG = "DashcastNaviTest";

    private static final String SERVICE_PKG = "ecarx.naviservice";
    private static final String SERVICE_CLS =
            "ecarx.naviservice.service.NaviService";

    private TextView log;
    private StringBuilder sb = new StringBuilder();

    private boolean bound = false;
    private IBinder naviBinder;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bound = true;
            naviBinder = service;
            append("✓ onServiceConnected");
            append("  component = " + name.flattenToShortString());

            if (service == null) {
                append("✗ IBinder 是 null");
                return;
            }

            /* 关键是这个：Binder 的接口描述符能证明是哪个 AIDL */
            String desc = null;
            try {
                desc = service.getInterfaceDescriptor();
            } catch (Throwable t) {
                append("  getInterfaceDescriptor 抛异常: " + t);
            }
            append("  descriptor = " + desc);

            /* 试调 registerNaviObserver（事务码逐个探测） */
            probeTransactions(service);

            DashLog.i(TAG, "NAVI_BIND ok descriptor=" + desc);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            naviBinder = null;
            append("✗ onServiceDisconnected");
            DashLog.w(TAG, "NAVI_BIND disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            append("✗ onBindingDied");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            append("✗ onNullBinding（服务返回 null，通常是权限不足）");
            DashLog.w(TAG, "NAVI_BIND onNullBinding");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(getColor(R.color.surface_root));

        Button bindBtn = new Button(this);
        bindBtn.setText("1. bind NaviService");
        bindBtn.setAllCaps(false);
        bindBtn.setOnClickListener(v -> doBind());
        root.addView(bindBtn);

        Button unbindBtn = new Button(this);
        unbindBtn.setText("2. unbind");
        unbindBtn.setAllCaps(false);
        unbindBtn.setOnClickListener(v -> doUnbind());
        root.addView(unbindBtn);

        Button clearBtn = new Button(this);
        clearBtn.setText("3. 清空日志");
        clearBtn.setAllCaps(false);
        clearBtn.setOnClickListener(v -> {
            sb.setLength(0);
            log.setText("（已清空）");
        });
        root.addView(clearBtn);

        ScrollView sv = new ScrollView(this);
        log = new TextView(this);
        log.setTextSize(12);
        log.setText("点「1. bind NaviService」开始验证\n"
                + "权限: " + (hasPerm() ? "已有 OPENAPI_NAVI_PERMISSION" : "声明了但运行时未知")
                + "\n目标: " + SERVICE_PKG + "/.service.NaviService");
        sv.addView(log);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        svLp.setMargins(0, dp(12), 0, 0);
        root.addView(sv, svLp);

        setContentView(root);
    }

    private boolean hasPerm() {
        try {
            return checkSelfPermission("ecarx.oem.permission.OPENAPI_NAVI_PERMISSION")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void doBind() {
        append("── 开始 bind ──");
        append("权限检查 = " + hasPerm());

        Intent it = new Intent();
        it.setComponent(new ComponentName(SERVICE_PKG, SERVICE_CLS));
        try {
            boolean ok = bindService(it, conn, Context.BIND_AUTO_CREATE);
            append("bindService 返回 = " + ok);
            DashLog.i(TAG, "NAVI_BIND bindService=" + ok
                    + " perm=" + hasPerm());
        } catch (Throwable t) {
            append("✗ bindService 抛异常: " + t);
            DashLog.e(TAG, "NAVI_BIND bindService failed", t);
        }
    }

    private void doUnbind() {
        if (!bound) {
            append("当前未绑定");
            return;
        }
        try {
            unbindService(conn);
            bound = false;
            naviBinder = null;
            append("已 unbind");
        } catch (Throwable t) {
            append("unbind 抛异常: " + t);
        }
    }

    /**
     * 逐个探测事务码，看哪个不抛异常。
     *
     * 这里不指望真的拿到数据 —— 只是想确认 Binder 能响应 transact，
     * 以及哪个事务码是 register / getInfo。
     */
    private void probeTransactions(IBinder service) {
        append("── 探测事务码 ──");
        /*
         * AIDL 事务码从 1 开始（FIRST_CALL_TRANSACTION）。
         * 逐个试，看哪个返回正常。
         *
         * 注意：错误的事务码可能让服务抛异常或崩，所以只试前 40 个，
         * 且每个都 try/catch。真实调用要拿准确的 AIDL 定义。
         */
        int okCount = 0;
        for (int code = 1; code <= 40; code++) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("ecarx.naviservice.INaviServer");
                boolean alive = service.transact(code, data, reply, 0);
                if (alive) okCount++;
            } catch (Throwable t) {
                /* 单个事务码失败不算问题 */
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        append("前 40 个事务码里有 " + okCount + " 个响应成功");
        DashLog.i(TAG, "NAVI_BIND transact probes ok=" + okCount);
    }

    private void append(String s) {
        sb.append(s).append('\n');
        if (log != null) log.setText(sb.toString());
    }

    @Override
    protected void onDestroy() {
        doUnbind();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
