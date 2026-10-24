param(
    [string]$ComposeFile = 'deploy/compose.yaml',
    [string]$PostgresContainer = 'ledgerline-postgres',
    [string]$EvidenceDirectory = (Join-Path $env:TEMP ('ledgerline-stability-' + [guid]::NewGuid())),
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
Push-Location $repo
$previousToken = $env:ACCESS_TOKEN
try {
    & "$PSScriptRoot/local-demo.ps1" -ComposeFile $ComposeFile -NoBuild:$NoBuild *> (Join-Path $EvidenceDirectory 'initial-demo.log')
    $summary = Get-Content -LiteralPath 'bench/results/local-demo-summary.json' | ConvertFrom-Json
    $checking = ([guid]$summary.checkingAccountId).ToString()
    $savings = ([guid]$summary.savingsAccountId).ToString()
    $env:ACCESS_TOKEN = $summary.customerToken
    $evidencePath = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
    $previousErrorPreference = $ErrorActionPreference
    try {
    $ErrorActionPreference = 'Continue'
    & docker run --rm -e ACCESS_TOKEN -e "ACCOUNT_A=$checking" -e "ACCOUNT_B=$savings" `
        -e CLOUD_URL=http://host.docker.internal:8080/api/v1 -e K6_PROFILE=correctness `
        -v "${repo}/bench:/bench:ro" -v "${evidencePath}:/evidence" grafana/k6:latest `
        run /bench/k6-cloud-load.js --summary-export=/evidence/k6-summary.json *> (Join-Path $EvidenceDirectory 'k6.log')
    $loadExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorPreference }
    if ($loadExitCode -ne 0) { throw 'The unchanged k6 correctness workload failed; inspect k6.log' }

    # Preserve all existing database/broker data. Only observe the accounts created by this run.
    $sql = @"
WITH scope AS (SELECT unnest(ARRAY['$checking'::uuid,'$savings'::uuid]) id),
ordered AS (
 SELECT *, sum(amount_minor) OVER (PARTITION BY account_id ORDER BY created_at,id) expected
 FROM statement_view WHERE account_id IN (SELECT id FROM scope)
), days AS (
 SELECT account_id, (created_at AT TIME ZONE 'UTC')::date summary_date,
  (array_agg(running_balance_minor-amount_minor ORDER BY created_at,id))[1] opening,
  (array_agg(running_balance_minor ORDER BY created_at DESC,id DESC))[1] closing,
  coalesce(sum(amount_minor) FILTER (WHERE amount_minor>0),0) inflow,
  coalesce(sum(-amount_minor) FILTER (WHERE amount_minor<0),0) outflow, count(*) posting_count
 FROM ordered GROUP BY account_id, (created_at AT TIME ZONE 'UTC')::date
), checks AS (
 SELECT
  (SELECT count(*) FROM postings WHERE account_id IN (SELECT id FROM scope)) ledger_rows,
  (SELECT count(*) FROM statement_view WHERE account_id IN (SELECT id FROM scope)) projection_rows,
  (SELECT count(*) FROM account_balances b WHERE account_id IN (SELECT id FROM scope)
    AND balance_minor<>(SELECT coalesce(sum(amount_minor),0) FROM postings WHERE account_id=b.account_id)) drift,
  (SELECT coalesce(sum(balance_minor),0) FROM account_balances WHERE account_id IN (SELECT id FROM scope)) total,
  (SELECT count(*) FROM ordered WHERE running_balance_minor<>expected) statement_drift,
  (SELECT count(*) FROM days e LEFT JOIN daily_account_summary d USING (account_id,summary_date)
   WHERE d.account_id IS NULL OR d.opening_balance_minor<>e.opening OR d.closing_balance_minor<>e.closing
    OR d.total_inflow_minor<>e.inflow OR d.total_outflow_minor<>e.outflow OR d.posting_count<>e.posting_count) daily_drift
) SELECT row_to_json(checks) FROM checks;
"@
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $raw = & docker exec $PostgresContainer psql -U postgres -d ledgerline -At -c $sql
        if ($LASTEXITCODE -ne 0) { throw 'Post-load database verification query failed' }
        $checks = $raw | ConvertFrom-Json
        if ($checks.drift -ne 0 -or $checks.total -ne 100000) { throw 'Post-load financial invariants failed' }
        if ($checks.ledger_rows -eq $checks.projection_rows) { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Projection did not catch up within 60 seconds after load' }
        Start-Sleep -Seconds 1
    } while ($true)
    if ($checks.statement_drift -ne 0 -or $checks.daily_drift -ne 0) {
        throw 'Projection history or daily summary diverges from the full-history oracle'
    }
    $checks | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'post-load-invariants.json')

    # Rerun against the SAME database and broker after load, without clearing offsets or volumes.
    & "$PSScriptRoot/local-demo.ps1" -ComposeFile $ComposeFile -NoBuild *> (Join-Path $EvidenceDirectory 'reused-demo.log')
    & "$PSScriptRoot/verify-local-api.ps1" *> (Join-Path $EvidenceDirectory 'api-regressions.log')
    Write-Host "PASS: unchanged load, financial invariants, projection catch-up, reused-stack evaluator and API probes. Evidence: $EvidenceDirectory"
} finally {
    $env:ACCESS_TOKEN = $previousToken
    Pop-Location
}
