# 真实大模型接入冒烟（需先用环境变量启动应用，见同目录 start-real-llm.cmd）
# 覆盖: 资料命中问答(带引用) / Function Calling(calculator) / Function Calling(datetime) / 长文检索
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18099'
$script:pass = 0; $script:fail = 0
function Check($n, $c, $d) {
  if ($c) { Write-Host "  PASS  $n :: $d" -ForegroundColor Green; $script:pass++ }
  else { Write-Host "  FAIL  $n :: $d" -ForegroundColor Red; $script:fail++ }
}
Write-Host '=== 真实大模型接入冒烟 ==='

# 0) 确认 provider 已是真实模型
$h = Invoke-RestMethod -Uri "$base/api/health"
Check 'llm-provider-real' ($h.data.llmProvider -eq 'openai') "llmProvider=$($h.data.llmProvider) model=$($h.data.chatModel)"
Check 'embedding-fallback' ($h.data.embeddingModel -like '*bigram*') "embeddingModel=$($h.data.embeddingModel)（网关无 /embeddings 时的混合模式）"

# 1) 导知识文档
$tmp = Join-Path $env:TEMP 'real-llm-doc.md'
@'
# Java 后端核心知识

## Redis 缓存三大问题
缓存穿透：查询数据库中不存在的数据，请求绕过缓存直达数据库。解决方案：布隆过滤器或缓存空值。
缓存击穿：热点 key 过期瞬间大量并发请求打库。解决方案：互斥锁或逻辑过期。
缓存雪崩：大量 key 同时过期。解决方案：过期时间加随机值、多级缓存、集群高可用。

## RocketMQ 可靠性
消息丢失三个环节：生产端发送失败、Broker 存储丢失、消费端处理失败。对应手段：同步发送加失败重试、同步刷盘加主从复制、消费成功后手动 ACK。
'@ | Set-Content -Path $tmp -Encoding UTF8

$boundary = [System.Guid]::NewGuid().ToString()
$bytes = [System.IO.File]::ReadAllBytes($tmp)
$enc = [System.Text.Encoding]::UTF8
$head = "--$boundary`r`n" + "Content-Disposition: form-data; name=`"file`"; filename=`"real-llm-doc.md`"`r`n" + "Content-Type: application/octet-stream`r`n`r`n"
$ms = New-Object System.IO.MemoryStream
$hb = $enc.GetBytes($head); $ms.Write($hb, 0, $hb.Length)
$ms.Write($bytes, 0, $bytes.Length)
$tb = $enc.GetBytes("`r`n--$boundary--`r`n"); $ms.Write($tb, 0, $tb.Length)
$payload = $ms.ToArray(); $ms.Dispose()
$up = Invoke-RestMethod -Uri "$base/api/documents?userId=realverify" -Method Post -ContentType "multipart/form-data; boundary=$boundary" -Body $payload
Check 'upload-accepted' ($up.code -eq 200) "docId=$($up.data.docId)"
for ($i = 0; $i -lt 40; $i++) { Start-Sleep -Milliseconds 500; $t = Invoke-RestMethod -Uri "$base/api/tasks/$($up.data.taskId)"; if ($t.data.status -eq 'SUCCESS') { break } }
Check 'index-built' ($t.data.status -eq 'SUCCESS') "status=$($t.data.status)"

function Ask($q) {
  $body = @{ question = $q; userId = ('rv' + (Get-Random)) } | ConvertTo-Json
  return Invoke-RestMethod -Uri "$base/api/chat" -Method Post -ContentType 'application/json' -Body $body
}

Write-Host '[1] 资料命中问答（应带 [n] 引用）'
$a1 = Ask '缓存击穿和缓存穿透有什么区别？'
Check 'rag-answer' ($a1.code -eq 200 -and $a1.data.answer.Length -gt 20) "answerLen=$($a1.data.answer.Length)"
Check 'rag-citations' ($a1.data.citations.Count -ge 1) "citations=$($a1.data.citations.Count)"
Check 'rag-has-ref-marker' ($a1.data.answer -match '\[1\]') 'answer 含 [1] 引用标注'

Write-Host '[2] Function Calling: calculator（真实模型自主决策调用）'
$a2 = Ask '帮我算一下 23*17+5 等于多少'
Check 'tool-calculator-called' ($a2.data.pluginCalls.Count -ge 1 -and $a2.data.pluginCalls[0].tool -eq 'calculator') "tool=$($a2.data.pluginCalls[0].tool) result=$($a2.data.pluginCalls[0].result)"
Check 'tool-result-in-answer' ($a2.data.answer -match '396') 'answer 含计算结果 396（工具结果被采纳，未拒答）'

Write-Host '[3] Function Calling: datetime'
$a3 = Ask '现在几点了？'
Check 'tool-datetime-called' ($a3.data.pluginCalls.Count -ge 1 -and $a3.data.pluginCalls[0].tool -eq 'datetime') "tool=$($a3.data.pluginCalls[0].tool)"
Check 'datetime-in-answer' ($a3.data.answer -match '\d{4}-\d{2}-\d{2}') 'answer 含日期时间'

Write-Host '[4] 长文检索'
$a4 = Ask 'RocketMQ 消息可能在哪几个环节丢失？'
Check 'longdoc-retrieval' ($a4.data.answer -match '生产端') 'answer 命中长文档内容'

Write-Host ("  ---- checks: PASS=" + $script:pass + " FAIL=" + $script:fail + " ----")
if ($script:fail -gt 0) { exit 1 }