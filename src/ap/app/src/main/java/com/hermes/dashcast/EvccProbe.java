package com.hermes.dashcast;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.kooo.evcc.evccapi.EvccApiContract;
import com.kooo.evcc.evccapi.EvccApiInfo;
import com.kooo.evcc.evccapi.EvccPageRequest;
import com.kooo.evcc.evccapi.EvccPropertyDescriptor;
import com.kooo.evcc.evccapi.EvccPropertyPage;
import com.kooo.evcc.evccapi.EvccPropertyRecord;
import com.kooo.evcc.evccapi.EvccSessionInfo;
import com.kooo.evcc.evccapi.EvccSourceState;
import com.kooo.evcc.evccapi.client.EvccApiClient;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * EVCC 属性目录探测器（2026-11-12）
 *
 * 目的：摸清 EVCC 到底能提供哪些车辆属性、来自哪个源、叫什么名字。
 *
 * 为什么必须先探测再写正式映射：
 *   VHAL 里 0x11400e01（远光）/ 0x11400e03（双闪）的 timestamp 恒为 0，
 *   拿到的 [1] 是 CarService 初始化默认值。EVCC 有 9 路属性源
 *   （VHAL/APVP/VEHICLE_AIDL/CAPI/ADAPT_API/SOA/GEELY_CAPI/GEELY_AIDL），
 *   同一个概念在不同源里 ID 和名字完全不同。
 *   先导目录，才能知道「远光」在 EVCC 里到底叫什么、在哪个源。
 *
 * 输出：/sdcard/Download/Dashcast/evcc_catalog.txt
 *   每行一个属性，含 source / hexId / name / description / enumOptions
 */
public final class EvccProbe {

    private static final String TAG = "DashcastEvccProbe";
/*
     * 落盘位置（2026-11-12）
     *
     * 原来是 /sdcard/Download/Dashcast，实测写不进去——
     * 车机的 MANAGE_EXTERNAL_STORAGE 虽然声明了但运行时没生效，
     * FileOutputStream 直接抛 FileNotFoundException。
     *
     * 改到 App 私有目录：这是**一定**写得进去的地方，
     * 读取用 `adb shell run-as com.hermes.dashcast cat files/evcc_catalog.txt`。
     */
    private static final String OUT_FILE = "evcc_catalog.txt";   /* 相对 filesDir */

    private EvccProbe() {}

    /** 探测结果回调（主线程） */
    public interface Callback {
        void onResult(boolean ok, String summary, String filePath);
    }

