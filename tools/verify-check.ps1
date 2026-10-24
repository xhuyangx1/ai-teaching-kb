# verify-all 调用的核心链路断言脚本（UTF-8 BOM，兼容 Windows PowerShell 5.1 与 PowerShell 7+）
# 环境变量：KB_PORT=服务端口, KB_FX=工作目录
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = "http://127.0.0.1:$env:KB_PORT"
$fx   = Join-Path $env:KB_FX 'fixtures'
New-Item -ItemType Directory -Force -Path $fx | Out-Null
$script:pass = 0; $script:fail = 0
function Check($n, $c, $d) {
  if ($c) { Write-Host "  PASS  $n :: $d" -ForegroundColor Green; $script:pass++ }
  else    { Write-Host "  FAIL  $n :: $d" -ForegroundColor Red;   $script:fail++ }
}

# multipart 上传：PS7+ 直接用 -Form；Windows PowerShell 5.1 无该参数，退回手工构造 multipart。
function Upload-Document($Path, $UserId) {
  $uri = "$base/api/documents?userId=$UserId"
  if ($PSVersionTable.PSVersion.Major -ge 6) {
    return Invoke-RestMethod -Uri $uri -Method Post -Form @{ file = Get-Item -LiteralPath $Path }
  }
  $boundary = [System.Guid]::NewGuid().ToString()
  $fileName = [System.IO.Path]::GetFileName($Path)
  $bytes    = [System.IO.File]::ReadAllBytes($Path)
  $enc = [System.Text.Encoding]::UTF8
  $head = "--$boundary`r`n" + "Content-Disposition: form-data; name=`"file`"; filename=`"$fileName`"`r`n" + "Content-Type: application/octet-stream`r`n`r`n"
  $ms = New-Object System.IO.MemoryStream
  $hb = $enc.GetBytes($head); $ms.Write($hb, 0, $hb.Length)
  $ms.Write($bytes, 0, $bytes.Length)
  $tb = $enc.GetBytes("`r`n--$boundary--`r`n"); $ms.Write($tb, 0, $tb.Length)
  $payload = $ms.ToArray(); $ms.Dispose()
  return Invoke-RestMethod -Uri $uri -Method Post -ContentType "multipart/form-data; boundary=$boundary" -Body $payload
}

# 上传分片：PS7+ 用 -Form；5.1 手工构造 multipart（与 Upload-Document 同源写法）。
function Upload-ChunkPart($UploadId, $Seq, $Bytes) {
  $uri = "$base/api/uploads/$UploadId/chunks/$Seq" + "?userId=verify"
  if ($PSVersionTable.PSVersion.Major -ge 6) {
    $tmp = Join-Path $fx "part_$Seq.bin"
    [System.IO.File]::WriteAllBytes($tmp, $Bytes)
    return Invoke-RestMethod -Uri $uri -Method Put -Form @{ chunk = Get-Item -LiteralPath $tmp }
  }
  $boundary = [System.Guid]::NewGuid().ToString()
  $enc = [System.Text.Encoding]::UTF8
  $head = "--$boundary`r`n" + "Content-Disposition: form-data; name=`"chunk`"; filename=`"part_$Seq.bin`"`r`n" + "Content-Type: application/octet-stream`r`n`r`n"
  $ms = New-Object System.IO.MemoryStream
  $hb = $enc.GetBytes($head); $ms.Write($hb, 0, $hb.Length)
  $ms.Write($Bytes, 0, $Bytes.Length)
  $tb = $enc.GetBytes("`r`n--$boundary--`r`n"); $ms.Write($tb, 0, $tb.Length)
  $payload = $ms.ToArray(); $ms.Dispose()
  return Invoke-RestMethod -Uri $uri -Method Put -ContentType "multipart/form-data; boundary=$boundary" -Body $payload
}

# ---- fixtures ----
$doc = Join-Path $fx 'kb-verify.txt'
@'
AI teaching knowledge base verification document.
The importer splits a document into chunks by aggregating paragraphs, then embeds each chunk.
Retrieval uses a flat cosine scan over the in-memory index, truncated by a score threshold.
'@ | Set-Content -Path $doc -Encoding UTF8
$cn = Join-Path $fx 'verify-cn.txt'
Set-Content -Path $cn -Encoding UTF8 -Value "这是一份用于验证中文文件名与内容不乱码的文档。它会被切分成切片并建立向量索引。"

Write-Host ""
Write-Host "--- 1) health ---"
try {
  $h = Invoke-WebRequest -Uri "$base/api/health" -UseBasicParsing
  $hj = ConvertFrom-Json $h.Content
  Check 'health-200' ($h.StatusCode -eq 200) "HTTP $($h.StatusCode)"
  Check 'health-UP'  ($hj.data.status -eq 'UP') "status=$($hj.data.status) llm=$($hj.data.chatModel) mq=$($hj.data.mqMode)"
} catch { Check 'health-200' $false "exception: $($_.Exception.Message)" }

