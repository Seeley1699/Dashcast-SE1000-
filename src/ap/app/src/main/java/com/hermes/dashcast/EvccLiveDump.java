package com.hermes.dashcast;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.kooo.evcc.evccapi.EvccApiContract;
import com.kooo.evcc.evccapi.EvccApiInfo;
import com.kooo.evcc.evccapi.EvccMusicState;
import com.kooo.evcc.evccapi.EvccOperationResult;
import com.kooo.evcc.evccapi.EvccPropertyDescriptor;
import com.kooo.evcc.evccapi.EvccPropertyRecord;
import com.kooo.evcc.evccapi.EvccPropertySelector;
import com.kooo.evcc.evccapi.EvccSessionInfo;
import com.kooo.evcc.evccapi.EvccSourceState;
import com.kooo.evcc.evccapi.EvccValue;
import com.kooo.evcc.evccapi.EvccVariableRecord;
import com.kooo.evcc.evccapi.EvccShellResult;
import com.kooo.evcc.evccapi.client.EvccApiClient;
import com.kooo.evcc.evccapi.client.EvccMusicResources;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * EVCC 实时值探测器（2026-11-12）
 *
 * 目的：一次看清所有关心属性的**真实当前值**，定位：
 *   · 属性 ID 到底是多少（canonicalId vs hexId vs requestedSelector）
 *   · 值的类型和数值
 *   · status 是不是 AVAILABLE
 *   · 音乐状态的全部字段（含歌词、封面 revision）
 *
 * 为什么不用订阅事件：订阅事件只在「变化时」推，静态属性（比如停着的车）
 * 可能永远不推。这里用 readProperties 强制读一遍当前值，最直接。
 *
 * 输出：files/evcc_live.txt
 */
public final class EvccLiveDump {

    private static final String TAG = "DashcastEvccLive";
    private static final String OUT_FILE = "evcc_live.txt";

    private EvccLiveDump() {}

    public interface Callback {
        void onResult(boolean ok, String summary, String filePath);
    }

    /** 要探测的属性：{id, areaId, 说明} */
    private static final long[][] PROPS = {
            // id, area, 说明索引（靠下面的 NAMES 对齐）
            {0x11600207L, 0},
            {0x21607300L, 0},
            {0x216072FFL, 0},
            {0x21607337L, 0},
            {0x2160733CL, 0},
            {0x21408064L, 0},
            {0x2140710CL, 0},
            {0x21400009L, 0},
            {0x21400010L, 0},
            {0x11400065L, 0},
            {0x11400408L, 0},
            {0x11400E00L, 0},
            {0x124007F0L, 0},
            {0x11400E02L, 0},
            {0x2140752DL, 0},
            {0x11600703L, 0},
            {0x21607522L, 0},
            {0x17600309L, 1},
            {0x17600309L, 2},
            {0x17600309L, 4},
            {0x17600309L, 8},
    };

    private static final String[] NAMES = {
            "车速", "纯电续航", "燃油续航", "电量%", "油量%",
            "档位", "驾驶模式(VHAL)", "驾驶模式APVP1", "驾驶模式APVP2", "驾驶模式Req", "转向灯", "近光", "远光(APVP)",
            "雾灯", "双闪", "车外温度", "车内温度",
            "胎压左前", "胎压右前", "胎压左后", "胎压右后",
    };

