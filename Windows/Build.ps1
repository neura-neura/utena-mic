param(
    [ValidateSet('win-x64', 'win-arm64')]
    [string]$Runtime = 'win-x64',
    [string]$OutDir = (Join-Path $PSScriptRoot "publish/$Runtime")
)
$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot 'UtenaMic.csproj'
dotnet publish $project -c Release -r $Runtime --self-contained true -p:DebugType=None -p:DebugSymbols=false -o $OutDir
if ($LASTEXITCODE -ne 0) { throw "Unable to publish Utena Mic for $Runtime." }
