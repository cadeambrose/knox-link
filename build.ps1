# Knox Link build (Windows). Produces mod\KnoxLinkServer\42\media\java\client\KnoxLinkServer.jar
# with the web app bundled inside. Needs JDK 25 (the game's Java version) and the game's projectzomboid.jar.
#
#   .\build.ps1                       # build
#   .\build.ps1 -Dev                  # also build the dev server (fake data, for web work)
#   $env:PZ_DIR = "D:\Games\ProjectZomboid"; .\build.ps1
param([switch]$Dev)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

$jdk = $env:JAVA_HOME_25
if (-not $jdk) { $jdk = (Get-ChildItem "$Root\.tools" -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName }
if (-not $jdk) { throw 'JDK 25 not found: set JAVA_HOME_25 or unpack one into .tools\' }
$pz = $env:PZ_DIR
if (-not $pz) { $pz = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid' }
$gameJar = Join-Path $pz 'projectzomboid.jar'
if (-not (Test-Path $gameJar)) { throw "projectzomboid.jar not found in $pz (set PZ_DIR)" }

$build = Join-Path $Root 'build'
$classes = Join-Path $build 'classes'
Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classes | Out-Null

$src = @(Get-ChildItem "$Root\java\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
# ZombieBuddy's API (compile only; the game provides it at run time): for the bug-fix patches in Tune.java
$zbJar = $env:ZOMBIEBUDDY_JAR
if (-not $zbJar) { $zbJar = (Get-ChildItem 'C:\Program Files (x86)\Steam\steamapps\workshop\content\108600' -Recurse -Filter 'ZombieBuddy.jar' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName }
if (-not $zbJar -or -not (Test-Path $zbJar)) { throw 'ZombieBuddy.jar not found: subscribe to ZombieBuddy on the Workshop or set ZOMBIEBUDDY_JAR' }
$cp = $gameJar + [IO.Path]::PathSeparator + $zbJar
& "$jdk\bin\javac.exe" --release 17 -encoding UTF-8 -Xlint:-options -nowarn -cp $cp -d $classes $src
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

# web app goes inside the jar under /web
Copy-Item "$Root\web" (Join-Path $classes 'web') -Recurse

$out = Join-Path $Root 'mod\KnoxLinkServer\42\media\java\client'
New-Item -ItemType Directory -Force $out | Out-Null
$jar = Join-Path $out 'KnoxLinkServer.jar'
Remove-Item $jar -ErrorAction SilentlyContinue
# fixed timestamps: the same sources always give the same jar (reproducible build)
& "$jdk\bin\jar.exe" --create --file $jar --date=2026-01-01T00:00:00Z -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
foreach ($m in 'KnoxLink', 'KnoxLinkServer') { New-Item -ItemType Directory -Force (Join-Path $Root "mod\$m\common") | Out-Null }
$hash = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower()
"built $jar"
"sha256 $hash"

if ($Dev) {
    $devOut = Join-Path $build 'dev'
    Remove-Item $devOut -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $devOut | Out-Null
    $devSrc = @(Get-ChildItem "$Root\java\dev" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    $devCp = $classes + [IO.Path]::PathSeparator + $gameJar + [IO.Path]::PathSeparator + $zbJar
    & "$jdk\bin\javac.exe" --release 17 -encoding UTF-8 -nowarn -cp $devCp -d $devOut $devSrc
    if ($LASTEXITCODE -ne 0) { throw 'dev javac failed' }
    "dev server built in $devOut"
}
