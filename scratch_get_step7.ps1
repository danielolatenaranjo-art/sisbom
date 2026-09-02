$url = "https://github.com/danielolatenaranjo-art/sisbom/commit/44d3913e8c03b224739e0e1064416790b5f6c225/checks/100117462791/logs/7"
$headers = @{
    'User-Agent' = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    'Accept' = 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
}
try {
    $req = Invoke-WebRequest -Uri $url -Headers $headers -UseBasicParsing
    $content = $req.Content
    Set-Content -Path "step7_log.txt" -Value $content

    $lines = $content -split "`n"
    Write-Output "TOTAL LINES: $($lines.Count)"
    $matches = $lines | Select-String -Pattern '(error:|fatal error:|=== XCODEBUILD|BUILD FAILED)'
    foreach ($m in $matches) {
        Write-Output $m.Line
    }
} catch {
    Write-Output "Exception: $_"
}
