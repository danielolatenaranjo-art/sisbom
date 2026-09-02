$url = "https://github.com/danielolatenaranjo-art/SisBom/actions/runs/33583613618"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

$lines = $content -split "`n"
$matches = $lines | Select-String -Pattern '(completed|failure|success|running|queued|in progress|artifact)'
$matches | Select-Object -First 10 | ForEach-Object { Write-Output $_.Line.Trim() }
