$url = "https://github.com/danielolatenaranjo-art/SisBom/actions"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

if ($content -match 'href="(/danielolatenaranjo-art/SisBom/actions/runs/\d+)"') {
    $runPath = $matches[1]
    $runUrl = "https://github.com" + $runPath
    Write-Output "LATEST_RUN_URL: $runUrl"

    $runReq = Invoke-WebRequest -Uri $runUrl -UseBasicParsing
    $runContent = $runReq.Content

    if ($runContent -match 'MiSisBom-iOS-IPA') {
        Write-Output "IPA_ARTIFACT: SUCCESS_FOUND"
    } else {
        Write-Output "IPA_ARTIFACT: NOT_FOUND"
    }

    if ($runContent -match '<span class="h4 color-fg-default">([^<]+)</span>') {
        Write-Output "STATUS: $($matches[1])"
    }
} else {
    Write-Output "NO RUN FOUND"
}