    /**
     * 跑一次完整探测：连接 → 会话 → 拉全量目录 → 写文件。
     */
    public static void run(Context ctx, Callback listener) {
        final Context app = ctx.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            boolean ok = false;
            String summary;

            try {
                long t0 = System.currentTimeMillis();

                /* ① 连接 + 等会话（SDK 是异步的，用 CountDownLatch 等） */
                final CountDownLatch ready = new CountDownLatch(1);
                final Throwable[] err = new Throwable[1];

                /*
                 * Kotlin interface 的默认实现（{}）在 Java 侧看不到，
                 * 必须把所有方法都写出来，否则编译报「不是抽象类且未覆盖
                 * 抽象方法 onResyncRequired」。
                 */
                EvccApiClient.Listener lis = new EvccApiClient.Listener() {
                    @Override public void onConnectionStateChanged(
                            EvccApiClient.ConnectionState s, String d) {
                        Log.i(TAG, "连接状态 " + s + " " + d);
                    }
                    @Override public void onSessionReady(EvccApiInfo info,
                                                         EvccSessionInfo session) {
                        ready.countDown();
                    }
                    @Override public void onSessionClosed(
                            com.kooo.evcc.evccapi.EvccOperationResult r) {}
                    @Override public void onMusicStateChanged(
                            com.kooo.evcc.evccapi.EvccMusicState st, long seq, String et) {}
                    @Override public void onMusicLyrics(
                            com.kooo.evcc.evccapi.client.EvccMusicResources.Lyrics l) {}
                    @Override public void onPropertyEvent(
                            com.kooo.evcc.evccapi.EvccPropertyRecord r, long seq, String et) {}
                    @Override public void onVariableEvent(
                            com.kooo.evcc.evccapi.EvccVariableRecord r, long seq, String et) {}
                    @Override public void onSourceStateChanged(
                            com.kooo.evcc.evccapi.EvccSourceState st, long seq) {}
                    @Override public void onShellCompleted(
                            com.kooo.evcc.evccapi.EvccShellResult r) {}
                    @Override public void onResyncRequired(long e, long a) {
                        Log.w(TAG, "事件缺口 " + e + "→" + a);
                    }
                    @Override public void onError(String op, Throwable t) {
                        err[0] = t;
                        Log.w(TAG, "EVCC 错误 " + op + ": " + t);
                    }
                };

                /* @JvmOverloads 生成的签名是 (Context, String clientPackage, Listener)，
                   传 null 走 context.packageName 默认值 */
/* clientPackage 是 Kotlin 非空参数，传 null 会 NPE，必须用真实包名 */
                EvccApiClient client = new EvccApiClient(app, app.getPackageName(), lis);

                client.connect();
                boolean connected = ready.await(15, TimeUnit.SECONDS);

                if (!connected) {
                    summary = "连接 EVCC 失败或超时"
                            + (err[0] != null ? "：" + err[0].getMessage() : "")
                            + "\n\n请检查：\n"
                            + "1. EVCC 是否在运行\n"
                            + "2. 是否已在 EVCC 里授权 Dashcast\n"
                            + "   路径：EVCC → 服务 → 实验功能 → EVCC 开放 API\n"
                            + "        → 开放 API 控制台 → 开放权限的应用";
                    Log.w(TAG, summary);
                    ok = false;
                } else {
                    /* ② 打印会话信息 */
                    EvccApiInfo ai = client.currentApiInfo();
                    EvccSessionInfo si = client.currentSessionInfo();

                    sb.append("════════ EVCC 会话 ════════\n");
                    if (ai != null) {
                        sb.append("API 版本: ").append(ai.getApiMajor())
                          .append('.').append(ai.getApiMinor()).append('\n');
                        sb.append("契约: ").append(ai.getContractHash()).append('\n');
                        sb.append("EVCC 版本: ").append(ai.getServiceVersionName())
                          .append(" (").append(ai.getServiceVersionCode()).append(")\n");
                    }
                    if (si != null) {
                        sb.append("会话: ").append(si.getSessionId()).append('\n');
                        sb.append("预设: ").append(si.getPreset()).append('\n');
                        sb.append("权限: ").append(si.getGrantedPermissions()).append('\n');
                    }
                    sb.append('\n');

                    /* ③ 各源状态 */
                    sb.append("════════ 属性源状态 ════════\n");
                    try {
                        List<EvccSourceState> states = client.querySourceStates();
                        if (states == null || states.isEmpty()) {
                            sb.append("(无)\n");
                        } else {
                            for (EvccSourceState s : states) {
                                sb.append(String.format(Locale.US,
                                        "%-14s state=%-12s gen=%d %s%n",
                                        s.getSource(),
                                        s.getState(),
                                        s.getSourceGeneration(),
                                        s.getMessage() == null ? "" : s.getMessage()));
                            }
                        }
                    } catch (Throwable t) {
                        sb.append("查询源状态失败: ").append(t).append('\n');
                    }
                    sb.append('\n');

                    /* ④ 拉全量目录（分页） */
                    sb.append("════════ 属性目录 ════════\n");
                    int total = 0;
                    String pageToken = "";
                    String snapshotToken = "";
                    int page = 0;
                    int maxPages = 60;   /* 安全上限：500×60 = 3 万条 */

                    while (page < maxPages) {
                        EvccPageRequest req = new EvccPageRequest(
                                "",             /* requestId */
                                pageToken,      /* pageToken */
                                EvccApiContract.Source.AUTO,
                                "",             /* query：空 = 全部 */
                                500,            /* limit */
                                false,          /* includeValues */
                                EvccApiContract.DirectoryMode.DIRECTORY,
                                snapshotToken);

                        EvccPropertyPage pg = client.queryPropertyDirectory(req);
                        if (pg == null) {
                            sb.append("(第 ").append(page + 1)
                              .append(" 页返回 null)\n");
                            break;
                        }

                        List<EvccPropertyRecord> items = pg.getItems();
                        if (items != null) {
                            for (EvccPropertyRecord r : items) {
                                EvccPropertyDescriptor d = r.getDescriptor();
                                if (d == null) continue;
                                sb.append(formatDescriptor(d)).append('\n');
                            }
                            total += items.size();
                        }

                        /* 第一页拿 snapshotToken，后续页都带着 */
                        if (snapshotToken.isEmpty() && pg.getSnapshotToken() != null) {
                            snapshotToken = pg.getSnapshotToken();
                        }

                        String next = pg.getNextPageToken();
                        page++;
                        if (next == null || next.isEmpty() || items == null
                                || items.isEmpty()) {
                            break;
                        }
                        pageToken = next;
                    }

                    sb.append("\n共 ").append(total).append(" 个属性，")
                      .append(page).append(" 页\n");

                    summary = String.format(Locale.US,
"探测成功\n属性数: %d\n页数: %d\n耗时: %dms\n"
                                    + "文件: %s",
                            total, page, System.currentTimeMillis() - t0,
                            app.getFilesDir() + "/" + OUT_FILE);
                    ok = true;
                }

                client.close();
            } catch (Throwable t) {
                Log.e(TAG, "探测异常", t);
                sb.append("\n\n════════ 异常 ════════\n")
                  .append(Log.getStackTraceString(t));
                summary = "探测异常: " + t;
                ok = false;
            }

            /* ⑤ 落盘 */
            String path = null;
            try {
File dir = app.getFilesDir();
                File f = new File(dir, OUT_FILE);
                String header = "EVCC 属性目录快照\n生成时间: "
                        + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                          .format(new Date())
                        + "\n\n";
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(header.getBytes("UTF-8"));
                    fos.write(sb.toString().getBytes("UTF-8"));
                }
                path = f.getAbsolutePath();
                Log.i(TAG, "目录已写入 " + path);
            } catch (Throwable t) {
                Log.e(TAG, "写文件失败", t);
            }

            final boolean fOk = ok;
            final String fSummary = summary;
            final String fPath = path;
            ui.post(() -> {
                if (listener != null) listener.onResult(fOk, fSummary, fPath);
            });
        }, "EvccProbe").start();
    }

    /** 一行描述符，便于 grep */
    private static String formatDescriptor(EvccPropertyDescriptor d) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "%-12s %-12s %-40s",
                nz(d.getSource()),
                nz(d.getHexId()),
                nz(d.getName())));
        if (d.getDescription() != null && !d.getDescription().isEmpty()) {
            b.append(" | ").append(d.getDescription());
        }
        if (d.getEnumOptions() != null && !d.getEnumOptions().isEmpty()) {
            b.append(" | 枚举: ").append(d.getEnumOptions());
        }
        if (!nz(d.getGroup()).isEmpty()) {
            b.append(" | 组: ").append(d.getGroup());
        }
        return b.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