    public static void run(Context ctx, Callback listener) {
        final Context app = ctx.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            boolean ok = false;
            String summary;

            try {
                final CountDownLatch ready = new CountDownLatch(1);
                final Throwable[] err = new Throwable[1];

                EvccApiClient client = new EvccApiClient(app, app.getPackageName(),
                        new EvccApiClient.Listener() {
                            @Override public void onSessionReady(EvccApiInfo i, EvccSessionInfo s) {
                                ready.countDown();
                            }
                            @Override public void onError(String op, Throwable t) { err[0] = t; }
                            @Override public void onConnectionStateChanged(EvccApiClient.ConnectionState s, String d) {}
                            @Override public void onSessionClosed(EvccOperationResult r) {}
                            @Override public void onMusicStateChanged(EvccMusicState s, long q, String e) {}
                            @Override public void onMusicLyrics(EvccMusicResources.Lyrics l) {}
                            @Override public void onPropertyEvent(EvccPropertyRecord r, long q, String e) {}
                            @Override public void onVariableEvent(EvccVariableRecord r, long q, String e) {}
                            @Override public void onSourceStateChanged(EvccSourceState s, long q) {}
                            @Override public void onShellCompleted(EvccShellResult r) {}
                            @Override public void onResyncRequired(long a, long b) {}
                        });

                client.connect();
                if (!ready.await(15, TimeUnit.SECONDS)) {
                    summary = "连接失败" + (err[0] != null ? ": " + err[0] : "");
                    ok = false;
                } else {
                    EvccApiInfo ai = client.currentApiInfo();
                    sb.append("EVCC ").append(ai == null ? "?" : ai.getServiceVersionName())
                      .append(" API ")
                      .append(ai == null ? "?" : (ai.getApiMajor() + "." + ai.getApiMinor()))
                      .append("\n\n");

                    /* ── ① 逐个强制读取属性 ── */
                    sb.append("════════ 属性实时值 ════════\n");
                    sb.append(String.format(Locale.US,
                            "%-12s %-12s %-10s %-14s %-16s %s%n",
                            "说明", "请求ID", "区", "canonicalId", "hexId", "值"));

                    for (int i = 0; i < PROPS.length; i++) {
                        long id = PROPS[i][0];
                        int area = (int) PROPS[i][1];
                        String label = i < NAMES.length ? NAMES[i] : ("#" + i);
                        try {
                            EvccPropertySelector sel = new EvccPropertySelector(
                                    id, "", EvccApiContract.Source.AUTO,
                                    area == 0 ? null : area,
                                    EvccApiContract.ResolutionMode.AUTO_PRIORITY);
                            /* forceRefresh=true：绕过缓存，直接问上游要当前值 */
                            EvccPropertyRecord r = client.readProperty(sel, true);
                            sb.append(formatRecord(label, id, area, r)).append('\n');
                        } catch (Throwable t) {
                            sb.append(String.format(Locale.US,
                                    "%-12s 0x%08X  0x%-6X %s%n",
                                    label, id, area, "读取异常: " + t));
                        }
                    }

                    /* ── ② 音乐状态全字段 ── */
                    sb.append("\n════════ 音乐状态 ════════\n");
                    try {
                        EvccMusicState st = client.readMusicState();
                        if (st == null) {
                            sb.append("(null)\n");
                        } else {
                            sb.append("status      = ").append(st.getStatus()).append('\n');
                            sb.append("message     = ").append(nz(st.getMessage())).append('\n');
                            sb.append("title       = ").append(nz(st.getTitle())).append('\n');
                            sb.append("artist      = ").append(nz(st.getArtist())).append('\n');
                            sb.append("album       = ").append(nz(st.getAlbum())).append('\n');
                            sb.append("trackId     = ").append(nz(st.getTrackId())).append('\n');
                            sb.append("sourceApp   = ").append(nz(st.getSourceAppName())).append('\n');
                            sb.append("playback    = ").append(nz(st.getPlaybackState())).append('\n');
                            sb.append("positionMs  = ").append(st.getPositionMs()).append('\n');
                            sb.append("durationMs  = ").append(st.getDurationMs()).append('\n');
                            sb.append("currentLyric= ").append(nz(st.getCurrentLyric())).append('\n');
                            sb.append("lineStartMs = ").append(st.getLineStartMs()).append('\n');
                            sb.append("lineEndMs   = ").append(st.getLineEndMs()).append('\n');
                            sb.append("lyricOffset = ").append(st.getLyricOffsetMs()).append('\n');
                            sb.append("lyricsStatus= ").append(st.getLyricsStatus()).append('\n');
                            sb.append("lyricsFormat= ").append(st.getLyricsFormat()).append('\n');
                            sb.append("lyricsRev   = ").append(st.getLyricsRevision()).append('\n');
                            sb.append("artworkSts  = ").append(st.getArtworkStatus()).append('\n');
                            sb.append("artworkRev  = ").append(st.getArtworkRevision()).append('\n');
                            sb.append("favorite    = ").append(st.getFavoriteKnown())
                              .append('/').append(st.getFavorite()).append('\n');
                            sb.append("wholeBpm    = ").append(st.getWholeBpm()).append('\n');

                            /* 完整歌词前 30 行 */
                            if (EvccApiContract.Status.AVAILABLE.equals(st.getLyricsStatus())) {
                                EvccMusicResources.Lyrics lyr = client.readMusicLyrics(st);
                                if (lyr != null && lyr.getText() != null) {
                                    String[] ls = lyr.getText().split("\\r?\\n");
                                    sb.append("\n---- 完整歌词前 30 行（共 ")
                                      .append(ls.length).append(" 行）----\n");
                                    for (int i = 0; i < Math.min(30, ls.length); i++) {
                                        sb.append("  ").append(ls[i]).append('\n');
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        sb.append("读取音乐状态失败: ").append(t).append('\n');
                    }

                    summary = "实时值导出完成";
                    ok = true;
                }
                client.close();
            } catch (Throwable t) {
                Log.e(TAG, "异常", t);
                sb.append("\n\n异常:\n").append(Log.getStackTraceString(t));
                summary = "异常: " + t;
                ok = false;
            }

            String path = null;
            try {
                File f = new File(app.getFilesDir(), OUT_FILE);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(sb.toString().getBytes("UTF-8"));
                }
                path = f.getAbsolutePath();
            } catch (Throwable t) {
                Log.e(TAG, "写文件失败", t);
            }

            final boolean fOk = ok;
            final String fSum = summary;
            final String fPath = path;
            ui.post(() -> { if (listener != null) listener.onResult(fOk, fSum, fPath); });
        }, "EvccLive").start();
    }

    /** 把一条 record 拼成一行 */
    private static String formatRecord(String label, long reqId, int area,
                                       EvccPropertyRecord r) {
        if (r == null) {
            return String.format(Locale.US, "%-12s 0x%08X  0x%-6X (null)", label, reqId, area);
        }
        EvccPropertyDescriptor d = r.getDescriptor();
        String canon = d == null ? "-" : String.format(Locale.US, "0x%X", d.getCanonicalId());
        String hex = d == null ? "-" : nz(d.getHexId());

        StringBuilder v = new StringBuilder();
        v.append("status=").append(r.getStatus());
        EvccValue val = r.getValue();
        if (val != null) {
            v.append(" type=").append(val.getType());
            if (val.getLongValue() != null) v.append(" long=").append(val.getLongValue());
            if (val.getDoubleValue() != null) v.append(" double=").append(val.getDoubleValue());
            if (val.getBooleanValue() != null) v.append(" bool=").append(val.getBooleanValue());
            if (val.getDisplayValue() != null && !val.getDisplayValue().isEmpty()) {
                v.append(" display=").append(val.getDisplayValue());
            }
        }
        if (r.getMessage() != null && !r.getMessage().isEmpty()) {
            v.append(" msg=").append(r.getMessage());
        }

        return String.format(Locale.US, "%-12s 0x%08X  0x%-6X %-14s %-16s %s",
                label, reqId, area, canon, hex, v);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
