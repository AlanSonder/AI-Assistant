# AI-Assistant 项目管理脚本
# 用法: .\manage.ps1 <命令>
#   start          - 启动后端 + 前端
#   stop           - 停止后端 + 前端
#   restart        - 重启后端 + 前端
#   start-backend  - 仅启动后端
#   start-frontend - 仅启动前端
#   stop-backend   - 仅停止后端
#   stop-frontend  - 仅停止前端
#   restart-backend  - 仅重启后端
#   restart-frontend - 仅重启前端
#   start-translator - 启动屏幕翻译工具
#   start-voice    - 启动语音助手
#   setup-python   - 创建/更新 Python 虚拟环境
#   status         - 查看运行状态
#   log-backend    - 查看后端日志
#   log-frontend   - 查看前端日志
#   build          - 构建后端 (install)

param(
    [Parameter(Position = 0)]
    [string]$Command = "menu"
)

# 修复中文显示
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = "SilentlyContinue"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendPort = 9090
$FrontendPort = 5173
$BackendLog = "$ProjectRoot\.logs\backend.log"
$FrontendLog = "$ProjectRoot\.logs\frontend.log"
$JvmArgs = "-Dsun.java2d.uiScale=1.0"
$VenvDir = "$ProjectRoot\ai-py\.venv"
$PythonExe = "$VenvDir\Scripts\python.exe"
$ActivateScript = "$VenvDir\Scripts\Activate.ps1"
$RequirementsFile = "$ProjectRoot\ai-py\requirements.txt"

# 项目专用 JAVA_HOME（JDK 21，不影响全局）
$ProjectJavaHome = "C:\Users\alans\scoop\apps\temurin21-jdk\current"
if (Test-Path $ProjectJavaHome) {
    $env:JAVA_HOME = $ProjectJavaHome
    $env:PATH = "$ProjectJavaHome\bin;$env:PATH"
}

# 确保日志目录存在
New-Item -ItemType Directory -Path "$ProjectRoot\.logs" -Force | Out-Null

# ======================== 工具函数 ========================

function Write-Banner {
    Write-Host ""
    Write-Host "  ╔══════════════════════════════════════╗" -ForegroundColor Cyan
    Write-Host "  ║     AI-Assistant 项目管理脚本        ║" -ForegroundColor Cyan
    Write-Host "  ╚══════════════════════════════════════╝" -ForegroundColor Cyan
    Write-Host ""
}

function Write-Status {
    param([string]$Name, [bool]$Running)
    $status = if ($Running) { "● 运行中" } else { "○ 已停止" }
    $color = if ($Running) { "Green" } else { "DarkGray" }
    Write-Host "  $Name : " -NoNewline
    Write-Host $status -ForegroundColor $color
}

function Get-ProcessByPort {
    param([int]$Port)
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1
    if ($conn -and $conn.OwningProcess -gt 0) {
        return Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
    }
    return $null
}

function Is-BackendRunning {
    return $null -ne (Get-ProcessByPort -Port $BackendPort)
}

function Is-FrontendRunning {
    return $null -ne (Get-ProcessByPort -Port $FrontendPort)
}

function Test-PythonVenvExists {
    return (Test-Path $PythonExe)
}

# ======================== Python 工具 ========================

function Setup-Python {
    Write-Host "  [Python] 正在设置虚拟环境..." -ForegroundColor Cyan

    if (Test-PythonVenvExists) {
        Write-Host "  [Python] 虚拟环境已存在，跳过创建" -ForegroundColor Yellow
    } else {
        python -m venv $VenvDir 2>&1 | Out-Null
        if (-not (Test-PythonVenvExists)) {
            Write-Host "  [Python] 创建失败，请确保已安装 Python 3.11+" -ForegroundColor Red
            return
        }
        Write-Host "  [Python] 虚拟环境创建成功" -ForegroundColor Green
    }

    Write-Host "  [Python] 正在安装依赖 (可能需要几分钟)..." -ForegroundColor Cyan
    & "$VenvDir\Scripts\pip.exe" install --upgrade pip -q 2>&1 | Out-Null
    & "$VenvDir\Scripts\pip.exe" install -r $RequirementsFile 2>&1 | ForEach-Object {
        if ($_ -match "Successfully") { Write-Host "  $_" -ForegroundColor Green }
        elseif ($_ -match "ERROR|error") { Write-Host "  $_" -ForegroundColor Red }
    }
    Write-Host "  [Python] 设置完成" -ForegroundColor Green
}

