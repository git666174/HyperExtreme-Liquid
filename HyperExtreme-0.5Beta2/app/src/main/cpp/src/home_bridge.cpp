// home_bridge.cpp —— HyperExtreme「系统桌面桥」（libxposed API 102 native 入口）
//
// 设计口径见 re-home/plan_A_bridge_checklist.md。本文件为自研实现；
// HyperChanger（AGPL 标注）仅作机制参照，未逐字取用。
//
// 分阶段编译（BRIDGE_STAGE）：
//   STAGE 1：装载自证 + 进程门控 + 目标库探测（历史阶段，保留）。
//   STAGE 2（默认，当前）：getter 捕获 + T3 边沿 → 桥（code7）+ 掉钩自愈（health 线程）。
//
// 激活门（7748 起真机核实，8583 复核一致）：
//   - usap64 父进程：cmdline = "usap64"；launcher 子进程：cmdline = "com.miui.home"；
//     两者 /proc/self/exe 均为 /system_ext/bin/hyos_spawner —— **cmdline 是唯一权威判据**。
//   - pre-fork（usap64）阶段：绝不创建线程、绝不碰目标库；重活一律等激活后做。
//
// 地址表（libapp_launcher.so；全部 fail-closed 入口字节校验）：
//   **当前目标 = 桌面 8.01.02.8583（2026-10-19）**；每版只改下面 4 个 kRva* 常量。
//
//   | 挂点 | 7748 | 8583 |
//   | --- | --- | --- |
//   | T3   update_fsg_gesture_region_state_with_source | 0x6aee28 | 0x6b6544 |
//   | getter get_system_ui_proxy_wrapper                | 0x8f4d10 | 0x900b98 |
//   | uvis LauncherUiVisibilityChannel::on_visibility_changed | 0x6fd7a4 | 0x7066b0 |
//   | action BpSystemUiProxy::onOverviewShown           | 0x987a64 | 0x994848 |
//
//   触发 T3（update_fsg_gesture_region_state_with_source）
//     —— a1 = **场景路由变化标志**，不是「进多任务」。真机 2026-10-17 实测：进文件夹同样是 1
//       （其来源是 Dart 侧 `LoggingNavigatorObserver._notifyRouteToRust`——任何路由变化都会走这里；
//        参数 a3/a6/source 字符串、调用点 x30 均无法区分，实证三路皆败）。
//       用途收窄为：**边沿 0（v=0）⇒ 无条件清位**（覆盖无 toHome 广播的退出路径）；
//       边沿 1（v=1）⇒ 只作兜底，SystemUI 侧还要求近期收到过 `toRecents` 广播才真正设位。
//   兜底 uvis on_visibility_changed —— 「桌面 UI 可见性」，visible=0（场景交给 App）
//       ⇒ 强制清位。覆盖「从文件夹打开应用」这条 T3 永不归零、位会永久卡住的路径。
//   捕获 getter get_system_ui_proxy_wrapper（返回 ArcInner；wrapper=arc+0x10）
//   动作 BpSystemUiProxy::onOverviewShown（fn(self, bool) → transact code 7）
//   （T1/T2 触发候选已实测否决、T4/T5 探针已清理 —— 见《04》v1.1.0 条目）
//
// ★ 精确判据不在 native 侧，而在 SystemUI 侧的系统广播
//   `com.miui.fullscreen_state_change` 的 `state` extra（`toRecents` / `toHome`）——
//   桌面 Dart 主动发出、只反映真实全屏切换；「进文件夹」完全不发。见 RecentsStatusBar.kt。
//
// ⚠⚠ **Dart AOT 函数一律不可用 libxposed hook_func 打入口**（v1.2.1 实测崩，5/5 tombstone 一致）：
//   Dart AOT 的调用约定把 **x15 当自己的栈帧指针**，而 AAPCS64 里 x15 = IP1 是 caller-saved 临时寄存器。
//   libxposed 会把入口 prologue 搬进 trampoline 再执行，但 trampoline 是 C 代码 —— 在搬到之前
//   **x15 已经被当成临时寄存器踩成垃圾**。于是搬过来的
//     `stp x29, x30, [x15, #-0x10]!` / `mov x29, x15` / `sub x15, x15, #0x48`
//   往 `[垃圾 − 0x10]` 写 → SIGSEGV write。
//   实锤：fault addr 0x1312a355e95 == x15(0x1312a355ea5) − 0x10，一字节不差；
//   崩溃帧 #00 = <anonymous trampoline>、#01 = 桌面的 Dart AOT 帧（onChangeToHoldState）。
//   ⇒ 本模块**只 hook libapp_launcher.so 的 Rust 函数**（Rust 用 SP 建栈，跳过 prologue 无害）。
//     要拿 Dart 侧信号只能自己手写 inline patch 裸汇编 stub（压栈→C→恢复 x15→内联 prologue→跳回），
//     详见《99》§99.8，已定案「暂不做」。
//
// ⚠ 分析坑：RVA ≠ 文件偏移。本库 LOAD 段 offset-vaddr 的 delta 依次为
//    0 / -0x4000 / -0x8000 / -0xc000，读静态字节必须先做 rva→offset 换算
//    （否则会读到错误位置、误判 fail-closed 依据）。

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>

