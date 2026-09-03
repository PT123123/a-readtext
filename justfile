# Android 应用 - Just 命令文件
# 使用方法: just [command]

# adb 命令（自动解析：Windows/WSL 下优先 adb.exe；可用 --set ADB=... 覆盖）
ADB := `bash scripts/resolve_adb.sh`

# APK 路径
DEBUG_APK := "app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK := "app/build/outputs/apk/release/app-release.apk"

# 动态探测已连接设备序列号
# scripts/pick_phone.sh 挑手机（按型号/特性排除平板），pick_tab.sh 挑平板
PHONE_SERIAL := `bash scripts/pick_phone.sh`
TAB_SERIAL := `bash scripts/pick_tab.sh`

# 构建项目 (debug|release)
[script]
build type="debug":
    if [ "{{type}}" = "release" ]; then
        task="assembleRelease"
        apk="{{RELEASE_APK}}"
    else
        task="assembleDebug"
        apk="{{DEBUG_APK}}"
    fi
    ./gradlew "$task"
    echo "✅ 构建完成: $apk"

# 安装到手机（动态序列号，自动排除平板）
install-phone:
    @test -n "{{PHONE_SERIAL}}" || (echo "未检测到手机设备（已按型号排除平板）…"; exit 1)
    {{ADB}} -s {{PHONE_SERIAL}} install -r "{{DEBUG_APK}}"

# 安装到平板（动态序列号）
install-tab:
    @test -n "{{TAB_SERIAL}}" || (echo "未检测到平板设备…"; exit 1)
    {{ADB}} -s {{TAB_SERIAL}} install -r "{{DEBUG_APK}}"

# 构建 + 安装到手机
[script]
bi type="debug":
    if [ "{{type}}" = "release" ]; then
        task="assembleRelease"
        apk="{{RELEASE_APK}}"
    else
        task="assembleDebug"
        apk="{{DEBUG_APK}}"
    fi
    ./gradlew "$task"
    if [ -z "{{PHONE_SERIAL}}" ]; then echo "未检测到手机设备（已按型号排除平板）…"; exit 1; fi
    {{ADB}} -s {{PHONE_SERIAL}} install -r "$apk"

# 清理构建
clean:
    @./gradlew clean

# 查看设备
devices:
    @{{ADB}} devices -l

# 查看日志
logcat:
    @{{ADB}} logcat | grep "AReadText\|SherpaStreamingAsr\|TranscriptionService"

# 清除应用数据
clear-data:
    @{{ADB}} shell pm clear com.example.areadtext

# 卸载应用
uninstall:
    @{{ADB}} uninstall com.example.areadtext

# 截图
screenshot file="screenshot.png":
    @{{ADB}} shell screencap -p /sdcard/{{file}} && {{ADB}} pull /sdcard/{{file}} . && {{ADB}} shell rm /sdcard/{{file}}

# 显示帮助
help:
    @echo "🛠️ Android 应用 - Just 命令"
    @echo ""
    @echo "  just build [debug|release]   构建APK"
    @echo "  just install-phone           安装到手机（自动排除平板）"
    @echo "  just install-tab             安装到平板"
    @echo "  just bi [debug|release]      构建+安装到手机"
    @echo "  just clean                   清理构建"
    @echo "  just devices                 查看设备"
    @echo "  just logcat                  查看日志"
    @echo "  just clear-data              清除应用数据"
    @echo "  just uninstall               卸载应用"
    @echo "  just screenshot [file]       截图"
    @echo ""
    @echo "示例:"
    @echo "  just build          # 构建debug版本"
    @echo "  just build release  # 构建release版本"
    @echo "  just install-phone  # 安装到手机"
    @echo "  just install-tab    # 安装到平板"

# 默认命令
default:
    @just --list