Write-Host ""
Write-Host "--- 2) import document (async) ---"
$importOk = $false
try {
  $u = Upload-Document $doc "verify"
  Check 'upload-200' ($u.code -eq 200 -and $u.data.docId) "docId=$($u.data.docId) taskId=$($u.data.taskId)"
  Check 'upload-PENDING' ($u.data.taskStatus -eq 'PENDING') "taskStatus=$($u.data.taskStatus) (async accepted)"
  $importOk = $true
} catch { Check 'upload-200' $false "exception: $($_.Exception.Message)" }

if ($importOk) {
  $st = $null
  for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    $t = Invoke-RestMethod -Uri "$base/api/tasks/$($u.data.taskId)"
    if ($t.data.status -eq 'SUCCESS' -or $t.data.status -eq 'FAILED') { $st = $t.data; break }
  }
  Check 'task-SUCCESS' ($st -and $st.status -eq 'SUCCESS') "status=$($st.status) progress=$($st.progress)"
  $ch = Invoke-RestMethod -Uri "$base/api/documents/$($u.data.docId)/chunks"
  Check 'chunks-created' ($ch.data.count -ge 1) "chunks=$($ch.data.count)"

  Write-Host ""
  Write-Host "--- 3) chat with citations ---"
  $b1 = '{"question":"How does retrieval work?","userId":"verify","conversationId":"verify_conv"}'
  try {
    $r = Invoke-WebRequest -Uri "$base/api/chat" -Method Post -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($b1)) -UseBasicParsing
    $a = ConvertFrom-Json $r.Content
    Check 'chat-200' ($r.StatusCode -eq 200) "HTTP $($r.StatusCode)"
    Check 'answer-nonempty' ($a.data.answer.Length -gt 0) "answerLen=$($a.data.answer.Length)"
    Check 'citations' ($a.data.citations.Count -ge 1 -and $a.data.citations[0].chunkId) "citations=$($a.data.citations.Count) firstChunk=$($a.data.citations[0].chunkId)"
} catch { Check 'chat-200' $false "exception: $($_.Exception.Message)" }

  Write-Host ""
  Write-Host "--- 4) plugin result appears in answer ---"
  # 注意：mock 模型按工具 description 里的中文"触发词"做规则触发（calculator 的触发词是"计算/等于多少/算一下"），
  # 因此这里必须用中文问句，否则不会触发插件——这与真实 LLM 的语义触发不同。
  $b2 = '{"question":"23*17+5 等于多少？","userId":"verify_p"}'
  try {
    $r2 = Invoke-WebRequest -Uri "$base/api/chat" -Method Post -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($b2)) -UseBasicParsing
    $a2 = ConvertFrom-Json $r2.Content
    if ($a2.data.pluginCalls.Count -ge 1) {
      Check 'plugin-triggered' $true "tool=$($a2.data.pluginCalls[0].tool) result=$($a2.data.pluginCalls[0].result)"
      Check 'plugin-in-answer' ($a2.data.answer -match '396') 'answer contains tool result'
    } else {
      Check 'plugin-triggered' $false 'no pluginCalls matched'
    }
} catch { Check 'plugin-triggered' $false "exception: $($_.Exception.Message)" }

  Write-Host ""
  Write-Host "--- 5) MD5 idempotency (re-import same content) ---"
  try {
    $u2 = Upload-Document $doc "verify"
    Check 'dedup-duplicate' ($u2.data.duplicate -eq $true) "duplicate=$($u2.data.duplicate)"
    Check 'dedup-same-doc'  ($u2.data.docId -eq $u.data.docId) "$($u.data.docId) == $($u2.data.docId)"
} catch { Check 'dedup-duplicate' $false "exception: $($_.Exception.Message)" }

  Write-Host ""
  Write-Host "--- 6) task history / ops endpoints ---"
  try {
    $hist = Invoke-RestMethod -Uri "$base/api/ops/tasks?limit=10"
    Check 'ops-tasks' ($hist.code -eq 200 -and $hist.data.count -ge 1) "count=$($hist.data.count)"
    $fs = Invoke-RestMethod -Uri "$base/api/ops/tasks/stats"
    Check 'ops-stats' ($fs.code -eq 200) 'stats returned'
} catch { Check 'ops-tasks' $false "exception: $($_.Exception.Message)" }

  Write-Host ""
  Write-Host "--- 7) chinese filename/content ---"
  try {
    $um = Upload-Document $cn "verify_cn"
    Check 'cn-filename' ($um.data.filename -eq 'verify-cn.txt') "filename=$($um.data.filename)"
    $stm = $null
    for ($i = 0; $i -lt 40; $i++) {
      Start-Sleep -Milliseconds 400
      $tm = Invoke-RestMethod -Uri "$base/api/tasks/$($um.data.taskId)"
      if ($tm.data.status -eq 'SUCCESS' -or $tm.data.status -eq 'FAILED') { $stm = $tm.data; break }
    }
    Check 'cn-import-SUCCESS' ($stm -and $stm.status -eq 'SUCCESS') "status=$($stm.status)"
    $cm = Invoke-RestMethod -Uri "$base/api/documents/$($um.data.docId)/chunks"
    $txt = $cm.data.chunks[0].content
    Check 'cn-no-mojibake' ($txt -match '[\u4e00-\u9fa5]' -and -not $txt.Contains([char]0xFFFD)) 'hasCJK and no U+FFFD'
} catch { Check 'cn-filename' $false "exception: $($_.Exception.Message)" }
}

