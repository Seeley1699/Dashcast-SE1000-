/*
 * dashcastd.c — CP 侧接收端（e04-p171，Weston + ivi-shell）
 *
 * 职责：
 *   1. 监听 TCP 端口（默认 8444），收 AP 的握手 + 帧流
 *   2. 连 Wayland（wayland-1），建 ivi_surface（单个 surface，不重建）
 *   3. 用 LayerManagerControl 建 layer 99 并压到最上层
 *   4. 每收到一帧：解图块 → 写 wl_shm ARGB buffer → attach + commit
 *      buffer 复用（双缓冲 + release 事件），不每帧重建
 *
 * 安全性（用户硬性要求）：
 *   - 所有图层改动只在 Weston 运行时内存里，不落盘
 *   - 进程退出 → 图层消失 → 仪表自动还原
 *   - render order 不持久，重启 Weston 即恢复
 *
 * 依赖：wayland-client, ivi-application, pthread
 * 编译：见 build-dashcastd.sh
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <poll.h>

#include <wayland-client.h>
#include "ivi-application-client-protocol.h"

#include "dashcast_proto.h"
#include "lz4mini.h"

/* ===== 可调参数 ===== */
#define DEFAULT_PORT     8444
#define SCREEN_W         1920
#define SCREEN_H         532
#define LAYER_ID         99

/* CP 侧 H.264 接收（h264rx.c） */
extern int h264_rx_run(int ctl_fd, uint8_t *dst, int w, int h,
                       void (*commit)(void), volatile int *running);
#define SURFACE_ID       9001
#define SHM_NAME         "/dashcast-shm"
#define NUM_BUFFERS      4          /* 四缓冲：30fps 持续推流下单靠 2 个会在合成器
                                       * release 稍慢时周期性地"无 buffer 可用"而丢帧
                                       * （实测 200 帧丢 61 帧 ≈ 30%）。4 个留足周转。 */

/* 帧接收上限（防畸形/跨版本帧头把 CP 内存撑爆）：
 * 真实画布 1920×532 只有 270 个 64×64 图块，8192 已极宽裕；
 * 一整帧未压缩 BGRA 约 4 MB，16 MB 上限覆盖任意图块组合。 */
#define MAX_TILES_PER_FRAME  8192
#define MAX_FRAME_BYTES      (16u * 1024u * 1024u)

/*
 * 握手尺寸的硬上限。
 *
 * 仪表屏物理就是 1920×532；这里放宽到 4096×2160（4K）给未来换屏留余地，
 * 但**必须有个上限** —— 握手包是 TCP 上来的，不校验就等于让对端指定
 * 我们分配多少内存（见 run_session 里的校验注释）。
 */
#define MAX_DIM_W            4096
#define MAX_DIM_H            2160
/* shm pool 总上限（int32 offset 与 /dev/shm 都是硬约束） */
#define MAX_POOL_BYTES       (64u * 1024u * 1024u)

/* ===== 全局状态 ===== */
static int running = 1;
static struct wl_display *display = NULL;
static struct wl_compositor *compositor = NULL;
static struct wl_shm *shm = NULL;
static struct ivi_application *ivi_app = NULL;
static struct ivi_surface *ivi_surface = NULL;
static struct wl_surface *surface = NULL;
static struct wl_shm_pool *pool = NULL;

static int frame_w = SCREEN_W;
static int frame_h = SCREEN_H;
static int stride = SCREEN_W * 4;
static size_t frame_size = SCREEN_W * SCREEN_H * 4;

/* 帧缓冲（CPU 侧，收帧写入区） */
static uint8_t *frame_buffer = NULL;
static pthread_mutex_t fb_mutex = PTHREAD_MUTEX_INITIALIZER;

/* 保护 libwayland 客户端连接（真机并发死锁修复，2026-10-30）。
 *
 * libwayland 的 wl_display 不是线程安全的：主循环的 wl_display_dispatch
 * 和网络线程里的 commit_frame()/blank_frame()（内部 wl_display_flush /
 * wl_display_roundtrip）若并发，会同时读同一个 socket —— 事件被两个线程
 * 抢着消费，roundtrip 等不到自己的 sync 回执就永不返回。
 *
 * 表现（实机复现）：第一次断连后 cleanup_layer 卡在 blank_frame 的
 * roundtrip，network_thread 回不到 accept()，下一次连接握手 ACK 超时、
 * 仪表画面留在原地。加这把锁让所有 wl_* 调用严格串行，roundtrip 自己
 * dispatch 出 buffer release，不再依赖主循环抢 socket。 */
static pthread_mutex_t wl_mutex = PTHREAD_MUTEX_INITIALIZER;

/* ===== 双缓冲管理 ===== */
typedef struct {
    struct wl_buffer *wl_buffer;
    uint8_t *data;          /* mmap 的共享内存 */
    /*
     * **必须记录这块映射有多大**。
     *
     * 原来只存指针，所有写入都用全局 frame_size —— 而 frame_size 会在
     * 下一轮握手时被改大，此时 buffers[i].data 还指向上一轮（更小）的映射。
     * 于是 memset(b->data, 0, 新frame_size) 会写穿到邻居映射/空洞 → SIGSEGV。
     * （触发路径：分辨率变大 + init_buffers 的 ftruncate/mmap 失败）
     */
    size_t data_size;
    int busy;               /* 1 = 已提交待 release */
} buffer_t;

static buffer_t buffers[NUM_BUFFERS];

/* 当前 shm 映射的基址/长度。保留它是为了在下一轮 init_buffers() 和进程退出时
 * munmap —— 不记录就只能泄漏（wl_shm_pool_destroy 不会卸载客户端映射）。 */
static void  *g_shm_base = NULL;
static size_t g_shm_len  = 0;

/* 提交/丢帧计数（排查"画面卡住"时看这两个数：提交涨=在动，丢帧涨=合成器跟不上） */
static int g_commit_total = 0;
static int g_drop_total = 0;

/* 运行期巡检计数：每 N 帧查一次"本 surface 有没有被挂到别的图层"（重影根治） */
static int g_detach_tick = 0;

/* 是否正处在一次投屏会话中（有 AP 连接且图层已建）。
 * 巡检线程只在会话中动手，没投屏时不碰仪表，避免干扰原厂界面。 */
static volatile int g_session_active = 0;

/* commit_frame() 位置靠前就要调用它，先前置声明。
 * verbose=1 打印收集/摘除细节（setup_layer 阶段）；
 * verbose=0 静默（巡检模式，只有真的摘掉时才打一行）。 */
static void detach_surface_from_all_layers(int verbose);

/* ===== 日志 ===== */
#define LOG(fmt, ...) fprintf(stderr, "[dashcastd] " fmt "\n", ##__VA_ARGS__)

/* 图层目的矩形：定义放在下面 setup_layer 区，这里先声明供 run_session 使用。
 * AP 握手后把遮罩矩形发来，CP 用 destination region 把图层缩到那块，
 * 区域外合成器不绘制 → 原厂仪表自然显露（H.264 无 alpha 也能用）。 */
static int  g_layer_x, g_layer_y, g_layer_w, g_layer_h;
static int  g_layer_rect_valid;
static void apply_layer_rect(void);


/* 前向声明（network_thread 里断连后直接调用清理图层） */
static void cleanup_layer(void);
static void setup_layer(void);
static void verify_layer_once(void);

/* ===== 信号处理 ===== */
static void on_signal(int sig) {
    (void)sig;
    running = 0;
}

/* ===== Wayland 回调 ===== */

static void shm_format_cb(void *data, struct wl_shm *wl_shm, uint32_t format) {
    (void)data; (void)wl_shm; (void)format;
}

static const struct wl_shm_listener shm_listener = {
    shm_format_cb
};

static void ivi_surface_configure(void *data, struct ivi_surface *s, int32_t w, int32_t h) {
    (void)data; (void)s; (void)w; (void)h;
}

static const struct ivi_surface_listener ivi_surface_listener = {
    ivi_surface_configure
};

/* buffer release：释放后标记为可用 */
static void buffer_release(void *data, struct wl_buffer *buffer) {
    (void)buffer;
    buffer_t *b = (buffer_t *)data;
    b->busy = 0;
}

static const struct wl_buffer_listener buffer_listener = {
    buffer_release
};

static void registry_global(void *data, struct wl_registry *registry,
                            uint32_t name, const char *interface, uint32_t version) {
    (void)data;
    if (strcmp(interface, wl_compositor_interface.name) == 0) {
        compositor = wl_registry_bind(registry, name, &wl_compositor_interface, 4);
    } else if (strcmp(interface, wl_shm_interface.name) == 0) {
        shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
        wl_shm_add_listener(shm, &shm_listener, NULL);
    } else if (strcmp(interface, "ivi_application") == 0) {
        ivi_app = wl_registry_bind(registry, name, &ivi_application_interface, 1);
    }
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data; (void)registry; (void)name;
}

static const struct wl_registry_listener registry_listener = {
    registry_global,
    registry_global_remove
};

/* ===== 初始化共享内存缓冲池（一次建好，之后复用）===== */