function Start-Translator {
    if (-not (Test-PythonVenvExists)) {
        Write-Host "  [Translator] 虚拟环境不存在，请先运行 setup-python" -ForegroundColor Red
        return
    }

    Write-Host "  [Translator] 正在启动屏幕翻译工具..." -ForegroundColor Cyan
    $cmd = "& '$ActivateScript'; cd '$ProjectRoot\ai-py\screen-translator'; `$Host.UI.RawUI.WindowTitle='Screen Translator'; python main.py"
    Start-Process pwsh -ArgumentList "-NoExit", "-Command", $cmd -WorkingDirectory "$ProjectRoot\ai-py\screen-translator"
    Write-Host "  [Translator] 已在新窗口启动" -ForegroundColor Green
}

function Start-VoiceAssistant {
    if (-not (Test-PythonVenvExists)) {
        Write-Host "  [Voice] 虚拟环境不存在，请先运行 setup-python" -ForegroundColor Red
        return
    }

    Write-Host "  [Voice] 正在启动语音助手..." -ForegroundColor Cyan
    $cmd = "& '$ActivateScript'; cd '$ProjectRoot\ai-py\ai-voice-assistant'; `$Host.UI.RawUI.WindowTitle='Voice Assistant'; python main.py"
    Start-Process pwsh -ArgumentList "-NoExit", "-Command", $cmd -WorkingDirectory "$ProjectRoot\ai-py\ai-voice-assistant"
    Write-Host "  [Voice] 已在新窗口启动" -ForegroundColor Green
}

# ======================== 启动 ========================

function Start-Backend {
    if (Is-BackendRunning) {
        Write-Host "  [后端] 已在运行 (端口 $BackendPort)" -ForegroundColor Yellow
        return
    }

    Write-Host "  [后端] 正在构建并启动..." -ForegroundColor Cyan

    # 先 install 依赖模块
    Push-Location $ProjectRoot
    & .\mvnw.cmd install -DskipTests -pl ai-translator -am -q 2>&1 | Out-Null
    Pop-Location

    # 在新终端窗口中启动 Spring Boot
    $mvnwPath = "$ProjectRoot\mvnw.cmd"
    $javaSetup = "`$env:JAVA_HOME='$ProjectJavaHome'; `$env:PATH='$ProjectJavaHome\bin;' + `$env:PATH;"
    $cmd = "$javaSetup `$Host.UI.RawUI.WindowTitle='AI-Assistant 后端'; cd '$ProjectRoot'; & '$mvnwPath' spring-boot:run -pl ai-app '-Dspring-boot.run.jvmArguments=$JvmArgs'"
    Start-Process pwsh -ArgumentList "-NoExit", "-Command", $cmd -WorkingDirectory $ProjectRoot

    # 等待启动
    Write-Host "  [后端] 等待启动完成..." -ForegroundColor DarkGray -NoNewline
    $timeout = 60
    $elapsed = 0
    while ($elapsed -lt $timeout) {
        Start-Sleep -Seconds 2
        $elapsed += 2
        Write-Host "." -NoNewline -ForegroundColor DarkGray
        if (Is-BackendRunning) {
            Write-Host ""
            Write-Host "  [后端] 启动成功 http://localhost:$BackendPort" -ForegroundColor Green
            return
        }
    }
    Write-Host ""
    Write-Host "  [后端] 启动超时，请检查终端窗口" -ForegroundColor Red
}