#define LOG_TAG "HyperExtremeHome"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#ifndef BRIDGE_STAGE
#define BRIDGE_STAGE 2
#endif

// ---------------------------------------------------------------- libxposed native 入口 ABI
using Hook = int (*)(void *target, void *replacement, void **original);
using Unhook = int (*)(void *handle);
using Loaded = void (*)(const char *name, void *handle);
struct NativeApiEntries {
    uint32_t version;
    Hook hook_func;
    Unhook unhook_func;
};

namespace {

Hook g_hook = nullptr;
Unhook g_unhook = nullptr;
uint32_t g_api_ver = 0;

std::atomic<int> g_state{0};         // 0=待定 1=已激活（桌面进程） 2=无关进程
std::atomic<bool> g_gate_installed{false};
std::atomic<bool> g_app_hooked{false};
std::atomic<int> g_lib_events{0};

uintptr_t g_lib_base = 0;

constexpr const char *kTargetLib = "libapp_launcher.so";
constexpr const char *kHomeCmdline = "com.miui.home";

// ---------------------------------------------------------------- 基础工具
bool read_text_file(const char *path, char *buf, size_t n, ssize_t *out_len) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t r = read(fd, buf, n - 1);
    close(fd);
    if (r <= 0) return false;
    buf[r] = 0;
    if (out_len != nullptr) *out_len = r;
    return true;
}

bool is_home_process() {
    char buf[128];
    if (!read_text_file("/proc/self/cmdline", buf, sizeof(buf), nullptr)) return false;
    return strcmp(buf, kHomeCmdline) == 0;
}

bool is_spawner_exe() {
    char buf[160];
    ssize_t r = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (r <= 0) return false;
    buf[r] = 0;
    return strcmp(buf, "/system_ext/bin/hyos_spawner") == 0;
}

__attribute__((unused)) void log_self_identity(const char *why) {
    char cmd[128] = {0};
    char exe[160] = {0};
    ssize_t l = 0;
    read_text_file("/proc/self/cmdline", cmd, sizeof(cmd), &l);
    l = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (l > 0) exe[l] = 0;
    LOGI("ident(%s): pid=%d cmd=%s exe=%s", why, (int)getpid(), cmd, exe);
}

int phdr_find_cb(struct dl_phdr_info *info, size_t, void *) {
    if (info->dlpi_name != nullptr && strstr(info->dlpi_name, kTargetLib) != nullptr) {
        g_lib_base = (uintptr_t)info->dlpi_addr;
        return 1;
    }
    return 0;
}

uintptr_t find_lib_base() {
    if (g_lib_base != 0) return g_lib_base;
    dl_iterate_phdr(phdr_find_cb, nullptr);
    return g_lib_base;
}

// ---------------------------------------------------------------- 地址表（每版只改这 4 行）
// 目标版本：系统桌面 8.01.02.8583（2026-10-19）。用 work/addr_table.py 生成，勿手抄。
constexpr uint32_t kRvaFsgUpdate = 0x6b6544;  // 7748: 0x6aee28
constexpr uint32_t kRvaGetter = 0x900b98;     // 7748: 0x8f4d10
constexpr uint32_t kRvaUvis = 0x7066b0;       // 7748: 0x6fd7a4
constexpr uint32_t kRvaAction = 0x994848;     // 7748: 0x987a64

