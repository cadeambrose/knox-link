# Assembles the release mod folders in dist\ so one download works on every build:
#   <mod>\mod.info + media\   Build 41 (reads the mod root)
#   <mod>\42\ + common\       Build 42 (reads the versioned folder)
# Run .\build.ps1 first (it builds the server jar into mod\KnoxLinkServer\42\media\java).
param([string]$Version = '1.0.0')
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$dist = Join-Path $Root 'dist'
Remove-Item $dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dist | Out-Null

foreach ($m in 'KnoxLink', 'KnoxLinkServer') {
    $src = Join-Path $Root "mod\$m"
    $dst = Join-Path $dist $m
    Copy-Item $src $dst -Recurse
    # Build 41 copy at the mod root
    Copy-Item (Join-Path $src '42\media') (Join-Path $dst 'media') -Recurse
    foreach ($img in 'icon.png', 'poster.png') {
        $p = Join-Path $src "42\$img"
        if (Test-Path $p) { Copy-Item $p (Join-Path $dst $img) }
    }
    $info42 = Get-Content (Join-Path $src '42\mod.info')
    $info41 = $info42 | Where-Object { $_ -notmatch '^versionMin=' } | ForEach-Object {
        # Build 41 lists required mods without the leading backslash
        if ($_ -match '^require=') { $_ -replace '\\', '' } else { $_ }
    }
    Set-Content (Join-Path $dst 'mod.info') $info41 -Encoding ascii
    New-Item -ItemType Directory -Force (Join-Path $dst 'common') | Out-Null
}

# Build 41 reads translations from UI_EN.txt (a Lua table), Build 42 from UI.json
$json = Get-Content (Join-Path $Root 'mod\KnoxLink\42\media\lua\shared\Translate\EN\UI.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$lines = @('UI_EN = {')
foreach ($p in $json.PSObject.Properties) { $lines += "    $($p.Name) = `"$($p.Value -replace '"', '\"')`"," }
$lines += '}'
$t41 = Join-Path $dist 'KnoxLink\media\lua\shared\Translate\EN'
Set-Content (Join-Path $t41 'UI_EN.txt') $lines -Encoding UTF8
Remove-Item (Join-Path $t41 'UI.json')

$zip = Join-Path $dist "KnoxLink-$Version.zip"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$tmp = Join-Path $env:TEMP "knoxlink-zip-$PID"
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $tmp | Out-Null
Copy-Item (Join-Path $dist 'KnoxLink'), (Join-Path $dist 'KnoxLinkServer') $tmp -Recurse
[IO.Compression.ZipFile]::CreateFromDirectory($tmp, $zip)
Remove-Item $tmp -Recurse -Force
"packaged $zip"
