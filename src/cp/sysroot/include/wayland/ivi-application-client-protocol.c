/* ivi-application-client-protocol.c — ivi_application 协议的 interface 定义
 * 等价于 wayland-scanner private-code 的输出。
 * 手工生成，对应 ivi-application.xml version 1。
 */
#include <stddef.h>
#include "wayland-util.h"

extern const struct wl_interface wl_surface_interface;

/* ===== ivi_surface ===== */
static const struct wl_message ivi_surface_requests[] = {
	{ "destroy", "", NULL },              /* 无参数 */
};

static const struct wl_message ivi_surface_events[] = {
	{ "configure", "ii", NULL },          /* width(int), height(int) */
};

const struct wl_interface ivi_surface_interface = {
	"ivi_surface", 1,
	1, ivi_surface_requests,
	1, ivi_surface_events,
};

/* ===== ivi_application =====
 * surface_create 的参数类型表：
 *   参数1: ivi_id (uint)     → NULL（基础类型）
 *   参数2: surface (object)  → &wl_surface_interface
 *   参数3: id (new_id)       → &ivi_surface_interface
 */
static const struct wl_interface *ivi_application_surface_create_types[] = {
	NULL,
	&wl_surface_interface,
	&ivi_surface_interface,
};

static const struct wl_message ivi_application_requests[] = {
	{ "surface_create", "uon", ivi_application_surface_create_types },
};

const struct wl_interface ivi_application_interface = {
	"ivi_application", 1,
	1, ivi_application_requests,
	0, NULL,
};
