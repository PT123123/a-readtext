#!/usr/bin/env bash
# 输出当前环境可用的 adb 命令。
# 优先 adb.exe（Windows / WSL interop 下能看到 Windows 连接的设备），否则 adb。
# 可用环境变量 ADB 直接覆盖（如 just --set ADB=...）。
set -euo pipefail

if [[ -n "${ADB:-}" ]]; then
  printf '%s\n' "$ADB"
  exit 0
fi

if command -v adb.exe >/dev/null 2>&1; then
  printf '%s\n' "adb.exe"
elif command -v adb >/dev/null 2>&1; then
  printf '%s\n' "adb"
else
  printf '%s\n' "adb"
fi