static int init_buffers(void) {
    /* 会话可能反复建立：先把上一轮的 pool / buffer 收掉，避免泄漏 */
    if (pool) {
        for (int i = 0; i < NUM_BUFFERS; i++) {
            if (buffers[i].wl_buffer) {
                wl_buffer_destroy(buffers[i].wl_buffer);
                buffers[i].wl_buffer = NULL;
            }
            buffers[i].busy = 0;
        }
        wl_shm_pool_destroy(pool);
        pool = NULL;
    }

    /* 先清掉可能残留的同名 shm 对象（上次崩溃遗留），避免大小不匹配 */
    shm_unlink(SHM_NAME);

    /*
     * **先把上一轮的 mmap 卸掉**。
     *
     * wl_shm_pool_destroy() 只是发请求，不会卸载客户端自己的映射；
     * 上一轮 shm_unlink 之后那层映射仍然钉着 tmpfs 页 ——
     * 每轮会话泄漏 frame_size × NUM_BUFFERS（1920×532 档约 16 MB，
     * H.264 档约 4 MB）。车上反复投屏/断连 20 次就吃掉 300+ MB，
     * /dev/shm 满了之后 ftruncate 失败，进而引爆后面 buffer 尺寸不匹配的写穿。
     */
    if (g_shm_base && g_shm_len) {
        munmap(g_shm_base, g_shm_len);
    }
    g_shm_base = NULL;
    g_shm_len = 0;

    /* 建一个大的 shm pool，放 NUM_BUFFERS 个帧 */
    size_t pool_size = frame_size * NUM_BUFFERS;
    int fd = shm_open(SHM_NAME, O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) { LOG("shm_open 失败: %s", strerror(errno)); return -1; }
    if (ftruncate(fd, pool_size) < 0) {
        LOG("ftruncate 失败: %s", strerror(errno));
        close(fd);
        shm_unlink(SHM_NAME);       /* 别留一个半截的对象在 /dev/shm */
        return -1;
    }

    uint8_t *base = mmap(NULL, pool_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (base == MAP_FAILED) {
        LOG("mmap 失败: %s", strerror(errno));
        close(fd);
        shm_unlink(SHM_NAME);
        return -1;
    }
    g_shm_base = base;
    g_shm_len = pool_size;

    /*
     * **shm 内存必须先清零**。
     *
     * shm_open(O_CREAT|O_TRUNC) + ftruncate 之后，内核只保证大小，不保证
     * 内容是 0 —— 可能是同一块共享内存被上一个进程用剩的像素。
     * 这些 buffer 第一次 attach 时如果没被完整写过，残留内容会直接上屏，
     * 看起来就是"一开投屏就有花花绿绿的重影块"。
     */
    memset(base, 0, pool_size);

    pool = wl_shm_create_pool(shm, fd, pool_size);

    for (int i = 0; i < NUM_BUFFERS; i++) {
        buffers[i].data = base + (size_t)i * frame_size;
        buffers[i].data_size = frame_size;      /* 记下这块映射多大，写入只按它 */
        buffers[i].busy = 0;
        buffers[i].wl_buffer = wl_shm_pool_create_buffer(pool,
                i * frame_size, frame_w, frame_h, stride, WL_SHM_FORMAT_ARGB8888);
        if (!buffers[i].wl_buffer) {
            LOG("wl_shm_pool_create_buffer 失败（index %d）", i);
            buffers[i].data = NULL;
            buffers[i].data_size = 0;
            return -1;
        }
        wl_buffer_add_listener(buffers[i].wl_buffer, &buffer_listener, &buffers[i]);
    }

    close(fd); /* pool 创建后 fd 可关 */
    LOG("缓冲池就绪：%d 个 buffer，每个 %zu 字节", NUM_BUFFERS, frame_size);
    return 0;
}

/* ===== 提交一帧（复用 buffer，双缓冲）===== */

/*
 * 从 socket 读一轮 wayland 事件并派发（**调用方必须已持有 wl_mutex**）。
 *
 * 为什么需要它：wl_buffer.release 事件只有真正从 socket 读出来才会到达
 * release 回调，把 busy 清 0。之前主循环把阻塞 dispatch 换成
 * wl_display_dispatch_pending，等于只清内部队列、从不读 socket ——
 * 两个 buffer 提交后永久 busy，第 3 帧起 acquire_buffer 恒返回 NULL、
 * 帧被静默丢弃，表现就是"投屏上去第一帧就卡死、两条链路都一样"。
 *
 * 这里用 libwayland 官方的 prepare_read / poll / read_events 三段式：
 * 因为所有调用都在 wl_mutex 内、且同一时刻只有一个线程碰 wl_display，
 * 不会出现两线程抢同一个 socket 的读竞争。
 */
static void wl_pump_locked(int timeout_ms)
{
    if (!display) return;

    /* prepare_read 失败 = 还有 pending 事件没派发，先派发再重试 */
    while (wl_display_prepare_read(display) != 0) {
        if (wl_display_dispatch_pending(display) < 0) return;
    }

    wl_display_flush(display);

    struct pollfd pfd;
    pfd.fd = wl_display_get_fd(display);
    pfd.events = POLLIN;
    pfd.revents = 0;

    int pr = poll(&pfd, 1, timeout_ms);
    if (pr > 0 && (pfd.revents & POLLIN)) {
        if (wl_display_read_events(display) < 0) return;
    } else {
        wl_display_cancel_read(display);
    }
    wl_display_dispatch_pending(display);
}

/*
 * 取一个空闲 buffer；都在忙就 pump 事件等 release 回来（**调用方持有 wl_mutex**）。
 *
 * 每轮 pump 3ms，最多 8 轮 ≈ 24ms：30fps 的单帧预算是 33ms，留 8ms 给
 * 拷贝(4MB memcpy 约 1-2ms) + attach/commit + flush，不会把下一帧挤掉。
 * 合成器 release 通常在 1~2 个合成周期（16~33ms）内回来，这个预算够用。
 */
static buffer_t *acquire_buffer_locked(void) {
    for (int attempt = 0; attempt < 8; attempt++) {
        for (int i = 0; i < NUM_BUFFERS; i++) {
            if (!buffers[i].busy) {
                buffers[i].busy = 1;
                return &buffers[i];
            }
        }
        if (attempt == 7) break;
        wl_pump_locked(3);          /* 等 3ms，让 release 事件到岸 */
    }
    return NULL;
}

static void commit_frame(void) {
    if (!surface) return;

    /* 整个取 buffer → 拷贝 → attach/commit 段独占 wl_display。
     * 拷贝放在锁内是为了保证"写这块 shm 时它没被复用"，代价是几毫秒，
     * 在 33ms 帧预算里可以接受；换来的是绝不出撕裂/复用竞争。 */
    pthread_mutex_lock(&wl_mutex);

    buffer_t *b = acquire_buffer_locked();
    if (!b) {
        pthread_mutex_unlock(&wl_mutex);
        /* 极少数情况（合成器卡顿）才走到这里：丢这一帧，下一帧继续。
         * 计数与告警分两个静态量，避免"每 30 帧才打一次"把真实的丢弃
         * 数量藏起来 —— 排查"卡顿/掉帧"时这个数字是第一手证据。 */
        static int dropped_total = 0;
        static int committed_total = 0;
        g_drop_total = ++dropped_total;
        if (dropped_total <= 3 || dropped_total % 100 == 0) {
            LOG("等待 buffer release 超时，累计丢 %d 帧（已提交 %d 帧）",
                dropped_total, committed_total);
        }
        return;
    }

    pthread_mutex_lock(&fb_mutex);
    /* 按这块映射的真实大小拷，不用全局 frame_size —— 后者可能已经被
     * 下一轮握手改大，而 b->data 还指向旧的小映射（会写穿）。 */
    size_t copy = b->data_size;
    if (copy > frame_size) copy = frame_size;
    memcpy(b->data, frame_buffer, copy);
    pthread_mutex_unlock(&fb_mutex);

    wl_surface_attach(surface, b->wl_buffer, 0, 0);
    wl_surface_damage(surface, 0, 0, frame_w, frame_h);
    wl_surface_commit(surface);
    wl_display_flush(display);

    pthread_mutex_unlock(&wl_mutex);

    /* 统计：每秒打一次提交帧率，便于判断"画面在动"还是"冻住" */
    {
        static struct timespec t_last;
        static int n_since = 0;

        /*
         * **周期性摘除其他图层上的本 surface（重影根治，2026-11-02）**。
         *
         * 实测：setup_layer() 里 detach 成功（自检日志确认 layer10 只剩 4711），
         * 但投屏开始后约 15 秒，9001 又出现在 layer 10 上 → 仪表两份画面。
         *
         * 已用隔离实验逐一排除，确认**不是**这些动作导致的：
         *   · wl_surface_commit（30fps 跑 3 秒 / 90+ 次，layer10 保持干净）
         *   · set layer 99 visibility 1
         *   · set screen 0 render order 10,99
         *   · add surface 9001 to layer 99
         *   · destroy/create layer 99
         * 杀掉 dashcastd 后 9001 消失 → 确认是本进程持有的 surface 被挂回去。
         *
         * 剩下最可能的是 ivi-shell 在某个内部时机（比如第一次收到 damage/buffer
         * release，或 layer 属性变更后重排）把 surface 回填到默认层。
         * 这个时机由合成器决定，客户端无法精确规避。
         *
         * 所以采取**运行期巡检**：由独立的 layer_watchdog_thread 每秒复查一次
         * （不能挂在这里 —— 画面静止时 AP 不发图块，这里根本不会被调用，
         *   而双挂载恰恰在画面静止时才稳定存在，形成逻辑死锁）。
         */
        if (t_last.tv_sec == 0) { clock_gettime(CLOCK_MONOTONIC, &t_last); }
        g_commit_total++;
        if (++n_since >= 30) {
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            double dt = (double)(now.tv_sec - t_last.tv_sec)
                      + (double)(now.tv_nsec - t_last.tv_nsec) / 1e9;
            if (dt > 0.05) {
                LOG("提交 %d 帧 / %.2fs = %.1f fps（累计丢 %d）",
                    n_since, dt, n_since / dt, g_drop_total);
                t_last = now;
                n_since = 0;
            }
        }
    }

    /* 首帧真正上屏之后，复核一次 render order（此时图层才有内容可被列出） */
    verify_layer_once();
}

/* ===== 网络收帧线程 ===== */

static int read_full(int fd, void *buf, size_t len) {
    uint8_t *p = (uint8_t *)buf;
    size_t got = 0;
    while (got < len) {
        ssize_t n = recv(fd, p + got, len - got, 0);
        /*
         * **EINTR 不是错误**。
         *
         * signal() 默认没有 SA_RESTART，本进程又会 fork/exec 一堆
         * LayerManagerControl（每次 system() 都产生 SIGCHLD），以及
         * SIGINT/SIGTERM —— 这些都可能打断 recv。
         * 原来把 EINTR 当成 -1 处理，等于「清理图层时正好在收帧 → 会话被误断」。
         * h264rx.c 里的 read_full 一直是对的，这里不一致。
         */
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        got += (size_t)n;
    }
    return 0;
}

/* 跑一个完整会话：accept → 握手 → 收帧 → 断连。
 * 返回 0 表示会话正常结束（可以继续等下一个），-1 表示监听口坏了。 */
static int run_session(int server_fd) {
    /*
     * accept 也要处理 EINTR：signal() 没有 SA_RESTART，任何 SIGCHLD/SIGINT
     * 都能把它打断。原来 -1 直接 return -1 → network_thread 永久退出，
     * 进程还在、但再也不会收帧，只能靠 App 重新 deploy。
     */
    int client;
    for (;;) {
        if (!running) return -1;
        client = accept(server_fd, NULL, NULL);
        if (client >= 0) break;
        if (errno == EINTR) continue;
        LOG("accept 失败: %s", strerror(errno));
        return -1;
    }
    LOG("AP 已连接");

    uint8_t *tile_buf = NULL;
    size_t tile_buf_cap = 0;

    /* 等握手 */
    dashcast_handshake_t hs;
    if (read_full(client, &hs, sizeof(hs)) != 0) { LOG("读握手失败"); goto out; }
    if (hs.magic != DASHCAST_MAGIC || hs.version != DASHCAST_VERSION) {
        LOG("握手非法 (magic=%08x ver=%d)", hs.magic, hs.version);
        goto out;
    }

    /* H.264 档的 flags 里带的是"编码尺寸"（画布的一半），
     * 图层就按这个尺寸建，由合成器拉伸到屏 —— 跟 BGRA 档建 1920×532 的层
     * 最终看到的大小一样，但省掉一次软件 2 倍放大。 */
    const int h264 = (hs.codec == DASHCAST_CODEC_H264);
    int enc_w = h264 ? DASHCAST_FLAG_ENC_W(hs.flags) : 0;
    int enc_h = h264 ? DASHCAST_FLAG_ENC_H(hs.flags) : 0;
    if (h264 && (enc_w <= 0 || enc_h <= 0)) {
        LOG("H.264 档但 flags 没带编码尺寸，退回画布尺寸");
        enc_w = (int)hs.width; enc_h = (int)hs.height;
    }

    /*
     * **握手参数必须先校验再分配**。
     *
     * hs.width/height 是 TCP 上来的 uint32_t，原来直接强转 int 参与
     * frame_size 计算，没有任何范围检查。后果：
     *   · width=0x80000000 → frame_w 溢出成 INT_MIN，frame_size 接近 2^64
     *   · width=5000000   → 要 26GB
     * calloc 必然失败 → goto out → cleanup_layer() → blank_frame()
     * → memset(buffers[0].data=NULL, 0, 巨大值) → 直接 SIGSEGV。
     *
     * 也就是**一个 28 字节的握手包就能打死仪表守护进程**，
     * 之后 /run/dashcast-shm、layer 99 全靠运气。
     *
     * 另外 stride 是 int，frame_w*4 也必须装得下；pool offset 是 int32，
     * frame_size 乘 NUM_BUFFERS 同样要装得下，否则 Wayland protocol error
     * 会把整条 wl_display 连接判死，进程活着但永远不上屏。
     */
    if (hs.codec != DASHCAST_CODEC_BGRA
            && hs.codec != DASHCAST_CODEC_RLE
            && hs.codec != DASHCAST_CODEC_H264) {
        LOG("握手参数字段非法：codec=%u", (unsigned)hs.codec);
        goto out;
    }
    if (hs.width == 0 || hs.height == 0
            || hs.width > MAX_DIM_W || hs.height > MAX_DIM_H) {
        LOG("握手尺寸非法：%ux%u（上限 %d/%d）",
            (unsigned)hs.width, (unsigned)hs.height, MAX_DIM_W, MAX_DIM_H);
        goto out;
    }

    pthread_mutex_lock(&fb_mutex);
    frame_w = h264 ? enc_w : (int)hs.width;
    frame_h = h264 ? enc_h : (int)hs.height;
    pthread_mutex_unlock(&fb_mutex);

    /* 编码尺寸同样要限幅，而且必须是偶数（4:2:0 色度抽样要求） */
    if (frame_w <= 0 || frame_h <= 0
            || frame_w > MAX_DIM_W || frame_h > MAX_DIM_H) {
        LOG("图层尺寸非法：%dx%d", frame_w, frame_h);
        goto out;
    }
    if (h264 && ((frame_w & 1) || (frame_h & 1))) {
        LOG("H.264 编码尺寸必须是偶数：%dx%d", frame_w, frame_h);
        goto out;
    }
    /* stride(int) 与 pool offset(int32) 都要装得下 */
    if ((size_t)frame_w > (size_t)INT32_MAX / 4) {
        LOG("图层宽度过大（stride 溢出）：%d", frame_w);
        goto out;
    }

    pthread_mutex_lock(&fb_mutex);
    stride = frame_w * 4;
    frame_size = (size_t)frame_w * frame_h * 4;
    if (frame_size > (size_t)MAX_POOL_BYTES / NUM_BUFFERS) {
        pthread_mutex_unlock(&fb_mutex);
        LOG("帧尺寸过大：%zu（单池上限 %zu）", frame_size,
            (size_t)MAX_POOL_BYTES / NUM_BUFFERS);
        goto out;
    }
    free(frame_buffer);
    /*
     * **必须 calloc，不能 malloc**。
     *
     * BGRA 档走图块增量：每帧只覆盖"与上一帧不同"的块，其余区域保留
     * frame_buffer 里的旧内容。malloc 出来的是上次释放的堆块残留（随机像素），
     * 那些从没被任何图块覆盖过的角落就会一直显示这堆垃圾 ——
     * 表现为重影/花屏/黑块。
     */
    frame_buffer = calloc(1, frame_size);
    pthread_mutex_unlock(&fb_mutex);

    if (!frame_buffer) { LOG("malloc 帧缓冲失败"); goto out; }
    LOG("握手 OK: 画布 %dx%d 图层 %dx%d codec=%d",
        (int)hs.width, (int)hs.height, frame_w, frame_h, hs.codec);

    /* 先回 ACK 再建缓冲池和图层。
     * 因为 setup_layer() 里是一串 LayerManagerControl 的 system() 调用，
     * 在这台 CP 上要几百毫秒到一两秒；AP 端的握手超时比这短，会误判为连接失败。 */
    dashcast_handshake_ack_t ack;
    ack.magic = DASHCAST_MAGIC;
    ack.status = 0;
    if (send(client, &ack, sizeof(ack), 0) != (ssize_t)sizeof(ack)) {
        LOG("回 ACK 失败");
        goto out;
    }

    /* 尝试读 AP 紧随 ACK 发来的「图层矩形」（20 字节）。
     * 图层矩形不再从 TCP 上读。
     *
     * 曾经尝试在握手后读 20 字节矩形，但**无法与帧头区分**：
     *   矩形: magic(0x54534344) | x | y | w | h
     *   帧头: magic(0x54534344) | seq | tile_count | payload_len
     * 两者前 4 字节完全相同，MSG_PEEK 只能看到 magic，必然误判 ——
     * 把帧头当矩形读走，紧接着解析帧头就报"帧头非法"，会话立刻断。
     *
     * 现在统一约定：矩形只由 App 侧用 LayerManagerControl 外部设置
     * （见 CpManager.setLayerRect）。这里不做任何读取，帧流不受干扰。
     */
    g_layer_rect_valid = 1;   /* 保留外部设置的矩形（若有），否则 apply 时会跳过 */
    if (g_layer_w <= 0 || g_layer_h <= 0) {
        g_layer_rect_valid = 0;   /* 没设过 → 按整屏处理（旧行为） */
    }

    /* 握手确定了真实分辨率，这时才建共享内存缓冲池 + 图层 */
    if (init_buffers() != 0) { LOG("缓冲池创建失败"); goto out; }
    setup_layer();
    /* 图层矩形由 App 外部用 LayerManagerControl 设置，这里不覆盖。
     * CP 进程内的 g_layer_* 与外部设置的 destination region 是两份状态，
     * 用前者覆盖只会把外部刚设好的值冲掉。 */

    /* H.264 档：图层建好之后，像素不走 TCP 图块，改走 TCP 上的 NAL 流。
     * 收完（AP 断开 TCP）后复用 cleanup_layer()，和 BGRA 档完全一致。 */
    if (h264) {
        LOG("会话模式: H.264（复用 TCP 控制连接，%dx%d）", frame_w, frame_h);
        h264_rx_run(client, frame_buffer, frame_w, frame_h, commit_frame, &running);
        cleanup_layer();
        goto out;
    }
    LOG("会话模式: BGRA+LZ4（TCP 图块）");

    /* 收帧循环 */
    while (running) {
        dashcast_frame_header_t fh;
        if (read_full(client, &fh, sizeof(fh)) != 0) {
            LOG("连接断开");
            break;
        }
        if (fh.magic != DASHCAST_MAGIC) { LOG("帧头非法"); break; }

        /* tile_count 上限：按住 realloc，畸形头不该能要求分配任意内存 */
        if (fh.tile_count == 0 || fh.tile_count > MAX_TILES_PER_FRAME) {
            LOG("帧头 tile_count 非法: %u", fh.tile_count);
            break;
        }
        if (fh.payload_len > MAX_FRAME_BYTES) {
            LOG("帧头 payload_len 非法: %u", fh.payload_len);
            break;
        }

        /* 读所有图块头（20 字节/个） */
        size_t headers_len = (size_t)fh.tile_count * sizeof(dashcast_tile_t);
        if (headers_len > tile_buf_cap) {
            uint8_t *nb = realloc(tile_buf, headers_len);
            if (!nb) { LOG("realloc 失败"); break; }
            tile_buf = nb;
            tile_buf_cap = headers_len;
        }
        if (read_full(client, tile_buf, headers_len) != 0) break;

        /* 读像素数据 */
        uint8_t *payload = NULL;
        if (fh.payload_len > 0) {
            payload = malloc(fh.payload_len);
            if (!payload) { LOG("malloc payload 失败"); break; }
            if (read_full(client, payload, fh.payload_len) != 0) {
                free(payload);
                break;
            }
        }

        /* 解图块，写进帧缓冲（逐行拷贝，解决 stride 错位）。
         *
         * 合规性加固（2026-10-30）：这之前是"信任对端"，畸形 tile 头就能写穿
         * frame_buffer / 读穿 payload —— 内网可信不是不验的理由。这里有
         * ① 几何越界校验 ② payload 读入按 data_len 而非 raw_len ③
         * raw_len 必须与 w*h*4 一致 ④ 全帧完整才提交，坏帧跳过。 */
        pthread_mutex_lock(&fb_mutex);
        {
            const dashcast_tile_t *tiles = (const dashcast_tile_t *)tile_buf;
            const uint8_t *p = payload;
            uint32_t consumed = 0;
            int frame_ok = 1;

            for (uint32_t i = 0; i < fh.tile_count; i++) {
                const dashcast_tile_t *t = &tiles[i];

                /* ① 图块必须落在画布内（防越界写 frame_buffer） */
                if (t->w == 0 || t->h == 0
                        || (uint32_t)t->x > (uint32_t)frame_w
                        || (uint32_t)t->y > (uint32_t)frame_h
                        || (uint32_t)t->x + t->w > (uint32_t)frame_w
                        || (uint32_t)t->y + t->h > (uint32_t)frame_h) {
                    LOG("图块越界: (%u,%u %ux%u) 画布 %dx%d",
                        (unsigned)t->x, (unsigned)t->y,
                        (unsigned)t->w, (unsigned)t->h, frame_w, frame_h);
                    frame_ok = 0;
                    break;
                }
                /* ② 读取不得越过本帧 payload */
                if ((uint64_t)consumed + t->data_len > fh.payload_len) {
                    frame_ok = 0;
                    break;
                }
                /* ③ raw_len 必须与几何尺寸严格一致（BGRA/RLE 都据此分配输出区） */
                if (t->raw_len != (uint32_t)t->w * t->h * 4) {
                    LOG("图块 raw_len 与几何不符: %u vs %ux%u",
                        t->raw_len, (unsigned)t->w, (unsigned)t->h);
                    frame_ok = 0;
                    break;
                }

                if (t->data_len == 0) continue;   /* 空图块：零贡献 */

                uint8_t *tile_raw = malloc(t->raw_len);
                if (!tile_raw) { frame_ok = 0; break; }

                if (t->mode == DASHCAST_CODEC_BGRA) {
                    /* 原始 BGRA：data_len 必须等于 raw_len（未压缩），
                     * 且读入按 data_len —— 从 payload 读，杜绝按 raw_len 读穿。 */
                    if (t->data_len != t->raw_len) {
                        free(tile_raw);
                        frame_ok = 0;
                        break;
                    }
                    memcpy(tile_raw, p, t->data_len);
                } else if (t->mode == DASHCAST_CODEC_RLE) {
                    if (lz4_decompress_block(p, t->data_len, tile_raw,
                                             t->raw_len, t->raw_len) < 0) {
                        free(tile_raw);
                        frame_ok = 0;
                        break;
                    }
                } else {
                    free(tile_raw);
                    frame_ok = 0;
                    break;
                }

                /* 逐行拷贝：图块行宽 t->w*4，帧缓冲行宽 frame_w*4 */
                int tile_stride = (int)t->w * 4;
                for (uint32_t j = 0; j < t->h; j++) {
                    uint8_t *dst = frame_buffer
                        + (((size_t)(t->y + j) * (size_t)frame_w + t->x) * 4);
                    memcpy(dst, tile_raw + (size_t)j * (size_t)tile_stride,
                           (size_t)tile_stride);
                }

                free(tile_raw);
                p += t->data_len;
                consumed += t->data_len;
            }
            pthread_mutex_unlock(&fb_mutex);

            free(payload);

            /*
             * 全程解完才提交；坏帧直接丢，保持上一帧，不把半截糊上仪表。
             *
             * **但坏帧必须把 frame_buffer 整块重同步**（2026-11-01 重影修复）。
             *
             * 问题：图块是逐个解、逐个写进 frame_buffer 的。如果第 k 个图块
             * 解压失败（frame_ok=0），前面 k-1 个已经写进去了，后面的图块被 break 跳过。
             * 这一帧不提交，仪表上看到的还是上一帧 —— 看起来"正常"。
             *
             * 可是下一帧 AP 只发"与它自己的 prev 不同"的图块。那 k-1 个块
             * 在 AP 的 prev 里还是**旧值**（AP 不知道 CP 解码失败了），
             * 所以**永远不会重发**。结果 frame_buffer 里那 k-1 个脏块永久留存，
             * 之后每一帧 commit 都把它们糊上屏 → 用户看到的"重影/残影"。
             *
             * 修复：坏帧时把 frame_buffer 清空并标记需要整帧重画 ——
             * 下一帧 AP 那边我们必须用 force_full 才能补全，
             * 但 AP 侧不知道这里失败了……所以这里直接让会话重连：
             * 断开 TCP，AP 的 enc_connect 会重置 prev 并 force_full 重发整帧。
             *
             * 为什么选断连而不是本地清空：本地清空只能保证"不再显示脏数据"，
             * 但画面会变成一块真·黑/透明，用户看到"投屏区空了一块"；
             * 断连重连能拿到完整的一帧，代价是 ~1 秒闪一下，可接受。
             */
            if (frame_ok) {
                commit_frame();
            } else {
                LOG("坏帧：不提交，且断开重连让 AP 重发整帧（否则脏块会永久留存）");
                break;
            }
        }
    }

out:
    free(tile_buf);
    close(client);
    /* 断连/出错 → 只清理图层，然后回到 accept 等下一次连接。
     * 之前的版本是 exit(0)，导致 AP 一停投屏 CP 这边就没了，
     * 下一次必须靠 App 重新部署才能连上。 */
    cleanup_layer();
    /* 会话汇总：这两个数字是判断"这一场投得健不健康"的直接证据 */
    LOG("会话结束，图层已还原（本会话提交 %d 帧 / 丢 %d 帧），等待下一个连接",
        g_commit_total, g_drop_total);
    g_commit_total = 0;
    g_drop_total = 0;
    return 0;
}

/* 线程入口：一直等连接，除非监听口本身坏了 */
static void *network_thread(void *arg) {
    int server_fd = *(int *) arg;
    while (run_session(server_fd) == 0) {
        /* 继续等下一个 AP 连接 */
    }
    LOG("监听口异常，网络线程退出");
    return NULL;
}

/*
 * 图层巡检线程（重影根治，2026-11-02）。
 *
 * 为什么不放在 commit_frame() 里：
 *   实测发现 **画面静止时 AP 不发任何图块**（BGRA 增量编码只发变化的块），
 *   CP 侧就没有 commit_frame() 调用 ——
 *   于是挂在提交路径上的巡检**永远不会运行**，
 *   而双挂载恰恰在画面静止时才稳定存在（用户看到"不动的时候有两份叠着"）。
 *
 *   这是一个逻辑死锁：需要巡检的场景 = 巡检不运行的场景。
 *
 * 所以改成独立线程，1 秒一次，与画面是否变化无关。
 */
static void *layer_watchdog_thread(void *arg) {
    (void) arg;
    int ticks = 0;
    while (running) {
        /* 1 秒一次；sleep 拆小片，便于退出时快速响应 */
        for (int i = 0; i < 10 && running; i++) usleep(100 * 1000);
        if (!running) break;

        /* 只在投屏会话中巡检（没连接时不动仪表，避免干扰原厂） */
        if (!g_session_active) continue;

        detach_surface_from_all_layers(0);
        if (++ticks % 60 == 0) {
            LOG("图层巡检已运行 %d 秒（每秒查一次双挂载）", ticks);
        }
    }
    return NULL;
}

/* ===== 初始化 Wayland + ivi ===== */

static int init_wayland(void) {
    display = wl_display_connect(NULL);
    if (!display) {
        LOG("连 Wayland 失败（检查 XDG_RUNTIME_DIR / WAYLAND_DISPLAY）");
        return -1;
    }

    struct wl_registry *registry = wl_display_get_registry(display);
    wl_registry_add_listener(registry, &registry_listener, NULL);
    wl_display_roundtrip(display);
    wl_display_roundtrip(display);

    if (!compositor || !shm || !ivi_app) {
        LOG("缺少必需接口 (compositor=%p shm=%p ivi=%p)",
            (void*)compositor, (void*)shm, (void*)ivi_app);
        return -1;
    }

    /* 建单个 wl_surface，通过 ivi_application 关联到 ivi_id（只做一次）。
     * 注意：ivi_surface 的位置/大小/可见性不由客户端设，
     * 而是通过 LayerManagerControl 命令（见 setup_layer）控制。
     * surface_create 本身就把 wl_surface 绑定到了 SURFACE_ID。 */
    surface = wl_compositor_create_surface(compositor);
    ivi_surface = ivi_application_surface_create(ivi_app, SURFACE_ID, surface);
    ivi_surface_add_listener(ivi_surface, &ivi_surface_listener, NULL);
    wl_surface_commit(surface);
    wl_display_roundtrip(display);

    /* 注意：帧缓冲和缓冲池不在这一起建，因为此时还不知道
     * 真实分辨率（要等握手 AP 传 w/h）。这里只连 Wayland + 建 surface。
     * frame_buffer 和 buffers 在 network_thread 收到握手后、确定
     * frame_w/frame_h 之后才分配（见 init_buffers / network_thread）。 */

    LOG("Wayland + ivi 就绪（surface %d 已建，等握手确定分辨率）", SURFACE_ID);
    return 0;
}

/* ===== 用 LayerManagerControl 建图层并置顶 ===== */

/*
 * render order 是**整表覆盖**，不是增量：少写一个 layer ID，那层就消失。
 * 原厂仪表是 layer 10（+ 20），直接写 "10 99" 会把 20 顶掉。
 * 所以：先 get screen 0 把现状读出来存档，追加本层，退出时原表还原。
 */
static char g_saved_order[256] = "";

/* —— 图层目的矩形（v2.9.1）——
 * AP 握手后发来遮罩矩形，CP 用它设置 ivi 图层的 destination region。
 * 图层只绘制在这块区域内，区域外合成器不绘制 → 原厂仪表自然显露。
 * 这套逻辑不依赖 alpha，所以 H.264 档也能用（该档没有透明通道）。
 * 全 0 表示"没收到 / 用整屏"，即旧行为。 */
/* （变量已在上方声明） */

/*
 * 用 LayerManagerControl 设置 layer 99 的目的矩形。
 *
 * 实测这台 CP 支持：
 *   LayerManagerControl set layer 99 destination region <x> <y> <w> <h>
 * 设完之后 get layer 99 会显示新的 destination region，source region 不变
 * （所以画面会被自动缩放填充到新矩形）。
 *
 * 失败不致命：没设成就是整屏铺满（旧行为），宁可退回也不让会话断掉。
 */
static void apply_layer_rect(void)
{
    if (!g_layer_rect_valid) return;

    char cmd[512];
    snprintf(cmd, sizeof(cmd),
             "LayerManagerControl set layer %d destination region %d %d %d %d 2>/dev/null",
             LAYER_ID, g_layer_x, g_layer_y, g_layer_w, g_layer_h);
    int rc = system(cmd);
    LOG("图层目的矩形: %d,%d %dx%d（set_rc=%d）",
        g_layer_x, g_layer_y, g_layer_w, g_layer_h, rc);

    /* 回读确认（顺手把实际生效值打进日志，排查时不用再登 CP） */
    FILE *f = popen("LayerManagerControl get layer 99 2>/dev/null | head -8", "r");
    if (f) {
        char buf[1024];
        size_t n = fread(buf, 1, sizeof(buf) - 1, f);
        fclose(f);
        buf[n] = '\0';
        const char *k = strstr(buf, "destination region");
        if (k) {
            LOG("回读确认: %.60s", k);
        } else {
            LOG("回读没拿到 destination region（该 ROM 可能只列启动时存在的层）");
        }
    }
}

/* 从一段文本里按出现顺序收集整数 */
static int collect_ids(const char *s, int *out, int max) {
    int n = 0;
    while (*s && n < max) {
        while (*s && (*s < '0' || *s > '9')) s++;
        if (!*s) break;
        int v = 0;
        while (*s >= '0' && *s <= '9') { v = v * 10 + (*s - '0'); s++; }
        if (v >= 0 && v < 1024) out[n++] = v;   /* 层 id 不会大到哪里去 */
    }
    return n;
}

/*
 * get screen 0 里那一行长这样：
 *   - layer render order:   10(0xa),
 * 括号里是十六进制说明，必须先剥掉，否则 "(0xa)" 里的 0 会被当成一个层 id，
 * 拼出 "10 0 99" 这种不存在的层，set render order 直接被拒，投屏就上不了屏。
 */
static int parse_order_line(const char *line, int *ids, int max) {
    char clean[512];
    size_t o = 0;
    int depth = 0;
    for (const char *p = line; *p && o + 1 < sizeof(clean); p++) {
        if (*p == '(') { depth++; continue; }
        if (*p == ')') { if (depth) depth--; continue; }
        if (depth) continue;
        if (*p == '\n' || *p == '\r') break;
        clean[o++] = *p;
    }
    clean[o] = '\0';
    return collect_ids(clean, ids, max);
}

/* 读现有 render order；格式未知，所以只扫关键字后面那串数字 */
static int read_render_order(int *ids, int max) {
    FILE *f = popen("LayerManagerControl get screen 0 2>/dev/null", "r");
    if (!f) return -1;
    char buf[8192];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    buf[n] = '\0';
    pclose(f);

    const char *key = strstr(buf, "render order");
    if (!key) key = strstr(buf, "render_order");
    if (!key) {
        LOG("get screen 0 里没找到 render order，原始输出：%.300s", buf);
        return -1;
    }
    return parse_order_line(key, ids, max);
}

/* 现在的 render order 里有没有某个层：1 有 / 0 没有 / -1 读不到 */
static int order_contains(int layer_id, int *ids, int max) {
    int n = read_render_order(ids, max);
    if (n < 0) return -1;
    for (int i = 0; i < n; i++) if (ids[i] == layer_id) return 1;
    return 0;
}

/**
 * 把我们的 surface 从**所有**图层上摘掉（除了正在用的 LAYER_ID 之外也一并处理）。
 *
 * 为什么需要：ivi-shell 里一个 surface 可以同时属于多个 layer。
 * `destroy layer 99` 不会清理它挂过的 surface —— surface 会掉回之前所属的图层。
 * 实测事故（2026-11-01）：
 *     layer 10（原厂）surface render order: 4711, 9001
 *     layer 99（投屏）surface render order: 9001
 * 同一个 surface 9001 同时出现在两层 → 仪表上两份画面 → 用户报"重影"。
 *
 * 实现：直接 popen 读 `get scene` 的 listing，用 strstr 扫出所有
 * `layer <id> (0x` 形式的 id，再逐个 `remove surface`。
 * 不用 shell 的 sed/管道 —— 这台 CP 的 busybox 正则行为不一致
 * 实测按 layer 关键字提取 id 的写法输出为空，所以改在 C 里解析。
 */

/*
 * 把我们的 surface 从**所有**图层上摘掉（LAYER_ID 自己除外）。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么要做这个（2026-11-01 重影根因）
 * ══════════════════════════════════════════════════════════════
 * ivi-shell 里一个 surface **可以同时属于多个 layer**，而且
 * `add surface to layer` 是**追加**、不是迁移。
 *
 * 时序问题：
 *   init_wayland() 里 ivi_application_surface_create(9001) + commit
 *   → ivi-shell 自动把 surface 挂到**默认层**（这台机器是 layer 10 = 原厂）
 *   之后 setup_layer() 如果只做 add 到 99、不摘 10，
 *   就变成 layer 10 和 layer 99 各挂一份 → 仪表上两份画面叠在一起。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么必须先读完再执行（这一版的关键修复）
 * ══════════════════════════════════════════════════════════════
 * 上一版是在 popen 的 fgets 循环里直接 system() 执行 remove。
 * popen 会 fork 一个 `sh -c` 子进程并保持 pipe 打开，
 * 在循环内再 system()（又一次 fork+exec）时，本进程同时持有
 * 两个子 shell —— 在这台 CP 的 uClibc/weston 环境下，
 * system() 会静默失败（`2>/dev/null` 又吞掉了错误输出）。
 *
 * 表现就是：日志明明白白打出「已从 layer 10 上摘掉 surface 9001」，
 * 但 `get layer 10` 里 9001 还在 —— 日志在 system() 之后无条件打印，
 * 所以看起来"做了却没生效"，排查时极具迷惑性。
 *
 * 修法：先把所有 layer id 收集到数组里，**pclose 关掉 pipe**，
 * 再逐个 system() 执行 remove。
 */
static void detach_surface_from_all_layers(int verbose)
{
    char line[512];
    char cmd[256];
    int  ids[32];
    int  n = 0;

    FILE *fp = popen("LayerManagerControl get scene 2>/dev/null", "r");
    if (!fp) {
        /* 兜底：至少把最常见的那两层摘一遍（原厂 10 和 我们自己的 99 之外） */
        snprintf(cmd, sizeof(cmd),
                 "LayerManagerControl remove surface %d from layer 10 2>/dev/null; "
                 "LayerManagerControl remove surface %d from layer %d 2>/dev/null",
                 SURFACE_ID, SURFACE_ID, LAYER_ID);
        system(cmd);
        return;
    }

    /* ① 只读：把 layer id 全部收集起来，读完立刻关 pipe */
    while (fgets(line, sizeof(line), fp) && n < (int)(sizeof(ids) / sizeof(ids[0]))) {
        const char *p = line;
        while ((p = strstr(p, "layer ")) != NULL) {
            p += 6;                                   /* 跳过 "layer " */
            if (*p < '0' || *p > '9') { p++; continue; }

            int id = 0;
            const char *q = p;
            while (*q >= '0' && *q <= '9') { id = id * 10 + (*q - '0'); q++; }
            while (*q == ' ') q++;
            /* 后面必须紧跟 " (0x"，才算合法的 layer 头行 */
            if (*q != '(' || q[1] != '0' || (q[2] != 'x' && q[2] != 'X')) {
                if (verbose) LOG("[detach] 跳过非 layer 头行: %.60s", line);
                p++;
                continue;
            }

            if (id == LAYER_ID) { p = q; continue; }    /* 自己的层留着 */
            ids[n++] = id;
            if (verbose) LOG("[detach] 收集到 layer id=%d", id);
            p = q;
        }
    }
    pclose(fp);                                       /* ★ 必须先关，再 system() */

    if (verbose && n > 0) LOG("[detach] 共收集 %d 个待摘图层", n);

    /* ② 再逐个执行 remove */
    int removed = 0;
    for (int i = 0; i < n; i++) {
        snprintf(cmd, sizeof(cmd),
                 "LayerManagerControl remove surface %d from layer %d 2>/dev/null",
                 SURFACE_ID, ids[i]);
        int rc = system(cmd);
        /*
         * 巡检模式下只在**真的又出现了**时打日志（repair 场景）——
         * 正常运行时每帧都摘一次、没有可摘的，保持完全静默，
         * 否则每秒一条会把真信息淹掉。
         */
        if (verbose) {
            LOG("已从 layer %d 上摘掉 surface %d（rc=%d，防重复挂载 → 重影）",
                ids[i], SURFACE_ID, rc);
        } else {
            LOG("巡检：发现 surface %d 又在 layer %d 上，已摘掉（rc=%d）",
                SURFACE_ID, ids[i], rc);
        }
        removed++;
    }

    if (verbose && removed == 0) {
        LOG("surface %d 未挂在其他图层上（干净）", SURFACE_ID);
    }
}

static void setup_layer(void) {
    char cmd[512];
    char order[512];
    int ids[32];

    /* ① 存档 */
    int n = read_render_order(ids, 32);
    if (n > 0) {
        g_saved_order[0] = '\0';
        for (int i = 0; i < n; i++) {
            char t[24];
            /* 逗号分隔 —— LayerManagerControl 只认这个格式 */
            snprintf(t, sizeof(t), "%s%d", i ? "," : "", ids[i]);
            strncat(g_saved_order, t,
                    sizeof(g_saved_order) - strlen(g_saved_order) - 1);
        }
        LOG("现有 render order: [%s]", g_saved_order);
    } else {
        strcpy(g_saved_order, "10");
        LOG("读不到现有 render order，按 [10] 兜底（退出时仍会还原成这个）");
    }

    /*
     * ② 清残留 + 建层。
     *
     * ivi-shell 的语义：一个 surface **可以同时属于多个 layer**，
     * 而 `add surface to layer` 是**追加**，不是迁移 ——
     * 它不会把 surface 从原层摘走。
     *
     * 实测踩到（2026-11-01）：
     *   layer 10（原厂图层）surface render order: 4711, 9001
     *   layer 99（投屏图层）surface render order: 9001
     * 同一个 surface 9001 同时挂在两层上 → 仪表上出现两份画面
     * （原厂层那份被 dest region 拉伸），用户报"重影"。
     *
     * 根因是时序：
     *   init_wayland() 里 ivi_application_surface_create(9001) + wl_surface_commit()
     *   → ivi-shell 自动把它挂到**默认层**（这台机器上是 layer 10）。
     *   之后 setup_layer() 只做 add 到 99，从不摘 10 —— 所以一直双挂。
     *
     * **正确顺序必须是：先 add 到 99，再摘掉其他所有层**。
     * 反过来（先摘后 add）没用：add 之后 ivi-shell 仍保留 10 上的登记。
     */
    snprintf(cmd, sizeof(cmd),
             "LayerManagerControl destroy layer %d 2>/dev/null", LAYER_ID);
    system(cmd);

    /*
     * ★ 建层全流程：执行 → 回读验证 → 失败重试 3 轮（2026-11-11 实测事故）
     *
     * 事故现场（CP 侧 DSI-1，1920x532）：
     *   LayerManagerControl get layers  → 只有 Layer 10，**layer 99 不存在**
     *   LayerManagerControl get screen 0 → render order 只有 10
     *   仪表上看到的是原厂界面，我们的画面从未上屏
     *
     * 但同样这几条命令在 shell 里手敲（带 XDG_RUNTIME_DIR/WAYLAND_DISPLAY）
     * 一次就成功。说明 system() 链路有过静默失败，而 `2>/dev/null`
     * 把错误吞了，日志里完全看不出来。
     *
     * 所以现在：每条命令去掉 2>/dev/null（让错误进日志），
     * 全部执行完回读校验 visibility 和 render order，
     * 不达标就重试 3 轮，最终仍失败则明确报错。
     */
    {
        int ok = 0;
        for (int attempt = 0; attempt < 3 && !ok; attempt++) {
            if (attempt) {
                LOG("建层第 %d 次重试…", attempt + 1);
                usleep(200 * 1000);
            }

            /* ① 建层 */
            snprintf(cmd, sizeof(cmd),
                     "LayerManagerControl create layer %d %d %d",
                     LAYER_ID, frame_w, frame_h);
            int crc = system(cmd);
            if (crc != 0) {
                LOG("create layer %d 返回 %d（%dx%d）",
                    LAYER_ID, crc, frame_w, frame_h);
            }

            /* ② 设可见 —— create 出来默认 visibility=0，不设永不上屏 */
            snprintf(cmd, sizeof(cmd),
                     "LayerManagerControl set layer %d visibility 1", LAYER_ID);
            int vrc = system(cmd);
            if (vrc != 0) LOG("set visibility 返回 %d", vrc);

            /* ③ 挂 surface */
            snprintf(cmd, sizeof(cmd),
                     "LayerManagerControl add surface %d to layer %d",
                     SURFACE_ID, LAYER_ID);
            int arc = system(cmd);
            if (arc != 0) LOG("add surface 返回 %d", arc);

            /* ④ 把 surface 从其他层（原厂 layer 10）摘掉 */
            detach_surface_from_all_layers(1);

            /* ⑤ 拼 render order（逗号分隔，空格会被整条拒绝） */
            int already = 0;
            for (int i = 0; i < n; i++) if (ids[i] == LAYER_ID) already = 1;
            if (already) {
                snprintf(order, sizeof(order), "%s", g_saved_order);
            } else {
                snprintf(order, sizeof(order), "%s,%d", g_saved_order, LAYER_ID);
            }
            snprintf(cmd, sizeof(cmd),
                     "LayerManagerControl set screen 0 render order %s", order);
            int orc = system(cmd);
            if (orc != 0) LOG("set render order 返回 %d", orc);

            /* ⑥ 回读验证：visibility 必须 1，且 99 必须在 render order 里 */
            int vis = -1, in_order = 0;
            snprintf(cmd, sizeof(cmd),
                     "LayerManagerControl get layer %d 2>/dev/null "
                     "| grep visibility", LAYER_ID);
            FILE *vf = popen(cmd, "r");
            if (vf) {
                char lb[256];
                if (fgets(lb, sizeof(lb), vf)) {
                    char *p = strchr(lb, ':');
                    if (p) vis = atoi(p + 1);
                }
                pclose(vf);
            }

            snprintf(cmd, sizeof(cmd),
                     "LayerManagerControl get screen 0 2>/dev/null "
                     "| grep 'render order'");
            vf = popen(cmd, "r");
            if (vf) {
                char lb[512];
                if (fgets(lb, sizeof(lb), vf)) {
                    char key[32];
                    snprintf(key, sizeof(key), "%d(0x%x)", LAYER_ID, LAYER_ID);
                    if (strstr(lb, key)) in_order = 1;
                }
                pclose(vf);
            }

            ok = (vis == 1) && in_order;
            LOG("图层校验 #%d: visibility=%d in_render_order=%d => %s",
                attempt + 1, vis, in_order, ok ? "OK" : "重试");
        }

        if (!ok) {
            LOG("★ 图层准备失败（已重试 3 次）：layer %d 不可见或不在 render order。"
                "仪表上会看不到投屏画面 —— 在 CP 上手工执行 "
                "`LayerManagerControl get layers` 排查", LAYER_ID);
        } else {
            LOG("图层就绪：layer %d（%dx%d）render order: %s",
                LAYER_ID, frame_w, frame_h, order);
        }
    }

    /* 会话建立完毕，可以开始图层巡检（独立线程每秒查一次双挂载） */
    g_session_active = 1;
}

/* 首帧提交之后核一次：此时图层已经有内容，listing 里应该能看到它 */
static void verify_layer_once(void) {
    static int done = 0;
    if (done) return;
    done = 1;

    int ids[32];
    int chk = -1;
    for (int i = 0; i < 5; i++) {
        chk = order_contains(LAYER_ID, ids, 32);
        if (chk == 1) break;
        usleep(200 * 1000);
    }

    if (chk == 1) {
        LOG("首帧后复核：render order 里已包含 layer %d", LAYER_ID);
    } else {
        /*
         * ★ 首帧后仍然不在 render order → **立即修复**（2026-11-11 实测）
         *
         * 旧版本的注释在这里写着"该 ROM 只列启动时已存在的层，看不到也正常"，
         * 这个假设是**错的**。实测（DSI-1 1920x532）：
         *   手工 set screen 0 render order 10,99 之后，get screen 0 立刻列出 99；
         *   而投屏跑了一整晚、dashcastd 日志一直在刷"巡检摘掉双挂载"，
         *   仪表上却始终只有原厂画面 —— 因为 99 从未进过 render order。
         *
         * 所以这里必须重设一次，而不是"只陈述事实"。
         */
        LOG("首帧后复核：layer %d 不在 render order，立即补救", LAYER_ID);
        char cmd[512];
        char order[256];
        order[0] = '\0';
        /* 用 read_render_order 读当前实际 order，再追加 99 */
        {
            int cur[32];
            int cn = read_render_order(cur, 32);
            for (int i = 0; i < cn; i++) {
                char t[24];
                snprintf(t, sizeof(t), "%s%d", i ? "," : "", cur[i]);
                strncat(order, t, sizeof(order) - strlen(order) - 1);
            }
        }
        if (order[0] == '\0') {
            snprintf(order, sizeof(order), "10,%d", LAYER_ID);
        } else {
            char t[300];
            snprintf(t, sizeof(t), "%s,%d", order, LAYER_ID);
            snprintf(order, sizeof(order), "%s", t);
        }
        snprintf(cmd, sizeof(cmd),
                 "LayerManagerControl set screen 0 render order %s", order);
        system(cmd);
        snprintf(cmd, sizeof(cmd),
                 "LayerManagerControl set layer %d visibility 1", LAYER_ID);
        system(cmd);
        LOG("已补救：render order=%s, visibility=1", order);
    }
}

/* ===== 清理图层 ===== */

/* 销毁图层之前先"画一帧全透明"。
 *
 * 这台 ROM 的 ivi-shell 在 destroy layer 时不产生 damage —— 图层确实没了
 * （render order 也回到 [10]），但面板上那块像素没人重画，用户看到的就是
 * "点了停止，画面还留在仪表上"。提交一帧 alpha=0 的缓冲会强制合成器重绘
 * 这块区域，露出底下的原厂仪表。buffer 格式是 ARGB8888，全 0 = 全透明。 */
static void blank_frame(void)
{
    if (!surface) return;

    /*
     * **必须确认缓冲池真的建好了**。
     *
     * 握手参数非法 / calloc 失败时，会话会 goto out → cleanup_layer()
     * → blank_frame()，而这时 init_buffers() 根本没跑过，buffers[] 全是
     * static 零值（wl_buffer=NULL, data=NULL）。直接 memset(NULL, 0, size)
     * 就是 SIGSEGV —— 一个畸形握手包打死守护进程的最后一环。
     */
    if (!buffers[0].wl_buffer || !buffers[0].data || buffers[0].data_size == 0) {
        LOG("缓冲池未就绪，跳过硬刷透明帧");
        return;
    }

    /* 全程独占 wl_display：取 buffer 要 pump 出 buffer release 事件，
     * roundtrip 也要自己读写 socket —— 都必须在锁内，避免与其它线程竞争。 */
    pthread_mutex_lock(&wl_mutex);

    buffer_t *b = acquire_buffer_locked();
    if (!b) {
        pthread_mutex_unlock(&wl_mutex);
        LOG("清屏帧拿不到空闲 buffer，跳过");
        return;
    }
    memset(b->data, 0, b->data_size);   /* 只清这块映射的真实大小 */
    wl_surface_attach(surface, b->wl_buffer, 0, 0);
    wl_surface_damage(surface, 0, 0, frame_w, frame_h);
    wl_surface_commit(surface);
    wl_display_flush(display);
    for (int i = 0; i < 4; i++) wl_display_roundtrip(display);

    pthread_mutex_unlock(&wl_mutex);
    LOG("已提交一帧全透明（强制合成器重绘这块区域）");
}

static void cleanup_layer(void) {
    char cmd[512];
    char order[256];
    order[0] = '\0';

    /* 会话结束，先停巡检 —— 否则它会在还原过程中又把 surface 挂回去 */
    g_session_active = 0;

    /* 存档里万一混进了 99（上一次清理失败留下的），剥掉再还原 ——
     * 否则每次"还原"都忠实地把 99 写回去，仪表就永远停在最后一帧。
     *
     * 兼容两种分隔符：老版本存的是 "10 99"（空格），新版本是 "10,99"（逗号）。
     * 输出统一用逗号 —— 那是 LayerManagerControl 唯一认的格式。 */
    if (g_saved_order[0]) {
        char tmp[256];
        strncpy(tmp, g_saved_order, sizeof(tmp) - 1);
        tmp[sizeof(tmp) - 1] = '\0';
        char *tok = strtok(tmp, " ,");
        while (tok) {
            if (atoi(tok) != LAYER_ID) {
                if (order[0]) strncat(order, ",", sizeof(order) - strlen(order) - 1);
                strncat(order, tok, sizeof(order) - strlen(order) - 1);
            }
            tok = strtok(NULL, " ,");
        }
    }
    if (!order[0]) snprintf(order, sizeof(order), "10");

    /* 顺序很讲究：先把图层刷成全透明（逼合成器重绘，画面立刻回原厂），
     * 再摘 render order、再 destroy。反过来如果 destroy 失败，摘表也兜得住。 */
    blank_frame();
    for (int i = 0; i < 3; i++) {
        /*
         * **先把 surface 从所有图层上摘掉，再 destroy**。
         *
         * 只 destroy layer 99 的话，ivi-shell 会把 surface 9001 掉回它
         * 之前所属的图层（实测掉回了 layer 10 = 原厂图层），
         * 于是下一轮会话再 add surface 到 99 时，它同时挂在两层上 →
         * 仪表出现两份画面（用户看到的就是"重影"）。
         */
        detach_surface_from_all_layers(0);

        snprintf(cmd, sizeof(cmd),
                 "LayerManagerControl set screen 0 render order %s 2>/dev/null", order);
        system(cmd);
        snprintf(cmd, sizeof(cmd),
                 "LayerManagerControl destroy layer %d 2>/dev/null", LAYER_ID);
        system(cmd);

        int ids[32];
        int has = order_contains(LAYER_ID, ids, 32);
        if (has == 0) {
            LOG("图层已清理（第 %d 次验证通过），render order=[%s]", i + 1, order);
            return;
        }
        LOG("图层清理第 %d 次未验证通过（order_has=%d，-1=读不到），重试", i + 1, has);
        usleep(300 * 1000);
    }
    LOG("图层清理 3 次仍未验证通过 —— 需要人工检查 LayerManagerControl 环境");
}

/* 启动时把 CP 真实屏幕尺寸打出来（AP 侧据此建屏，别再靠猜） */
static void log_screen_info(void) {
    FILE *f = popen("LayerManagerControl get screen 0 2>/dev/null | head -8", "r");
    if (!f) return;
    char buf[1024];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    buf[n] = '\0';
    pclose(f);
    LOG("CP 屏幕信息：\n%s", buf);
}

/* ===== 主函数 ===== */

/* ===== 演示模式（仅 DC_DEMO 构建）：本地 H.264 解码上屏 =====
 *
 * 目的：在真机上"看见" H.264 这一档的画质和 CPU 代价，再决定要不要正式做。
 * 它读一个本地 .264 文件，循环解码，按 30fps 提交到同一个 ivi 图层，
 * 跑够秒数后走 cleanup_layer() 还原（和正式会话同一条清理路径）。
 *
 * 生产构建不定义 DC_DEMO，这段完全不参与编译。 */
#ifdef DC_DEMO
/* openh264 的 C++ 接口由 h264shim.cpp 包成三个 C 函数，见那边注释 */
extern void *h264_open(void);
extern int   h264_decode(void *, const unsigned char *, int,
                         unsigned char **, unsigned char **, unsigned char **,
                         int *, int *, int *, int *);
extern void  h264_reset(void *);
extern void  h264_close(void *);

static void yuv420_to_argb(uint8_t *dst, const uint8_t *yp, const uint8_t *up, const uint8_t *vp,
                           int w, int h, int sy, int suv) {
    for (int j = 0; j < h; j++) {
        const uint8_t *y = yp + (size_t) j * sy;
        const uint8_t *u = up + (size_t) (j / 2) * suv;
        const uint8_t *v = vp + (size_t) (j / 2) * suv;
        uint32_t *row = (uint32_t *) (dst + (size_t) j * w * 4);
        for (int i = 0; i < w; i++) {
            int Y = y[i];
            int U = (int) u[i >> 1] - 128;
            int V = (int) v[i >> 1] - 128;
            int r = Y + ((91881 * V) >> 16);
            int g = Y - ((22554 * U + 46802 * V) >> 16);
            int b = Y + ((116130 * U) >> 16);
            if (r < 0) r = 0; else if (r > 255) r = 255;
            if (g < 0) g = 0; else if (g > 255) g = 255;
            if (b < 0) b = 0; else if (b > 255) b = 255;
            row[i] = 0xFF000000u | ((uint32_t) r << 16) | ((uint32_t) g << 8) | (uint32_t) b;
        }
    }
}

static int run_demo(const char *path, int seconds, int w, int h) {
    FILE *f = fopen(path, "rb");
    if (!f) { LOG("demo: 打不开 %s", path); return 2; }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *buf = (uint8_t *) malloc((size_t) n);
    if (fread(buf, 1, (size_t) n, f) != (size_t) n) { LOG("demo: 读失败"); return 2; }
    fclose(f);

    /* 切 NAL（保留起始码，不削尾随 0） */
    size_t *offs = (size_t *) malloc(sizeof(size_t) * 4096);
    int nals = 0;
    for (long i = 0; i + 4 < n; ) {
        if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 0 && buf[i + 3] == 1) {
            if (nals < 4096) offs[nals++] = (size_t) i;
            i += 4;
        } else i++;
    }
    if (nals < 3) { LOG("demo: NAL 太少 (%d)", nals); return 2; }
    LOG("demo: %s  %ld 字节  %d 个 NAL  %dx%d  %d 秒", path, n, nals, w, h, seconds);

    frame_w = w; frame_h = h; stride = w * 4; frame_size = (size_t) w * h * 4;
    free(frame_buffer);
    frame_buffer = (uint8_t *) malloc(frame_size);
    if (!frame_buffer) { LOG("demo: 帧缓冲分配失败"); return 3; }

    if (init_wayland() != 0) { LOG("demo: wayland 失败"); return 3; }
    setup_layer();
    if (init_buffers() != 0) { LOG("demo: buffer 失败"); cleanup_layer(); return 3; }

    void *dec = h264_open();
    if (!dec) { LOG("demo: 建解码器失败"); cleanup_layer(); return 3; }

    struct timespec t0, tn;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    double cpu0 = (double) clock() / CLOCKS_PER_SEC;

    long frames = 0;
    int loop = 0;
    double next_t = 0;
    while (running) {
        for (int k = 0; k < nals; ++k) {
            size_t s = offs[k];
            size_t e = (k + 1 < nals) ? offs[k + 1] : (size_t) n;
            unsigned char *py = NULL, *pu = NULL, *pv = NULL;
            int sy = 0, suv = 0, dw = 0, dh = 0;
            int r = h264_decode(dec, buf + s, (int) (e - s),
                                &py, &pu, &pv, &sy, &suv, &dw, &dh);
            if (r != 1) continue;

            yuv420_to_argb(frame_buffer, py, pu, pv, w, h, sy, suv);
            commit_frame();
            frames++;

            /* 按 30fps 节流 */
            next_t += 1.0 / 30.0;
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            double el = (now.tv_sec - t0.tv_sec) + (now.tv_nsec - t0.tv_nsec) / 1e9;
            double wait = next_t - el;
            if (wait > 0) usleep((useconds_t) (wait * 1e6));
            else if (wait < -0.5) next_t = el;       /* 落后太多就重新对齐，别追 */

            clock_gettime(CLOCK_MONOTONIC, &tn);
            el = (tn.tv_sec - t0.tv_sec) + (tn.tv_nsec - t0.tv_nsec) / 1e9;
            if (el >= seconds) goto done;
        }
        /* 循环回开头：重建解码器，避免参考帧失效 */
        h264_reset(dec);
        loop++;
    }

done: {
    struct timespec t1;
    clock_gettime(CLOCK_MONOTONIC, &t1);
    double cpu1 = (double) clock() / CLOCKS_PER_SEC;
    double wall = (t1.tv_sec - t0.tv_sec) + (t1.tv_nsec - t0.tv_nsec) / 1e9;
    double cpu = cpu1 - cpu0;
    LOG("DEMO 结果: 帧=%ld 耗时=%.2fs fps=%.1f CPU/帧=%.2fms 单核=%.0f%% 循环=%d",
        frames, wall, frames / (wall > 0 ? wall : 1),
        cpu * 1000.0 / (frames > 0 ? frames : 1),
        wall > 0 ? 100.0 * cpu / wall : 0, loop);
}

    h264_close(dec);
    cleanup_layer();
    free(offs);
    free(buf);
    LOG("demo 结束，图层已还原");
    return 0;
}
#endif

