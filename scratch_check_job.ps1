$url = "https://github.com/danielolatenaranjo-art/sisbom/actions/runs/33584570897"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

if ($content -match 'href="(/danielolatenaranjo-art/sisbom/actions/runs/33584570897/job/\d+)"') {
    $jobUrl = "https://github.com" + $matches[1]
    Write-Output "JOB_URL: $jobUrl"
    
    $jobReq = Invoke-WebRequest -Uri $jobUrl -UseBasicParsing
    $jobContent = $jobReq.Content
    
    $lines = $jobContent -split "`n"
    $errLines = $lines | Select-String -Pattern 'error:'
    $errLines | Select-Object -First 10 | ForEach-Object { Write-Output $_.Line.Trim() }
}