// ---------------------------------------------------------------- fail-closed 入口字节
// 7748 → 8583 四个函数入口前 3 字逐字节相同（只换 RVA 即可），故这里不分版本。
struct Bytes3 {
    uint32_t a, b, c;
};
constexpr Bytes3 kB_fsgUpdate = {0xd104c3ff, 0xa90d7bfd, 0xa90e6ffc};     // kRvaFsgUpdate
constexpr Bytes3 kB_getter = {0xa9be7bfd, 0xa9014ff4, 0x910003fd};        // kRvaGetter
constexpr Bytes3 kB_actionFn = {0xd10143ff, 0xa9027bfd, 0xa90357f6};      // kRvaAction
// 兜底：recents::launcher_ui_visibility_channel::on_visibility_changed(bool)
constexpr Bytes3 kB_uvis = {0xa9be7bfd, 0xf9000bf3, 0x910003fd};          // kRvaUvis

bool verify_bytes(uintptr_t addr, const Bytes3 &b) {
    const volatile uint32_t *p = reinterpret_cast<const volatile uint32_t *>(addr);
    return p[0] == b.a && p[1] == b.b && p[2] == b.c;
}

// ---------------------------------------------------------------- 单调时钟（自愈退避计时用）
int64_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// ================================================================ 挂点页钉固（掉钩自愈用）
// 前置声明：装钩处与自愈段共用。
void pin_page(uintptr_t addr, const char *name);

#if BRIDGE_STAGE >= 2
// ================================================================ STAGE 2 状态
std::atomic<int> g_comb{0};        // 上一次「场景路由变化」标志（0 起始：首个非变化不报边沿）
std::atomic<uintptr_t> g_last_arc{0};
std::atomic<bool> g_cap_logged{false};
std::atomic<int> g_bridge_calls{0};
void *g_self_word = nullptr;       // 传给 onOverviewShown 的 self：fn 仅读 [self] = binder

void *(*orig_getter)() = nullptr;

using Fn8 = void (*)(uint64_t, uint64_t, uint64_t, uint64_t,
                     uint64_t, uint64_t, uint64_t, uint64_t);
Fn8 orig_fsg = nullptr;
void (*orig_uvis)(uint64_t) = nullptr;   // on_visibility_changed(bool)

// ---------------------------------------------------------------- 桥动作
// payload 末字节（int32 的低字节）传三种值：
//   0 = 退出多任务 / 桌面 UI 交出（清位）
//   1 = 进入多任务（原有语义）
//   2 = **桌面 UI 重新可见**（v1.2.15 新增）—— 用户又能看到桌面了 ⇒ 绝不该藏着状态栏。
//       SystemUI 侧据此过滤「BACK 回文件夹时桌面迟发的 toRecents 误广播」。
void send_bridge(int value) {
    void *binder = __atomic_load_n(&g_self_word, __ATOMIC_RELAXED);
    if (binder == nullptr) {
        LOGI("bridge: no binder captured yet, skip value=%d", value);
        return;
    }
    if (g_lib_base == 0) return;
    uintptr_t fnaddr = g_lib_base + kRvaAction;
    if (!verify_bytes(fnaddr, kB_actionFn)) {
        LOGI("bridge: FAIL-CLOSED action fn bytes mismatch, skip");
        return;
    }
    using Fn = int (*)(void *, uint8_t);
    Fn fn = reinterpret_cast<Fn>(fnaddr);
    int rc = fn(reinterpret_cast<void *>(&g_self_word), static_cast<uint8_t>(value));
    int n = ++g_bridge_calls;
    LOGI("bridge: code7 sent value=%d rc=0x%08x n=%d", value, (unsigned)rc, n);
}