int main(int argc, char **argv) {
#ifdef DC_DEMO
    if (argc > 2 && strcmp(argv[1], "--demo") == 0) {
        int secs = argc > 3 ? atoi(argv[3]) : 20;
        int dw = argc > 4 ? atoi(argv[4]) : 960;
        int dh = argc > 5 ? atoi(argv[5]) : 528;
        return run_demo(argv[2], secs, dw, dh);
    }
#endif
    int port = DEFAULT_PORT;
    if (argc > 1) port = atoi(argv[1]);

    /*
     * **必须忽略 SIGPIPE**。
     *
     * 默认动作是「终止进程」。本项目有两条必经的写信道：
     *   ① 回握手 ACK 的 send(client, ...)  —— AP 超时/用户点停止会同时 close，
     *      命中 EPIPE 直接被信号杀死，layer 99 来不及清理，留在 render order 里。
     *   ② commit_frame() 的 wl_display_flush() —— Weston 重启时同样会自杀。
     * 这两种场景在车上都会真实发生。dashcmd.c 早就忽略了，这里一直漏着。
     */
    signal(SIGPIPE, SIG_IGN);

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);

    LOG("dashcastd 启动，端口 %d", port);
    log_screen_info();

    if (init_wayland() != 0) {
        LOG("Wayland 初始化失败，退出");
        return 1;
    }

    /* 注意：图层 setup_layer() 不在这里调了——因为分辨率要等握手
     * AP 传 w/h 才知道。移到 network_thread 收到握手之后调用。 */

    /* 建 TCP 监听 */
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) { LOG("socket 失败"); return 1; }
    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons((uint16_t)port);

    if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOG("bind 失败: %s", strerror(errno));
        return 1;
    }
    if (listen(server_fd, 1) < 0) { LOG("listen 失败"); return 1; }
    LOG("监听 0.0.0.0:%d", port);

    /* 网络线程 */
    pthread_t th;
    pthread_create(&th, NULL, network_thread, &server_fd);
    pthread_detach(th);

    /*
     * 图层巡检线程：独立于画面变化，每秒查一次 surface 有没有被挂到别的图层。
     *
     * 不能挂在 commit_frame 里 —— 画面静止时 AP 不发图块，commit_frame
     * 根本不会被调用，而双挂载恰恰在静止画面下才稳定存在（逻辑死锁）。
     */
    pthread_t wth;
    pthread_create(&wth, NULL, layer_watchdog_thread, NULL);
    pthread_detach(wth);

    /* 主循环：**不参与 wayland 事件读取**，只维持进程存活与退出信号。
     *
     * 事件驱动完全交给持锁的 wl_pump_locked()：
     *   · commit_frame() 每次提交前会 pump（等到 buffer release 才返回）
     *   · blank_frame() 用 roundtrip 保证清屏帧真的上屏
     * 这样任何时刻只有一个线程读写 wl_display 的 socket，既没有并发死锁，
     * 也不会出现"release 事件没人读 → 双缓冲永久 busy → 卡在第二帧"。
     *
     * （历史教训：上一版这里用 wl_display_dispatch_pending 当兜底，它只清
     *   内部队列、从不读 socket，于是 buffer.release 永远到不了，画面在
     *   第一帧后就冻住 —— 两条链路都卡，因为共用这段 commit 逻辑。） */
    while (running) {
        usleep(5000);   /* 5ms；不做任何 wl 调用，避免与网络线程争 socket */
    }

    LOG("正在退出…");
    close(server_fd);
    cleanup_layer();

    /* 释放 buffer */
    for (int i = 0; i < NUM_BUFFERS; i++) {
        if (buffers[i].wl_buffer) wl_buffer_destroy(buffers[i].wl_buffer);
        buffers[i].wl_buffer = NULL;
        buffers[i].data = NULL;
        buffers[i].data_size = 0;
    }
    if (pool) { wl_shm_pool_destroy(pool); pool = NULL; }

    /* 卸载 shm 映射并删掉对象：不 munmap 会把 tmpfs 页钉住，不 unlink 会留垃圾 */
    if (g_shm_base && g_shm_len) {
        munmap(g_shm_base, g_shm_len);
        g_shm_base = NULL;
        g_shm_len = 0;
    }
    shm_unlink(SHM_NAME);

    /* 顺序：ivi_surface 先于它承载的 wl_surface */
    if (ivi_surface) { ivi_surface_destroy(ivi_surface); ivi_surface = NULL; }
    if (surface) { wl_surface_destroy(surface); surface = NULL; }
    if (display) { wl_display_flush(display); wl_display_disconnect(display); display = NULL; }

    LOG("资源已释放");
    return 0;
}
