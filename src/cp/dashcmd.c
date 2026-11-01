/*
 * dashcmd.c — CP 侧明文命令守护（e04-p171，替代 SSH 通道）
 *
 * 职责：
 *   1. 监听 TCP 端口（默认 8445），收 AP 发来的命令
 *   2. 明文协议，无加密无握手，几十行搞定
 *   3. 命令集：
 *        PING             -> 回 PONG（连通性测试）
 *        PUSH <size>\n    -> 紧跟 <size> 字节二进制，写到 /run/dashcastd 并 chmod +x
 *        START            -> 设环境变量，nohup 启动 /run/dashcastd
 *        STOP             -> killall dashcastd（图层自动清理）
 *        STATUS           -> 回 dashcastd 是否在跑 + 文件大小
 *   4. 单连接处理完一个命令就关，简单可靠
 *
 * 安全性：/run 是 tmpfs，重启清空；dashcastd 退出即图层消失。
 * 编译：zig cc -target aarch64-linux-gnu -O2 -static dashcmd.c -o dashcmd
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <signal.h>

#define PORT       8445
#define CP_PATH    "/run/dashcastd"
#define CP_DIR     "/run"

static void send_all(int fd, const char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = send(fd, buf + off, len - off, 0);
        if (n <= 0) return;
        off += (size_t)n;
    }
}

static void reply(int fd, const char *msg) {
    send_all(fd, msg, strlen(msg));
}

/* 读一行（以 \n 结尾，不含 \n），返回 malloc 的字符串，失败返回 NULL */
static char *read_line(int fd) {
    char *buf = malloc(512);
    if (!buf) return NULL;
    size_t cap = 512, len = 0;
    for (;;) {
        char c;
        ssize_t n = recv(fd, &c, 1, 0);
        if (n <= 0) { free(buf); return NULL; }
        if (c == '\n') break;
        if (len + 1 >= cap) {
            cap *= 2;
            buf = realloc(buf, cap);
            if (!buf) return NULL;
        }
        buf[len++] = c;
    }
    buf[len] = '\0';
    return buf;
}

/* 读恰好 n 字节 */
static int read_exact(int fd, void *dst, size_t n) {
    char *p = (char *)dst;
    size_t got = 0;
    while (got < n) {
        ssize_t r = recv(fd, p + got, n - got, 0);
        if (r <= 0) return -1;
        got += (size_t)r;
    }
    return 0;
}

/* 命令 PUSH <size>：写 /run/dashcastd */
static int cmd_push(int fd, long size) {
    if (size <= 0 || size > (64 * 1024 * 1024)) {
        reply(fd, "ERR bad size\n");
        return -1;
    }
    char tmp[64];
    snprintf(tmp, sizeof(tmp), "%s/.dashcastd.tmp", CP_DIR);

    int out = open(tmp, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (out < 0) {
        reply(fd, "ERR open tmp\n");
        return -1;
    }

    char buf[65536];
    long remain = size;
    while (remain > 0) {
        size_t chunk = remain > (long)sizeof(buf) ? sizeof(buf) : (size_t)remain;
        if (read_exact(fd, buf, chunk) != 0) {
            close(out);
            unlink(tmp);
            reply(fd, "ERR read\n");
            return -1;
        }
        ssize_t w = write(out, buf, chunk);
        if (w != (ssize_t)chunk) {
            close(out);
            unlink(tmp);
            reply(fd, "ERR write\n");
            return -1;
        }
        remain -= (long)chunk;
    }
    close(out);

    if (rename(tmp, CP_PATH) != 0) {
        unlink(tmp);
        reply(fd, "ERR rename\n");
        return -1;
    }
    chmod(CP_PATH, 0755);

    /* 校验大小 */
    struct stat st;
    char resp[128];
    if (stat(CP_PATH, &st) == 0) {
        snprintf(resp, sizeof(resp), "OK pushed %ld bytes\n", (long)st.st_size);
    } else {
        snprintf(resp, sizeof(resp), "OK pushed (stat fail)\n");
    }
    reply(fd, resp);
    return 0;
}

static void cmd_start(int fd) {
    /* 先杀掉旧的，再启动新的 */
    system("killall dashcastd 2>/dev/null");
    usleep(100000);

    const char *cmd =
        "XDG_RUNTIME_DIR=/etc/weston/weston_xdg_runtime_dir "
        "WAYLAND_DISPLAY=wayland-1 "
        "nohup " CP_PATH " >/dev/null 2>&1 &";
    int r = system(cmd);
    (void)r;
    reply(fd, "OK started\n");
}

static void cmd_stop(int fd) {
    system("killall dashcastd 2>/dev/null");
    reply(fd, "OK stopped\n");
}

static void cmd_status(int fd) {
    /* 检查进程是否在跑 */
    char resp[256];
    int running = 0;
    FILE *fp = popen("pidof dashcastd", "r");
    if (fp) {
        char line[64];
        if (fgets(line, sizeof(line), fp) && line[0] != '\0') {
            running = 1;
        }
        pclose(fp);
    }

    struct stat st;
    long sz = -1;
    if (stat(CP_PATH, &st) == 0) sz = (long)st.st_size;

    snprintf(resp, sizeof(resp), "STATUS running=%d size=%ld\n", running, sz);
    reply(fd, resp);
}

static void handle_client(int fd) {
    char *line = read_line(fd);
    if (!line) {
        close(fd);
        return;
    }

    if (strcmp(line, "PING") == 0) {
        reply(fd, "PONG\n");
    } else if (strncmp(line, "PUSH ", 5) == 0) {
        long size = atol(line + 5);
        cmd_push(fd, size);
    } else if (strcmp(line, "START") == 0) {
        cmd_start(fd);
    } else if (strcmp(line, "STOP") == 0) {
        cmd_stop(fd);
    } else if (strcmp(line, "STATUS") == 0) {
        cmd_status(fd);
    } else {
        reply(fd, "ERR unknown\n");
    }

    free(line);
    close(fd);
}

int main(void) {
    /* 忽略 SIGPIPE，避免写入已关连接时被杀 */
    signal(SIGPIPE, SIG_IGN);
    /* 忽略 SIGCHLD，避免僵尸进程 */
    signal(SIGCHLD, SIG_IGN);

    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) {
        perror("socket");
        return 1;
    }

    int one = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(PORT);

    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind");
        return 1;
    }
    if (listen(srv, 8) < 0) {
        perror("listen");
        return 1;
    }

    fprintf(stderr, "dashcmd listening on %d\n", PORT);

    for (;;) {
        int cli = accept(srv, NULL, NULL);
        if (cli < 0) {
            if (errno == EINTR) continue;
            perror("accept");
            continue;
        }
        /* fork 处理，主进程继续 accept */
        pid_t pid = fork();
        if (pid == 0) {
            close(srv);
            handle_client(cli);
            _exit(0);
        }
        close(cli);
    }
    return 0;
}
