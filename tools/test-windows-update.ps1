# The computer's updater tried on Windows (2026-11-11, a tester: the update uninstalled ink music and put nothing in),
# nothing published: an old release installed, a newer msi built here at a test version, a stand-in release served
# from this PC (latest.json, SHA256SUMS), and the old app pointed at it (-Dink.updates). Take the update in the app; this
# then says what's installed, and keeps the logs in D:\ink-music-test\update\report-<from>.txt.
#
#   powershell -ExecutionPolicy Bypass -File tools\test-windows-update.ps1 -From 2.1.12   (the cache inside the install
#                                                                                           folder: the bug, likely)
#   powershell -ExecutionPolicy Bypass -File tools\test-windows-update.ps1 -From 2.1.15   (the cache moved out)
#
# The old msi: D:\ink-music-test\update\ink-music-<from>-windows.msi, or fetched from inkhome.app. The test one is built
# once (versionName set for the build, then put back) and kept there for the next run.
param(
    [string]$From = "2.1.15",
    [string]$To = "2.1.16",
    [int]$Port = 8765
)
$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot
$work = "D:\ink-music-test\update"
$serve = Join-Path $work "serve"
$app = Join-Path $env:LOCALAPPDATA "ink music\ink music.exe"
$home2 = Join-Path $env:APPDATA "ink music"
New-Item -ItemType Directory -Force $work, $serve | Out-Null

function Installed {
    $k = Get-ChildItem HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall, HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ItemProperty $_.PSPath } | Where-Object { $_.DisplayName -like "ink music*" }
    if ($k) { ($k | Select-Object -First 1).DisplayVersion } else { $null }
}
function Quit-Ink { Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $app } | Stop-Process -Force; Start-Sleep 1 }

# 1. the old release in
$old = Join-Path $work "ink-music-$From-windows.msi"
if (-not (Test-Path $old)) {
    Write-Host "fetching $From from inkhome.app"
    Invoke-WebRequest "https://inkhome.app/download/ink-music-$From-windows.msi" -OutFile $old -UseBasicParsing
}
Quit-Ink
Write-Host "installing $From (was: $(Installed))"
$p = Start-Process msiexec -ArgumentList "/i", "`"$old`"", "/passive", "/norestart", "/l*v", "`"$work\install-$From.log`"" -Wait -PassThru
if ((Installed) -notlike "$From*") { throw "$From didn't go in (msiexec said $($p.ExitCode)): $work\install-$From.log" }

