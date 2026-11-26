$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$stage = Join-Path $root 'artifacts\module-stage'
$properties = Get-Content -Raw -LiteralPath (Join-Path $root 'module\module.prop') | ConvertFrom-StringData
$version = $properties.version
if ($version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+-dev$') { throw 'Invalid module version' }
$update = Get-Content -Raw -LiteralPath (Join-Path $root 'update.json') | ConvertFrom-Json
$tag = 'v' + ($version -replace '-dev$', '')
if ($update.version -cne $version -or $update.versionCode -ne [int]$properties.versionCode -or
    $update.zipUrl -cne "https://github.com/chxcodepro/claude-privacy/releases/download/$tag/claude-privacy-$version.zip") {
    throw 'update.json does not match the module version and ZIP name'
}
$artifact = Join-Path $root "artifacts\claude-privacy-$version.zip"
$artifactDir = [IO.Path]::GetFullPath((Join-Path $root 'artifacts'))
$stageFull = [IO.Path]::GetFullPath($stage)
if (-not $stageFull.StartsWith($artifactDir + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Module stage is outside artifacts directory'
}

Push-Location $root
try {
    & (Join-Path $root 'node_modules\.bin\vp.cmd') build apps/web
    if ($LASTEXITCODE -ne 0) { throw 'WebUI build failed' }
    Push-Location (Join-Path $root 'hook')
    try {
        & .\gradlew.bat :app:assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Hook APK build failed' }
    } finally { Pop-Location }

    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
    if (Test-Path -LiteralPath $stageFull) { Remove-Item -LiteralPath $stageFull -Recurse -Force }
    Copy-Item -LiteralPath (Join-Path $root 'module') -Destination $stageFull -Recurse
    Copy-Item -LiteralPath (Join-Path $root 'apps\web\src\default-config.json') -Destination (Join-Path $stageFull 'default-config.json')
    Copy-Item -LiteralPath (Join-Path $root 'hook\app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $stageFull 'hook.apk')
    Copy-Item -LiteralPath (Join-Path $root 'apps\web\dist') -Destination (Join-Path $stageFull 'webroot') -Recurse
    if (Test-Path -LiteralPath $artifact) { Remove-Item -LiteralPath $artifact -Force }
    Compress-Archive -Path (Join-Path $stageFull '*') -DestinationPath $artifact -CompressionLevel Optimal
    Write-Output $artifact
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $stageFull) { Remove-Item -LiteralPath $stageFull -Recurse -Force }
}