// 触发（T3）：`update_fsg_gesture_region_state_with_source` 的 a1 = **场景路由变化标志**
// （不是"进多任务"！真机 2026-10-17 实测：进文件夹同样是 1，其真实来源是桌面 Dart 侧
//   `LoggingNavigatorObserver._notifyRouteToRust`——任何路由变化都会走这里）。
// 边沿 → 桥 sent=false 无条件清位（可靠）；sent=true 只作兜底（SystemUI 侧还要求近期收到过
// `toRecents` 广播才真正设位，见 RecentsStatusBar）。日志带调用点 x30 的 RVA，便于跟版诊断。
void my_fsg_update(uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3,
                   uint64_t a4, uint64_t a5, uint64_t a6, uint64_t a7) {
    uint64_t lr = 0;
    asm volatile("mov %0, x30" : "=r"(lr));
    int v = (a1 != 0) ? 1 : 0;
    int prev = g_comb.exchange(v);
    if (prev != v) {
        LOGI("ROUTE edge -> %d (rva=0x%llx)", v,
             (unsigned long long)(lr - g_lib_base));
        send_bridge(v);
    }
    if (orig_fsg != nullptr) orig_fsg(a0, a1, a2, a3, a4, a5, a6, a7);
}

// 桌面 UI 可见性通道（`recents::launcher_ui_visibility_channel::on_visibility_changed`）——
// **兜底信号**（v1.1.4）。T3 那条链是「任何路由变化」，从文件夹打开应用时它不会归零、位会永久卡住；
// 而本信号精确表达「桌面是否还占着屏幕」：
//   visible=0（桌面 UI 不可见 = 场景已交给 App）→ 必须还原状态栏，发 value=0；
//   visible=1（桌面 UI 可见）→ 用户又能看到桌面了 ⇒ **绝不该藏着状态栏**，发 value=2
//     （v1.2.15 新增：SystemUI 侧据此过滤「BACK 回文件夹时桌面迟发 toRecents」的误广播）。
// 实测（2026-10-17）：文件夹内点计算器 → visible=0；回桌面 → visible=1；
//                   APP_SWITCH 进多任务 → visible=0；BACK 回文件夹 → visible=1。
void my_uvis(uint64_t visible) {
    int hidden = (visible == 0) ? 1 : 0;
    LOGI("UVIS visible=%llu%s", (unsigned long long)visible,
         hidden ? " -> force clear (desktop handed off)" : " -> desktop visible");
    if (orig_uvis != nullptr) orig_uvis(visible);
    send_bridge(hidden ? 0 : 2);   // 0 = 交出屏幕（清位）；2 = 桌面重新可见
}

// ---------------------------------------------------------------- 捕获（getter 返回值）
void capture(void *arcp) {
    uintptr_t arc = (uintptr_t)arcp;
    if (g_last_arc.load(std::memory_order_relaxed) == arc) return;
    uintptr_t prev = g_last_arc.exchange(arc);
    if (prev == arc) return;
    // 新 arc：+1 引脚（防 SystemUI 重启 reinit 后旧 wrapper 悬垂；每次 reinit 泄漏 1 个计数量级，可接受）
    __atomic_fetch_add(reinterpret_cast<uint64_t *>(arc), 1, __ATOMIC_RELAXED);
    void *handle = *reinterpret_cast<void **>(arc + 0x10);
    if (handle == nullptr) return;
    void *binder = *reinterpret_cast<void **>(handle);
    if (binder == nullptr) return;
    __atomic_store_n(&g_self_word, binder, __ATOMIC_RELEASE);
    if (!g_cap_logged.exchange(true)) {
        LOGI("capture: arc=%p handle=%p binder=%p", (void *)arc, handle, binder);
    }
}

void *my_getter() {
    void *arc = (orig_getter != nullptr) ? orig_getter() : nullptr;
    if (arc != nullptr) capture(arc);
    return arc;
}

