# Assembles the release mod folders in dist\ so one download works on every build:
#   <mod>\mod.info + media\   Build 41 (reads the mod root)
#   <mod>\42\ + common\       Build 42 (reads the versioned folder)
# Run .\build.ps1 first (it builds the server jar into mod\KnoxLinkServer\42\media\java).
param([string]$Version = '1.1.0')
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
[IO.File]::WriteAllLines((Join-Path $t41 'UI_EN.txt'), [string[]]$lines, (New-Object Text.UTF8Encoding $false))
Remove-Item (Join-Path $t41 'UI.json')

# Zips with "/" in the paths: Windows PowerShell's ZipFile.CreateFromDirectory writes "\", which Linux,
# macOS and Android keep as part of the file name. Empty folders (Build 42's common\) go in too.
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
function New-ModZip([string]$zip, [string[]]$folders) {
    $a = [IO.Compression.ZipFile]::Open($zip, 'Create')
    try {
        foreach ($f in $folders) {
            $base = (Split-Path -Parent $f).Length + 1
            Get-ChildItem $f -Recurse -Force | Sort-Object FullName | ForEach-Object {
                $rel = $_.FullName.Substring($base).Replace('\', '/')
                if (-not $_.PSIsContainer) {
                    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($a, $_.FullName, $rel, 'Optimal')
                } elseif (-not (Get-ChildItem $_.FullName -Force)) {
                    [void]$a.CreateEntry("$rel/")
                }
            }
        }
    } finally {
        $a.Dispose()
    }
}

$zip = Join-Path $dist "KnoxLink-$Version.zip"
New-ModZip $zip @((Join-Path $dist 'KnoxLink'), (Join-Path $dist 'KnoxLinkServer'))
"packaged $zip"

# the release downloads: the mods, Viewpoint on Android (docs\THOR-SETUP.md) and the Android app
$release = Join-Path $dist 'release'
New-Item -ItemType Directory -Force $release | Out-Null
Copy-Item $zip $release
$t3d = Join-Path $dist 'KnoxThor3D'
Copy-Item (Join-Path $Root 'extras\KnoxThor3D\mod') $t3d -Recurse
New-Item -ItemType Directory -Force (Join-Path $t3d 'common') | Out-Null
New-ModZip (Join-Path $release 'KnoxThor3D.zip') @($t3d)
$apk = Join-Path $Root 'build\KnoxLink.apk'
if (Test-Path $apk) { Copy-Item $apk $release } else { 'no build\KnoxLink.apk: run .\build-apk.ps1 first for the app' }
"release files in $release"
