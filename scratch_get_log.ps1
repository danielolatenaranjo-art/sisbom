$uri = "https://api.github.com/repos/danielolatenaranjo-art/SisBom/actions/jobs/100108702388/logs"
try {
    $res = Invoke-WebRequest -Uri $uri -Headers @{'User-Agent'='PowerShell'} -MaximumRedirection 5
    $log = $res.Content
    Set-Content -Path "job_log.txt" -Value $log
    $lines = $log -split "`n"
    Write-Output "TOTAL LOG LINES: $($lines.Count)"
    $errs = $lines | Select-String -Pattern 'error:'
    Write-Output "ERRORS FOUND: $($errs.Count)"
    $errs | Select-Object -First 30 | ForEach-Object { Write-Output $_.Line }
} catch {
    Write-Output "Error fetching log: $_"
}
