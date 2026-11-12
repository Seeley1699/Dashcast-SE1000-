package com.hud.dash;

/** 仪表数据。字段与交接文档 §4 一一对应，可变 POJO，直接改字段后调 DashView.invalidate() */
public class HudData {
    // 车速与智驾
    public float speed = 0f;
    public boolean noa = true;
    public String noaLabel = "NOA";
    public int noaSpeed = 120;

    // 档位与模式
    public String gear = "D";
    public String driveMode = "纯电";
    public String[] modes = {"智能", "纯电", "增程", "性能"};

    // 续航（km）
    public float evRange = 130f, evCap = 180f;
    public float fuelRange = 1370f, fuelCap = 1560f;

    // 功率（kW）
    public float power = 142f, powerMax = 300f;

    // 车道引导
    public String[] lanes = {"left", "straight", "straight", "straight", "right"};
    public int[] laneActive = {2, 3};

    // 行程
    public String tripKm = "24.6", tripAvg = "42", tripMin = "35";
    public String tripEnergy = "15.8", tripFuel = "6.2";

    // 导航
    public boolean navActive = false;
    public String navEnd = "exited";              // arrived → 行程；exited → 胎压
    public String navDist = "300", navUnit = "米";
    public String navStreet = "中山北路", navTurn = "右";
    public String navLeft = "12.4 km", navEta = "09 : 52", navTime = "23 分钟";

    // 胎压：名称与 bar 值按下标对应
    public String[] tireNames = {"左前", "右前", "左后", "右后"};
    public float[] tireBars = {2.4f, 2.4f, 2.5f, 2.1f};
    public float[] tireTemps = {36f, 37f, 35f, 38f};           // 胎温 °C，>75 告警

    // 环境
    public String outdoor = "18°", indoor = "22°";
    public String weather = "晴 · 东南风 2 级";

    // 灯光
    public boolean lightLeft = true, lightAuto = true, lightLow = true, lightHigh = false;
    /*
     * **lightHold 默认必须是 false**（2026-11-12 修「自动驻车一直亮」）
     *
     * 原来写的是 true，而且 EvccVehicleFeed 从来没有给它赋过值 ——
     * 所以那盏 (A) 灯一直亮着，跟车的实际状态完全对不上。
     *
     * 正确语义：**D 档 + 刹车踩到底 → 自动驻车生效 → 灯亮**。
     * 现在由 EvccVehicleFeed 里的 P_AUTO_HOLD（APVP 0x21407645
     * SETTING_FUNC_AUTO_HOLD_ACTIVE）驱动，读不到就保持 false。
     *
     * 默认 false 比 true 安全：数据通道挂了的时候，
     * 用户看到灯不亮，而不是误以为车已经驻住了 ——
     * 这是安全指示，宁可漏报不能误报。
     */
    public boolean lightHold = false, lightFogR = false, lightRight = false;
    public boolean lightHazard = false;           // 双闪：左右同相闪烁

    /*
     * 媒体封面（2026-11-12 新增）
     *
     * EVCC 的 openMusicArtwork 返回图片字节，我们存到 files/cover.jpg，
     * 这里只存路径 + 版本号。渲染器按路径加载并缓存 Bitmap，
     * 版本变了才重新解码（避免每帧读盘）。
     */
    public String mediaCoverPath = "";
    public long mediaCoverRev = -1;

    // 媒体
    public boolean mediaPlaying = false;
    public String mediaTitle = "夜航西飞", mediaArtist = "陈明";
    public String lyric0 = "风把城市 吹得很远";
    public String lyric1 = "云在脚下 慢慢退潮";
    public String lyric2 = "我把夜航 折成一封信";
    public String lyric3 = "而你还在 灯火那边";
    public float mediaPct = 42f;
    public String mediaTime = "1:42", mediaDur = "4:05";

    // 地图开关：true 时不画底图，交给外部 MapView
    public boolean mapExternal = false;

    /** 全量拷贝（用于 setData 一个现成对象） */
    public void copyFrom(HudData s) {
        speed = s.speed; noa = s.noa; noaLabel = s.noaLabel; noaSpeed = s.noaSpeed;
        gear = s.gear; driveMode = s.driveMode; modes = s.modes;
        evRange = s.evRange; evCap = s.evCap; fuelRange = s.fuelRange; fuelCap = s.fuelCap;
        power = s.power; powerMax = s.powerMax;
        lanes = s.lanes; laneActive = s.laneActive;
        tripKm = s.tripKm; tripAvg = s.tripAvg; tripMin = s.tripMin;
        tripEnergy = s.tripEnergy; tripFuel = s.tripFuel;
        navActive = s.navActive; navEnd = s.navEnd;
        navDist = s.navDist; navUnit = s.navUnit; navStreet = s.navStreet; navTurn = s.navTurn;
        navLeft = s.navLeft; navEta = s.navEta; navTime = s.navTime;
        tireNames = s.tireNames; tireBars = s.tireBars; tireTemps = s.tireTemps;
        outdoor = s.outdoor; indoor = s.indoor; weather = s.weather;
        lightLeft = s.lightLeft; lightAuto = s.lightAuto; lightLow = s.lightLow; lightHigh = s.lightHigh;
        lightHold = s.lightHold; lightFogR = s.lightFogR; lightRight = s.lightRight; lightHazard = s.lightHazard;
        mediaCoverPath = s.mediaCoverPath; mediaCoverRev = s.mediaCoverRev;
        mediaPlaying = s.mediaPlaying; mediaTitle = s.mediaTitle; mediaArtist = s.mediaArtist;
        lyric0 = s.lyric0; lyric1 = s.lyric1; lyric2 = s.lyric2; lyric3 = s.lyric3;
        mediaPct = s.mediaPct; mediaTime = s.mediaTime; mediaDur = s.mediaDur;
        mapExternal = s.mapExternal;
    }
}
