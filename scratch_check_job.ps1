$url = "https://github.com/danielolatenaranjo-art/sisbom/actions/runs/33585506491"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

if ($content -match 'href="(/danielolatenaranjo-art/sisbom/actions/runs/33585506491/job/\d+)"') {
    $jobUrl = "https://github.com" + $matches[1]
    Write-Output "JOB_URL: $jobUrl"
    
    $jobId = $matches[1] -replace '.*/job/',''
    Write-Output "JOB_ID: $jobId"
}
