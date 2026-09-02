$url = "https://github.com/danielolatenaranjo-art/sisbom/actions/runs/33589362722"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

$lines = $content -split "`n"
$found = $false
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'Build Failed') {
        $found = $true
        $start = [Math]::Max(0, $i - 2)
        $end = [Math]::Min($lines.Count - 1, $i + 60)
        for ($j = $start; $j -le $end; $j++) {
            Write-Output "[$j]: $($lines[$j].Trim())"
        }
        break
    }
}
if (-not $found) {
    Write-Output "Build Failed section not found in summary, printing first 20 lines with 'error':"
    $lines | Select-String -Pattern 'error' | Select-Object -First 20 | ForEach-Object { Write-Output $_.Line.Trim() }
}
