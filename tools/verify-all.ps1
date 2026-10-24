# verify-all 编排脚本：构建检查 -> 启动 -> 健康等待 -> 核心链路断言 -> 停止 -> 汇总
# 由 verify-all.cmd（纯 ASCII shim）调用，本文件为 UTF-8，可安全输出中文。
# 用法: powershell -ExecutionPolicy Bypass -File tools\verify-all.ps1 [-Port 18080]
param([int]$Port = 18080, [string]$Root = "")

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 定位项目根目录（三重兜底，避免依赖 $PSScriptRoot 是否被设置）：
#   1) 调用方显式传入 -Root（verify-all.cmd 用 %~dp0 传入，最可靠）
#   2) $PSScriptRoot / $MyInvocation.MyCommand.Path 的上级目录
#   3) 当前工作目录
function Resolve-ProjectRoot {
  param([string]$Explicit)
  if (-not [string]::IsNullOrWhiteSpace($Explicit)) {
    $p = $Explicit.TrimEnd('\', '/')
    if (Test-Path -LiteralPath (Join-Path $p "pom.xml")) { return $p }
    # 传入的是 tools 目录时，上一级才是项目根
    $parent = Split-Path -Parent $p
    if ($parent -and (Test-Path -LiteralPath (Join-Path $parent "pom.xml"))) { return $parent }
  }
  foreach ($cand in @($PSScriptRoot, (Split-Path -Parent $MyInvocation.MyCommand.Path))) {
    if ([string]::IsNullOrWhiteSpace($cand)) { continue }
    if (Test-Path -LiteralPath (Join-Path $cand "pom.xml")) { return $cand }
    $parent = Split-Path -Parent $cand
    if ($parent -and (Test-Path -LiteralPath (Join-Path $parent "pom.xml"))) { return $parent }
  }
  return (Get-Location).Path
}
$root = Resolve-ProjectRoot -Explicit $Root
$scriptDir = Join-Path $root "tools"
if (-not (Test-Path -LiteralPath $scriptDir)) { $scriptDir = (Get-Location).Path }
$jar  = Join-Path $root "target\ai-teaching-kb-1.0.0.jar"
$work = Join-Path $root "target\verify-work"
$log  = Join-Path $work "app.log"
$proc = $null
function Say($msg) { Write-Host $msg }
Say "        root=$root"

# ---------- 1) 构建检查 ----------
Say "[1/6] checking jar ..."
if (-not (Test-Path -LiteralPath $jar)) {
  Say "        jar missing, building ..."
  $mvn = Join-Path $root "..\tools\apache-maven-3.9.9\bin\mvn.cmd"
  if (-not (Test-Path -LiteralPath $mvn)) { $mvn = Join-Path $root "tools\apache-maven-3.9.9\bin\mvn.cmd" }
  if (-not (Test-Path -LiteralPath $mvn)) { Say "[FAIL] maven not found"; exit 1 }
  $settings = Join-Path $root "..\tools\settings.xml"
  $mvnArgs = @("-B")
  if (Test-Path -LiteralPath $settings) { $mvnArgs += @("-s", $settings) }
  $mvnArgs += @("-f", (Join-Path $root "pom.xml"), "package", "-DskipTests")
  & $mvn @mvnArgs
  if ($LASTEXITCODE -ne 0) { Say "[FAIL] build failed"; exit 1 }
}
Say "        OK: $jar"

# ---------- 2) 干净工作目录 ----------
Say "[2/6] preparing clean work dir ..."
if (Test-Path -LiteralPath $work) {
  try { Remove-Item -Recurse -Force -LiteralPath $work -ErrorAction Stop }
  catch { Say "[FAIL] cannot clean work dir (another instance running?): $work"; exit 1 }
}
New-Item -ItemType Directory -Force -Path $work | Out-Null

# ---------- 3) 启动 ----------
Say "[3/6] starting app on port $Port ..."
$javaExe = "java"
if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) { $javaExe = "$env:JAVA_HOME\bin\java.exe" }
elseif (Test-Path -LiteralPath "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe") {
  $javaExe = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe"
}
$appArgs = @("-Dfile.encoding=UTF-8", "-jar", $jar, "--server.port=$Port")
$proc = Start-Process -FilePath $javaExe -WorkingDirectory $work -PassThru -NoNewWindow `
  -RedirectStandardOutput $log -RedirectStandardError (Join-Path $work "app.err.log") `
  -ArgumentList $appArgs
Say "        pid=$($proc.Id) log=$log"

# ---------- 4) 等待就绪 ----------
Say "[4/6] waiting for health endpoint ..."
$up = $false
for ($i = 0; $i -lt 90; $i++) {
  if ($proc.HasExited) { break }
  try {
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/health" -UseBasicParsing -TimeoutSec 2
    if ($r.StatusCode -eq 200) { $up = $true; break }
  } catch { }
  Start-Sleep -Milliseconds 1000
}
if (-not $up) {
  Say "[FAIL] app did not become healthy on port $Port"
  if (Test-Path -LiteralPath $log) { Say "----- app.log (tail) -----"; Get-Content -LiteralPath $log -Tail 25 }
  $errLog = Join-Path $work "app.err.log"
  if (Test-Path -LiteralPath $errLog) { $e = Get-Content -LiteralPath $errLog -Tail 15; if ($e) { Say "----- app.err.log -----"; $e } }
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Say ""; Say "===== VERIFY-ALL: FAILED ====="
  exit 1
}
Say "        OK: app is up"

# ---------- 5) 核心链路断言 ----------
Say "[5/6] running core-chain checks ..."
$env:KB_PORT = "$Port"
$env:KB_FX   = $work
$checkScript = Join-Path $scriptDir "verify-check.ps1"
& $checkScript
$rc = $LASTEXITCODE
if ($null -eq $rc) { $rc = 1 }   # 断言脚本自身解析/执行失败时不能误报成功
# ---------- 6) 停止 ----------
Say "[6/6] stopping app ..."
if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
Start-Sleep -Milliseconds 500

if ($rc -eq 0) { Say ""; Say "===== VERIFY-ALL: PASSED =====" } else { Say ""; Say "===== VERIFY-ALL: FAILED =====" }
exit $rc
