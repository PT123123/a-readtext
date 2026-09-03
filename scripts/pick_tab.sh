#!/usr/bin/env bash
# 从已连接的 adb 设备中挑选一台「平板」，输出其序列号。
# 判定：ro.build.characteristics == "tablet" 为平板，其余（nosdcard/default/...）均为手机。
# 找不到时静默输出空并退出 0（由 justfile 中的 test 提示用户）。
set -euo pipefail

ADB_CMD="${ADB:-$(bash "$(dirname "$0")/resolve_adb.sh")}"

is_tablet() {
  local serial="$1" chars
  chars="$("$ADB_CMD" -s "$serial" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')"
  [[ "$chars" == "tablet" ]]
}

"$ADB_CMD" start-server >/dev/null 2>&1 || true

mapfile -t serials < <("$ADB_CMD" devices 2>/dev/null | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')

for s in "${serials[@]:-}"; do
  if is_tablet "$s"; then
    printf '%s\n' "$s"
    exit 0
  fi
done

exit 0
