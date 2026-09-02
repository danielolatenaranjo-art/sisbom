$url = "https://github.com/danielolatenaranjo-art/sisbom/actions/runs/33590270506"
$req = Invoke-WebRequest -Uri $url -UseBasicParsing
$content = $req.Content

$lines = $content -split "`n"
$found = $false
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'Build Succeeded' -or $lines[$i] -match 'Build Failed' -or $lines[$i] -match 'xcodebuild') {
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
    Write-Output "SUMMARY NOT MATCHED, SEARCHING FOR CODE BLOCKS OR ERROR STRINGS:"
    $codeMatches = $lines | Select-String -Pattern '(error:|fatal error:|BUILD FAILED)'
    $codeMatches | Select-Object -First 30 | ForEach-Object { Write-Output $_.Line.Trim() }
}