// ---------------------------------------------------------------- 掉钩自愈（health）
// 机制（2026-10-17 实测实锤）：launcher 运行中会回收/丢弃自己库的 file-backed 补丁页（内核回收或
// 运行时 MADV_DONTNEED），COW 页被丢弃后从 APK 重读 → 原始字节回归、钩子静默失效；且框架会
// 拒绝重复 hook（实测 rc=1）。HyperChanger 的 page_guard 记载同现象（"file-backed images'
// trampoline pages can be reclaimed and refilled"）。两层防线：
//   ① 预防：装钩前后 mlock 钉固挂点页 —— 内核回收与 MADV_DONTNEED 都会跳过 locked 页；
//   ② 自愈：每 5s 巡检。补丁丢失（回到原始字节）时优先把安装时快照的"已补丁 16 字节"直接
//      写回（框架 trampoline 在匿名内存、回收不掉，回写即恢复）；无快照再 unhook(target)+重装。
std::atomic<bool> g_health_started{false};
std::atomic<bool> g_getter_ok{false};   // 只对"装成功过"的挂点做自愈（防 fail-closed 滑过时的日志刷屏）
std::atomic<bool> g_fsg_ok{false};
std::atomic<bool> g_uvis_ok{false};
std::atomic<bool> g_getter_snap_ok{false};
std::atomic<bool> g_fsg_snap_ok{false};
std::atomic<bool> g_uvis_snap_ok{false};
uint32_t g_getter_snap[4] = {0, 0, 0, 0};
uint32_t g_fsg_snap[4] = {0, 0, 0, 0};
uint32_t g_uvis_snap[4] = {0, 0, 0, 0};
int64_t g_getter_next_try = 0;
int64_t g_fsg_next_try = 0;
int64_t g_uvis_next_try = 0;

void pin_page(uintptr_t addr, const char *name) {
    long ps = sysconf(_SC_PAGESIZE);
    uintptr_t page = addr & ~(uintptr_t)(ps - 1);
    int rc = mlock(reinterpret_cast<void *>(page), static_cast<size_t>(ps));
    LOGI("pin %s @%p mlock rc=%d errno=%d", name, reinterpret_cast<void *>(page), rc,
         rc == 0 ? 0 : errno);
}

void read16(uintptr_t addr, uint32_t out[4]) {
    const volatile uint32_t *p = reinterpret_cast<const volatile uint32_t *>(addr);
    out[0] = p[0]; out[1] = p[1]; out[2] = p[2]; out[3] = p[3];
}

// 安装后快照：只在"当前 ≠ 原始"（补丁确实在位）时采，防把未补丁态存成快照。
bool try_snapshot(uintptr_t addr, const Bytes3 &o, uint32_t snap[4],
                  std::atomic<bool> &ok, const char *name) {
    if (ok.load(std::memory_order_acquire)) return true;
    uint32_t cur[4];
    read16(addr, cur);
    if (cur[0] == o.a && cur[1] == o.b && cur[2] == o.c) return false;
    snap[0] = cur[0]; snap[1] = cur[1]; snap[2] = cur[2]; snap[3] = cur[3];
    ok.store(true, std::memory_order_release);
    LOGI("health: snapshot %s = %08x %08x %08x %08x", name, cur[0], cur[1], cur[2], cur[3]);
    return true;
}

// 回写 16 字节快照：mprotect RWX → 后半/前半各一次 8 字节原子写（入口两字原子落位）→ 恢复 R-X。
bool restore_snapshot(uintptr_t addr, const uint32_t snap[4]) {
    long ps = sysconf(_SC_PAGESIZE);
    uintptr_t page = addr & ~(uintptr_t)(ps - 1);
    if (mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(ps),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGI("health: mprotect RWX failed errno=%d", errno);
        return false;
    }
    uint64_t lo = (static_cast<uint64_t>(snap[1]) << 32) | snap[0];
    uint64_t hi = (static_cast<uint64_t>(snap[3]) << 32) | snap[2];
    __atomic_store_n(reinterpret_cast<uint64_t *>(addr + 8), hi, __ATOMIC_RELEASE);
    __atomic_store_n(reinterpret_cast<uint64_t *>(addr), lo, __ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char *>(addr), reinterpret_cast<char *>(addr + 16));
    mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(ps), PROT_READ | PROT_EXEC);
    uint32_t cur[4];
    read16(addr, cur);
    return cur[0] == snap[0] && cur[1] == snap[1] && cur[2] == snap[2] && cur[3] == snap[3];
}

