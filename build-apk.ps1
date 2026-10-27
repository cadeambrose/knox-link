# Builds the Knox Link Android app (android/) into build\KnoxLink.apk with the plain SDK tools
# (no Gradle, no Android Studio). Needs: Android SDK build-tools 35 + platform 35, and a JDK 17+.
#
#   .\build-apk.ps1
#   $env:ANDROID_SDK = "C:\Android\Sdk"; .\build-apk.ps1
param()
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = $env:ANDROID_SDK
if (-not $sdk) { $sdk = Join-Path $Root '.tools\android-sdk' }
$bt = Join-Path $sdk 'build-tools\35.0.0'
$androidJar = Join-Path $sdk 'platforms\android-35\android.jar'
if (-not (Test-Path $androidJar)) { throw "Android platform 35 not found under $sdk (set ANDROID_SDK)" }
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "$env:LOCALAPPDATA\Programs\Eclipse Adoptium" -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

$out = Join-Path $Root 'build\apk'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

# 1. resources
& "$bt\aapt2.exe" compile --dir (Join-Path $Root 'android\res') -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $androidJar --manifest (Join-Path $Root 'android\AndroidManifest.xml') `
    --java "$out\gen" --min-sdk-version 26 --target-sdk-version 35 --auto-add-overlay "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

# 2. java -> class -> dex
$src = @(Get-ChildItem (Join-Path $Root 'android\src'), "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& $javac --release 11 -encoding UTF-8 -nowarn -Xlint:none -cp $androidJar -d "$out\classes" $src
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
# one jar of all classes: listing every class file overflows the Windows command line
$classesJar = "$out\classes.jar"
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory("$out\classes", $classesJar)
& "$bt\d8.bat" --release --min-api 26 --lib $androidJar --output "$out\dex" $classesJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

# 3. add classes.dex and the assets (the game's fonts with their licence texts), align, sign.
# The assets go in by hand: aapt2's -A on Windows names them with backslashes, which Android can't find.
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') | Out-Null
$assets = Join-Path $Root 'android\assets'
if (Test-Path $assets) {
    Get-ChildItem $assets -Recurse -File | ForEach-Object {
        $name = 'assets/' + $_.FullName.Substring($assets.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $name) | Out-Null
    }
}
$zip.Dispose()
& "$bt\zipalign.exe" -p -f 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$ks = $env:KNOXLINK_KEYSTORE
$pass = $env:KNOXLINK_KEYPASS
if (-not $ks) {
    # local test key; release builds pass their own keystore through the two variables above
    $ks = Join-Path $Root '.tools\knoxlink-debug.keystore'
    $pass = 'android'
    if (-not (Test-Path $ks)) {
        & $keytool -genkeypair -keystore $ks -alias knoxlink -keyalg RSA -keysize 2048 -validity 10000 `
            -storepass $pass -keypass $pass -dname 'CN=Knox Link debug' | Out-Null
    }
}
$apk = Join-Path $Root 'build\KnoxLink.apk'
& "$bt\apksigner.bat" sign --ks $ks --ks-pass "pass:$pass" --key-pass "pass:$pass" --out $apk "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
"built $apk ($([math]::Round((Get-Item $apk).Length / 1KB)) KB)"