function Start-Frontend {
    if (Is-FrontendRunning) {
        Write-Host "  [前端] 已在运行 (端口 $FrontendPort)" -ForegroundColor Yellow
        return
    }

    Write-Host "  [前端] 正在启动..." -ForegroundColor Cyan

    # 在新终端窗口中启动前端
    $cmd = "`$Host.UI.RawUI.WindowTitle='AI-Assistant 前端'; cd '$ProjectRoot\ui'; npm run dev"
    Start-Process pwsh -ArgumentList "-NoExit", "-Command", $cmd -WorkingDirectory "$ProjectRoot\ui"

    # 等待启动
    Write-Host "  [前端] 等待启动完成..." -ForegroundColor DarkGray -NoNewline
    $timeout = 30
    $elapsed = 0
    while ($elapsed -lt $timeout) {
        Start-Sleep -Seconds 2
        $elapsed += 2
        Write-Host "." -NoNewline -ForegroundColor DarkGray
        if (Is-FrontendRunning) {
            Write-Host ""
            Write-Host "  [前端] 启动成功 http://localhost:$FrontendPort" -ForegroundColor Green
            return
        }
    }
    Write-Host ""
    Write-Host "  [前端] 启动超时，请检查终端窗口" -ForegroundColor Red
}

# ======================== 停止 ========================