// ---------------------------------------------------------------- 挂钩
bool install_one(uintptr_t base, uint32_t rva, const Bytes3 &bytes,
                 void *repl, void **orig, const char *name,
                 uint32_t *snap, std::atomic<bool> *snap_ok) {
    uintptr_t addr = base + rva;
    if (!verify_bytes(addr, bytes)) {
        LOGI("FAIL-CLOSED %s @%p bytes mismatch, skipped", name, (void *)addr);
        return false;
    }
    pin_page(addr, name);   // 先钉页再打补丁：关死"打补丁-被回收"竞态
    int rc = g_hook(reinterpret_cast<void *>(addr), repl, orig);
    LOGI("hook %s @%p rc=%d orig=%p", name, (void *)addr, rc, *orig);
    bool ok = rc == 0 && *orig != nullptr;
    if (ok && snap != nullptr && snap_ok != nullptr) {
        try_snapshot(addr, bytes, snap, *snap_ok, name);
    }
    return ok;
}

// 单挂点巡检：健康（非原始字节）→ 顺带补采快照；丢失 → 快照回写优先、unhook+重装兜底。
void heal_site(uintptr_t addr, const Bytes3 &o, void *repl, void **orig_fn,
               uint32_t snap[4], std::atomic<bool> &snap_ok, std::atomic<bool> &ok_flag,
               int64_t &next_try, const char *name) {
    if (!ok_flag.load(std::memory_order_relaxed)) return;
    if (!verify_bytes(addr, o)) {
        try_snapshot(addr, o, snap, snap_ok, name);
        return;
    }
    int64_t now = now_ms();
    if (now < next_try) return;
    if (snap_ok.load(std::memory_order_acquire) && restore_snapshot(addr, snap)) {
        pin_page(addr, name);
        LOGI("health: %s lost -> healed (snapshot restore)", name);
        return;
    }
    if (g_unhook != nullptr) {
        int urc = g_unhook(reinterpret_cast<void *>(addr));
        LOGI("health: %s unhook rc=%d", name, urc);
    }
    int rc = g_hook(reinterpret_cast<void *>(addr), repl, orig_fn);
    LOGI("health: %s re-hook rc=%d orig=%p", name, rc, *orig_fn);
    if (rc == 0 && *orig_fn != nullptr && !verify_bytes(addr, o)) {
        pin_page(addr, name);
        try_snapshot(addr, o, snap, snap_ok, name);
        LOGI("health: %s re-armed OK", name);
        return;
    }
    LOGI("health: %s heal failed (retry in 60s)", name);
    next_try = now + 60000;
}

void* health_main(void *) {
    while (true) {
        sleep(5);
        if (g_state.load(std::memory_order_relaxed) != 1) continue;
        if (!g_app_hooked.load(std::memory_order_relaxed)) continue;
        uintptr_t base = g_lib_base;
        if (base == 0) continue;
        heal_site(base + kRvaGetter, kB_getter, (void *)my_getter, (void **)&orig_getter,
                  g_getter_snap, g_getter_snap_ok, g_getter_ok, g_getter_next_try, "getter");
        heal_site(base + kRvaFsgUpdate, kB_fsgUpdate, (void *)my_fsg_update, (void **)&orig_fsg,
                  g_fsg_snap, g_fsg_snap_ok, g_fsg_ok, g_fsg_next_try, "fsg_update");
        heal_site(base + kRvaUvis, kB_uvis, (void *)my_uvis, (void **)&orig_uvis,
                  g_uvis_snap, g_uvis_snap_ok, g_uvis_ok, g_uvis_next_try, "uvis");
    }
    return nullptr;
}

void start_health() {
    if (g_health_started.exchange(true)) return;
    pthread_t t;
    if (pthread_create(&t, nullptr, health_main, nullptr) == 0) {
        pthread_detach(t);
        LOGI("health: thread started (5s period)");
    } else {
        g_health_started.store(false);
        LOGI("health: thread create failed");
    }
}

void install_app_hooks(uintptr_t base) {
    LOGI("install app hooks @ base=%p", (void *)base);
    g_getter_ok.store(install_one(base, kRvaGetter, kB_getter, (void *)my_getter,
                                  (void **)&orig_getter, "getter", g_getter_snap, &g_getter_snap_ok));
    g_fsg_ok.store(install_one(base, kRvaFsgUpdate, kB_fsgUpdate, (void *)my_fsg_update,
                               (void **)&orig_fsg, "fsg_update", g_fsg_snap, &g_fsg_snap_ok));
    g_uvis_ok.store(install_one(base, kRvaUvis, kB_uvis, (void *)my_uvis,
                                (void **)&orig_uvis, "uvis", g_uvis_snap, &g_uvis_snap_ok));
    start_health();
}
#endif // BRIDGE_STAGE >= 2

