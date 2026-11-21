param([switch]$WithEmulator)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21' }
if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw '请设置 JAVA_HOME 指向 JDK 17 或更高版本。' }
& python -X utf8 (Join-Path $PSScriptRoot 'bootstrap.py')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$env:ANDROID_HOME = Join-Path $projectRoot '.local\sdk'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.local\android-user'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
1..100 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$env:ANDROID_HOME" --licenses
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$packages = @('platforms;android-35', 'build-tools;35.0.0', 'platform-tools')
if ($WithEmulator) { $packages += @('emulator','system-images;android-30;google_apis;x86_64') }
& $sdkManager "--sdk_root=$env:ANDROID_HOME" @packages
exit $LASTEXITCODE
