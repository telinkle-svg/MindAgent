[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InputPath,

    [string] $ThresholdPath,

    [string] $BaselinePath
)

$ErrorActionPreference = 'Stop'

function Read-JsonFile([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "JSON file not found: $Path"
    }
    try {
        return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    } catch {
        throw "Unable to parse JSON file '$Path': $($_.Exception.Message)"
    }
}

function Get-JsonFiles([string] $Path) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        return @(Get-Item -LiteralPath $Path)
    }
    if (Test-Path -LiteralPath $Path -PathType Container) {
        return @(Get-ChildItem -LiteralPath $Path -Filter 'performance-baseline-*.json' -File)
    }
    throw "Input path not found: $Path"
}

function Require-Property($Object, [string] $Name, [string] $Source) {
    if ($null -eq $Object.PSObject.Properties[$Name]) {
        throw "$Source is missing required property '$Name'"
    }
    return $Object.$Name
}

function Assert-Number($Value, [string] $Name, [string] $Source) {
    if ($null -eq $Value -or $Value -is [bool] -or -not ($Value -is [int] -or $Value -is [long] -or $Value -is [double] -or $Value -is [decimal])) {
        throw "$Source property '$Name' must be numeric"
    }
    if ([double]::IsNaN([double]$Value) -or [double]::IsInfinity([double]$Value)) {
        throw "$Source property '$Name' must be finite"
    }
}

function Get-Threshold($Thresholds, [string] $Name) {
    $property = $Thresholds.PSObject.Properties[$Name]
    if ($null -eq $property) {
        throw "Threshold configuration is missing '$Name'"
    }
    return [double]$property.Value
}

function Get-MinRuns($Thresholds, [string] $Scenario) {
    $property = $Thresholds.minRuns.PSObject.Properties[$Scenario]
    if ($null -eq $property) {
        throw "Threshold configuration is missing minRuns for scenario '$Scenario'"
    }
    return [int]$property.Value
}

function Get-RegressionPercent($Thresholds, [string] $Level, [string] $Metric) {
    $levelProperty = $Thresholds.relativeRegressionPercent.PSObject.Properties[$Level]
    if ($null -eq $levelProperty) {
        throw "Threshold configuration is missing relativeRegressionPercent.$Level"
    }
    return Get-Threshold $levelProperty.Value $Metric
}

function Compare-Report($Report, [string] $Source, $Thresholds, $BaselineByScenario) {
    $schemaVersion = Require-Property $Report 'schemaVersion' $Source
    if ([int]$schemaVersion -ne [int]$Thresholds.schemaVersion) {
        throw "$Source schemaVersion=$schemaVersion is unsupported; expected $($Thresholds.schemaVersion)"
    }
    $scenario = [string](Require-Property $Report 'scenario' $Source)
    if ([string]::IsNullOrWhiteSpace($scenario)) {
        throw "$Source scenario must not be empty"
    }

    $runs = Require-Property $Report 'runs' $Source
    $p95 = Require-Property $Report 'p95Ms' $Source
    $p99 = Require-Property $Report 'p99Ms' $Source
    $max = Require-Property $Report 'maxMs' $Source
    $terminalSuccesses = Require-Property $Report 'terminalSuccesses' $Source
    $terminalFailures = Require-Property $Report 'terminalFailures' $Source
    $terminalReasons = Require-Property $Report 'terminalReasons' $Source
    foreach ($pair in @(@('runs', $runs), @('p95Ms', $p95), @('p99Ms', $p99), @('maxMs', $max), @('terminalSuccesses', $terminalSuccesses), @('terminalFailures', $terminalFailures))) {
        Assert-Number $pair[1] $pair[0] $Source
    }

    $minRuns = Get-MinRuns $Thresholds $scenario
    if ([int]$runs -lt $minRuns) {
        throw "$scenario runs=$runs is below minimum $minRuns"
    }
    foreach ($pair in @(@('p95Ms', $p95, (Get-Threshold $Thresholds.latencyMs 'p95')), @('p99Ms', $p99, (Get-Threshold $Thresholds.latencyMs 'p99')), @('maxMs', $max, (Get-Threshold $Thresholds.latencyMs 'max')))) {
        if ([double]$pair[1] -ge [double]$pair[2]) {
            throw "$scenario $($pair[0])=$($pair[1]) ms violates strict upper bound $($pair[2]) ms"
        }
    }
    if ([int]$terminalSuccesses -ne [int]$runs -or [int]$terminalFailures -ne 0) {
        throw "$scenario terminal status is not fully successful (successes=$terminalSuccesses, failures=$terminalFailures, runs=$runs)"
    }
    if ($null -eq $terminalReasons.PSObject.Properties['completed'] -or [int]$terminalReasons.completed -ne [int]$runs) {
        throw "$scenario terminalReasons.completed must equal runs=$runs"
    }

    if ($null -ne $BaselineByScenario) {
        $baseline = $BaselineByScenario[$scenario]
        if ($null -eq $baseline) {
            throw "$scenario has no matching baseline report"
        }
        foreach ($pair in @(
            @('p95Ms', $p95, $baseline.p95Ms, (Get-RegressionPercent $Thresholds 'warning' 'p95'), (Get-RegressionPercent $Thresholds 'failure' 'p95')),
            @('p99Ms', $p99, $baseline.p99Ms, (Get-RegressionPercent $Thresholds 'warning' 'p99'), (Get-RegressionPercent $Thresholds 'failure' 'p99')),
            @('maxMs', $max, $baseline.maxMs, (Get-RegressionPercent $Thresholds 'warning' 'max'), (Get-RegressionPercent $Thresholds 'failure' 'max'))
        )) {
            Assert-Number $pair[2] "$($pair[0]) baseline" $Source
            $warningLimit = [double]$pair[2] * (1 + ([double]$pair[3] / 100))
            $failureLimit = [double]$pair[2] * (1 + ([double]$pair[4] / 100))
            if ([double]$pair[1] -gt $failureLimit) {
                throw "$scenario $($pair[0])=$($pair[1]) ms regressed beyond baseline $($pair[2]) ms + $($pair[4])% failure threshold (limit=$failureLimit ms)"
            }
            if ([double]$pair[1] -gt $warningLimit) {
                Write-Warning "$scenario $($pair[0])=$($pair[1]) ms exceeds baseline $($pair[2]) ms + $($pair[3])% warning threshold (limit=$warningLimit ms)"
            }
        }
    }
    return "$scenario PASS (runs=$runs, p95=$p95 ms, p99=$p99 ms, max=$max ms)"
}

