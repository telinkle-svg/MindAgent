[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [ValidateSet('Inspect', 'ReindexIvfflat')]
    [string] $Mode = 'Inspect',

    [string] $PsqlPath,

    [ValidateRange(1, 2147483647)]
    [int] $MinimumChunkCount = 1000,

    [switch] $AllowSmallCorpus,

    [switch] $ConfirmReindex
)

<#
.SYNOPSIS
Safely inspects or explicitly rebuilds MindAgent's current IVFFlat index.

.DESCRIPTION
The default Inspect mode is read-only. ReindexIvfflat preserves the existing
index operator class and lists setting, runs REINDEX INDEX CONCURRENTLY, and
then analyzes the chunk table. It never runs automatically from the Spring
application or document-upload request path.

The command resolves the connection from MINDAGENT_DB_URL,
MINDAGENT_DB_USERNAME, and MINDAGENT_DB_PASSWORD. Passwords are passed only
through the child psql process environment and are never printed.

Examples:
  .\scripts\maintain-pgvector-index.ps1
  .\scripts\maintain-pgvector-index.ps1 -Mode ReindexIvfflat -ConfirmReindex
  .\scripts\maintain-pgvector-index.ps1 -Mode ReindexIvfflat -ConfirmReindex -WhatIf

The script deliberately does not change IVFFlat lists or migrate to HNSW.
Those are schema/index-design changes and require a separately reviewed
capacity and rollback plan.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-PsqlExecutable {
    param([string] $RequestedPath)

    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        if (Test-Path -LiteralPath $RequestedPath -PathType Leaf) {
            return (Resolve-Path -LiteralPath $RequestedPath).Path
        }
        $resolvedCommand = Get-Command $RequestedPath -ErrorAction SilentlyContinue
        if ($null -ne $resolvedCommand) {
            return $resolvedCommand.Source
        }
        throw "psql executable was not found at '$RequestedPath'. Provide -PsqlPath with a valid psql.exe path."
    }

    $command = Get-Command 'psql.exe' -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        $command = Get-Command 'psql' -ErrorAction SilentlyContinue
    }
    if ($null -eq $command) {
        throw 'psql was not found on PATH. Install PostgreSQL client tools or pass -PsqlPath <path-to-psql.exe>.'
    }
    return $command.Source
}

function Resolve-PostgresUrl {
    $configuredUrl = $env:MINDAGENT_DB_URL
    if ([string]::IsNullOrWhiteSpace($configuredUrl)) {
        $configuredUrl = 'jdbc:postgresql://localhost:5432/jchatmind'
    }
    if ($configuredUrl.StartsWith('jdbc:', [System.StringComparison]::OrdinalIgnoreCase)) {
        $configuredUrl = $configuredUrl.Substring(5)
    }
    if (-not $configuredUrl.StartsWith('postgresql://', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "MINDAGENT_DB_URL must be a PostgreSQL JDBC URL, but was '$configuredUrl'."
    }
    return $configuredUrl
}

$script:PsqlExecutable = Resolve-PsqlExecutable -RequestedPath $PsqlPath
$script:PostgresUrl = Resolve-PostgresUrl
$script:PostgresUser = if ([string]::IsNullOrWhiteSpace($env:MINDAGENT_DB_USERNAME)) {
    'jchatmind'
} else {
    $env:MINDAGENT_DB_USERNAME
}

function Invoke-PsqlQuery {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Sql
    )

    $hadPgPassword = Test-Path Env:PGPASSWORD
    $originalPgPassword = if ($hadPgPassword) { $env:PGPASSWORD } else { $null }
    try {
        if (-not [string]::IsNullOrWhiteSpace($env:MINDAGENT_DB_PASSWORD)) {
            $env:PGPASSWORD = $env:MINDAGENT_DB_PASSWORD
        }
        $output = & $script:PsqlExecutable `
            '--no-psqlrc' `
            '--set' 'ON_ERROR_STOP=1' `
            '--quiet' `
            '--tuples-only' `
            '--no-align' `
            '--dbname' $script:PostgresUrl `
            '--username' $script:PostgresUser `
            '--command' $Sql 2>&1
        $exitCode = if (Test-Path Variable:LASTEXITCODE) { $LASTEXITCODE } else { 0 }
        if ($exitCode -ne 0) {
            $message = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
            throw "psql command failed: $message"
        }
        return @($output | ForEach-Object { $_.ToString() })
    } finally {
        if ($hadPgPassword) {
            $env:PGPASSWORD = $originalPgPassword
        } else {
            Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
        }
    }
}

function Get-PsqlScalar {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Sql
    )

    $lines = @(Invoke-PsqlQuery -Sql $Sql | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($lines.Count -eq 0) {
        return ''
    }
    return $lines[$lines.Count - 1].Trim()
}

