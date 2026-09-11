# Hercules 阶梯压测启动脚本（阶段 H-4，简化方案）
# 用法示例：
#   .\run-loadtest.ps1 -Threads 5   -Rampup 2  -Duration 15    # 冒烟
#   .\run-loadtest.ps1 -Threads 20  -Rampup 10 -Duration 60    # 冒烟
#   .\run-loadtest.ps1 -Threads 100 -Rampup 30 -Duration 180   # 阶梯第 1 级
#   .\run-loadtest.ps1 -Threads 500 -Rampup 30 -Duration 600   # 500 稳态
#
# 设计要点：
#   1. 登录/课程 ID 区间/线程划分全部在本脚本完成，JMX 内无任何脚本元素与冷门控制器；
#   2. 全部参数经 -q 属性文件传入，java 命令行不携带任何 -J（规避 CLI 解析坑）；
#   3. 线程组规模表达读7:写3（读 = floor(Threads*0.7)，其余为写）；
#   4. 看门狗超时（rampup+duration+180s）强制终止 JMeter，JTL 已落盘可用 -g 重生成报告。
#   5. 跑完解析 statistics.json 做成败门禁（错误率 / 读端点 P95），不达标 exit 1——
#      否则 JMeter CLI 无论失败多少都返回 0，压测结论只能靠人眼看。
# 前置：Docker 栈已启动（网关 8081）；JMeter 运行时已在 jmeter-runtime（缺失时先跑 mvnw configure）

param(
    [int]$Threads = 100,
    [int]$Rampup = 30,
    [int]$Duration = 300,
    [string]$Hostname = "127.0.0.1",
    [string]$Port = "8081",
    # 读路径门禁阈值：错误率 0.1% + P95 800ms
    #   依据：最近一轮报告读端点 p99 为 472ms 且错误率为 0，阈值留有余量。
    #   只对读端点设门禁——写端点在单账号下重复选课会按预期返回 409
    #   （t_enrollment.uk_student_course 生效后），那是正确行为而非故障，故不纳入判定。
    [double]$MaxReadErrorPct = 0.1,
    [int]$MaxReadP95Ms = 800
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$runtime = Join-Path $scriptDir "jmeter-runtime"
$jmx = Join-Path $scriptDir "src\test\jmeter\hercules-loadtest.jmx"
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"

# 1) 运行时兜底：缺失则经 jmeter:configure 组装并复制最新产物
if (-not (Test-Path (Join-Path $runtime "bin\ApacheJMeter-5.6.2.jar"))) {
    Write-Host "==> JMeter 运行时缺失，先经 jmeter:configure 组装（首次需联网）..."
    Push-Location (Split-Path -Parent $scriptDir)
    try {
        & .\mvnw.cmd -B -f loadtest/pom.xml jmeter:configure | Out-Null
    } finally {
        Pop-Location
    }
    $guid = Get-ChildItem (Join-Path $scriptDir "target") -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName "jmeter\bin\ApacheJMeter-5.6.2.jar") } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $guid) { Write-Host "[错误] 组装失败，未找到 JMeter 运行时"; exit 1 }
    Copy-Item (Join-Path $guid.FullName "jmeter") $runtime -Recurse -Force
}

