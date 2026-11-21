param([ValidateSet('yijian_api30','yijian_api35')][string]$Avd = 'yijian_api30', [int]$Port = 5554)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not ('RelaxTranslatorEmulatorShortPath' -as [type])) {
    Add-Type -TypeDefinition 'using System; using System.Text; using System.Runtime.InteropServices; public static class RelaxTranslatorEmulatorShortPath { [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] public static extern uint GetShortPathName(string path, StringBuilder result, uint length); }'
}
$buffer = [System.Text.StringBuilder]::new(1024)
[void][RelaxTranslatorEmulatorShortPath]::GetShortPathName($projectRoot, $buffer, 1024)
$shortRoot = if ($buffer.Length -gt 0) { $buffer.ToString() } else { $projectRoot }
$env:ANDROID_HOME = Join-Path $shortRoot '.local\sdk'
$env:ANDROID_USER_HOME = Join-Path $shortRoot '.local\android-user'
$env:ANDROID_EMULATOR_HOME = Join-Path $shortRoot '.local\emulator-user'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
$env:LOCALAPPDATA = Join-Path $shortRoot '.local\emulator-user'
$env:TEMP = Join-Path $shortRoot '.local\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Path (Join-Path $env:LOCALAPPDATA 'Temp\avd\running') -Force | Out-Null
$emulator = Join-Path $shortRoot '.local\emulator35\emulator\emulator.exe'
if (!(Test-Path -LiteralPath $emulator)) { $emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe' }
if (!(Test-Path -LiteralPath $emulator)) { throw '先运行 tools/setup-sdk.ps1 -WithEmulator。' }
& $emulator -avd $Avd -port $Port -no-window -no-audio -no-boot-anim -no-snapshot -gpu host -feature -Vulkan -memory 2048 -cores 2 -no-metrics -grpc ($Port + 3000) -grpc-use-jwt
