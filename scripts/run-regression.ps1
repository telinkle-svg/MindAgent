[CmdletBinding()]
param(
    [ValidateSet("deepseek", "ollama", "pgvector", "document")]
    [string[]] $Integration = @(),
    [switch] $SkipFrontend,
    [switch] $SkipPerformance,
    [switch] $InstallFrontendDependencies,
    [string] $MavenRepoLocal
)

$ErrorActionPreference = "Stop"

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [string] $Executable,
        [Parameter(Mandatory = $true)] [string[]] $Arguments,
        [Parameter(Mandatory = $true)] [string] $WorkingDirectory
    )

    Write-Host "`n==> $Name"
    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $Executable @Arguments
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode"
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$backendRoot = Join-Path $repoRoot "mind-agent"
$frontendRoot = Join-Path $repoRoot "ui"
$mavenExecutable = if ($IsWindows) {
    Join-Path $backendRoot "mvnw.cmd"
} else {
    Join-Path $backendRoot "mvnw"
}
$npmExecutable = if ($IsWindows) { "npm.cmd" } else { "npm" }

$mavenArguments = @("test")
if ([string]::IsNullOrWhiteSpace($MavenRepoLocal) -and
    -not [string]::IsNullOrWhiteSpace($env:MINDAGENT_MAVEN_REPO)) {
    $MavenRepoLocal = $env:MINDAGENT_MAVEN_REPO
}
if (-not [string]::IsNullOrWhiteSpace($MavenRepoLocal)) {
    $mavenArguments = @("-Dmaven.repo.local=$MavenRepoLocal") + $mavenArguments
}

$integrationProperties = @{
    deepseek = "mindagent.deepseek.integration"
    ollama = "mindagent.ollama.integration"
    pgvector = "mindagent.pgvector.integration"
    document = "mindagent.document.integration"
}
foreach ($integrationName in $Integration) {
    $mavenArguments = @("-D$($integrationProperties[$integrationName])=true") + $mavenArguments
}

if ($IsWindows) {
    Invoke-CheckedCommand -Name "Backend Maven regression" `
        -Executable $mavenExecutable -Arguments $mavenArguments -WorkingDirectory $backendRoot
} else {
    # The repository keeps mvnw with a platform-neutral 0644 mode. Invoke it
    # through sh so the same entrypoint also works on Linux CI runners.
    Invoke-CheckedCommand -Name "Backend Maven regression" `
        -Executable "sh" -Arguments (@($mavenExecutable) + $mavenArguments) `
        -WorkingDirectory $backendRoot
}

if (-not $SkipPerformance) {
    $comparisonScript = Join-Path (Join-Path $repoRoot "scripts") "compare-performance-baseline.ps1"
    if (-not (Test-Path -LiteralPath $comparisonScript)) {
        throw "Performance comparator not found: $comparisonScript"
    }
    $powerShellExecutable = if ($IsWindows) { "pwsh.exe" } else { "pwsh" }
    Invoke-CheckedCommand -Name "Performance baseline threshold check" `
        -Executable $powerShellExecutable `
        -Arguments @("-NoProfile", "-File", $comparisonScript, "-BackendRoot", $backendRoot) `
        -WorkingDirectory $repoRoot
}

if (-not $SkipFrontend) {
    if ($InstallFrontendDependencies -or -not (Test-Path -LiteralPath (Join-Path $frontendRoot "node_modules"))) {
        Invoke-CheckedCommand -Name "Install frontend dependencies" `
            -Executable $npmExecutable -Arguments @("ci", "--ignore-scripts") `
            -WorkingDirectory $frontendRoot
    }
    Invoke-CheckedCommand -Name "Frontend unit regression" `
        -Executable $npmExecutable -Arguments @("run", "test", "--", "--run") `
        -WorkingDirectory $frontendRoot
    Invoke-CheckedCommand -Name "Frontend lint" `
        -Executable $npmExecutable -Arguments @("run", "lint") `
        -WorkingDirectory $frontendRoot
    Invoke-CheckedCommand -Name "Frontend production build" `
        -Executable $npmExecutable -Arguments @("run", "build") `
        -WorkingDirectory $frontendRoot
}

Write-Host "`nRegression checks completed successfully."
