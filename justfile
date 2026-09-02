# Android语音转写应用 - Just命令文件
# 使用方法: just [command]
# 说明: 命令全部内联执行，不依赖 .bat / .sh 脚本

# 构建项目 (debug|release)
[script]
build type="debug":
    if [ "{{type}}" = "release" ]; then
        task="assembleRelease"
        apk="app/build/outputs/apk/release/app-release.apk"
    else
        task="assembleDebug"
        apk="app/build/outputs/apk/debug/app-debug.apk"
    fi
    ./gradlew "$task"
    echo "✅ 构建完成: $apk"

# 安装APK到设备 (debug|release)，APK缺失时自动构建
[script]
install type="debug":
    if [ "{{type}}" = "release" ]; then
        task="assembleRelease"
        apk="app/build/outputs/apk/release/app-release.apk"
    else
        task="assembleDebug"
        apk="app/build/outputs/apk/debug/app-debug.apk"
    fi
    if ! command -v adb >/dev/null 2>&1; then echo "❌ 未找到 adb 命令，请将 platform-tools 加入 PATH"; exit 1; fi
    if ! adb devices | grep -q "device$"; then echo "❌ 未找到已连接的 Android 设备"; adb devices; exit 1; fi
    if [ ! -f "$apk" ]; then echo "⚠️  APK 不存在，先自动构建"; ./gradlew "$task"; fi
    adb install -r "$apk"

# 构建 + 安装
[script]
bi type="debug":
    if [ "{{type}}" = "release" ]; then
        task="assembleRelease"
        apk="app/build/outputs/apk/release/app-release.apk"
    else
        task="assembleDebug"
        apk="app/build/outputs/apk/debug/app-debug.apk"
    fi
    ./gradlew "$task"
    adb install -r "$apk"

# 清理构建
clean:
    @./gradlew clean

# 查看设备
devices:
    @adb devices -l

# 查看日志
logcat:
    @adb logcat | grep "AReadText\|SherpaStreamingAsr\|TranscriptionService"

# 清除应用数据
clear-data:
    @adb shell pm clear com.example.areadtext

# 卸载应用
uninstall:
    @adb uninstall com.example.areadtext

# 截图
screenshot file="screenshot.png":
    @adb shell screencap -p /sdcard/{{file}} && adb pull /sdcard/{{file}} . && adb shell rm /sdcard/{{file}}

# 显示帮助
help:
    @echo "🚀 Android语音转写应用 - Just命令"
    @echo ""
    @echo "  just build [debug|release]   构建APK"
    @echo "  just install [debug|release] 安装APK到设备"
    @echo "  just bi [debug|release]      构建+安装"
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
    @echo "  just install        # 安装debug APK"
    @echo "  just bi release     # 构建并安装release"

# 默认命令
default:
    @just --list