# 2. the newer one, built here at the test version
$new = Join-Path $work "ink-music-$To-windows.msi"
if (-not (Test-Path $new)) {
    if (-not (Test-Path "$repo\desktop\native\windows-x64\libmpv-2.dll")) { throw "no libmpv-2.dll in desktop\native\windows-x64: run tools/build-windows.sh once" }
    $gradle = Join-Path $repo "app\build.gradle.kts"
    $was = [IO.File]::ReadAllText($gradle)
    try {
        [IO.File]::WriteAllText($gradle, ($was -replace 'versionName = "[^"]+"', "versionName = `"$To`""))
        $env:JAVA_HOME = "D:\ink-windows\toolchain\jbr"
        Remove-Item -Recurse -Force "$repo\desktop\build\compose\binaries\main\msi" -ErrorAction SilentlyContinue
        Push-Location $repo
        & .\gradlew.bat -q :desktop:packageInkMsi -PdesktopOnly --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "the build failed" }
        Pop-Location
    } finally { [IO.File]::WriteAllText($gradle, $was) }
    Copy-Item (Get-ChildItem "$repo\desktop\build\compose\binaries\main\msi\*.msi" | Select-Object -First 1).FullName $new
}

# 3. a stand-in release: the msi, its sum, latest.json, served on localhost
$name = "ink-music-$To-windows.msi"
Copy-Item $new (Join-Path $serve $name) -Force
$sum = (Get-FileHash (Join-Path $serve $name) -Algorithm SHA256).Hash.ToLower()
[IO.File]::WriteAllText((Join-Path $serve "SHA256SUMS"), "$sum  $name`n")
$base = "http://localhost:$Port"
$latest = @{
    tag_name = "v$To"; name = "ink music $To (test)"; body = "- a test update, from this PC"
    assets = @(
        @{ name = $name; browser_download_url = "$base/$name"; size = (Get-Item $new).Length },
        @{ name = "SHA256SUMS"; browser_download_url = "$base/SHA256SUMS"; size = 0 }
    )
} | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText((Join-Path $serve "latest.json"), $latest)
$server = Start-Job -ArgumentList $serve, $Port -ScriptBlock {
    param($dir, $port)
    $l = [Net.HttpListener]::new(); $l.Prefixes.Add("http://localhost:$port/"); $l.Start()
    while ($true) {
        $c = $l.GetContext()
        $f = Join-Path $dir ($c.Request.Url.AbsolutePath.TrimStart("/"))
        if (Test-Path $f -PathType Leaf) {
            $c.Response.ContentLength64 = (Get-Item $f).Length
            $s = [IO.File]::OpenRead($f); $s.CopyTo($c.Response.OutputStream); $s.Close()
        } else { $c.Response.StatusCode = 404 }
        $c.Response.Close()
    }
}
Start-Sleep 2
try { Invoke-WebRequest "$base/latest.json" -UseBasicParsing | Out-Null } catch { throw "the stand-in release isn't served: $_" }

# 4. the old app, pointed at it (its last look at the releases forgotten, so it looks now)
Remove-Item (Join-Path $home2 "update.json") -ErrorAction SilentlyContinue
$logAt = if (Test-Path (Join-Path $home2 "update.log")) { (Get-Content (Join-Path $home2 "update.log")).Count } else { 0 }
$env:JAVA_TOOL_OPTIONS = "-Dink.updates=$base/latest.json"
Start-Process $app
Remove-Item Env:JAVA_TOOL_OPTIONS
Write-Host ""
Write-Host "ink music $From is open. Open listen (or settings, check for updates), take the $To update, and leave it be."
Write-Host "Watching for 6 minutes..."

# 5. what happened
$went = $false; $gone = $false
foreach ($i in 1..120) {
    Start-Sleep 3
    $v = Installed
    if (-not $v) { $gone = $true }
    if ($v -like "$To*") { $went = $true; Start-Sleep 10; break }
}
Stop-Job $server; Remove-Job $server
$running = [bool](Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $app })
$r = @()
$r += "update from $From to $To, $(Get-Date -Format s)"
$r += "installed now: $(if (Installed) { Installed } else { 'NOTHING' })   (gone for a moment on the way: $gone)"
$r += "ink music.exe there: $(Test-Path $app)   running again: $running"
$r += "the old cache's update folder there: $(Test-Path (Join-Path $env:LOCALAPPDATA 'ink music\cache\update'))   the new one's: $(Test-Path (Join-Path $env:LOCALAPPDATA 'ink\music\update'))"
$ul = Join-Path $home2 "update.log"
if (Test-Path $ul) { $r += "--- update.log (this run)"; $r += (Get-Content $ul | Select-Object -Skip $logAt) }
$ml = Join-Path $home2 "msi.log"
if (Test-Path $ml) {
    $r += "--- msi.log: errors and its end"
    $r += (Select-String -Path $ml -Pattern "Return value 3|Error \d{4}|error status|Installation success or error" | ForEach-Object { $_.Line })
}
$r += "--- MsiInstaller, the last 15 minutes"
$r += (Get-WinEvent -FilterHashtable @{ LogName = "Application"; ProviderName = "MsiInstaller"; StartTime = (Get-Date).AddMinutes(-15) } -ErrorAction SilentlyContinue |
    ForEach-Object { "$($_.TimeCreated.ToString('s'))  $($_.Id)  $($_.Message -replace '\s+', ' ')" })
$report = Join-Path $work "report-$From.txt"
$r | Set-Content $report
$r | ForEach-Object { Write-Host $_ }
Write-Host ""
Write-Host $(if ($went) { "UPDATED: $From to $To" } else { "NOT UPDATED: see $report" })
