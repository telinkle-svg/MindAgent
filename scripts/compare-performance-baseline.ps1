[CmdletBinding()]
param(
    [string] $BackendRoot,
    [string] $InputPath,
    [string] $BaselinePath,
    [string] $ThresholdPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($BackendRoot)) {
    $BackendRoot = Join-Path $repoRoot 'mind-agent'
}
if ([string]::IsNullOrWhiteSpace($InputPath)) {
    $InputPath = Join-Path $BackendRoot 'target'
}
if ([string]::IsNullOrWhiteSpace($ThresholdPath)) {
    $ThresholdPath = Join-Path $repoRoot 'scripts/performance-thresholds.json'
}
$delegate = Join-Path $BackendRoot 'scripts/Compare-PerformanceBaseline.ps1'
if (-not (Test-Path -LiteralPath $delegate -PathType Leaf)) {
    throw "Performance comparator implementation not found: $delegate"
}
$arguments = @('-NoProfile', '-File', $delegate, '-InputPath', $InputPath, '-ThresholdPath', $ThresholdPath)
if (-not [string]::IsNullOrWhiteSpace($BaselinePath)) {
    $arguments += @('-BaselinePath', $BaselinePath)
}
& pwsh @arguments
exit $LASTEXITCODE
