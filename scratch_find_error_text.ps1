$url = "https://github.com/danielolatenaranjo-art/sisbom/actions/runs/33587594253/job/100114865633"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

$lines = $content -split "`n"
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'Annotations') {
        $start = [Math]::Max(0, $i - 5)
        $end = [Math]::Min($lines.Count - 1, $i + 40)
        for ($j = $start; $j -le $end; $j++) {
            Write-Output "[$j]: $($lines[$j].Trim())"
        }
    }
}
