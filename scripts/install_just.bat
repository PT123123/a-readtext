@echo off
REM Just命令安装和测试脚本 (Windows)

echo ================================================
echo   Just命令安装和测试脚本
echo ================================================
echo.

REM 检查winget是否可用
where winget >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 未找到winget命令
    echo 请手动安装Just: https://github.com/casey/just/releases
    pause
    exit /b 1
)

echo [1/3] 检查Just是否已安装...
where just >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [成功] Just已安装
    for /f "tokens=*" %%i in ('just --version') do echo 版本: %%i
    goto :test_just
)

echo [信息] Just未安装，开始安装...
echo.
echo [2/3] 安装Just命令...
winget install --id Casey.Just -e --accept-source-agreements --accept-package-agreements

if %ERRORLEVEL% NEQ 0 (
    echo [错误] Just安装失败
    echo 请手动下载安装: https://github.com/casey/just/releases
    pause
    exit /b 1
)

echo [成功] Just安装完成

:test_just
echo.
echo [3/3] 测试Just命令...

REM 刷新环境变量
call refreshenv

REM 测试just命令
where just >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [警告] Just命令未在PATH中找到
    echo 请重新打开命令提示符或重启计算机
    pause
    exit /b 1
)

echo.
echo ================================================
echo   Just命令测试结果
echo ================================================
echo.
echo [测试] Just版本:
just --version
echo.

echo [测试] justfile语法检查:
cd /d "%~dp0"
if exist justfile (
    echo [成功] justfile存在
    just --list
) else (
    echo [错误] justfile不存在
    pause
    exit /b 1
)
echo.

echo [测试] help命令:
just help
echo.

echo [测试] 项目文件检查:
if exist gradlew (
    echo [成功] gradlew存在
) else (
    echo [警告] gradlew不存在
)

if exist local.properties (
    echo [成功] local.properties存在
) else (
    echo [警告] local.properties不存在
)

echo.
echo ================================================
echo   安装和测试完成！
echo ================================================
echo.
echo 🚀 快速开始:
echo    just build          - 构建APK
echo    just install        - 安装到设备
echo    just run            - 构建并运行
echo    just help           - 显示帮助
echo.
echo 📚 详细文档: JUST_USAGE.md
echo.
pause