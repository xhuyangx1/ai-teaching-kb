# 分片上传（断点续传）冒烟脚本 —— 需应用已在 127.0.0.1:18095 运行
# 用法: powershell -ExecutionPolicy Bypass -File tools\chunk-upload-smoke.ps1
$ErrorActionPreference = 'Stop'
$B = 'http://127.0.0.1:18095'
$pass = 0; $fail = 0
function Check($name, $cond, $detail) {
  if ($cond) { Write-Host "  PASS  $name :: $detail" -ForegroundColor Green; $script:pass++ }
  else { Write-Host "  FAIL  $name :: $detail" -ForegroundColor Red; $script:fail++ }
}
Write-Host '=== 分片上传 / 断点续传冒烟 ==='

$text = '# 断点续传测试' + ('验证分片上传与断点续传的中文内容。' * 150)
$bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
$CH = 3072
$total = $bytes.Length
$totalChunks = [Math]::Ceiling($total / $CH)
$md5 = [System.Security.Cryptography.MD5]::Create()
$fileMd5 = ($md5.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''

Write-Host "[1] init 文件 $total 字节 / $totalChunks 片"
$initBody = @{ filename='duan.md'; totalSize=$total; chunkSize=$CH; fileMd5=$fileMd5; userId='smoke' } | ConvertTo-Json
$init = Invoke-RestMethod -Method Post -Uri "$B/api/uploads/init" -ContentType 'application/json' -Body $initBody
Check 'init-200' ($init.code -eq 200) "uploadId=$($init.data.uploadId) totalChunks=$($init.data.totalChunks)"
$uploadId = $init.data.uploadId

function Put-Chunk($seq) {
  $start = $seq * $CH
  $len = [Math]::Min($CH, $total - $start)
  $slice = New-Object byte[] $len
  [Array]::Copy($bytes, $start, $slice, 0, $len)
  $tmp = Join-Path $env:TEMP "chunk_$seq.bin"
  [System.IO.File]::WriteAllBytes($tmp, $slice)
  $form = @{ chunk = Get-Item $tmp }
  return Invoke-RestMethod -Method Put -Uri "$B/api/uploads/$uploadId/chunks/$seq`?userId=smoke" -Form $form
}

Write-Host '[2] 只传前 2 片（模拟中断）'
$r0 = Put-Chunk 0; $r1 = Put-Chunk 1
Check 'partial-2' ($r1.data.receivedCount -eq 2) "received=$($r1.data.receivedCount)"

Write-Host '[3] 断点查进度'
$prog = Invoke-RestMethod -Uri "$B/api/uploads/$uploadId/progress?userId=smoke"
Check 'progress-missing' (($prog.data.missingSeqs -join ',') -eq '2,3,4') "received=[$($prog.data.receivedSeqs -join ',')] missing=[$($prog.data.missingSeqs -join ',')]"

Write-Host '[4] 只补缺失分片'
foreach ($s in $prog.data.missingSeqs) { $null = Put-Chunk $s }
$prog2 = Invoke-RestMethod -Uri "$B/api/uploads/$uploadId/progress?userId=smoke"
Check 'resume-complete' ($prog2.data.percent -eq 100) "percent=$($prog2.data.percent)%"

Write-Host '[5] 重传已传分片（幂等）'
$again = Put-Chunk 0
Check 'resend-idempotent' ($again.data.receivedCount -eq $totalChunks) "received=$($again.data.receivedCount)"

Write-Host '[6] merge'
$mg = Invoke-RestMethod -Method Post -Uri "$B/api/uploads/$uploadId/merge?userId=smoke"
Check 'merge-200' ($mg.code -eq 200) "docId=$($mg.data.docId)"
$docId = $mg.data.docId; $taskId = $mg.data.taskId

Write-Host '[7] 等任务完成'
$status = ''
for ($i = 0; $i -lt 30; $i++) {
  Start-Sleep -Milliseconds 500
  $t = Invoke-RestMethod -Uri "$B/api/tasks/$taskId"
  $status = $t.data.status
  if ($status -eq 'SUCCESS' -or $status -eq 'FAILED') { break }
}
Check 'task-success' ($status -eq 'SUCCESS') "status=$status"
$ch = Invoke-RestMethod -Uri "$B/api/documents/$docId/chunks"
Check 'chunks-created' ($ch.data.count -ge 1) "chunks=$($ch.data.count)"

Write-Host '[8] 秒传预判'
$instant = Invoke-RestMethod -Method Post -Uri "$B/api/uploads/init" -ContentType 'application/json' -Body $initBody
Check 'instant-upload' ($instant.data.instant -eq $true) "instant=$($instant.data.instant)"

Write-Host '[9] 错误路径：非法文件类型'
try {
  $badBody = @{ filename='x.exe'; totalSize=100; userId='smoke' } | ConvertTo-Json
  $null = Invoke-RestMethod -Method Post -Uri "$B/api/uploads/init" -ContentType 'application/json' -Body $badBody
  Check 'reject-type' $false 'should reject .exe'
} catch { Check 'reject-type' $true "rejected" }

Write-Host ("  ---- checks: PASS=" + $pass + " FAIL=" + $fail + " ----")
if ($fail -gt 0) { exit 1 }