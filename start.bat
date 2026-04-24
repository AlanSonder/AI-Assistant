@echo off

rem AI-Assistant 启动脚本
rem 版本: 1.0.0
rem 日期: 2026-04-24

echo ============================================
echo        AI-Assistant 启动脚本
 echo ============================================

rem 检查 Java 环境
echo 检查 Java 环境...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: 未检测到 Java 环境，请确保已安装 JDK 17 或更高版本
    pause
    exit /b 1
)

echo Java 环境检查通过

rem 检查 Maven Wrapper
echo 检查 Maven Wrapper...
if not exist "./mvnw" (
    echo 错误: Maven Wrapper 不存在
    pause
    exit /b 1
)

echo Maven Wrapper 检查通过

echo.
echo ============================================
echo 开始构建项目...
echo ============================================

rem 构建项目
call ./mvnw clean package
if %errorlevel% neq 0 (
    echo 错误: 项目构建失败
    pause
    exit /b 1
)

echo 项目构建成功

echo.
echo ============================================
echo 启动 AI-Assistant 服务...
echo 服务地址: http://localhost:9090
echo ============================================

rem 启动应用
call ./mvnw spring-boot:run -pl ai-app

if %errorlevel% neq 0 (
    echo 错误: 服务启动失败
    pause
    exit /b 1
)
