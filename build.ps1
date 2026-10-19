param([string[]]$CompatibilityApks = @())

$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$javaHome = 'C:\Users\desktop\.jdks\jbr-17.0.14'
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = 'C:\Users\desktop\AppData\Local\Android\Sdk'

$bashPath = 'C:\Program Files\Git\bin\bash.exe'
if (-not (Test-Path -LiteralPath $bashPath)) {
    $bashPath = (Get-Command bash -ErrorAction Stop).Source
}
Push-Location -LiteralPath $projectRoot
try {
    & $bashPath 'tests/guard_test.sh'
    if ($LASTEXITCODE -ne 0) { throw 'Root guard mock tests failed' }
    & $bashPath 'tests/control_test.sh'
    if ($LASTEXITCODE -ne 0) { throw 'UI control mock tests failed' }
} finally {
    Pop-Location
}

# Preserve local APK analysis under build/; run the shared-source unit tests too.
$gradleArguments = @('-p', $projectRoot, '--no-daemon', 'testDebugUnitTest', 'assembleRelease')
if ($CompatibilityApks.Count -gt 0) {
    foreach ($compatibilityApk in $CompatibilityApks) {
        if (-not (Test-Path -LiteralPath $compatibilityApk -PathType Leaf)) {
            throw "Compatibility APK not found: $compatibilityApk"
        }
    }
    $gradleArguments += '-PcompatibilityApks=' + ($CompatibilityApks -join [IO.Path]::PathSeparator)
}
& "$projectRoot\gradlew.bat" @gradleArguments
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$apk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $apk)) {
    throw "Signed release APK was not produced: $apk"
}

$dist = Join-Path $projectRoot 'dist'
$metadata = Get-Content -Raw -LiteralPath (Join-Path (Split-Path $apk) 'output-metadata.json') | ConvertFrom-Json
$version = $metadata.elements[0].versionName
if ($version -notmatch '^\d+\.\d+\.\d+$') {
    throw 'Invalid APK version metadata'
}
$staging = [IO.Path]::GetFullPath((Join-Path $dist "staging-v$version"))
$moduleSource = Join-Path $projectRoot 'module'
$apkTargetDir = Join-Path $staging 'system\app\ImeiPrivacy'
$zip = Join-Path $dist "IMEI-Privacy-v$version.zip"
$standaloneApk = Join-Path $dist "IMEI-Privacy-v$version.apk"
$moduleProperties = Get-Content -LiteralPath (Join-Path $moduleSource 'module.prop')
if ($moduleProperties -notcontains "version=$version" -or
    $moduleProperties -notcontains "versionCode=$($metadata.elements[0].versionCode)") {
    throw 'APK and module versions do not match'
}

if (Test-Path -LiteralPath $staging) {
    $distRoot = [IO.Path]::GetFullPath($dist) + [IO.Path]::DirectorySeparatorChar
    if (-not $staging.StartsWith($distRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($staging) -ne "staging-v$version") {
        throw 'Refusing to remove a staging directory outside dist'
    }
    Remove-Item -LiteralPath $staging -Recurse -Force
}
New-Item -ItemType Directory -Path $staging, $apkTargetDir -Force | Out-Null
Copy-Item -Path (Join-Path $moduleSource '*') -Destination $staging -Recurse -Force
Copy-Item -LiteralPath $apk -Destination (Join-Path $apkTargetDir 'ImeiPrivacy.apk') -Force
Copy-Item -LiteralPath $apk -Destination $standaloneApk -Force

if (Test-Path -LiteralPath $zip) {
    Remove-Item -LiteralPath $zip -Force
}
Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zip -CompressionLevel Optimal

Write-Host "APK: $standaloneApk"
Write-Host "KernelSU module: $zip"
