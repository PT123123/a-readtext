#!/bin/bash
# Just命令功能测试脚本

echo "🧪 Just命令功能测试"
echo "===================="

# 测试1: 检查just是否安装
echo "📋 测试1: 检查Just是否安装"
if command -v just &> /dev/null; then
    echo "✅ Just已安装: $(just --version)"
else
    echo "❌ Just未安装，请先安装Just命令"
    echo "💡 安装方法:"
    echo "   Windows: winget install casey.just"
    echo "   macOS: brew install just"
    echo "   Linux: cargo install just"
    exit 1
fi

# 测试2: 检查justfile是否存在
echo ""
echo "📋 测试2: 检查justfile是否存在"
if [ -f "justfile" ]; then
    echo "✅ justfile存在"
else
    echo "❌ justfile不存在"
    exit 1
fi

# 测试3: 检查justfile语法
echo ""
echo "📋 测试3: 检查justfile语法"
if just --list &> /dev/null; then
    echo "✅ justfile语法正确"
    echo "📋 可用命令:"
    just --list | grep "^    "
else
    echo "❌ justfile语法错误"
    exit 1
fi

# 测试4: 检查gradlew是否存在
echo ""
echo "📋 测试4: 检查Gradle wrapper"
if [ -f "gradlew" ]; then
    echo "✅ gradlew存在"
    chmod +x gradlew
else
    echo "⚠️  gradlew不存在，某些命令可能无法执行"
fi

# 测试5: 检查Android SDK配置
echo ""
echo "📋 测试5: 检查Android SDK配置"
if [ -f "local.properties" ]; then
    if grep -q "sdk.dir=" local.properties; then
        SDK_PATH=$(grep "sdk.dir=" local.properties | cut -d'=' -f2)
        echo "✅ Android SDK路径已配置: $SDK_PATH"
        if [ -d "$SDK_PATH" ]; then
            echo "✅ Android SDK目录存在"
        else
            echo "❌ Android SDK目录不存在: $SDK_PATH"
        fi
    else
        echo "❌ local.properties中未找到sdk.dir配置"
    fi
else
    echo "❌ local.properties文件不存在"
fi

# 测试6: 检查ADB是否可用
echo ""
echo "📋 测试6: 检查ADB"
if command -v adb &> /dev/null; then
    echo "✅ ADB已安装: $(adb version | head -1)"
    DEVICE_COUNT=$(adb devices | grep "device$" | wc -l)
    if [ "$DEVICE_COUNT" -gt 0 ]; then
        echo "✅ 已连接 $DEVICE_COUNT 个设备"
        adb devices -l
    else
        echo "⚠️  未找到已连接的设备"
    fi
else
    echo "❌ ADB未安装"
fi

# 测试7: 测试help命令
echo ""
echo "📋 测试7: 测试help命令"
if just help &> /dev/null; then
    echo "✅ help命令正常工作"
else
    echo "❌ help命令执行失败"
fi

# 测试8: 测试devices命令
echo ""
echo "📋 测试8: 测试devices命令"
if just devices &> /dev/null; then
    echo "✅ devices命令正常工作"
else
    echo "⚠️  devices命令执行失败（可能未连接设备）"
fi

# 测试9: 检查项目结构
echo ""
echo "📋 测试9: 检查项目结构"
REQUIRED_DIRS=("app/src/main/java" "app/src/main/res" "app/build.gradle.kts")
ALL_EXIST=true

for dir in "${REQUIRED_DIRS[@]}"; do
    if [ -d "$dir" ]; then
        echo "✅ $dir 存在"
    else
        echo "❌ $dir 不存在"
        ALL_EXIST=false
    fi
done

if [ "$ALL_EXIST" = true ]; then
    echo "✅ 项目结构完整"
else
    echo "⚠️  项目结构不完整"
fi

# 总结
echo ""
echo "🎉 测试完成！"
echo ""
echo "📊 可用命令摘要:"
echo "   just build [debug|release]     - 构建APK"
echo "   just install [debug|release]   - 安装到设备"
echo "   just run [debug|release]       - 构建+安装+运行"
echo "   just clean                     - 清理构建"
echo "   just logcat                    - 查看日志"
echo "   just devices                   - 查看设备"
echo "   just help                      - 显示帮助"
echo ""
echo "💡 快速开始:"
echo "   just build && just install && just run"
echo ""
echo "📚 详细文档: JUST_USAGE.md"