function Get-IndexState {
    $chunkCountText = Get-PsqlScalar -Sql 'SELECT count(*)::text FROM public.chunk_bge_m3;'
    $extensionVersion = Get-PsqlScalar -Sql "SELECT extversion FROM pg_extension WHERE extname = 'vector';"
    $indexDefinition = Get-PsqlScalar -Sql "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'chunk_bge_m3' AND indexname = 'idx_chunk_embedding';"
    $indexValid = Get-PsqlScalar -Sql "SELECT i.indisvalid::text FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = 'idx_chunk_embedding';"
    $indexOwner = Get-PsqlScalar -Sql "SELECT pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = 'idx_chunk_embedding';"
    $indexSize = Get-PsqlScalar -Sql "SELECT pg_size_pretty(pg_relation_size('public.idx_chunk_embedding'::regclass));"
    $currentUser = Get-PsqlScalar -Sql 'SELECT current_user;'
    $currentUserIsSuperuser = Get-PsqlScalar -Sql "SELECT rolsuper::text FROM pg_roles WHERE rolname = current_user;"

    if ([string]::IsNullOrWhiteSpace($chunkCountText)) {
        throw 'Unable to read public.chunk_bge_m3 row count.'
    }
    if ([string]::IsNullOrWhiteSpace($indexDefinition)) {
        throw 'Required index public.idx_chunk_embedding was not found.'
    }
    if ($indexDefinition -notmatch '(?i)USING\s+ivfflat') {
        throw "public.idx_chunk_embedding is not an IVFFlat index: $indexDefinition"
    }

    $lists = $null
    if ($indexDefinition -match "(?i)lists\s*=\s*'?([0-9]+)'?") {
        $lists = [int] $Matches[1]
    }

    return [PSCustomObject]@{
        Database = Get-PsqlScalar -Sql 'SELECT current_database();'
        User = $currentUser
        IsSuperuser = $currentUserIsSuperuser
        PgvectorVersion = $extensionVersion
        ChunkCount = [long] $chunkCountText
        IndexName = 'idx_chunk_embedding'
        IndexValid = $indexValid
        IndexOwner = $indexOwner
        IvfflatLists = $lists
        IndexSize = $indexSize
        IndexDefinition = $indexDefinition
    }
}

$state = Get-IndexState
Write-Host 'pgvector IVFFlat index state:'
$state | Select-Object Database, User, IsSuperuser, PgvectorVersion, ChunkCount, IndexName, IndexOwner, IndexValid, IvfflatLists, IndexSize | Format-List | Out-Host
Write-Host "Index definition: $($state.IndexDefinition)"

if ($state.IndexValid -notin @('t', 'true')) {
    throw "public.idx_chunk_embedding is not valid (indisvalid=$($state.IndexValid)). Repair the index before serving RAG queries."
}
if ($state.ChunkCount -lt $MinimumChunkCount) {
    Write-Warning "chunk_bge_m3 has $($state.ChunkCount) rows, below the $MinimumChunkCount maintenance threshold. Do not treat an IVFFlat benchmark on this table as representative of a populated corpus."
}

if ($Mode -eq 'Inspect') {
    return
}

if (-not $ConfirmReindex) {
    throw 'ReindexIvfflat requires -ConfirmReindex in addition to the PowerShell confirmation prompt. No database change was made.'
}
if ($state.ChunkCount -lt $MinimumChunkCount -and -not $AllowSmallCorpus) {
    throw "Refusing to reindex $($state.ChunkCount) rows below -MinimumChunkCount $MinimumChunkCount. Use -AllowSmallCorpus only after reviewing why a small corpus needs an IVFFlat index."
}
if ($state.User -ne $state.IndexOwner -and $state.IsSuperuser -notin @('t', 'true')) {
    throw "Current database user '$($state.User)' does not own public.idx_chunk_embedding (owner '$($state.IndexOwner)') and is not a superuser. No reindex was attempted."
}
if (-not $PSCmdlet.ShouldProcess('public.idx_chunk_embedding', 'REINDEX INDEX CONCURRENTLY and ANALYZE public.chunk_bge_m3')) {
    return
}

Invoke-PsqlQuery -Sql 'REINDEX INDEX CONCURRENTLY public.idx_chunk_embedding;' | Out-Null
Invoke-PsqlQuery -Sql 'ANALYZE public.chunk_bge_m3;' | Out-Null

$afterState = Get-IndexState
if ($afterState.IndexValid -notin @('t', 'true')) {
    throw 'Concurrent reindex completed but the replacement index is not valid. Investigate before enabling RAG traffic.'
}
Write-Host "Reindex completed successfully. Current row count: $($afterState.ChunkCount); IVFFlat lists: $($afterState.IvfflatLists); index size: $($afterState.IndexSize)."
