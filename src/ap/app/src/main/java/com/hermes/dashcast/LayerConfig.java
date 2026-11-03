package com.hermes.dashcast;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * LayerConfig —— 图层形状配置（圆角矩形 / 梯形 / 椭圆 + 羽化 + 边框）
 *
 * 参数与 dcapd2 的 layer_mask.h 一一对应，落到 /data/local/tmp/dcapd2.layer。
 * dcapd2 每 500ms 查一次 mtime，文件一变就重建遮罩 —— 所以编辑器里
 * 调滑块能"边调边看"，不用重启抓帧进程。
 *
 * 为什么这些参数在 alpha 上：形状外 alpha=0，Weston 合成器就把底下的
 * 原厂仪表透出来；形状内 alpha=255 盖住它。这就是 README 里说的
 * "叠加一层可消失的透明膜"，不替换、不破坏原厂仪表。
 *
 * 注意：H.264 档没有 alpha（4:2:0 格式决定），这些形状在 H.264 下会变成
 * 黑块。所以只有 BGRA+LZ4 档能用，编辑器在 H.264 档要提示用户。
 */
public final class LayerConfig {

    private static final String TAG = "DashcastLayer";

    /** dcapd2 读取的配置文件 */
    public static final String CFG_PATH = "/data/local/tmp/dcapd2.layer";

    /* 形状类型（与 layer_mask.h 的 LAYER_SHAPE_* 一致） */
    public static final int SHAPE_RECT    = 0;
    public static final int SHAPE_ROUND   = 1;
    public static final int SHAPE_TRAP    = 2;
    public static final int SHAPE_ELLIPSE = 3;

    public static final String[] SHAPE_NAMES = { "矩形", "圆角矩形", "梯形", "椭圆" };

    public boolean enabled = false;

    public int canvasW = 1920, canvasH = 532;

    public int x = 0, y = 0, w = 1920, h = 532;
    public int grid = 32;          /* 拖拽吸附步长（仅 UI） */
    public int shape = SHAPE_ROUND;
    public int dpi = 160;          /* 仅 UI 元数据 */

    public int border = 0;         /* 边框宽度 px */
    public int rTl = 24, rTr = 24, rBl = 24, rBr = 24;   /* 四角圆角 */
    public int trapTop = 0, trapBot = 0;                  /* 梯形上/下沿内缩 */
    public int feather = 0;        /* 羽化宽度 px */
    public int fit = 0;    /* 0=铺满遮罩(默认,不留黑边) 1=等比居中 */

    /*
     * ===== 地图模式的抓取源区域（窗口坐标，仅 mapMode 生效）=====
     *
     * 为什么需要它：
     *   applyMapCrop() 原本按"遮罩比例适配窗口 + 居中"算抓取区，
     *   只能水平/垂直居中，**没法指定纵向起点**。
     *   实测遮罩 1440×400 时居中算出抓 y=364–1075，
     *   本车箭头（窗口 y≈1030）落在画面 94% 位置 —— 几乎在最底下，
     *   前方路线几乎看不到。
     *
     * 改成显式指定：源 560,900 1440×400 → 本车落在 33% 位置，
     * 上方留出足够的前方视野。这才是开车时该有的构图。
     *
     * srcH 为 0 表示"按老逻辑自动算"（向后兼容）。
     */
    public int srcX = 0, srcY = 0, srcH = 0;

    /*
     * ===== App 页面截取区（2026-11-03 新增）=====
     *
     * 和遮罩**完全独立**的两套坐标：
     *   遮罩 (x,y,w,h)          = 画布 1920×532 上的位置
     *                            → 决定内容投到仪表哪一块
     *   截取 (srcX,srcY,srcW,srcH) = 屏幕 2560×1600 上的位置
     *                            → 决定抓 App 的哪一块像素
     *
     * 这样用户可以"只抓 App 的某一块画面，投到仪表的某个位置"。
     *
     * srcW 是独立字段（不像地图模式那样由 srcH 反推宽度）：
     * App 页面的比例千奇百怪，必须让用户直接给 W。
     *
     * srcAppPkg 记录这条尺寸属于哪个 App；
     * 换 App 时 UI 会去查该 App 的历史值，命中就自动套用。
     */
    public int srcW = 0;
    public String srcAppPkg = null;

