#!/usr/bin/env bash
# 装机 + 同步 LSPosed 的 apk_path + 重启全部作用域。
#
# 为什么需要同步 apk_path：LSPosed 的 modules 表缓存了模块 APK 的安装路径，而
# `adb install -r` 每次都换一个新随机目录 ⇒ 缓存路径失效 ⇒ **桌面进程注入直接失败**
# （表现为 native 侧完全没有日志，极难排查）。2026-10-17 实踩过一次。
#
# 用法：./tools/install_and_scope.sh
set -e
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

APK=$(ls -t "$ROOT"/app/build/outputs/apk/release/*.apk | head -1 | sed "s|^/c/|C:/|")
echo "APK = $APK"
md5sum "$APK"
$ADB install -r "$APK" 2>&1 | tail -1

NEW=$($ADB shell pm path com.frederic22.hyperextreme | sed 's/^package://' | tr -d '\r')
echo "实际路径 = $NEW"
[ -z "$NEW" ] && { echo "!! 取不到安装路径"; exit 1; }

# 拉库 → 改 apk_path → 推回（保留权限 root:root 660）
$ADB shell "su -c 'cp /data/adb/lspd/config/modules_config.db /data/local/tmp/mc_sync.db; chmod 644 /data/local/tmp/mc_sync.db'"
$ADB pull /data/local/tmp/mc_sync.db ./mc_sync.db >/dev/null
cp ./mc_sync.db ./mc_sync_local.db
python - "$NEW" <<'PYEOF'
import sqlite3, sys
p = "./mc_sync_local.db"
c = sqlite3.connect(p)
c.execute("UPDATE modules SET apk_path=? WHERE module_pkg_name='com.frederic22.hyperextreme'", (sys.argv[1],))
c.commit()
print("apk_path ->", [r[0] for r in c.execute(
    "SELECT apk_path FROM modules WHERE module_pkg_name='com.frederic22.hyperextreme'")])
print("scope    ->", [r[0] for r in c.execute(
    "SELECT app_pkg_name FROM scope WHERE module_pkg_name='com.frederic22.hyperextreme'")])
c.close()
PYEOF
$ADB push ./mc_sync_local.db /data/local/tmp/mc_sync_local.db >/dev/null
$ADB shell "su -c 'cp /data/local/tmp/mc_sync_local.db /data/adb/lspd/config/modules_config.db; chmod 660 /data/adb/lspd/config/modules_config.db; chown root:root /data/adb/lspd/config/modules_config.db'"

# 重启全部作用域（usap64 必须连旧的一起杀，否则旧 spawner 不带新库）
$ADB shell "su -c 'pkill -9 -f usap64; pkill -9 -f com.miui.home; killall com.android.systemui'"
sleep 14
$ADB shell "su -c 'input keyevent KEYCODE_HOME'" || true
sleep 8
echo "=== 进程 ==="
$ADB shell "su -c 'ps -A | grep -E \"miui.home|usap64|systemui\"'"
echo "=== 装载确认 ==="
sleep 3
$ADB logcat -d 2>/dev/null | tr -d '\000' | grep -aE "HyperExtremeHome.*(ACTIVATED|hook uvis)" | tail -4
