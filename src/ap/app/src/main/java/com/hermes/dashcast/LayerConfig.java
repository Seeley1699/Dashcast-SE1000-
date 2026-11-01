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

    /* ============================ 持久化 ============================ */

    /** 生成 key=value 文本（格式必须与 dcapd2 的 dc_layer_parse 对齐） */
    public String toText() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("version=1\n");
        sb.append("enabled=").append(enabled ? 1 : 0).append('\n');
        sb.append("canvas_w=").append(canvasW).append('\n');
        sb.append("canvas_h=").append(canvasH).append('\n');
        sb.append("x=").append(x).append('\n');
        sb.append("y=").append(y).append('\n');
        sb.append("w=").append(w).append('\n');
        sb.append("h=").append(h).append('\n');
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
        sb.append("fit=").append(fit).append("\n");
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
     * **保存并应用**：写配置文件 + 存 prefs + 同步图层矩形。
     *
     * 这是编辑器里唯一会真实落到仪表的动作 —— 拖拽/滑块只改内存，
     * 点「保存」才生效。这样：
     *   · 拖动时不会每松一次手就把仪表刷一遍（原来是几十次 SSH + 图层重设）
     *   · 试错的形状不会污染当前投屏，不保存就等于取消
     */
    public void pushAsync() {
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
                if (snapshot.enabled && snapshot.w > 0 && snapshot.h > 0) {
                    sCp.setLayerRect(snapshot.x, snapshot.y, snapshot.w, snapshot.h);
                } else {
                    sCp.setLayerRect(0, 0, snapshot.canvasW, snapshot.canvasH);
                }
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
        e.apply();
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
        sanitize();
        return true;
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

        if (shape < SHAPE_RECT || shape > SHAPE_ELLIPSE) shape = SHAPE_RECT;
        if (fit != 0) fit = 1;
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
}
