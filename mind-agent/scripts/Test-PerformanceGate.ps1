[CmdletBinding()]
param(
    [string] $InputPath
)

$ErrorActionPreference = 'Stop'
$comparator = Join-Path $PSScriptRoot 'Compare-PerformanceBaseline.ps1'
$repoRoot = (Resolve-Path (Join-Path (Join-Path $PSScriptRoot '..') '..')).Path
$thresholds = Join-Path (Join-Path $repoRoot 'scripts') 'performance-thresholds.json'
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('mind-agent-performance-gate-' + [guid]::NewGuid().ToString('N'))

try {
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    $fixture = [ordered]@{
        schemaVersion = 1
        scenario = 'simple'
        runs = 100
        p50Ms = 1
        p95Ms = 2
        p99Ms = 3
        maxMs = 4
        totalContextChars = 10
        maxContextChars = 10
        totalTruncatedToolResults = 0
        totalSummaryAttempts = 0
        totalSummaryFailures = 0
        terminalSuccesses = 100
        terminalFailures = 0
        terminalReasons = [ordered]@{ completed = 100 }
    }
    $validFixture = Join-Path $tempRoot 'performance-baseline-simple.json'
    $fixture | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $validFixture -Encoding utf8
    & pwsh -NoProfile -File $comparator -InputPath $validFixture -ThresholdPath $thresholds
    if ($LASTEXITCODE -ne 0) {
        throw 'Comparator rejected the valid deterministic fixture'
    }

    $invalidFixture = Join-Path $tempRoot 'performance-baseline-invalid.json'
    $fixture.runs = 1
    $fixture | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $invalidFixture -Encoding utf8
    & pwsh -NoProfile -File $comparator -InputPath $invalidFixture -ThresholdPath $thresholds 2>$null
    if ($LASTEXITCODE -eq 0) {
        throw 'Comparator accepted a fixture below the minimum run count'
    }

    $fixture.runs = 100
    $incompleteRoot = Join-Path $tempRoot 'incomplete-reports'
    New-Item -ItemType Directory -Path $incompleteRoot | Out-Null
    foreach ($scenario in @('simple', 'multi-step', 'summary')) {
        $scenarioFixture = [ordered]@{} + $fixture
        $scenarioRuns = if ($scenario -eq 'simple') { 100 } else { 20 }
        $scenarioFixture.scenario = $scenario
        $scenarioFixture.runs = $scenarioRuns
        $scenarioFixture.terminalSuccesses = $scenarioRuns
        $scenarioFixture.terminalReasons = [ordered]@{ completed = $scenarioRuns }
        $scenarioPath = Join-Path $incompleteRoot "performance-baseline-$scenario.json"
        $scenarioFixture | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $scenarioPath -Encoding utf8
    }
    & pwsh -NoProfile -File $comparator -InputPath $incompleteRoot -ThresholdPath $thresholds 2>$null
    if ($LASTEXITCODE -eq 0) {
        throw 'Comparator accepted a directory with a missing configured scenario'
    }

    $fixture.p95Ms = 2
    $baselineFixture = [ordered]@{} + $fixture
    $baselineFixture.p95Ms = 1.5
    $baselineFixture.p99Ms = 2.5
    $baselineFixture.maxMs = 3.5
    $baselinePath = Join-Path $tempRoot 'baseline-simple.json'
    $baselineFixture | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $baselinePath -Encoding utf8
    & pwsh -NoProfile -File $comparator -InputPath $validFixture -ThresholdPath $thresholds -BaselinePath $baselinePath
    if ($LASTEXITCODE -ne 0) {
        throw 'Comparator rejected a fixture within the configured relative warning band'
    }

    $baselineFixture.p95Ms = 1
    $baselineFixture | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $baselinePath -Encoding utf8
    & pwsh -NoProfile -File $comparator -InputPath $validFixture -ThresholdPath $thresholds -BaselinePath $baselinePath 2>$null
    if ($LASTEXITCODE -eq 0) {
        throw 'Comparator accepted a fixture beyond the configured relative failure band'
    }

    if ($InputPath) {
        & pwsh -NoProfile -File $comparator -InputPath $InputPath -ThresholdPath $thresholds
        if ($LASTEXITCODE -ne 0) {
            throw "Comparator rejected performance output at '$InputPath'"
        }
    }
    Write-Output 'PERFORMANCE_GATE_SELF_TEST_PASS'
    exit 0
} catch {
    Write-Error "PERFORMANCE_GATE_SELF_TEST_FAILED: $($_.Exception.Message)"
    exit 1
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}
