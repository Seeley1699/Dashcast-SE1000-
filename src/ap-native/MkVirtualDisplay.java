import android.content.Context;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Looper;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

/**
 * MkVirtualDisplay — 直接调 IDisplayManager.createVirtualDisplay，显式传 packageName。
 *
 * 关键：DisplayManagerGlobal.createVirtualDisplay 内部用 context.getPackageName()
 * 做 packageName，system context 返回 "android"（uid 1000），与 shell(2000) 不匹配。
 * 这里直接拿 IDisplayManager（DisplayManagerGlobal.mDm 字段），反射调
 * createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, IMediaProjection, String packageName)，
 * 显式传 packageName="com.android.shell"。
 *
 * 用法（不要 su，shell uid 2000）：
 *   CLASSPATH=/data/local/tmp/mkvd.dex app_process /system/bin MkVirtualDisplay 1920 532 160 dashcast-virtual
 */
public class MkVirtualDisplay {
    public static void main(String[] args) {
        int w = 1920, h = 532, dpi = 160;
        String name = "dashcast-virtual";
        if (args.length >= 2) { w = Integer.parseInt(args[0]); h = Integer.parseInt(args[1]); }
        if (args.length >= 3) dpi = Integer.parseInt(args[2]);
        if (args.length >= 4) name = args[3];

        System.out.println("[MkVD] 启动 " + w + "x" + h + " dpi=" + dpi + " name=" + name);
        System.out.flush();

        Looper.prepareMainLooper();

        try {
            // 1. 拿 DisplayManagerGlobal 实例和它的 mDm（IDisplayManager）字段
            Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgClass.getMethod("getInstance").invoke(null);
            Field mDmField = dmgClass.getDeclaredField("mDm");
            mDmField.setAccessible(true);
            Object idm = mDmField.get(dmg);  // IDisplayManager binder 代理
            System.out.println("[MkVD] IDisplayManager=" + idm);
            System.out.flush();

            // 2. 构造 VirtualDisplayConfig（Android 11 新配置类）
            Class<?> vdcBuilderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object vdcBuilder = vdcBuilderClass.getConstructor(String.class, int.class, int.class, int.class)
                .newInstance(name, w, h, dpi);

            // setFlags(flags)
            int flags = 1 | 8 | 16;  // PUBLIC | OWN_CONTENT_ONLY | AUTO_MIRROR
            Method setFlags = vdcBuilderClass.getMethod("setFlags", int.class);
            setFlags.invoke(vdcBuilder, flags);

            // setSurface(null) 不需要，默认 null
            Object vdc = vdcBuilderClass.getMethod("build").invoke(vdcBuilder);
            System.out.println("[MkVD] VirtualDisplayConfig=" + vdc);
            System.out.flush();

            // 3. 反射调 IDisplayManager.createVirtualDisplay(VirtualDisplayConfig,
            //    IVirtualDisplayCallback, IMediaProjection, String packageName) → int displayId
            Method cvd = null;
            for (Method m : idm.getClass().getMethods()) {
                if (m.getName().equals("createVirtualDisplay") && m.getParameterCount() == 4) {
                    cvd = m;
                    break;
                }
            }
            if (cvd == null) {
                System.out.println("[MkVD] 找不到 4 参数 createVirtualDisplay");
                System.exit(3);
            }
            System.out.println("[MkVD] 方法: " + cvd);
            System.out.flush();

            String packageName = "com.android.shell";  // 匹配 shell uid 2000
            // 打印实际 uid 和 packageName 对应关系，诊断校验失败
            int myUid = android.os.Process.myUid();
            System.out.println("[MkVD] Process.myUid()=" + myUid);
            System.out.println("[MkVD] 传的 packageName=" + packageName);
            System.out.flush();

            int displayId = (Integer) cvd.invoke(idm, vdc, null, null, packageName);

            System.out.println("[MkVD] === 建屏成功 displayId=" + displayId + " ===");
            System.out.println("[MkVD] 搬 App: am start --display " + displayId + " -n <component>");
            System.out.flush();

            Thread.sleep(Long.MAX_VALUE);
        } catch (Exception e) {
            System.out.println("[MkVD] 异常: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }
}
