# dialcode for Windows: set up Dialcode on a Wear OS watch and sync accounts to it, over adb.
#
#   .\dialcode.ps1 setup            connect to the watch, install Dialcode and open it
#   .\dialcode.ps1 sync EXPORT      mirror: the watch list becomes exactly this export
#   .\dialcode.ps1 add FILE         merge: only add accounts the watch doesn't have yet
#   .\dialcode.ps1 install [APK]    install or update the app (default: dist\dialcode.apk, else the latest release)
#   .\dialcode.ps1 connect          find the watch on the network and connect adb to it
#
# EXPORT is an unencrypted Proton Authenticator export (.json). FILE can also be a text file with one
# otpauth:// URI per line. Set $env:ANDROID_SERIAL to pick a device when more than one is connected.
param([string]$Command = "", [string]$Path = "")
$ErrorActionPreference = "Stop"

$Pkg = if ($env:DIALCODE_PKG) { $env:DIALCODE_PKG } else { "dev.spundone.dialcode" }
$Activity = "dev.spundone.dialcode.MainActivity"
$Repo = "spundone/dialcode"

function Usage { Get-Content $PSCommandPath | Select-Object -Skip 1 -First 10 | ForEach-Object { $_ -replace '^# ?', '' }; exit 2 }
function Die([string]$msg) { Write-Host $msg -ForegroundColor Red; exit 1 }

function Need-Adb {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        Die "adb isn't installed. Install it with: winget install Google.PlatformTools (or download SDK Platform-Tools from https://developer.android.com/tools/releases/platform-tools and add it to PATH)"
    }
}

function Find-Watch {
    $lines = adb devices | Select-Object -Skip 1
    foreach ($line in $lines) {
        $parts = $line -split "\s+"
        if ($parts.Length -ge 2 -and $parts[1] -eq "device") {
            $ch = adb -s $parts[0] shell getprop ro.build.characteristics 2>$null
            if ("$ch" -match "watch") { return $parts[0] }
        }
    }
    return $null
}

function Connect-Mdns {
    $services = adb mdns services 2>$null | Where-Object { $_ -match "_adb-tls-connect" }
    foreach ($s in $services) { adb connect (($s -split "\s+")[-1]) *> $null }
}

function Connect-Watch {
    Need-Adb
    if ($env:ANDROID_SERIAL) { return }
    $s = Find-Watch
    if (-not $s) { Connect-Mdns; $s = Find-Watch }
    if (-not $s) {
        Write-Host "No watch found. On the watch, open Settings > Developer options > Wireless debugging."
        Write-Host 'If this computer has never connected to it, tap "Pair new device" there.'
        $pair = Read-Host "Pairing IP address and port shown on the watch (or Enter to skip)"
        if ($pair) {
            $code = Read-Host "Pairing code"
            adb pair $pair $code
            if ($LASTEXITCODE -ne 0) { Die "Pairing failed. Check the code and try again." }
            Start-Sleep 2
            Connect-Mdns
            $s = Find-Watch
        }
    }
    if (-not $s) { Die "Still no watch. Check it's on the same Wi-Fi as this computer with Wireless debugging on, then run: .\dialcode.ps1 connect" }
    $env:ANDROID_SERIAL = $s
}

function Test-Installed { adb shell pm path $Pkg *> $null; return $LASTEXITCODE -eq 0 }
function Open-App { adb shell am start -n "$Pkg/$Activity" *> $null }