# 2) 登录取 token（每级压测前重新登录，规避 30min JWT 过期）
$loginResp = Invoke-RestMethod -Method Post -Uri "http://${Hostname}:${Port}/api/v1/auth/login" `
    -ContentType "application/json" -Body '{"username":"st001","password":"123456"}' -TimeoutSec 15
$token = $loginResp.data.accessToken
if (-not $token -or $token.Length -lt 50) { Write-Host "[错误] 登录失败：未取得 accessToken"; exit 1 }
Write-Host "==> 登录成功：token len=$($token.Length)"

# 3) 压测课程 ID 区间动态查询（每轮播种自增前移，不能写死）
$idRow = docker exec hercules-mysql mysql -uroot -proot123 -N -e "SELECT MIN(id), MAX(id) FROM hercules.t_course WHERE course_code LIKE 'LOADTEST-%';" 2>$null
if ($idRow) {
    $parts = ($idRow -join " ") -split "\s+" | Where-Object { $_ -match "^\d+$" }
    $idFrom = $parts[0]; $idTo = $parts[$parts.Count - 1]
    Write-Host "==> 压测课程 ID 区间：$idFrom ~ $idTo"
} else {
    Write-Host "[错误] 无法查询压测课程 ID 区间（MySQL 容器未运行？）"
    exit 1
}

# 4) 线程划分：读 70% / 写 30%
$readThreads = [int][Math]::Floor($Threads * 0.7)
$writeThreads = $Threads - $readThreads

# 5) 属性文件：全部参数（JMeter 侧 ${__P(...)} 读取）
$propsFile = Join-Path $env:TEMP "jmeter-props-$stamp.properties"
@(
    "host=$Hostname",
    "port=$Port",
    "token=$token",
    "idFrom=$idFrom",
    "idTo=$idTo",
    "readThreads=$readThreads",
    "writeThreads=$writeThreads",
    "rampup=$Rampup",
    "duration=$Duration"
) | Set-Content -Path $propsFile -Encoding ASCII

# 6) 运行（headless + 自动 HTML 报告 + 看门狗限时）
$env:JMETER_HOME = $runtime
# 客户端 JVM 调优（阶段H-4调研）：加堆减 GC 频率；客户端重吞吐选 ParallelGC（服务端保留 G1 求低延迟）
$env:JVM_ARGS = "-Xms1g -Xmx4g -XX:+UseParallelGC"
New-Item -ItemType Directory -Path (Join-Path $scriptDir "results") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $scriptDir "reports") -Force | Out-Null
$jtl = Join-Path $scriptDir "results\$stamp.jtl"
$reportDir = Join-Path $scriptDir "reports\$stamp"

Write-Host "==> 压测：$Threads 线程（读 $readThreads / 写 $writeThreads）/ rampup ${Rampup}s / duration ${Duration}s → http://${Hostname}:${Port}"
$javaArgs = @("-jar", (Join-Path $runtime "bin\ApacheJMeter-5.6.2.jar"), "-n", "-t", $jmx, "-q", $propsFile, "-l", $jtl, "-e", "-o", $reportDir)
Write-Host "==> JAVA ARGS: $($javaArgs -join ' ')"

$p = Start-Process -FilePath "java" -ArgumentList $javaArgs -NoNewWindow -PassThru -WorkingDirectory $scriptDir
$budgetMs = ($Rampup + $Duration + 180) * 1000
if (-not $p.WaitForExit($budgetMs)) {
    Write-Host "[看门狗] 超时（>$($Rampup + $Duration + 180)s），强制终止 JMeter（PID $($p.Id)）"
    Stop-Process -Id $p.Id -Force
    Write-Host "==> JTL 已保留：$jtl"
    Write-Host "==> 可手动重生成报告：java -jar `"$runtime\bin\ApacheJMeter-5.6.2.jar`" -g `"$jtl`" -o `"$reportDir`""
    exit 2
}
if ($p.ExitCode -ne 0) { Write-Host "[错误] JMeter 退出码 $($p.ExitCode)"; exit $p.ExitCode }
Remove-Item $propsFile -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "==> 完成。HTML 报告：reports\$stamp\index.html"
Write-Host "==> 结果数据：results\$stamp.jtl"

# 7) 成败门禁：解析 JMeter dashboard 的 statistics.json，只对读端点判定
#    （pct2ResTime = 95th 百分位；pct1/pct2/pct3 依次为 90/95/99）
#    写端点不设门禁：单账号重复选课按预期返回 409（uk_student_course 生效后的正确行为）。
$statsFile = Join-Path $reportDir "statistics.json"
if (-not (Test-Path $statsFile)) {
    Write-Host "[警告] 未找到 statistics.json，跳过门禁判定"
    exit 0
}
$stats = Get-Content $statsFile -Raw | ConvertFrom-Json

Write-Host ""
Write-Host "==> 压测结果摘要"
$gateFailures = @()
foreach ($name in @('read-list', 'read-detail')) {
    $s = $stats.$name
    if ($null -eq $s) { continue }
    $errPct = [double]$s.errorPct
    $p95 = [int]$s.pct2ResTime
    Write-Host ("    {0,-12} 样本={1,-9} 错误率={2}%  P95={3}ms  (阈值 {4}% / {5}ms)" -f `
        $name, $s.sampleCount, $errPct, $p95, $MaxReadErrorPct, $MaxReadP95Ms)
    if ($errPct -gt $MaxReadErrorPct) {
        $gateFailures += "$name 错误率 $errPct% 超过阈值 $MaxReadErrorPct%"
    }
    if ($p95 -gt $MaxReadP95Ms) {
        $gateFailures += "$name P95 ${p95}ms 超过阈值 ${MaxReadP95Ms}ms"
    }
}
$write = $stats.'write-enroll'
if ($null -ne $write) {
    Write-Host ("    {0,-12} 样本={1,-9} 错误率={2}%  （含预期 409，不计入门禁）" -f `
        'write-enroll', $write.sampleCount, $write.errorPct)
}
Write-Host ("    总体         样本={0,-9} 错误率={1}% 吞吐={2}/s" -f `
    $stats.Total.sampleCount, $stats.Total.errorPct, [math]::Round([double]$stats.Total.throughput, 1))

if ($gateFailures.Count -gt 0) {
    foreach ($f in $gateFailures) { Write-Host "[失败] $f" }
    Write-Host "==> 压测门禁未通过（阈值可用 -MaxReadErrorPct / -MaxReadP95Ms 覆盖）"
    exit 1
}
Write-Host "==> 压测门禁通过（读端点错误率与 P95 均达标）"
