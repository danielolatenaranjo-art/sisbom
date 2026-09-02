$url = "https://github.com/danielolatenaranjo-art/sisbom/actions/runs/33586585114/job/100111897096"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

$lines = $content -split "`n"
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '1 error') {
        $start = [Math]::Max(0, $i - 10)
        $end = [Math]::Min($lines.Count - 1, $i + 40)
        for ($j = $start; $j -le $end; $j++) {
            Write-Output "[$j]: $($lines[$j].Trim())"
        }
    }
}