function Stop-Backend {
    $proc = Get-ProcessByPort -Port $BackendPort
    if ($null -eq $proc) {
        Write-Host "  [后端] 未在运行" -ForegroundColor DarkGray
        return
    }

    Write-Host "  [后端] 正在停止 (PID: $($proc.Id))..." -ForegroundColor Cyan -NoNewline
    # 终止进程树 (mvnw -> java)
    & taskkill /PID $proc.Id /T /F 2>$null | Out-Null
    Start-Sleep -Seconds 2

    if (-not (Is-BackendRunning)) {
        Write-Host " 已停止" -ForegroundColor Green
    } else {
        $remaining = Get-ProcessByPort -Port $BackendPort
        if ($remaining) { Stop-Process -Id $remaining.Id -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 1
        if (-not (Is-BackendRunning)) {
            Write-Host " 已停止" -ForegroundColor Green
        } else {
            Write-Host " 停止失败" -ForegroundColor Red
        }
    }
}

function Stop-Frontend {
    $proc = Get-ProcessByPort -Port $FrontendPort
    if ($null -eq $proc) {
        Write-Host "  [前端] 未在运行" -ForegroundColor DarkGray
        return
    }

    Write-Host "  [前端] 正在停止 (PID: $($proc.Id))..." -ForegroundColor Cyan -NoNewline
    # 终止进程树 (cmd.exe -> node.exe)
    & taskkill /PID $proc.Id /T /F 2>$null | Out-Null
    Start-Sleep -Seconds 2

    if (-not (Is-FrontendRunning)) {
        Write-Host " 已停止" -ForegroundColor Green
    } else {
        # 回退方案：强制结束占用端口的进程
        $remaining = Get-ProcessByPort -Port $FrontendPort
        if ($remaining) { Stop-Process -Id $remaining.Id -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 1
        if (-not (Is-FrontendRunning)) {
            Write-Host " 已停止" -ForegroundColor Green
        } else {
            Write-Host " 停止失败" -ForegroundColor Red
        }
    }
}

# ======================== 重启 ========================

function Restart-Backend {
    Write-Host "  [后端] 正在重启..." -ForegroundColor Cyan
    Stop-Backend
    Start-Sleep -Seconds 1
    Start-Backend
}

function Restart-Frontend {
    Write-Host "  [前端] 正在重启..." -ForegroundColor Cyan
    Stop-Frontend
    Start-Sleep -Seconds 1
    Start-Frontend
}

# ======================== 状态 ========================

function Show-Status {
    Write-Host ""
    Write-Host "  ── 运行状态 ──────────────────────────" -ForegroundColor DarkGray
    Write-Status "后端" (Is-BackendRunning)
    Write-Host "         端口: $BackendPort" -ForegroundColor DarkGray
    Write-Status "前端" (Is-FrontendRunning)
    Write-Host "         端口: $FrontendPort" -ForegroundColor DarkGray
    Write-Host "  ── Python 工具 ────────────────────────" -ForegroundColor DarkGray
    if (Test-PythonVenvExists) {
        $ver = & $PythonExe --version 2>&1
        $ver = $ver -replace "Python ", ""
        Write-Status "Python 环境" $true
        Write-Host "         版本: $ver | 路径: .venv" -ForegroundColor DarkGray
    } else {
        Write-Status "Python 环境" $false
        Write-Host "         运行 setup-python 以创建" -ForegroundColor DarkGray
    }
    Write-Host "  ─────────────────────────────────────" -ForegroundColor DarkGray
    Write-Host ""
}

# ======================== 构建 ========================

function Build-Backend {
    Write-Host "  [构建] 正在构建后端..." -ForegroundColor Cyan
    Push-Location $ProjectRoot
    & .\mvnw.cmd install -DskipTests 2>&1 | ForEach-Object {
        if ($_ -match "BUILD SUCCESS") {
            Write-Host "  $_" -ForegroundColor Green
        } elseif ($_ -match "BUILD FAILURE|ERROR") {
            Write-Host "  $_" -ForegroundColor Red
        }
    }
    Pop-Location
    Write-Host "  [构建] 完成" -ForegroundColor Green
}

# ======================== 日志 ========================

function Show-Log {
    param([string]$LogFile, [string]$Name)
    if (-not (Test-Path $LogFile)) {
        Write-Host "  [$Name] 日志文件不存在: $LogFile" -ForegroundColor Red
        return
    }
    Write-Host "  [$Name] 显示最近 50 行日志 (Ctrl+C 退出)" -ForegroundColor Cyan
    Write-Host ""
    Get-Content $LogFile -Tail 50 -Wait -Encoding UTF8
}

# ======================== 交互菜单 ========================

function Show-Menu {
    Write-Banner
    Show-Status

    Write-Host "  可用命令:" -ForegroundColor White
    Write-Host ""
    Write-Host "    1" -ForegroundColor Yellow -NoNewline; Write-Host "  start            启动后端 + 前端"
    Write-Host "    8" -ForegroundColor Yellow -NoNewline; Write-Host "  stop             停止后端 + 前端"
    Write-Host "    3" -ForegroundColor Yellow -NoNewline; Write-Host "  restart          重启后端 + 前端"
    Write-Host "    4" -ForegroundColor Yellow -NoNewline; Write-Host "  start-backend    仅启动后端"
    Write-Host "    5" -ForegroundColor Yellow -NoNewline; Write-Host "  start-frontend   仅启动前端"
    Write-Host "    6" -ForegroundColor Yellow -NoNewline; Write-Host "  stop-backend     仅停止后端"
    Write-Host "    7" -ForegroundColor Yellow -NoNewline; Write-Host "  stop-frontend    仅停止前端"
    Write-Host "    2" -ForegroundColor Yellow -NoNewline; Write-Host "  restart-backend  仅重启后端"
    Write-Host "    9" -ForegroundColor Yellow -NoNewline; Write-Host "  restart-frontend 仅重启前端"
    Write-Host "   10" -ForegroundColor Yellow -NoNewline; Write-Host "  build            构建后端 (mvn install)"
    Write-Host "   11" -ForegroundColor Yellow -NoNewline; Write-Host "  log-backend      查看后端日志"
    Write-Host "   12" -ForegroundColor Yellow -NoNewline; Write-Host "  log-frontend     查看前端日志"
    Write-Host ""  -ForegroundColor DarkGray
    Write-Host "   13" -ForegroundColor Magenta -NoNewline; Write-Host "  setup-python     创建/更新 Python 虚拟环境" -ForegroundColor Magenta
    Write-Host "   14" -ForegroundColor Magenta -NoNewline; Write-Host "  start-translator 启动屏幕翻译工具" -ForegroundColor Magenta
    Write-Host "   15" -ForegroundColor Magenta -NoNewline; Write-Host "  start-voice      启动语音助手" -ForegroundColor Magenta
    Write-Host ""
    Write-Host "    0" -ForegroundColor Yellow -NoNewline; Write-Host "  exit             退出"
    Write-Host ""

    while ($true) {
        $choice = Read-Host "  请输入编号"
        switch ($choice) {
            "1" { Start-Backend; Start-Frontend; Show-Status; break }
            "8" { Stop-Frontend; Stop-Backend; Show-Status; break }
            "3" { Stop-Frontend; Stop-Backend; Start-Sleep 1; Start-Backend; Start-Frontend; Show-Status; break }
            "4" { Start-Backend; Show-Status; break }
            "5" { Start-Frontend; Show-Status; break }
            "6" { Stop-Backend; Show-Status; break }
            "7" { Stop-Frontend; Show-Status; break }
            "2" { Restart-Backend; Show-Status; break }
            "9" { Restart-Frontend; Show-Status; break }
            "10" { Build-Backend; break }
            "11" { Show-Log $BackendLog "后端"; break }
            "12" { Show-Log $FrontendLog "前端"; break }
            "13" { Setup-Python; Show-Status; break }
            "14" { Start-Translator; break }
            "15" { Start-VoiceAssistant; break }
            "0" { return }
            default { Write-Host "  无效输入" -ForegroundColor Red }
        }
    }
}

# ======================== 命令路由 ========================

switch ($Command.ToLower()) {
    "start" {
        Write-Banner
        Start-Backend
        Start-Frontend
        Show-Status
    }
    "stop" {
        Write-Banner
        Stop-Frontend
        Stop-Backend
        Show-Status
    }
    "restart" {
        Write-Banner
        Stop-Frontend
        Stop-Backend
        Start-Sleep -Seconds 1
        Start-Backend
        Start-Frontend
        Show-Status
    }
    "start-backend" {
        Write-Banner
        Start-Backend
        Show-Status
    }
    "start-frontend" {
        Write-Banner
        Start-Frontend
        Show-Status
    }
    "stop-backend" {
        Write-Banner
        Stop-Backend
        Show-Status
    }
    "stop-frontend" {
        Write-Banner
        Stop-Frontend
        Show-Status
    }
    "restart-backend" {
        Write-Banner
        Restart-Backend
        Show-Status
    }
    "restart-frontend" {
        Write-Banner
        Restart-Frontend
        Show-Status
    }
    "status" {
        Write-Banner
        Show-Status
    }
    "build" {
        Write-Banner
        Build-Backend
    }
    "log-backend" {
        Show-Log $BackendLog "后端"
    }
    "log-frontend" {
        Show-Log $FrontendLog "前端"
    }
    "setup-python" {
        Write-Banner
        Setup-Python
        Show-Status
    }
    "start-translator" {
        Write-Banner
        Start-Translator
    }
    "start-voice" {
        Write-Banner
        Start-VoiceAssistant
    }
    "menu" {
        Show-Menu
    }
    default {
        Write-Banner
        Write-Host "  未知命令: $Command" -ForegroundColor Red
        Write-Host "  用法: .\manage.ps1 [start|stop|restart|start-backend|start-frontend|stop-backend|stop-frontend|restart-backend|restart-frontend|status|build|log-backend|log-frontend|setup-python|start-translator|start-voice|menu]" -ForegroundColor Yellow
    }
}
