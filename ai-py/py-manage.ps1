# AI-Assistant Python 环境管理脚本
# 用法: .\py-manage.ps1 <命令>
#   setup            - 创建虚拟环境并安装所有依赖
#   install          - 安装/更新依赖（需已有 .venv）
#   activate         - 激活虚拟环境并打开 Shell
#   run-translator   - 启动屏幕翻译工具
#   run-voice        - 启动语音助手
#   status           - 查看虚拟环境状态
#   menu             - 交互式菜单（默认）

param(
    [Parameter(Position = 0)]
    [string]$Command = "menu"
)

# 修复中文显示
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = "SilentlyContinue"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$VenvDir = "$ProjectRoot\.venv"
$RequirementsFile = "$ProjectRoot\requirements.txt"
$PythonExe = "$VenvDir\Scripts\python.exe"
$PipExe = "$VenvDir\Scripts\pip.exe"
$ActivateScript = "$VenvDir\Scripts\Activate.ps1"

# ======================== 工具函数 ========================

function Write-Banner {
    Write-Host ""
    Write-Host "  +------------------------------------+" -ForegroundColor Cyan
    Write-Host "  |   AI-Assistant Python Environment  |" -ForegroundColor Cyan
    Write-Host "  +------------------------------------+" -ForegroundColor Cyan
    Write-Host ""
}

function Test-VenvExists {
    return (Test-Path $PythonExe)
}

function Get-PythonVersion {
    if (Test-VenvExists) {
        $ver = & $PythonExe --version 2>&1
        return $ver -replace "Python ", ""
    }
    return $null
}

function Write-EnvStatus {
    Write-Host "  -- Python 环境状态 ----------------------" -ForegroundColor DarkGray
    if (Test-VenvExists) {
        $ver = Get-PythonVersion
        Write-Host "  虚拟环境 : " -NoNewline
        Write-Host "已创建" -ForegroundColor Green
        Write-Host "  路径     : $VenvDir" -ForegroundColor DarkGray
        Write-Host "  Python   : $ver" -ForegroundColor DarkGray
        $pipCount = (& $PipExe list --format=freeze 2>$null | Measure-Object).Count
        Write-Host "  已安装包 : $pipCount 个" -ForegroundColor DarkGray
    } else {
        Write-Host "  虚拟环境 : " -NoNewline
        Write-Host "未创建" -ForegroundColor Red
        Write-Host "  运行 " -NoNewline
        Write-Host ".\py-manage.ps1 setup" -ForegroundColor Yellow -NoNewline
        Write-Host " 以创建"
    }
    Write-Host "  -----------------------------------------" -ForegroundColor DarkGray
    Write-Host ""
}

# ======================== 核心命令 ========================

function Invoke-Setup {
    Write-Host "  [Setup] 正在创建 Python 虚拟环境..." -ForegroundColor Cyan

    if (Test-VenvExists) {
        Write-Host "  [Setup] 虚拟环境已存在，跳过创建" -ForegroundColor Yellow
    } else {
        # 创建 venv
        python -m venv $VenvDir 2>&1 | Out-Null
        if (-not (Test-VenvExists)) {
            Write-Host "  [Setup] 创建失败，请确保已安装 Python 3.11+" -ForegroundColor Red
            return
        }
        Write-Host "  [Setup] 虚拟环境创建成功" -ForegroundColor Green
    }

    # 升级 pip
    Write-Host "  [Setup] 正在升级 pip..." -ForegroundColor Cyan
    & $PythonExe -m pip install --upgrade pip -q 2>&1 | Out-Null

    # 安装依赖
    Invoke-Install
}

function Invoke-Install {
    if (-not (Test-VenvExists)) {
        Write-Host "  [Install] 虚拟环境不存在，请先运行 setup" -ForegroundColor Red
        return
    }

    Write-Host "  [Install] 正在安装依赖 (可能需要几分钟)..." -ForegroundColor Cyan
    & $PipExe install -r $RequirementsFile 2>&1 | ForEach-Object {
        if ($_ -match "Successfully installed|already satisfied") {
            # 只显示关键信息
        } elseif ($_ -match "ERROR|error") {
            Write-Host "  $_" -ForegroundColor Red
        } elseif ($_ -match "Requirement already satisfied") {
            # 跳过
        } elseif ($_ -match "Installing collected packages") {
            Write-Host "  $_" -ForegroundColor Cyan
        } elseif ($_ -match "Successfully") {
            Write-Host "  $_" -ForegroundColor Green
        }
    }
    Write-Host "  [Install] 依赖安装完成" -ForegroundColor Green
}

