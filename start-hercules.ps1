# ============================================================
#  Hercules 一键启动脚本（由根目录 start.bat 调起，也可单独运行）
#  用法：双击 start.bat；或 powershell -File start-hercules.ps1
#  行为：检查环境 → 端口占用检测 →（必要时）打包 → 新窗口启动应用
#        → 健康检查（60s）→ 打开浏览器
# ============================================================

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$platform  = Join-Path $scriptDir 'hercules-platform'
$jarPath   = Join-Path $platform 'hercules-application\target\hercules-application-0.0.1-SNAPSHOT.jar'
$base      = 'http://127.0.0.1:8080'

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Fail([string]$msg) { Write-Host "[错误] $msg" -ForegroundColor Red }

Write-Host '============================================================'
Write-Host '  Hercules 一键启动（本机直连 MySQL/Redis，无需 Docker）'
Write-Host '============================================================'

# 1) 工程目录
if (-not (Test-Path $platform)) {
    Write-Fail "未找到 $platform（请将脚本放在工作区根目录）"
    exit 3
}

# 2) Java 环境
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Fail '未找到 java，请安装 JDK 21+ 并加入 PATH'
    exit 3
}

# 3) 已在运行检测（8080 端口）
$listen = netstat -ano | Select-String ':8080\s' | Select-String 'LISTENING'
if ($listen) {
    Write-Host '[提示] 端口 8080 已有服务监听，Hercules 可能已在运行。' -ForegroundColor Yellow
    Write-Host '       如需重启：先关闭应用日志窗口或结束对应 java 进程。'
    Start-Process "$base/api/v1/courses?page=1&size=10"
    exit 0
}

# 4) 打包（jar 缺失时才构建）
if (-not (Test-Path $jarPath)) {
    Write-Step '首次构建：正在打包（跳过测试；首次需联网下载依赖，请耐心等待）'
    Push-Location $platform
    try {
        & .\mvnw.cmd -B package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Write-Fail '打包失败。常见原因：依赖下载网络问题（按约定停下反馈）、JDK 版本不符'
            exit 1
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Step '检测到已有构建产物，跳过打包（强制重建：删除 hercules-application\target 目录）'
}

# 5) 启动应用（新窗口显示日志，关闭该窗口即停止应用）
Write-Step '启动应用（新窗口显示日志；关闭该窗口即停止应用）'
$cmdLine = 'cd /d "' + $platform + '" && java -jar "' + $jarPath + '"'
Start-Process -FilePath 'cmd.exe' -ArgumentList '/k', $cmdLine -WorkingDirectory $platform

# 6) 健康检查（最多 60 秒）
Write-Step '等待应用就绪（最多 60 秒）'
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    try {
        $health = Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 2
        if ($health.status -eq 'UP') { $ready = $true; break }
    } catch {
        # 尚未就绪，继续等待
    }
}
if (-not $ready) {
    Write-Fail '60 秒内未就绪，请查看应用窗口日志，常见原因：'
    Write-Host '       1. MySQL / Redis 服务未启动'
    Write-Host '       2. application-local.yml 凭据不符'
    Write-Host '       3. 端口冲突'
    exit 2
}

# 7) 就绪
Write-Host ''
Write-Host '============================================================' -ForegroundColor Green
Write-Host '  Hercules 启动成功   http://127.0.0.1:8080' -ForegroundColor Green
Write-Host '   课程列表   /api/v1/courses?page=1&size=10'
Write-Host '   缓存统计   /api/v1/cache/stats'
Write-Host '   冲突演示   POST /api/v1/debug/simulate-conflict'
Write-Host '   指标端点   /actuator/prometheus'
Write-Host '   停止应用：关闭应用日志窗口即可'
Write-Host '============================================================' -ForegroundColor Green
Start-Process "$base/api/v1/courses?page=1&size=10"
exit 0
