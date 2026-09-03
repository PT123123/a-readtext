#!/usr/bin/env bash
# 从已连接的 adb 设备中挑选一台「平板」，输出其序列号。
# 找不到时静默输出空并退出 0（由 justfile 中的 test 提示用户）。
#
# 平板判定：ro.build.characteristics 含 tablet，或机型名含 tab/pad/tablet 等关键词。
# 可用环境变量 ADB 覆盖 adb 命令路径。
set -euo pipefail

ADB="${ADB:-adb}"

is_tablet() {
  local serial="$1" chars model
  chars="$("$ADB" -s "$serial" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')"
  if [[ "$chars" == *tablet* ]]; then
    return 0
  fi
  model="$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  if [[ "$model" =~ [Tt]ab|[Pp]ad|[Tt]ablet|[Mm]atepad|[Ii]Pad ]]; then
    return 0
  fi
  return 1
}

"$ADB" start-server >/dev/null 2>&1 || true

mapfile -t serials < <("$ADB" devices 2>/dev/null | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')

for s in "${serials[@]:-}"; do
  if is_tablet "$s"; then
    printf '%s\n' "$s"
    exit 0
  fi
done

exit 0
