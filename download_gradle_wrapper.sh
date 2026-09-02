#!/usr/bin/env bash
# 下载 gradle-wrapper.jar 的脚本

echo "📥 下载 Gradle Wrapper JAR 文件..."

# 设置变量
GRADLE_VERSION="8.9"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_DIR="gradle/wrapper"
WRAPPER_JAR="${WRAPPER_DIR}/gradle-wrapper.jar"

# 创建目录
mkdir -p "$WRAPPER_DIR"

# 检查文件是否已存在
if [ -f "$WRAPPER_JAR" ]; then
    echo "✅ gradle-wrapper.jar 已存在"
    ls -lh "$WRAPPER_JAR"
    exit 0
fi

# 尝试下载文件
echo "正在从 $WRAPPER_URL 下载..."

if command -v curl &> /dev/null; then
    curl -L -o "$WRAPPER_JAR" "$WRAPPER_URL"
elif command -v wget &> /dev/null; then
    wget -O "$WRAPPER_JAR" "$WRAPPER_URL"
else
    echo "❌ 未找到 curl 或 wget 命令"
    echo "💡 请手动下载 gradle-wrapper.jar 并放置到 $WRAPPER_DIR/"
    exit 1
fi

# 验证下载
if [ -f "$WRAPPER_JAR" ] && [ -s "$WRAPPER_JAR" ]; then
    echo "✅ 下载成功！"
    ls -lh "$WRAPPER_JAR"
    
    # 测试 gradlew
    if [ -f "gradlew" ]; then
        echo ""
        echo "🧪 测试 Gradle Wrapper..."
        chmod +x gradlew
        ./gradlew --version
    fi
else
    echo "❌ 下载失败或文件为空"
    rm -f "$WRAPPER_JAR"
    exit 1
fi