function Install-App([string]$apk) {
    $tmp = $null
    $local = Join-Path $PSScriptRoot "..\dist\dialcode.apk"
    if (-not $apk -and (Test-Path $local)) { $apk = $local }
    if (-not $apk) {
        $tmp = Join-Path ([IO.Path]::GetTempPath()) ("dialcode-" + [guid]::NewGuid())
        New-Item -ItemType Directory $tmp | Out-Null
        Write-Host "Downloading the latest Dialcode release..."
        $base = "https://github.com/$Repo/releases/latest/download"
        Invoke-WebRequest "$base/dialcode.apk" -OutFile "$tmp\dialcode.apk" -UseBasicParsing
        Invoke-WebRequest "$base/SHA256SUMS" -OutFile "$tmp\SHA256SUMS" -UseBasicParsing
        $want = ((Get-Content "$tmp\SHA256SUMS" | Where-Object { $_ -match "dialcode\.apk" }) -split "\s+")[0]
        $got = (Get-FileHash "$tmp\dialcode.apk" -Algorithm SHA256).Hash
        if ($want -ne $got.ToLower()) { Die "The downloaded APK doesn't match SHA256SUMS. Not installing." }
        $apk = "$tmp\dialcode.apk"
    }
    if (-not (Test-Path $apk)) { Die "No APK at $apk" }
    adb install -r $apk | Out-Null
    if ($LASTEXITCODE -ne 0) { Die "Install failed. If it says the signatures don't match, uninstall the old Dialcode first (that deletes its accounts)." }
    if ($tmp) { Remove-Item -Recurse -Force $tmp }
    Write-Host "Dialcode is installed on the watch."
}

function Push-Export([string]$mode, [string]$file) {
    if (-not (Test-Path $file -PathType Leaf)) { Die "No file at $file" }
    if (-not (Test-Installed)) { Die "Dialcode isn't on the watch yet. Run: .\dialcode.ps1 setup" }
    $ext = if ($file -like "*.json") { "json" } else { "txt" }
    $dest = "/sdcard/Android/data/$Pkg/files/$mode"
    # The app has to create its own drop folders: it can't read a folder made by adb's shell user.
    adb shell "[ -d $dest ]" *> $null
    if ($LASTEXITCODE -ne 0) {
        Open-App
        for ($i = 0; $i -lt 10; $i++) {
            Start-Sleep 1
            adb shell "[ -d $dest ]" *> $null
            if ($LASTEXITCODE -eq 0) { break }
        }
        if ($LASTEXITCODE -ne 0) { Die "Dialcode hasn't set up its folders yet. Unlock the watch, open Dialcode once, then run this again." }
    }
    $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    adb push $file "$dest/push-$stamp.$ext" *> $null
    if ($LASTEXITCODE -ne 0) { Die "Couldn't copy the file to the watch." }
    Open-App
    if ($mode -eq "sync") { Write-Host "Sent. Dialcode is opening on the watch and will match this export." }
    else { Write-Host "Sent. Dialcode is opening on the watch and will add any new accounts." }
    Write-Host "If the watch is locked, unlock it and open Dialcode. The file waits there until then."
    $ans = Read-Host "Delete $file from this computer now? It holds your 2FA secrets unencrypted. [y/N]"
    if ($ans -match "^(y|yes)$") { Remove-Item -Force $file; Write-Host "Deleted." }
    else { Write-Host "Kept. Delete it when you're done." }
}

switch ($Command) {
    "setup" {
        Connect-Watch
        Write-Host "Connected to $env:ANDROID_SERIAL."
        if ((Test-Installed) -and $Path -ne "--reinstall") { Write-Host "Dialcode is already installed. Use .\dialcode.ps1 install to update it." }
        else { Install-App "" }
        Open-App
        Write-Host "Dialcode is open on the watch. Follow the steps there."
        Write-Host "When it asks for your accounts, export them from Proton Authenticator without a password and run:"
        Write-Host "  .\dialcode.ps1 sync `$HOME\Downloads\your-export.json"
    }
    "install" { Connect-Watch; Install-App $Path }
    "connect" { Connect-Watch; Write-Host "Connected to $env:ANDROID_SERIAL." }
    "sync" { if (-not $Path) { Usage }; Connect-Watch; Push-Export "sync" $Path }
    "add" { if (-not $Path) { Usage }; Connect-Watch; Push-Export "import" $Path }
    default { Usage }
}
