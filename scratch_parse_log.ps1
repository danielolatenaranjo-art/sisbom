$url = "https://github.com/danielolatenaranjo-art/SisBom/actions/runs/33583613618"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

$matches = [regex]::Matches($content, 'href="([^"]*artifacts/[^"]*)"')
foreach ($m in $matches) {
    Write-Output $m.Groups[1].Value
}