// ---------------------------------------------------------------- 激活与挂钩编排
void maybe_hook_lib() {
    if (g_app_hooked.load(std::memory_order_acquire)) return;
    uintptr_t b = find_lib_base();
    if (b == 0) return;  // 目标库还没到；后续触发会重试
    bool expected = false;
    if (!g_app_hooked.compare_exchange_strong(expected, true)) return;
    g_lib_base = b;
    LOGI("target lib base=%p", (void *)b);
#if BRIDGE_STAGE >= 2
    install_app_hooks(b);
#else
    LOGI("STAGE1 idle: app hooks not installed (probe build)");
#endif
}

void try_activate(const char *why) {
    if (g_state.load(std::memory_order_acquire) == 0) {
        if (!is_home_process()) return;
        int expected = 0;
        if (!g_state.compare_exchange_strong(expected, 1)) return;
        LOGI("ACTIVATED (why=%s, pid=%d, api_v=%u)", why, (int)getpid(), g_api_ver);
    }
    maybe_hook_lib();
}

// ---------------------------------------------------------------- 门控钩子（spawner/home 才装）
void (*orig_setprogname)(const char *) = nullptr;
int (*orig_property_get)(const char *, char *) = nullptr;

void my_setprogname(const char *name) {
    if (orig_setprogname != nullptr) orig_setprogname(name);
    if (name != nullptr && strcmp(name, kHomeCmdline) == 0) try_activate("setprogname");
}

int my_property_get(const char *name, char *value) {
    try_activate("property_get");
    return orig_property_get != nullptr ? orig_property_get(name, value) : 0;
}

// ---------------------------------------------------------------- 库装载回调
void loaded_cb(const char *name, void *) {
    if (name != nullptr && strstr(name, kTargetLib) != nullptr) {
        LOGI("lib loaded: %s (state=%d)", name, g_state.load(std::memory_order_relaxed));
        try_activate("target_lib");
    } else if (g_state.load(std::memory_order_relaxed) == 1 &&
               !g_app_hooked.load(std::memory_order_relaxed)) {
        maybe_hook_lib();
    }
}

}  // namespace

extern "C" [[gnu::visibility("default")]] __attribute__((used))
Loaded native_init(const NativeApiEntries *api) {
    if (api == nullptr || api->hook_func == nullptr) return nullptr;
    g_api_ver = api->version;
    g_hook = api->hook_func;
    g_unhook = api->unhook_func;

    bool home = is_home_process();
    bool spawner = is_spawner_exe();
    LOGI("native_init: api_v=%u pid=%d home=%d spawner_exe=%d",
         g_api_ver, (int)getpid(), (int)home, (int)spawner);

    // 无关进程（aod / systemui 等只是因为模块作用域而被装载）：保持零足迹。
    if (!home && !spawner) {
        LOGI("native_init: inert in this process");
        return nullptr;
    }

    // 门控钩子：既覆盖 pre-fork spawner（fork 前装载），也覆盖直接装载进桌面的情况。
    if (!g_gate_installed.exchange(true)) {
        uintptr_t p = (uintptr_t)dlsym(RTLD_DEFAULT, "setprogname");
        if (p != 0) {
            int rc = g_hook((void *)p, (void *)my_setprogname, (void **)&orig_setprogname);
            LOGI("gate hook setprogname rc=%d orig=%p", rc, (void *)orig_setprogname);
        }
        p = (uintptr_t)dlsym(RTLD_DEFAULT, "__system_property_get");
        if (p != 0) {
            int rc = g_hook((void *)p, (void *)my_property_get, (void **)&orig_property_get);
            LOGI("gate hook __system_property_get rc=%d orig=%p", rc, (void *)orig_property_get);
        }
    }

    try_activate("native_init");
    return loaded_cb;
}
