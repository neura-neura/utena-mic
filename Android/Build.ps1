param(
    [string]$OutDir = (Join-Path $PSScriptRoot '../dist'),
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Platform = 'android-36',
    [string]$BuildTools = '36.1.0',
    [string]$KeyFile = (Join-Path $env:TEMP 'utena-mic-local-build.pfx')
)
$ErrorActionPreference = 'Stop'
if (!$SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path
$platformJar = Join-Path $sdk "platforms/$Platform/android.jar"
$tools = Join-Path $sdk "build-tools/$BuildTools"
$build = Join-Path $PSScriptRoot 'build'
$out = (New-Item -ItemType Directory -Force $OutDir).FullName

New-Item -ItemType Directory -Force "$build/classes","$build/dex","$build/generated" | Out-Null
& "$tools/aapt2.exe" compile --dir "$PSScriptRoot/res" -o "$build/resources.zip"
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed.' }
& "$tools/aapt2.exe" link -I $platformJar --manifest "$PSScriptRoot/AndroidManifest.xml" --java "$build/generated" -o "$build/unsigned.apk" "$build/resources.zip"
if ($LASTEXITCODE -ne 0) { throw 'Manifest compilation failed.' }

$source = @(Get-ChildItem "$PSScriptRoot/src","$build/generated" -Filter '*.java' -Recurse | ForEach-Object FullName)
javac --release 8 -encoding UTF-8 -cp $platformJar -d "$build/classes" @source
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }

$classes = @(Get-ChildItem "$build/classes" -Filter '*.class' -Recurse | ForEach-Object FullName)
& "$tools/d8.bat" --min-api 26 --lib $platformJar --output "$build/dex" @classes
if ($LASTEXITCODE -ne 0) { throw 'Dex compilation failed.' }

jar uf "$build/unsigned.apk" -C "$build/dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Unable to package classes.dex.' }
& "$tools/zipalign.exe" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }

& "$PSScriptRoot/Sign-Apk.ps1" -UnsignedApk "$build/aligned.apk" -Apk "$out/UtenaMic.apk" -KeyFile $KeyFile
& "$tools/apksigner.bat" verify "$out/UtenaMic.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
