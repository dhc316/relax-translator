param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest'))
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if (-not ('RelaxTranslatorShortPath' -as [type])) {
    Add-Type -TypeDefinition 'using System; using System.Text; using System.Runtime.InteropServices; public static class RelaxTranslatorShortPath { [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] public static extern uint GetShortPathName(string path, StringBuilder result, uint length); }'
}
$shortPathBuffer = [System.Text.StringBuilder]::new(1024)
[void][RelaxTranslatorShortPath]::GetShortPathName($projectRoot, $shortPathBuffer, 1024)
$toolRoot = if ($shortPathBuffer.Length -gt 0) { $shortPathBuffer.ToString() } else { $projectRoot }
if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
}
$env:ANDROID_HOME = Join-Path $toolRoot '.local\sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.local\android-user'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.local\gradle-home'
$env:TEMP = Join-Path $toolRoot '.local\tmp'
$env:TMP = $env:TEMP
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
$gradleExecutable = Join-Path $projectRoot '.local\gradle\gradle-8.11.1\bin\gradle.bat'
if (!(Test-Path -LiteralPath $gradleExecutable)) { throw '先运行 tools/setup-sdk.ps1 准备构建工具。' }
$releaseArgs = @()
if ($Tasks | Where-Object { $_ -match '(^|:)assembleRelease$' }) {
    # A deliverable must be rebuilt from current files, including same-size style edits.
    $releaseArgs = @('--no-build-cache', '--rerun-tasks', '--no-daemon')
}
& $gradleExecutable -p (Join-Path $projectRoot 'android') --console=plain @releaseArgs @Tasks
exit $LASTEXITCODE
