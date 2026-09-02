$url = "https://github.com/danielolatenaranjo-art/sisbom/actions/runs/33634967494"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

$lines = $content -split "`n"
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'Xcode Compilation Error' -or $lines[$i] -match 'Annotations' -or $lines[$i] -match 'error') {
        $start = [Math]::Max(0, $i - 2)
        $end = [Math]::Min($lines.Count - 1, $i + 15)
        for ($j = $start; $j -le $end; $j++) {
            Write-Output "[$j]: $($lines[$j].Trim())"
        }
    }
}
