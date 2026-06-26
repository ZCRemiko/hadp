# PowerShell: 生成模拟日志数据并发送到 Collector API
# 用于测试整个数据链路
#
# 用法: .\scripts\generate-sample-data.ps1

param(
    [int]$count = 100,
    [string]$apiUrl = "http://localhost:8080/api/logs/batch"
)

Write-Host "========================================"  -ForegroundColor Cyan
Write-Host "  HADP 模拟数据生成器" -ForegroundColor Cyan
Write-Host "  生成 $count 条日志事件" -ForegroundColor Cyan
Write-Host "  目标API: $apiUrl" -ForegroundColor Cyan
Write-Host "========================================"  -ForegroundColor Cyan

# 模拟的用户列表
$users = @("user_001", "user_002", "user_003", "user_004", "user_005",
           "user_006", "user_007", "user_008", "user_009", "user_010")

# 模拟的页面列表
$pages = @("/home", "/products", "/products/detail", "/about",
           "/contact", "/search", "/cart", "/checkout", "/profile", "/settings")

# 模拟的事件类型
$events = @("page_view", "click", "search", "purchase")

# 模拟的 Referrer 来源
$referrers = @("https://www.google.com", "https://www.baidu.com",
               "https://www.bing.com", "", "https://twitter.com")

$events_list = @()
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

for ($i = 0; $i -lt $count; $i++) {
    $userId = $users | Get-Random
    $pageUrl = $pages | Get-Random
    $eventType = $events | Get-Random
    $referrer = $referrers | Get-Random

    # 每条事件时间戳随机偏移(24小时内)
    $timestamp = $now - (Get-Random -Maximum 86400000)

    $event = @{
        userId    = $userId
        eventType = $eventType
        pageUrl   = $pageUrl
        referrer  = $referrer
        ipAddress = "192.168.1.$((Get-Random -Maximum 255))"
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        timestamp = $timestamp
        duration  = Get-Random -Minimum 1000 -Maximum 60000
    }

    $events_list += $event
}

# 序列化为JSON
$json = $events_list | ConvertTo-Json -Depth 3 -Compress

Write-Host "正在发送 $count 条事件..." -ForegroundColor Yellow

try {
    $response = Invoke-RestMethod -Uri $apiUrl `
        -Method Post `
        -Body $json `
        -ContentType "application/json" `
        -TimeoutSec 30

    Write-Host "响应: total=$($response.total), success=$($response.success), failed=$($response.failed)" -ForegroundColor Green
    Write-Host "数据生成完成!" -ForegroundColor Green
    Write-Host ""
    Write-Host "下一步:" -ForegroundColor Cyan
    Write-Host "  1. 在 HDFS Web UI 中查看日志: http://localhost:9870" -ForegroundColor White
    Write-Host "  2. 运行 MapReduce 分析: docker exec -it resourcemanager bash scripts/run-analytics.sh" -ForegroundColor White
    Write-Host "  3. 查询结果: curl http://localhost:8081/api/stats/daily?date=$(Get-Date -Format 'yyyy-MM-dd')" -ForegroundColor White

} catch {
    Write-Host "发送失败: $_" -ForegroundColor Red
    Write-Host "请确保 Collector 服务已启动 (端口 8080)" -ForegroundColor Yellow
}