Write-Host ""
Write-Host "--- 9) chunked upload / resume ---"
try {
  $utext = '# chunk smoke' + ('断点续传验证内容 abcdefghij. ' * 200)
  $ubytes = [System.Text.Encoding]::UTF8.GetBytes($utext)
  $uCH = 3072
  $utotal = $ubytes.Length
  $umd5 = [System.Security.Cryptography.MD5]::Create()
  $ufileMd5 = ($umd5.ComputeHash($ubytes) | ForEach-Object { $_.ToString('x2') }) -join ''
  $uinitBody = @{ filename='chunk-smoke.md'; totalSize=$utotal; chunkSize=$uCH; fileMd5=$ufileMd5; userId='verify' } | ConvertTo-Json
  $uinit = Invoke-RestMethod -Method Post -Uri "$base/api/uploads/init" -ContentType 'application/json' -Body $uinitBody
  Check 'chunk-init' ($uinit.code -eq 200) "totalChunks=$($uinit.data.totalChunks)"
  $uid = $uinit.data.uploadId
  $uN = [int]$uinit.data.totalChunks

  function Get-Part([int]$seq) {
    $s = $seq * $uCH
    $l = [Math]::Min($uCH, $utotal - $s)
    $b = New-Object byte[] $l
    [Array]::Copy($ubytes, $s, $b, 0, $l)
    return $b
  }

  $null = Upload-ChunkPart $uid 0 (Get-Part 0)
  $uprog = Invoke-RestMethod -Uri "$base/api/uploads/$uid/progress?userId=verify"
  Check 'chunk-progress' ($uprog.data.receivedCount -eq 1 -and $uprog.data.missingSeqs.Count -eq ($uN - 1)) "received=1 missing=$($uprog.data.missingSeqs -join ',')"

  foreach ($s2 in $uprog.data.missingSeqs) { $null = Upload-ChunkPart $uid $s2 (Get-Part $s2) }
  $uprog2 = Invoke-RestMethod -Uri "$base/api/uploads/$uid/progress?userId=verify"
  Check 'chunk-resume' ($uprog2.data.percent -eq 100) "percent=$($uprog2.data.percent)%"

  $null = Upload-ChunkPart $uid 0 (Get-Part 0)
  $uprog3 = Invoke-RestMethod -Uri "$base/api/uploads/$uid/progress?userId=verify"
  Check 'chunk-resend-idempotent' ($uprog3.data.receivedCount -eq $uN) "received=$($uprog3.data.receivedCount) total=$uN"

  $umg = Invoke-RestMethod -Method Post -Uri "$base/api/uploads/$uid/merge?userId=verify"
  Check 'chunk-merge' ($umg.code -eq 200) "docId=$($umg.data.docId)"
  $ustatus = ''
  for ($k = 0; $k -lt 40; $k++) {
    Start-Sleep -Milliseconds 500
    $ut = Invoke-RestMethod -Uri "$base/api/tasks/$($umg.data.taskId)"
    $ustatus = $ut.data.status
    if ($ustatus -eq 'SUCCESS' -or $ustatus -eq 'FAILED') { break }
  }
  Check 'chunk-task-success' ($ustatus -eq 'SUCCESS') "status=$ustatus"
  $uc = Invoke-RestMethod -Uri "$base/api/documents/$($umg.data.docId)/chunks"
  Check 'chunk-indexed' ($uc.data.count -ge 1) "chunks=$($uc.data.count)"

  $uinst = Invoke-RestMethod -Method Post -Uri "$base/api/uploads/init" -ContentType 'application/json' -Body $uinitBody
  Check 'chunk-instant-upload' ($uinst.data.instant -eq $true) 'instant=true（秒传命中）'

  # 已合并的会话再次 merge 应幂等返回同一 docId（不会产生第二份文档）
  $umg2 = Invoke-RestMethod -Method Post -Uri "$base/api/uploads/$uid/merge?userId=verify"
  Check 'chunk-merge-idempotent' ($umg2.data.docId -eq $umg.data.docId) "same docId=$($umg2.data.docId)"

  # 已合并会话不可 abort（业务约束：合并产物不能被回收）
  $abortRejected = $false
  try { $null = Invoke-RestMethod -Method Delete -Uri "$base/api/uploads/$uid?userId=verify" } catch { $abortRejected = $true }
  Check 'chunk-abort-after-merge-rejected' $abortRejected '已合并会话的 abort 被拒绝'
} catch {
  Check 'chunked-upload' $false "exception: $($_.Exception.Message)"
}
Write-Host ("  ---- checks: PASS=" + $script:pass + " FAIL=" + $script:fail + " ----")
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