function Invoke-Activate {
    if (-not (Test-VenvExists)) {
        Write-Host "  [Activate] 虚拟环境不存在，请先运行 setup" -ForegroundColor Red
        return
    }

    Write-Host "  [Activate] 正在打开 Python 虚拟环境 Shell..." -ForegroundColor Cyan
    $cmd = "& '$ActivateScript'; Write-Host '虚拟环境已激活 (.venv)' -ForegroundColor Green; Write-Host ''"
    Start-Process pwsh -ArgumentList "-NoExit", "-Command", $cmd -WorkingDirectory $ProjectRoot
}

function Invoke-RunTranslator {
    if (-not (Test-VenvExists)) {
        Write-Host "  [Run] 虚拟环境不存在，请先运行 setup" -ForegroundColor Red
        return
    }

    Write-Host "  [Run] 正在启动屏幕翻译工具..." -ForegroundColor Cyan
    $cmd = "& '$ActivateScript'; cd '$ProjectRoot\screen-translator'; `$Host.UI.RawUI.WindowTitle='Screen Translator'; python main.py"
    Start-Process pwsh -ArgumentList "-NoExit", "-Command", $cmd -WorkingDirectory "$ProjectRoot\screen-translator"
}

function Invoke-RunVoice {
    if (-not (Test-VenvExists)) {
        Write-Host "  [Run] 虚拟环境不存在，请先运行 setup" -ForegroundColor Red
        return
    }

    Write-Host "  [Run] 正在启动语音助手..." -ForegroundColor Cyan
    $cmd = "& '$ActivateScript'; cd '$ProjectRoot\ai-voice-assistant'; `$Host.UI.RawUI.WindowTitle='Voice Assistant'; python main.py"
    Start-Process pwsh -ArgumentList "-NoExit", "-Command", $cmd -WorkingDirectory "$ProjectRoot\ai-voice-assistant"
}

# ======================== 交互菜单 ========================

function Show-Menu {
    Write-Banner
    Write-EnvStatus

    Write-Host "  可用命令:" -ForegroundColor White
    Write-Host ""
    Write-Host "    1" -ForegroundColor Yellow -NoNewline; Write-Host "  setup            创建虚拟环境并安装依赖"
    Write-Host "    2" -ForegroundColor Yellow -NoNewline; Write-Host "  install          安装/更新依赖"
    Write-Host "    3" -ForegroundColor Yellow -NoNewline; Write-Host "  activate         激活虚拟环境 Shell"
    Write-Host "    4" -ForegroundColor Yellow -NoNewline; Write-Host "  run-translator   启动屏幕翻译工具"
    Write-Host "    5" -ForegroundColor Yellow -NoNewline; Write-Host "  run-voice        启动语音助手"
    Write-Host "    0" -ForegroundColor Yellow -NoNewline; Write-Host "  exit             退出"
    Write-Host ""

    while ($true) {
        $choice = Read-Host "  请输入编号"
        switch ($choice) {
            "1" { Invoke-Setup; Write-EnvStatus; break }
            "2" { Invoke-Install; break }
            "3" { Invoke-Activate; break }
            "4" { Invoke-RunTranslator; break }
            "5" { Invoke-RunVoice; break }
            "0" { return }
            default { Write-Host "  无效输入" -ForegroundColor Red }
        }
    }
}

# ======================== 命令路由 ========================

switch ($Command.ToLower()) {
    "setup" {
        Write-Banner
        Invoke-Setup
        Write-EnvStatus
    }
    "install" {
        Write-Banner
        Invoke-Install
    }
    "activate" {
        Write-Banner
        Invoke-Activate
    }
    "run-translator" {
        Write-Banner
        Invoke-RunTranslator
    }
    "run-voice" {
        Write-Banner
        Invoke-RunVoice
    }
    "status" {
        Write-Banner
        Write-EnvStatus
    }
    "menu" {
        Show-Menu
    }
    default {
        Write-Banner
        Write-Host "  未知命令: $Command" -ForegroundColor Red
        Write-Host "  用法: .\py-manage.ps1 [setup|install|activate|run-translator|run-voice|status|menu]" -ForegroundColor Yellow
    }
}
