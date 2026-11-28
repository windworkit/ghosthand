param(
    [string]$OutputPath = (Join-Path (Get-Location) "GhostHand-legacy-backup.json")
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found. Install Android SDK Platform-Tools and make sure adb is on PATH."
    }
}

Require-Command "adb"
Require-Command "tar.exe"

$devices = & adb devices
if ($LASTEXITCODE -ne 0) {
    throw "ADB could not communicate with the device. Check USB debugging and try again."
}
$readyDevices = @(
    $devices | Where-Object {
        $_ -match "^[^\s]+\s+device$"
    }
)
if ($readyDevices.Count -ne 1) {
    throw "Connect exactly one authorized Android device. In 'adb devices', its state must be 'device' (not 'unauthorized' or 'offline')."
}

# Verify that the legacy installation is present and debuggable before
# extracting anything. Do not uninstall GhostHand before this succeeds.
& adb shell run-as com.ghosthand ls files/scripts | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Could not access GhostHand's private scripts folder. Keep the current installation intact; run-as is unavailable for this build."
}

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("GhostHandBackup-" + [Guid]::NewGuid().ToString("N"))
$tarPath = Join-Path $work "legacy-scripts.tar"
$extractPath = Join-Path $work "extracted"
New-Item -ItemType Directory -Path $extractPath -Force | Out-Null

try {
    # Use cmd.exe for the binary redirection. PowerShell's native-command
    # output redirection can re-encode binary tar streams on Windows PowerShell.
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    $tarCommand = 'adb exec-out run-as com.ghosthand tar -cf - files/scripts > "' + $tarPath + '"'
    & $env:ComSpec /d /c $tarCommand
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tarPath)) {
        throw "ADB could not export GhostHand's scripts. The old installation has not been modified."
    }

    & tar.exe -xf $tarPath -C $extractPath
    if ($LASTEXITCODE -ne 0) {
        throw "The exported script archive could not be extracted."
    }

    $scriptFolder = Join-Path $extractPath "files/scripts"
    if (-not (Test-Path $scriptFolder)) {
        throw "The archive did not contain files/scripts."
    }

    $scriptFiles = @(Get-ChildItem -Path $scriptFolder -Filter "*.json" -File)
    $scripts = @()
    foreach ($file in $scriptFiles) {
        $scripts += (Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json)
    }

    $backup = [ordered]@{
        schemaVersion = 1
        scripts = @($scripts)
    }
    $json = $backup | ConvertTo-Json -Depth 100
    $absoluteOutput = [System.IO.Path]::GetFullPath($OutputPath)
    $parentFolder = Split-Path -Parent $absoluteOutput
    if ($parentFolder -and -not (Test-Path $parentFolder)) {
        New-Item -ItemType Directory -Path $parentFolder -Force | Out-Null
    }

    # No UTF-8 BOM: Kotlin serialization can read this file directly.
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($absoluteOutput, $json, $utf8NoBom)

    Write-Host ""
    Write-Host "Backup completed." -ForegroundColor Green
    Write-Host "Scripts exported: $($scripts.Count)"
    Write-Host "Backup file: $absoluteOutput"
    Write-Host ""
    Write-Host "Keep the old GhostHand installation until this JSON file exists and has been saved somewhere safe."
    Write-Host "After installing the new GhostHand build, open it and choose RESTORE SCRIPTS."
}
finally {
    if (Test-Path $work) {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}