try {
    if ([string]::IsNullOrWhiteSpace($ThresholdPath)) {
        $repoRoot = (Resolve-Path (Join-Path (Join-Path $PSScriptRoot '..') '..')).Path
        $ThresholdPath = Join-Path (Join-Path $repoRoot 'scripts') 'performance-thresholds.json'
    }
    $thresholds = Read-JsonFile $ThresholdPath
    $baselineByScenario = $null
    if ($BaselinePath) {
        $baselineByScenario = @{}
        foreach ($file in @(Get-JsonFiles $BaselinePath)) {
            $baseline = Read-JsonFile $file.FullName
            $scenario = [string](Require-Property $baseline 'scenario' $file.FullName)
            if ($baselineByScenario.ContainsKey($scenario)) {
                throw "Duplicate baseline scenario '$scenario' in '$BaselinePath'"
            }
            $baselineByScenario[$scenario] = $baseline
        }
    }

    $inputIsDirectory = Test-Path -LiteralPath $InputPath -PathType Container
    $reportEntries = @()
    $seenScenarios = @{}
    foreach ($file in @(Get-JsonFiles $InputPath)) {
        $report = Read-JsonFile $file.FullName
        $scenario = [string](Require-Property $report 'scenario' $file.FullName)
        if ([string]::IsNullOrWhiteSpace($scenario)) {
            throw "$($file.FullName) scenario must not be empty"
        }
        if ($seenScenarios.ContainsKey($scenario)) {
            throw "Duplicate performance scenario '$scenario' under '$InputPath'"
        }
        $seenScenarios[$scenario] = $true
        $reportEntries += [pscustomobject]@{ File = $file; Report = $report }
    }
    if ($reportEntries.Count -eq 0) {
        throw "No performance JSON reports found under '$InputPath'"
    }
    if ($inputIsDirectory) {
        $expectedScenarios = @($thresholds.minRuns.PSObject.Properties.Name)
        $missingScenarios = @($expectedScenarios | Where-Object { -not $seenScenarios.ContainsKey($_) })
        if ($missingScenarios.Count -gt 0) {
            throw "Missing performance reports for scenario(s): $($missingScenarios -join ', ')"
        }
        $unknownScenarios = @($seenScenarios.Keys | Where-Object { $expectedScenarios -notcontains $_ })
        if ($unknownScenarios.Count -gt 0) {
            throw "Unexpected performance scenario(s): $($unknownScenarios -join ', ')"
        }
    }
    foreach ($entry in $reportEntries) {
        Write-Output (Compare-Report $entry.Report $entry.File.FullName $thresholds $baselineByScenario)
    }
    exit 0
} catch {
    Write-Error "PERFORMANCE_GATE_FAILED: $($_.Exception.Message)"
    exit 1
}