    /*
     * ===== 遮罩是不是"铺满整块画布"（2026-11-03 新增）=====
     *
     * 为什么单独存一个标志，而不是每次用 x/y/w/h 反推：
     *
     * 画布尺寸现在跟着 CP 真实分辨率走（可能 1920×720 而不是 1920×532）。
     * 用户上次设好"铺满"（w=canvasW, h=canvasH），下次启动画布变大/变小后，
     * prefs 里读回来的 w/h 还是**旧画布的数** —— 用 x/y/w/h 反推会判成
     * "不是铺满"，于是遮罩只盖住一角。
     *
     * 用户的原话就是这个：「遮罩尺寸无法铺满仪表盘（明明遮罩尺寸已经铺满网格了）」。
     *
     * 存标志后：凡是从"铺满"状态加载的，一律重新铺满新画布。
     * 拖动/改尺寸会把它置 false（见 LayerPreviewView 的 notifyChanged）。
     */
    public boolean canvasFill = false;

    /* ============================ 持久化 ============================ */

    /**
     * 生成 key=value 文本（格式必须与 dcapd2 的 dc_layer_parse 对齐）。
     *
     * **x/y/w/h 会按当前编码档处理**（2026-11-03）：
     *   · H.264 → 下发对齐到 16 倍数的值（硬编要求，否则比例漂移变形）
     *   · BGRA  → 下发用户原始值（不需要对齐，1:1 零损失）
     *
     * 注意 cfg 自身**始终保存用户原始尺寸**，只有落盘这一刻才降级。
     * 这样切回 BGRA 时遮罩立刻恢复原样，不需要用户重调。
     */
    public String toText() {
        int[] rr = rectForCodec(MirrorController.getCodec());
        StringBuilder sb = new StringBuilder(512);
        sb.append("version=1\n");
        sb.append("enabled=").append(enabled ? 1 : 0).append('\n');
        sb.append("canvas_w=").append(canvasW).append('\n');
        sb.append("canvas_h=").append(canvasH).append('\n');
        sb.append("x=").append(rr[0]).append('\n');
        sb.append("y=").append(rr[1]).append('\n');
        sb.append("w=").append(rr[2]).append('\n');
        sb.append("h=").append(rr[3]).append('\n');
        sb.append("grid=").append(grid).append('\n');
        sb.append("shape=").append(shape).append('\n');
        sb.append("dpi=").append(dpi).append('\n');
        sb.append("border=").append(border).append('\n');
        sb.append("r_tl=").append(rTl).append('\n');
        sb.append("r_tr=").append(rTr).append('\n');
        sb.append("r_bl=").append(rBl).append('\n');
        sb.append("r_br=").append(rBr).append('\n');
        sb.append("trap_top=").append(trapTop).append('\n');
        sb.append("trap_bot=").append(trapBot).append('\n');
        sb.append("feather=").append(feather).append('\n');
        sb.append("fit=").append(fit).append('\n');

        /*
         * ── App 页面截取尺寸（2026-11-03 新增）──
         *
         * 和遮罩共用同一个配置文件 /data/local/tmp/dcapd2.layer：
         *   · dcapd2 的解析器按 key 逐个 kv_get_int 查，多出未知键不影响它
         *   · App 侧自己读写这几个键
         *
         * src_app_pkg 记这条尺寸属于哪个 App；
         * 换 App 时 UI 会去 prefs 查该 App 的历史尺寸，命中就自动套用。
         */
        if (srcAppPkg != null && !srcAppPkg.isEmpty()) {
            sb.append("src_app_pkg=").append(srcAppPkg).append('\n');
        }
        sb.append("src_x=").append(srcX).append('\n');
        sb.append("src_y=").append(srcY).append('\n');
        sb.append("src_w=").append(srcW).append('\n');
        sb.append("src_h=").append(srcH).append('\n');
        return sb.toString();
    }

