# Downloads the pinned engine sources, checks their SHA-256 and unpacks them into third_party/.
# Usage (from the app folder):  powershell -ExecutionPolicy Bypass -File tools\fetch-engines.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tp = Join-Path $root 'third_party'
$pins = @(
    @{ name = 'llama.cpp';   tag = 'v1.0.0'; dir = 'llama.cpp-1.0.0';   sha = 'b8d90ec2be080e8834d05e4449793160d68d62518f614be4248d380f3b7c118b' },
    @{ name = 'whisper.cpp'; tag = 'v1.9.5'; dir = 'whisper.cpp-1.9.5'; sha = '7a7a73071939f16bc028ad2f713f16f26e37afb6cc155d0f19e3d65705d2b1d9' }
)
$skip = @('docs', 'models', '.github', '.gemini', '.pi', 'skills', 'benches', 'media', 'gguf-py', 'conversion', '.devops', 'ci', 'bindings', 'samples')
foreach ($p in $pins) {
    $target = Join-Path $tp $p.name
    if (Test-Path $target) { Write-Host "$($p.name) already present, skipped"; continue }
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("oe-" + [Guid]::NewGuid())
    New-Item -ItemType Directory $tmp | Out-Null
    $zip = Join-Path $tmp "$($p.name).zip"
    Invoke-WebRequest -UserAgent 'build-client' "https://github.com/ggml-org/$($p.name)/archive/refs/tags/$($p.tag).zip" -OutFile $zip
    $h = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower()
    if ($h -ne $p.sha) { throw "$($p.name) $($p.tag): SHA-256 $h does not match the pin $($p.sha)" }
    Expand-Archive $zip $tmp
    $src = Join-Path $tmp $p.dir
    # Leave out only top-level folders the build doesn't use (nested ones like tools/mtmd/models are needed).
    New-Item -ItemType Directory $target | Out-Null
    Get-ChildItem $src -Force | Where-Object { -not ($_.PSIsContainer -and $skip -contains $_.Name) } |
        ForEach-Object { Copy-Item $_.FullName (Join-Path $target $_.Name) -Recurse }
    Remove-Item $tmp -Recurse -Force
    Write-Host "$($p.name) $($p.tag) verified and unpacked"
}
