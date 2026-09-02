$url = "https://github.com/danielolatenaranjo-art/sisbom/actions/runs/33586585114/job/100111897096"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

$lines = $content -split "`n"
$matches = $lines | Select-String -Pattern '(error|failed|failure|LOG START|Swift|warning)'
Write-Output "MATCHES COUNT: $($matches.Count)"
$matches | Select-Object -First 30 | ForEach-Object { Write-Output $_.Line.Trim() }