    /**
     * 写到 dcapd2 的配置文件（走本机 adbd root 通道）。
     *
     * **必须在后台线程调用。** AdbShell.exec 会开 socket，Android 禁止
     * 主线程做网络操作（NetworkOnMainThreadException）。之前版本没注意这点，
     * 异常被 catch 吞掉，表现就是"点了按钮没反应、配置永远不落盘"。
     * 现在先检查线程，主线程调用会直接抛 IllegalArgumentException 让问题
     * 立刻暴露，而不是静默失败。
     */
    public boolean push() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            throw new IllegalArgumentException("LayerConfig.push() 不能在主线程调用");
        }
        try {
            String body = toText();
            // 用 base64 传，避免 shell 引号/换行问题
            String b64 = android.util.Base64.encodeToString(
                    body.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            String cmd = "echo " + b64 + " | base64 -d > " + CFG_PATH;
            String out = AdbShell.exec(cmd, 4000);
            boolean ok = out != null;
            Log.i(TAG, "图层配置已写入 " + CFG_PATH
                    + "（enabled=" + enabled + " shape=" + SHAPE_NAMES[shape]
                    + " " + w + "x" + h + " 圆角" + rTl + " 羽化" + feather + "）");
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "图层配置写入失败", t);
            return false;
        }
    }

    /** 异步下发（UI 线程用这个）：落盘在后台跑，不阻塞界面 */
    /**
     * 异步下发：写配置文件 + **同步 ivi 图层矩形**。
     *
     * 两步都要：
     *   ① 写 /data/local/tmp/dcapd2.layer → dcapd2 重建遮罩（决定内容画在哪、alpha 形状）
     *   ② setLayerRect → CP 把 ivi 图层缩到同一块矩形（决定图层占屏哪块）
     * 只做①的话，H.264 档（无 alpha）遮罩外会是黑块；只做②的话，
     * BGRA 档的圆角/羽化会失效。两者配合才是完整效果。
     */
    /**
     * **保存并应用**（异步版）：完整下发三步。
     *
     * 这是编辑器里唯一会真实落到仪表的动作 —— 拖拽/滑块只改内存，
     * 点「保存」才生效。这样：
     *   · 拖动时不会每松一次手就把仪表刷一遍（原来是几十次 SSH + 图层重设）
     *   · 试错的形状不会污染当前投屏，不保存就等于取消
     *
     * 具体三步都在 applyFully() 里，这里只负责扔到后台线程。
     */
    public void pushAsync() {
        final LayerConfig snapshot = this;
        new Thread(() -> {
            try {
                snapshot.applyFully(null);
            } catch (Throwable t) {
                Log.e(TAG, "下发配置失败", t);
            }
        }, "layer-push").start();
    }

    /** 旧版异步下发实现（保留注释供追溯，逻辑已并入 applyFully） */
    private void pushAsyncLegacy() {
        final LayerConfig snapshot = this;
        new Thread(() -> {
            try {
                snapshot.push();
            } catch (Throwable t) {
                Log.e(TAG, "下发配置失败", t);
            }
            /* 图层矩形同步：只在投屏中做（未投屏 layer 99 不存在） */
            if (sCp == null || !sCasting) {
                Log.i(TAG, "配置已保存（未投屏，跳过图层矩形同步）");
                return;
            }
            try {
                /*
                 * **destination 必须按编码档算，不能直接用遮罩矩形**。
                 *
                 * 原来两个分支都是 setLayerRect(遮罩 x/y/w/h)，结果是：
                 * AP 端把画面画在画布的遮罩坐标上，CP 端又把整块
                 * 1920×532 画布压缩到同一块矩形 → 仪表上"正常画面 +
                 * 缩小副本"叠加 = 重影。用户反馈"保存遮罩后必现"就是这个。
                 *
                 * BGRA 有 alpha → destination = 整屏，靠画布透明挖洞
                 * H.264 无 alpha → destination = 遮罩矩形，图层本身缩进那块
                 */
                int[] dest = (snapshot.enabled && snapshot.w > 0 && snapshot.h > 0)
                        ? LayerGeometry.destinationFor(MirrorController.getCodec(),
                              new int[]{snapshot.x, snapshot.y, snapshot.w, snapshot.h},
                              snapshot.canvasW, snapshot.canvasH)
                        : new int[]{0, 0, snapshot.canvasW, snapshot.canvasH};
                sCp.setLayerRect(dest[0], dest[1], dest[2], dest[3]);
            } catch (Throwable t) {
                Log.w(TAG, "同步图层矩形失败: " + t);
            }
        }, "layer-push").start();
    }

    /** CpManager 引用（编辑器/控制器初始化时注入，用于同步图层矩形） */
    private static volatile CpManager sCp;

    /** 是否正在投屏。false 时 CP 上没有 layer 99，不能发 setLayerRect */
    private static volatile boolean sCasting = false;

    /** MirrorController 在投屏开始/结束时调用 */
    public static void setCasting(boolean b) { sCasting = b; }

    public static boolean isCasting() { return sCasting; }

    public static void attachCp(CpManager cp) { sCp = cp; }

    /** 异步清除配置文件 */
    public static void clearAsync() {
        new Thread(LayerConfig::clear, "layer-clear").start();
    }

    /** 删掉配置文件 = 恢复旧行为（全屏不透明） */
    public static void clear() {
        try {
            AdbShell.exec("rm -f " + CFG_PATH, 3000);
            Log.i(TAG, "图层配置已清除（恢复全屏铺满）");
        } catch (Throwable ignored) {
        }
    }

    /* ============================ 会话持久化 ============================ */

    private static final String PREF = "dashcast";

    /** 存进 SharedPreferences（重启 App 记得住） */
    public void saveToPrefs(Context ctx) {
        android.content.SharedPreferences.Editor e = ctx
                .getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        e.putBoolean("layer_enabled", enabled);
        e.putInt("layer_x", x);
        e.putInt("layer_y", y);
        e.putInt("layer_w", w);
        e.putInt("layer_h", h);
        e.putBoolean("layer_canvas_fill", canvasFill);
        e.putInt("layer_grid", grid);
        e.putInt("layer_shape", shape);
        e.putInt("layer_dpi", dpi);
        e.putInt("layer_border", border);
        e.putInt("layer_r_tl", rTl);
        e.putInt("layer_r_tr", rTr);
        e.putInt("layer_r_bl", rBl);
        e.putInt("layer_r_br", rBr);
        e.putInt("layer_trap_top", trapTop);
        e.putInt("layer_trap_bot", trapBot);
        e.putInt("layer_feather", feather);
        e.putInt("layer_fit", fit);
        e.putInt("layer_src_x", srcX);
        e.putInt("layer_src_y", srcY);
        e.putInt("layer_src_h", srcH);
        e.putInt("layer_src_w", srcW);
        e.apply();
    }

    /* ============ 按 App 记忆截取尺寸（2026-11-03 新增）============ */

    /*
     * 每个 App 单独记一套截取尺寸，存在同一个 prefs 里：
     *   app_rect_<pkg>.x / .y / .w / .h
     *
     * 用 prefs 而不是另开文件，理由：
     *   · 和遮罩配置同源（用户要求"跟遮罩尺寸是同个配置文件"）
     *   · prefs 本身是 XML，键值天然按 App 分槽，不需要自己管文件格式
     *   · 读写不涉及 adb，主线程也能安全调用（配置文本落盘才需要后台线程）
     */
    public static boolean hasAppRect(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        android.content.SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return p.contains("app_rect_" + pkg + ".w");
    }

    /** 读某个 App 的截取尺寸，返回 {x,y,w,h}；没存过返回 null */
    public static int[] loadAppRect(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        android.content.SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String k = "app_rect_" + pkg + ".";
        if (!p.contains(k + "w")) return null;
        return new int[]{
                p.getInt(k + "x", 0),
                p.getInt(k + "y", 0),
                p.getInt(k + "w", 0),
                p.getInt(k + "h", 0)
        };
    }

    /** 存某个 App 的截取尺寸 */
    public void saveAppRect(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        String k = "app_rect_" + pkg + ".";
        ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(k + "x", srcX)
                .putInt(k + "y", srcY)
                .putInt(k + "w", srcW)
                .putInt(k + "h", srcH)
                .apply();
        this.srcAppPkg = pkg;
        LocalStore.log(ctx, "记住 " + pkg + " 的截取区 "
                + srcW + "×" + srcH + " @(" + srcX + "," + srcY + ")");
    }

    /** 套用某个 App 的历史截取尺寸；没有就返回 false（保持当前值） */
    public boolean applyAppRect(Context ctx, String pkg) {
        int[] r = loadAppRect(ctx, pkg);
        if (r == null) return false;
        srcX = r[0];
        srcY = r[1];
        srcW = r[2];
        srcH = r[3];
        srcAppPkg = pkg;
        return true;
    }

    public static LayerConfig loadFromPrefs(Context ctx, int canvasW, int canvasH) {
        LayerConfig c = new LayerConfig();
        android.content.SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);

        c.canvasW = canvasW > 0 ? canvasW : 1920;
        c.canvasH = canvasH > 0 ? canvasH : 532;
        c.w = c.canvasW;
        c.h = c.canvasH;

        c.enabled    = p.getBoolean("layer_enabled", false);
        c.x          = p.getInt("layer_x", 0);
        c.y          = p.getInt("layer_y", 0);
        c.w          = p.getInt("layer_w", c.canvasW);
        c.h          = p.getInt("layer_h", c.canvasH);
        /*
         * 「上次是不是铺满整块画布」（2026-11-03）。
         *
         * 老版本没存这个键 → 用 x/y/w/h 反推（w/h 恰好等于当时的画布就认为铺满）。
         * 反推只会漏，不会误判成铺满，所以是安全的降级路径。
         */
        c.canvasFill = p.contains("layer_canvas_fill")
                ? p.getBoolean("layer_canvas_fill", false)
                : (c.x == 0 && c.y == 0 && c.w >= 1920 && c.h >= 532);
        /* 铺满状态 → 直接按**新画布**铺满（画布尺寸可能已经变了） */
        if (c.canvasFill) {
            c.x = 0;
            c.y = 0;
            c.w = c.canvasW;
            c.h = c.canvasH;
        }
        c.grid       = p.getInt("layer_grid", 32);
        c.shape      = p.getInt("layer_shape", SHAPE_ROUND);
        c.dpi        = p.getInt("layer_dpi", 160);
        c.border     = p.getInt("layer_border", 0);
        c.rTl        = p.getInt("layer_r_tl", 24);
        c.rTr        = p.getInt("layer_r_tr", 24);
        c.rBl        = p.getInt("layer_r_bl", 24);
        c.rBr        = p.getInt("layer_r_br", 24);
        c.trapTop    = p.getInt("layer_trap_top", 0);
        c.trapBot    = p.getInt("layer_trap_bot", 0);
        c.feather    = p.getInt("layer_feather", 0);
        c.fit        = p.getInt("layer_fit", 0);
        c.srcX       = p.getInt("layer_src_x", 0);
        c.srcY       = p.getInt("layer_src_y", 0);
        c.srcH       = p.getInt("layer_src_h", 0);
        c.srcW       = p.getInt("layer_src_w", 0);

        c.sanitize();
        return c;
    }

    /* ===================== 预设槽（3 个，存 Download/Dashcast/presets.txt） ===================== */

    public static final int PRESET_COUNT = 3;

    /** 预设文件：每行 "槽号 key=value ..."（纯文本，可直接编辑或拷走） */
    private static java.util.Properties readPresetProps(Context ctx) {
        java.util.Properties p = new java.util.Properties();
        File f = LocalStore.presetFile(ctx);
        if (!f.exists()) return p;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            p.load(new java.io.InputStreamReader(in, "UTF-8"));
        } catch (Throwable ignored) {
        }
        return p;
    }

    private static void writePresetProps(Context ctx, java.util.Properties p) {
        File f = LocalStore.presetFile(ctx);
        try {
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                p.store(new java.io.OutputStreamWriter(out, "UTF-8"),
                        "Dashcast 遮罩预设（可直接编辑）");
            }
        } catch (Throwable t) {
            Log.w("DashcastLayer", "预设写入失败: " + t.getMessage());
        }
    }

    /** 预设是否已保存 */
    public static boolean hasPreset(Context ctx, int slot) {
        return readPresetProps(ctx).containsKey("s" + slot + ".w");
    }

    /** 预设的简短描述（按钮上显示尺寸） */
    public static String presetLabel(Context ctx, int slot) {
        java.util.Properties p = readPresetProps(ctx);
        String w = p.getProperty("s" + slot + ".w");
        String h = p.getProperty("s" + slot + ".h");
        return (w == null || h == null) ? "空" : (w + "×" + h);
    }

    /** 把当前配置存进指定槽位 */
    public void savePreset(Context ctx, int slot) {
        java.util.Properties p = readPresetProps(ctx);
        String k = "s" + slot + ".";
        p.setProperty(k + "x",   String.valueOf(x));
        p.setProperty(k + "y",   String.valueOf(y));
        p.setProperty(k + "w",   String.valueOf(w));
        p.setProperty(k + "h",   String.valueOf(h));
        p.setProperty(k + "shape", String.valueOf(shape));
        p.setProperty(k + "r_tl", String.valueOf(rTl));
        p.setProperty(k + "r_tr", String.valueOf(rTr));
        p.setProperty(k + "r_bl", String.valueOf(rBl));
        p.setProperty(k + "r_br", String.valueOf(rBr));
        p.setProperty(k + "trap_top", String.valueOf(trapTop));
        p.setProperty(k + "trap_bot", String.valueOf(trapBot));
        p.setProperty(k + "feather",  String.valueOf(feather));
        p.setProperty(k + "border",   String.valueOf(border));
        p.setProperty(k + "fit",      String.valueOf(fit));
        /* 地图模式的抓取源区域也要一起存，否则切回预设时构图会丢 */
        p.setProperty(k + "src_x", String.valueOf(srcX));
        p.setProperty(k + "src_y", String.valueOf(srcY));
        p.setProperty(k + "src_h", String.valueOf(srcH));
        writePresetProps(ctx, p);
        LocalStore.log(ctx, "保存预设 " + (slot + 1) + " · " + w + "×" + h);
    }

    /** 从指定槽位读回（没存过返回 false，不改动当前配置） */
    public boolean loadPreset(Context ctx, int slot) {
        java.util.Properties p = readPresetProps(ctx);
        String k = "s" + slot + ".";
        if (!p.containsKey(k + "w")) return false;
        x        = intProp(p, k + "x", x);
        y        = intProp(p, k + "y", y);
        w        = intProp(p, k + "w", w);
        h        = intProp(p, k + "h", h);
        shape    = intProp(p, k + "shape", shape);
        rTl      = intProp(p, k + "r_tl", rTl);
        rTr      = intProp(p, k + "r_tr", rTr);
        rBl      = intProp(p, k + "r_bl", rBl);
        rBr      = intProp(p, k + "r_br", rBr);
        trapTop  = intProp(p, k + "trap_top", trapTop);
        trapBot  = intProp(p, k + "trap_bot", trapBot);
        feather  = intProp(p, k + "feather", feather);
        border   = intProp(p, k + "border", border);
        fit      = intProp(p, k + "fit", fit);
        /* 老预设里没有这三个键 → 保持当前值（intProp 的 def 就是自 assignment） */
        srcX     = intProp(p, k + "src_x", srcX);
        srcY     = intProp(p, k + "src_y", srcY);
        srcH     = intProp(p, k + "src_h", srcH);
        sanitize();
        return true;
    }

    /**
     * 应用预设槽的**完整路径** —— 点一下预设就等于点一次「保存并应用」。
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么需要这个方法（2026-11-02 bug 修复）
     * ══════════════════════════════════════════════════════════════
     * 原来 MainActivity 里点预设只做三件事：
     *     maskCfg.loadPreset(...)   → 改内存字段
     *     syncMaskControls()        → 改滑块 UI
     *     refreshMask()             → 刷预览
     *
     * **三处持久状态全没动**：
     *   · /data/local/tmp/dcapd2.layer 还是旧尺寸 → dcapd2 的编码尺寸、画面映射都不变
     *   · SharedPreferences 还是旧尺寸 → 8 秒保活的 currentMaskRect() 读到旧值
     *     → CP 的 destination region 还是旧值
     *   · 结果：用户看到预览变了，仪表纹丝不动
     *
     * 用户只能再点一次「保存并应用」把这三步补上 —— 这就是
     * 「预设的遮罩尺寸应用不上，需要重新保存并应用」的真正原因。
     *
     * H.264 档之所以最先暴露，是因为它要求编码尺寸 = 遮罩尺寸、
     * CP destination = 遮罩尺寸、三者 1:1；一旦不一致就是明显的
     * 「位置错 + 拉伸」。BGRA 整屏画布 + alpha 挖洞，容错性高得多。
     *
     * 所以把这三步收进 LayerConfig 里做成一个原子操作，
     * 预设、编辑器、主界面都调它，避免以后再漏。
     *
     * 注意：必须在**后台线程**调用（push() 里会 adb 写文件）。
     */
    public boolean applyFully(Context ctx) {
        boolean ok = push();                 /* ① 写 dcapd2.layer */
        if (ctx != null) saveToPrefs(ctx);   /* ② 写 prefs */

        /* ③ 同步 CP 图层矩形（只有投屏中才有 layer 99） */
        CpManager cp = sCp;
        if (cp != null && sCasting && enabled && w > 0 && h > 0) {
            try {
                /*
                 * **用按编码档对齐后的矩形**，不是原始 w/h。
                 *
                 * H.264 下 dcapd2 编码尺寸是 (w/16)*16 × (h/16)*16，
                 * 而 CP 的 destination region 必须与之**完全一致**，
                 * 否则合成器要把编码画面拉伸到 layer 尺寸 → 变形。
                 *
                 * 例：原始遮罩 1920×532，H.264 编码 1920×528，
                 * 若 CP 仍设 1920×532 → 528 拉成 532（纵向 +0.76%）。
                 */
                int[] rr = rectForCodec(MirrorController.getCodec());
                int[] dest = LayerGeometry.destinationFor(
                        MirrorController.getCodec(),
                        new int[]{rr[0], rr[1], rr[2], rr[3]},
                        canvasW, canvasH);
                cp.setLayerRect(dest[0], dest[1], dest[2], dest[3]);
            } catch (Throwable t) {
                Log.w(TAG, "预设同步图层矩形失败: " + t);
            }
        }
        return ok;
    }

    private static int intProp(java.util.Properties p, String key, int def) {
        try {
            String v = p.getProperty(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 清空指定槽位 */
    public static void clearPreset(Context ctx, int slot) {
        java.util.Properties p = readPresetProps(ctx);
        String k = "s" + slot + ".";
        for (String key : new java.util.ArrayList<>(p.stringPropertyNames())) {
            if (key.startsWith(k)) p.remove(key);
        }
        writePresetProps(ctx, p);
    }

    /** 夹到合法范围（与 C 侧 sanitize 同规则） */
    /**
     * 截取区的合法性夹取（**屏幕坐标系**，和遮罩的画布坐标是两回事）。
     *
     * 为什么不复用 sanitize()：那个把 x/w 夹在 canvasW/canvasH 内
     * （1920×532），而截取区是屏幕像素（2560×1600）。
     * 混用会把用户的框压成一小条。
     *
     * @param scrW 整屏宽（wm size）
     * @param scrH 整屏高
     */
    public void sanitizeCrop(int scrW, int scrH) {
        if (scrW < 16) scrW = 2560;
        if (scrH < 16) scrH = 1600;

        if (srcW < 16) srcW = 16;
        if (srcH < 16) srcH = 16;

        if (srcX < 0) srcX = 0;
        if (srcY < 0) srcY = 0;

        /* 不许超出屏幕：先保证宽高不超，再把起点往回推 */
        if (srcW > scrW) srcW = scrW;
        if (srcH > scrH) srcH = scrH;
        if (srcX + srcW > scrW) srcX = scrW - srcW;
        if (srcY + srcH > scrH) srcY = scrH - srcH;
        if (srcX < 0) srcX = 0;
        if (srcY < 0) srcY = 0;
    }

    public void sanitize() {

        if (canvasW < 1) canvasW = 1;
        if (canvasH < 1) canvasH = 1;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > canvasW - 1) x = canvasW - 1;
        if (y > canvasH - 1) y = canvasH - 1;
        if (w < 1) w = 1;
        if (h < 1) h = 1;
        if (x + w > canvasW) w = canvasW - x;
        if (y + h > canvasH) h = canvasH - y;

        if (grid < 1) grid = 1;
        if (border < 0) border = 0;
        if (feather < 0) feather = 0;
        if (trapTop < 0) trapTop = 0;
        if (trapBot < 0) trapBot = 0;

        int rmax = Math.min(w, h) / 2;
        rTl = Math.max(0, Math.min(rTl, rmax));
        rTr = Math.max(0, Math.min(rTr, rmax));
        rBl = Math.max(0, Math.min(rBl, rmax));
        rBr = Math.max(0, Math.min(rBr, rmax));

        trapTop = Math.min(trapTop, w / 2);
        trapBot = Math.min(trapBot, w / 2);

        /* "铺满整块画布"的状态实时校验：用户一改就不是铺满了 */
        canvasFill = (x == 0 && y == 0 && w == canvasW && h == canvasH);

        if (shape < SHAPE_RECT || shape > SHAPE_ELLIPSE) shape = SHAPE_RECT;
        if (fit != 0) fit = 1;
    }

    /**
     * **把遮罩宽高对齐到 16 的整数倍 —— 只在 H.264 档需要**。
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么只有 H.264 需要
     * ══════════════════════════════════════════════════════════════
     *   · H.264：MediaCodec 硬编要求宽高是 16 的倍数（YUV420 宏块），
     *            dcapd2 里是 `g_enc_w = (bw / 16) * 16`（向下取整）。
     *            不对齐 → 编码尺寸比遮罩小 → 比例漂移 → 合成器拉伸变形。
     *             遮罩 1920×532 → 编码 1920×528（3.64:1 vs 3.61:1）
     *   · BGRA：直接传 RGB 原始像素，**没有任何对齐要求**，
     *            遮罩 1920×532 就是 1920×532，1:1 零损失。
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么不在 sanitize() 里无条件对齐（踩过的坑）
     * ══════════════════════════════════════════════════════════════
     *   一开始写成无条件对齐，结果 BGRA 用户的遮罩也被砍：
     *     1920×532 → 1920×528，白白矮 4px，用户精调的位置被破坏。
     *   而这个 4px 对 BGRA 毫无必要 —— 它压根不经过编码器。
     *
     * 所以拆成独立方法，由**下发前**按当前编码档决定是否调用。
     * cfg 里始终保存用户原始尺寸，只有真正要给 H.264 编码时才降级。
     */
    public void alignForCodec(int codec) {
        /* CODEC_H264 = 2（与 dashcast_proto.h 一致） */
        if (codec != 2) return;

        if (w >= 16) w = (w / 16) * 16;
        if (h >= 16) h = (h / 16) * 16;

        /* 对齐后可能压到边界外，往回收 */
        if (x + w > canvasW) x = Math.max(0, canvasW - w);
        if (y + h > canvasH) y = Math.max(0, canvasH - h);
        if (x < 0) x = 0;
        if (y < 0) y = 0;
    }

    /** 返回按编码档对齐后的 {x,y,w,h}，不改自身（给下发用） */
    public int[] rectForCodec(int codec) {
        int rx = x, ry = y, rw = w, rh = h;
        if (codec == 2) {
            if (rw >= 16) rw = (rw / 16) * 16;
            if (rh >= 16) rh = (rh / 16) * 16;
            if (rx + rw > canvasW) rx = Math.max(0, canvasW - rw);
            if (ry + rh > canvasH) ry = Math.max(0, canvasH - rh);
            if (rx < 0) rx = 0;
            if (ry < 0) ry = 0;
        }
        return new int[]{rx, ry, rw, rh};
    }

    /** 把另一个配置的全部字段拷进来（「取消」用来回滚到 prefs 里的版本） */
    public void copyFrom(LayerConfig o) {
        if (o == null) return;
        enabled = o.enabled;
        canvasW = o.canvasW;  canvasH = o.canvasH;
        x = o.x;  y = o.y;  w = o.w;  h = o.h;
        grid = o.grid;
        shape = o.shape;
        dpi = o.dpi;
        border = o.border;
        rTl = o.rTl;  rTr = o.rTr;  rBl = o.rBl;  rBr = o.rBr;
        trapTop = o.trapTop;  trapBot = o.trapBot;
        feather = o.feather;
        fit = o.fit;
    }

    /** 按网格吸附 */
    public int snap(int v) {
        int g = grid > 0 ? grid : 1;
        return (int) (Math.round((double) v / g) * g);
    }

    /* ===================== 地图视角档位（三档） ===================== */

    /*
     * 为什么做成"档位"而不是让用户拖四个数字：
     *
     * 地图模式的抓取区必须同时满足两个约束，用户很难手动凑对：
     *   ① 比例 = 仪表比例 1920/532 ≈ 3.609（否则画面被拉伸变形）
     *   ② 本车在画面里的纵向位置要合适（太高看不到前方，太低看不到身后）
     *
     * 所以预置三个已经算好的档位，用户只管选"远/中/近"。
     *
     * 三档的抓取区宽高都按 3.6:1 算，编码尺寸 = 遮罩尺寸（1:1 零缩放）。
     * 遮罩统一铺满仪表 1920×532 —— 远景档正好是 1920×532 全屏，
     * 中/近景档遮罩小于画布时由 dcapd2 居中摆放。
     *
     * srcY 的选取原则：让本车箭头落在画面 33% 高度附近，
     * 这样上方有足够的前方视野，下方还能看到刚走过的路。
     * 实测本车在窗口 y≈1030 附近。
     */
    public static final int VIEW_FAR   = 0;   /* 远景：视野最广 */
    public static final int VIEW_MID   = 1;   /* 中景：默认 */
    public static final int VIEW_NEAR  = 2;   /* 近景：本车最大 */

    public static final String[] VIEW_NAMES = { "远景", "中景", "近景" };

    /** 返回 {srcX, srcY, srcW, srcH}（窗口坐标，宽高比 = 3.6） */
    public static int[] viewRect(int view) {
        switch (view) {
            case VIEW_FAR:
                /* 1920×533 → 3.602。x 居中，y 让本车落在 33%
                 *   (1030 - srcY) / 533 = 0.33  → srcY ≈ 854 */
                return new int[]{ 320, 854, 1920, 533 };
            case VIEW_NEAR:
                /* 1080×300 → 3.600。本车在 33%: (1030-srcY)/300=0.33 → 931
                 * 但 300 太矮会把下方路线切掉，取 320 高度更平衡 */
                return new int[]{ 740, 924, 1152, 320 };
            case VIEW_MID:
            default:
                /* 1440×400 → 3.600。本车在 33%: (1030-srcY)/400=0.33 → 898 */
                return new int[]{ 560, 898, 1440, 400 };
        }
    }

    /** 该档位对应的遮罩矩形（画布坐标，居中） */
    public static int[] viewMask(int view, int canvasW, int canvasH) {
        int[] r = viewRect(view);
        int w = r[2], h = r[3];
        /* 遮罩不能超过画布 */
        if (w > canvasW) w = canvasW;
        if (h > canvasH) h = canvasH;
        return new int[]{ (canvasW - w) / 2, (canvasH - h) / 2, w, h };
    }

    /** 把某个视角档位应用到自己身上（只改内存，落盘由 applyFully 负责） */
    public void applyView(int view, int canvasW, int canvasH) {
        this.canvasW = canvasW > 0 ? canvasW : this.canvasW;
        this.canvasH = canvasH > 0 ? canvasH : this.canvasH;
        int[] r = viewRect(view);
        this.srcX = r[0];
        this.srcY = r[1];
        this.srcH = r[3];          /* 宽度由 applyMapCrop 按比例反推 */

        int[] m = viewMask(view, this.canvasW, this.canvasH);
        this.x = m[0];
        this.y = m[1];
        this.w = m[2];
        this.h = m[3];

        this.enabled = true;
        this.shape = SHAPE_RECT;   /* 形状由编码档决定，这里不预设圆角 */
        this.fit = 0;              /* 铺满遮罩，不留黑边 */
        sanitize();
    }
}
