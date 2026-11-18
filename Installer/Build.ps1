param(
    [string]$OutDir = (Join-Path $PSScriptRoot '../dist'),
    [string]$VacArchive = (Join-Path $PSScriptRoot 'vac471lite.zip'),
    [string]$MakeNsis = "${env:ProgramFiles(x86)}\NSIS\makensis.exe"
)
$ErrorActionPreference = 'Stop'
$vac = (Resolve-Path -LiteralPath $VacArchive).Path
$out = (New-Item -ItemType Directory -Path $OutDir -Force).FullName
$staging = Join-Path ([IO.Path]::GetTempPath()) "utena-mic-release-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path $staging | Out-Null
$staging = (Resolve-Path -LiteralPath $staging).Path
try {
    foreach ($runtime in 'win-x64', 'win-arm64') {
        $payload = Join-Path $staging $runtime
        & "$PSScriptRoot/../Windows/Build.ps1" -Runtime $runtime -OutDir $payload
        $files = Get-ChildItem -LiteralPath $payload -File -Recurse
        $dirs = Get-ChildItem -LiteralPath $payload -Directory -Recurse | Sort-Object { $_.FullName.Length } -Descending
        $commands = foreach ($file in $files) {
            'Delete "$INSTDIR\' + [IO.Path]::GetRelativePath($payload, $file.FullName) + '"'
        }
        $commands += foreach ($dir in $dirs) {
            'RMDir "$INSTDIR\' + [IO.Path]::GetRelativePath($payload, $dir.FullName) + '"'
        }
        Set-Content -LiteralPath "$staging/$runtime-uninstall.nsh" -Value $commands -Encoding utf8
    }
    & $MakeNsis "/DOUTPUT=$out\UtenaMic-VAC-Setup.exe" "/DVAC_ARCHIVE=$vac" "/DPAYLOAD=$staging" "$PSScriptRoot/Setup.nsi"
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create the Windows installer.' }
}
finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($staging.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $staging -Leaf) -match '^utena-mic-release-[a-f0-9-]{36}$') {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
}
