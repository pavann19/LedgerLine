param(
    [string]$Location = "centralindia",
    [string]$VmSize = "Standard_D2ls_v6",
    [string]$PostgresSku = "Standard_B1ms",
    [ValidateSet("Burstable", "GeneralPurpose")]
    [string]$PostgresTier = "Burstable",
    [ValidateSet("P4", "P6", "P10", "P15", "P20", "P30", "P40", "P50")]
    [string]$PostgresPerformanceTier = "P4",
    [string]$LedgerMemory = "900m",
    [decimal]$LedgerCpu = 0.80,
    [string]$ProjectionMemory = "700m",
    [decimal]$ProjectionCpu = 0.45,
    [switch]$RecoveryChecks,
    [switch]$RemoteOnly,
    [string]$ComparisonBaselineLocalImage = "",
    [decimal]$BudgetAmount = 25,
    [ValidateSet("correctness", "performance")]
    [string]$K6Profile = "correctness",
    [string]$EvidenceDirectory = ""
)

$ErrorActionPreference = "Stop"
if ($RemoteOnly -and !$RecoveryChecks) { throw 'RemoteOnly requires RecoveryChecks to execute on-host verification' }
if ($ComparisonBaselineLocalImage -and (!$RemoteOnly -or !$RecoveryChecks)) { throw 'ComparisonBaselineLocalImage requires RemoteOnly and RecoveryChecks' }
if ($ComparisonBaselineLocalImage) {
    & docker image inspect $ComparisonBaselineLocalImage --format '{{.Id}}' > $null
    if ($LASTEXITCODE -ne 0) { throw 'The comparison baseline image must exist locally before provisioning Azure' }
}

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$short = ([guid]::NewGuid().ToString("N")).Substring(0, 10)
$rg = "ledgerline-evidence-$stamp"
$acr = "ll$short"
$pg = "llpg$short"
$vm = "ll-vm-$short"
$tag = (git -C $repo rev-parse --short HEAD).Trim()
if (!$EvidenceDirectory) { $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) "ledgerline-azure-$stamp" }
$results = $EvidenceDirectory
New-Item -ItemType Directory -Force -Path $results | Out-Null
$reviewerIp = (Invoke-RestMethod 'https://api.ipify.org').Trim()
if ($reviewerIp -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { throw 'Expected reviewer IPv4 address' }
$sshTunnel = $null
$cloudInitPath = $null
$oldAccessToken = $env:ACCESS_TOKEN

function New-Secret([int]$bytes = 48) {
    $buffer = New-Object byte[] $bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($buffer)
    $rng.Dispose()
    [Convert]::ToBase64String($buffer)
}

function ConvertTo-Base64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function New-Hs256Jwt([string]$secret) {
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = @{ alg = "HS256"; typ = "JWT" } | ConvertTo-Json -Compress
    $payload = @{
        sub = "azure-operator"
        roles = @("OPERATOR")
        iat = $now
        exp = $now + 7200
    } | ConvertTo-Json -Compress
    $h = ConvertTo-Base64Url([Text.Encoding]::UTF8.GetBytes($header))
    $p = ConvertTo-Base64Url([Text.Encoding]::UTF8.GetBytes($payload))
    $data = "$h.$p"
    $key = [Text.Encoding]::UTF8.GetBytes($secret)
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($key)
    $sig = ConvertTo-Base64Url($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($data)))
    "$data.$sig"
}

$dbPassword = "P@" + ([guid]::NewGuid().ToString("N")) + "9a"
$jwtSecret = New-Secret 48
$accessToken = New-Hs256Jwt $jwtSecret
$budgetName = "ledgerline-$stamp"
$today = (Get-Date).ToUniversalTime().Date
$monthStart = Get-Date -Year $today.Year -Month $today.Month -Day 1 -Hour 0 -Minute 0 -Second 0
$budgetEnd = $monthStart.AddYears(1).AddDays(-1)

$summary = [ordered]@{
    status = "started"
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    resourceGroup = $rg
    location = $Location
    acr = $acr
    postgres = $pg
    vm = $vm
    gitCommit = $tag
    budgetName = $budgetName
    budgetAmountBillingCurrency = $BudgetAmount
    vmSize = $VmSize
    k6Profile = $K6Profile
    postgresSku = $PostgresSku
    postgresTier = $PostgresTier
    postgresPerformanceTier = $PostgresPerformanceTier
    ledgerCpu = $LedgerCpu
    projectionCpu = $ProjectionCpu
    recoveryChecks = [bool]$RecoveryChecks
    remoteOnly = [bool]$RemoteOnly
    comparisonBaselineLocalImage = $ComparisonBaselineLocalImage
}

function Save-Summary {
    param([string]$Status)
    $summary.status = $Status
    $summary.updatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-run-status.json")
}

function Assert-LastExitCode {
    param([string]$FailureMessage)
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

function Invoke-DockerLogged {
    param(
        [string[]]$Arguments,
        [string]$LogName,
        [string]$FailureMessage
    )
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & docker @Arguments 2>&1 | Tee-Object -FilePath (Join-Path $results $LogName)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    if ($code -ne 0) { throw $FailureMessage }
}

function Wait-HttpHealth {
    param(
        [string]$Name,
        [string]$Uri,
        [string]$OutputName,
        [int]$Attempts = 90,
        [int]$DelaySeconds = 10
    )
    for ($i = 1; $i -le $Attempts; $i++) {
        try {
            $health = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
            $content = if ($health.Content -is [byte[]]) {
                [Text.Encoding]::UTF8.GetString($health.Content)
            } else {
                [string]$health.Content
            }
            if ($content -match '"status"\s*:\s*"UP"') {
                $content | Set-Content -Encoding UTF8 (Join-Path $results $OutputName)
                return
            }
        } catch {}
        Start-Sleep -Seconds $DelaySeconds
    }
    throw "$Name did not become healthy at $Uri"
}

Save-Summary "creating-resource-group"

try {
    az group create --name $rg --location $Location --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-resource-group.json")
    Assert-LastExitCode "Resource group creation failed"

    try {
        $subscriptionId = (az account show --query id -o tsv).Trim()
        $budgetRequest = @{ properties=@{ category='Cost'; amount=$BudgetAmount; timeGrain='Monthly';
            timePeriod=@{startDate=$monthStart.ToString('yyyy-MM-ddT00:00:00Z');endDate=$budgetEnd.ToString('yyyy-MM-ddT00:00:00Z')} } }
        $budgetFile = Join-Path $results 'budget-request.json'
        [IO.File]::WriteAllText($budgetFile, ($budgetRequest | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
        $budgetUrl = "https://management.azure.com/subscriptions/$subscriptionId/resourceGroups/$rg/providers/Microsoft.Consumption/budgets/${budgetName}?api-version=2024-08-01"
        az rest --method put --url $budgetUrl --body "@$budgetFile" --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-budget.json")
        Assert-LastExitCode "Azure budget creation failed; no hard spending cap is provided"
        $summary.budgetCreated = $true
    } catch {
        $summary.budgetCreated = $false
        @"
Azure budget creation did not complete automatically.

Reason:
$($_.Exception.Message)

Manual fallback:
Create a Cost Management budget in the Azure portal scoped to resource group '$rg'
with amount '$BudgetAmount' before running long-lived or repeated evidence tests.
This script still creates all resources inside one short-lived resource group and
deletes that group in finally, then polls 'az group exists' until it returns false.
"@ | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-budget-manual.md")
    }

    Save-Summary "creating-acr"
    az acr create --resource-group $rg --name $acr --sku Basic --admin-enabled true --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-acr.json")
    Assert-LastExitCode "ACR creation failed"
    $acrLoginServer = (az acr show --name $acr --resource-group $rg --query loginServer -o tsv).Trim()
    Assert-LastExitCode "ACR lookup failed"

    Save-Summary "building-images"
    az acr login --name $acr | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-acr-login.log")
    if ($LASTEXITCODE -ne 0) { throw "ACR login failed" }
    Invoke-DockerLogged -Arguments @("build", "-t", "$acrLoginServer/ledger-service:$tag", "-f", "deploy/Dockerfile.ledger", $repo) -LogName "03-azure-docker-build-ledger.log" -FailureMessage "ledger-service Docker build failed"
    Invoke-DockerLogged -Arguments @("push", "$acrLoginServer/ledger-service:$tag") -LogName "03-azure-docker-push-ledger.log" -FailureMessage "ledger-service Docker push failed"
    Invoke-DockerLogged -Arguments @("build", "-t", "$acrLoginServer/projection-service:$tag", "-f", "deploy/Dockerfile.projection", $repo) -LogName "03-azure-docker-build-projection.log" -FailureMessage "projection-service Docker build failed"
    Invoke-DockerLogged -Arguments @("push", "$acrLoginServer/projection-service:$tag") -LogName "03-azure-docker-push-projection.log" -FailureMessage "projection-service Docker push failed"
    if ($ComparisonBaselineLocalImage) {
        Invoke-DockerLogged -Arguments @("tag", $ComparisonBaselineLocalImage, "$acrLoginServer/ledger-baseline:comparison") -LogName "03-baseline-tag.log" -FailureMessage "Baseline image tag failed"
        Invoke-DockerLogged -Arguments @("push", "$acrLoginServer/ledger-baseline:comparison") -LogName "03-baseline-push.log" -FailureMessage "Baseline image push failed"
    }


    Save-Summary "creating-postgres"
    az postgres flexible-server create `
        --resource-group $rg `
        --name $pg `
        --location $Location `
        --admin-user ledgeradmin `
        --admin-password $dbPassword `
        --sku-name $PostgresSku `
        --tier $PostgresTier `
        --storage-size 32 `
        --performance-tier $PostgresPerformanceTier `
        --version 16 `
        --public-access $reviewerIp `
        --yes `
        --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-postgres.json")
    Assert-LastExitCode "PostgreSQL server creation failed"

    az postgres flexible-server db create --resource-group $rg --server-name $pg --name ledgerline --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-postgres-db.json")
    Assert-LastExitCode "PostgreSQL database creation failed"

    Save-Summary "creating-vm"
    $acrUser = (az acr credential show --name $acr --query username -o tsv).Trim()
    $acrPass = (az acr credential show --name $acr --query "passwords[0].value" -o tsv).Trim()
    $pgHost = "$pg.postgres.database.azure.com"

    $cloudInit = @"
#cloud-config
package_update: true
packages:
  - docker.io
  - postgresql-client
runcmd:
  - systemctl enable --now docker
  - docker network create ledgerline || true
  - echo '$acrPass' | docker login $acrLoginServer -u $acrUser --password-stdin
  - docker run -d --name ledgerline-kafka --restart unless-stopped --network ledgerline --network-alias kafka --memory=900m --cpus=0.75 -e KAFKA_HEAP_OPTS='-Xms256m -Xmx256m' -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 -e KAFKA_NUM_PARTITIONS=3 apache/kafka:3.8.0
  - sleep 60
  - docker run -d --name ledgerline-ledger-service --restart unless-stopped --network ledgerline -p 127.0.0.1:8080:8080 --memory=$LedgerMemory --cpus=$LedgerCpu -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=40 -XX:+ExitOnOutOfMemoryError' -e SPRING_DATASOURCE_URL='jdbc:postgresql://${pgHost}:5432/ledgerline?sslmode=require' -e SPRING_DATASOURCE_USERNAME=ledgeradmin -e SPRING_DATASOURCE_PASSWORD='$dbPassword' -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=12 -e SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=4 -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 -e SPRING_KAFKA_PRODUCER_PROPERTIES_LINGER_MS=5 -e SPRING_KAFKA_PRODUCER_PROPERTIES_BATCH_SIZE=32768 -e JWT_SECRET='$jwtSecret' -e LEDGER_OUTBOX_RELAY_ENABLED=true -e LEDGER_OUTBOX_RELAY_DELAY_MS=200 -e LEDGER_OUTBOX_RELAY_BATCH_SIZE=100 $acrLoginServer/ledger-service:$tag
  - timeout 600 bash -c "until curl -fsS http://127.0.0.1:8080/actuator/health | grep -q UP; do sleep 5; done"
  - docker run -d --name ledgerline-projection-service --restart unless-stopped --network ledgerline -p 127.0.0.1:8081:8081 --memory=$ProjectionMemory --cpus=$ProjectionCpu -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=35 -XX:+ExitOnOutOfMemoryError' -e SPRING_DATASOURCE_URL='jdbc:postgresql://${pgHost}:5432/ledgerline?sslmode=require' -e SPRING_DATASOURCE_USERNAME=ledgeradmin -e SPRING_DATASOURCE_PASSWORD='$dbPassword' -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=6 -e SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=2 -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 -e SPRING_KAFKA_CONSUMER_PROPERTIES_MAX_POLL_RECORDS=100 -e JWT_SECRET='$jwtSecret' $acrLoginServer/projection-service:$tag
"@
    $cloudInitPath = Join-Path ([IO.Path]::GetTempPath()) "ledgerline-cloud-init-$stamp.yaml"
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($cloudInitPath, $cloudInit, $utf8NoBom)

    az vm create `
        --resource-group $rg `
        --name $vm `
        --image Ubuntu2204 `
        --size $VmSize `
        --admin-username azureuser `
        --generate-ssh-keys `
        --nsg-rule NONE `
        --custom-data $cloudInitPath `
        --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-vm.json")
    Assert-LastExitCode "VM creation failed for size $VmSize in $Location"

    Remove-Item -Force $cloudInitPath
    $ip = (az vm show -d --resource-group $rg --name $vm --query publicIps -o tsv).Trim()
    Assert-LastExitCode "VM public IP lookup failed"
    $nsgId = (az network nic list --resource-group $rg --query '[0].networkSecurityGroup.id' -o tsv).Trim()
    Assert-LastExitCode "NSG lookup failed"
    $nsgName = ($nsgId -split '/')[-1]
    az network nsg rule create --resource-group $rg --nsg-name $nsgName --name ReviewerSSH --priority 1000 --source-address-prefixes "$reviewerIp/32" --destination-port-ranges 22 --access Allow --protocol Tcp --output none
    Assert-LastExitCode "Restricted SSH rule failed"
    az postgres flexible-server firewall-rule create --resource-group $rg --server-name $pg --name DemoVmOnly --start-ip-address $ip --end-ip-address $ip --output none
    Assert-LastExitCode "VM PostgreSQL firewall rule failed"
    $sshArgs = @('-N', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes', '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ServerAliveInterval=15', '-L', '127.0.0.1:8080:127.0.0.1:8080', '-L', '127.0.0.1:8081:127.0.0.1:8081', "azureuser@$ip")
    if (!$RemoteOnly) { $sshTunnel = Start-Process ssh.exe -ArgumentList $sshArgs -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $results 'ssh-tunnel.log') }
    $summary.publicIp = $ip
    Save-Summary "waiting-for-health"

    if (!$RemoteOnly) {
        try {
            Wait-HttpHealth -Name "Ledger service" -Uri "http://localhost:8080/actuator/health" -OutputName "03-azure-ledger-health.json"
            Wait-HttpHealth -Name "Projection service" -Uri "http://localhost:8081/actuator/health" -OutputName "03-azure-projection-health.json"
            Get-Content (Join-Path $results "03-azure-ledger-health.json") -Raw | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-health.json")
        } catch {
            az vm run-command invoke --resource-group $rg --name $vm --command-id RunShellScript --scripts "docker ps -a; docker logs --tail 200 ledgerline-ledger-service; docker logs --tail 100 ledgerline-kafka" --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-health-failure-logs.json")
            throw
        }

        Save-Summary "running-smoke"
        $env:ACCESS_TOKEN = $accessToken
        & "C:\msys64\usr\bin\bash.exe" "bench/smoke-test.sh" "http://localhost:8080" "http://localhost:8081" 2>&1 | Tee-Object -FilePath (Join-Path $results "03-azure-smoke.log")
        if ($LASTEXITCODE -ne 0) { throw "Smoke test failed" }

        $smokeText = Get-Content (Join-Path $results "03-azure-smoke.log") -Raw
        $accountA = [regex]::Match($smokeText, "Created Account A: ([0-9a-fA-F-]+)").Groups[1].Value
        $accountB = [regex]::Match($smokeText, "Created Account B: ([0-9a-fA-F-]+)").Groups[1].Value
        if (-not $accountA -or -not $accountB) { throw "Could not parse account IDs from smoke log" }

        $headers = @{ Authorization = "Bearer $accessToken"; 'Idempotency-Key' = "cloud-replay-$stamp" }
        $body = @{ fromAccountId=$accountA; toAccountId=$accountB; amountMinor=1; currency='USD' } | ConvertTo-Json
        $original = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/transfers' -Headers $headers -ContentType 'application/json' -Body $body
        $replay = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/transfers' -Headers $headers -ContentType 'application/json' -Body $body
        if ($original.transactionId -ne $replay.transactionId -or !$replay.idempotentReplay) { throw 'Cloud replay did not return original transaction' }
        @{ replayReturnedOriginal=$true; transactionId=$original.transactionId } | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $results '03-azure-replay.json')

        Save-Summary "running-k6"
        $benchPath = (Join-Path $repo "bench").Replace("\", "/")
        $evidenceMount = $results.Replace("\", "/")
        $oldPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        docker run --rm `
            -e ACCESS_TOKEN="$accessToken" `
            -e ACCOUNT_A="$accountA" `
            -e ACCOUNT_B="$accountB" `
            -e CLOUD_URL="http://host.docker.internal:8080/api/v1" `
            -e K6_PROFILE="$K6Profile" `
            -v "${benchPath}:/bench:ro" `
            -v "${evidenceMount}:/evidence" `
            grafana/k6 run /bench/k6-cloud-load.js --summary-export=/evidence/03-azure-k6-summary.json 2>&1 | Tee-Object -FilePath (Join-Path $results "03-azure-k6.log")
        $summary.k6ExitCode = $LASTEXITCODE
        $ErrorActionPreference = $oldPreference
        if ($summary.k6ExitCode -ne 0) { throw "Cloud k6 correctness thresholds failed" }
        # Exercise account creation and replay again on the same populated broker/database.
        & "C:\msys64\usr\bin\bash.exe" "bench/smoke-test.sh" "http://localhost:8080" "http://localhost:8081" 2>&1 | Tee-Object -FilePath (Join-Path $results "03-azure-reused-smoke.log")
        Assert-LastExitCode 'Cloud reused-stack smoke failed'
        Save-Summary "querying-invariants"

    } else {
        Save-Summary "waiting-for-remote-health"
        $healthProbe = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'verify-cloud-health.py')))
        $healthCommand = "set -e; printf '%s' '$healthProbe' | base64 -d > /tmp/verify-cloud-health.py; sudo python3 /tmp/verify-cloud-health.py"
        az vm run-command invoke --resource-group $rg --name $vm --command-id RunShellScript --scripts $healthCommand --output json | Set-Content -Encoding UTF8 (Join-Path $results '03-azure-remote-health.json')
        Assert-LastExitCode 'On-host cloud health invocation failed'
        $healthResult = Get-Content (Join-Path $results '03-azure-remote-health.json') -Raw | ConvertFrom-Json
        if (($healthResult.value.message -join "`n") -notmatch '"result": "PASS"') { throw 'On-host cloud health checks did not pass' }
    }

    $probe = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'verify-cloud-api.py')))
    $probeCommand = "set -e; printf '%s' '$probe' | base64 -d > /tmp/verify-cloud-api.py; sudo python3 /tmp/verify-cloud-api.py"
    az vm run-command invoke --resource-group $rg --name $vm --command-id RunShellScript --scripts $probeCommand --output json | Set-Content -Encoding UTF8 (Join-Path $results '03-azure-api-probes.json')
    Assert-LastExitCode 'Cloud API probe invocation failed'
    $probeResult = Get-Content (Join-Path $results '03-azure-api-probes.json') -Raw | ConvertFrom-Json
    if (($probeResult.value.message -join "`n") -notmatch '"result": "PASS"') { throw 'Cloud API probes did not pass' }

    $sql = Get-Content (Join-Path $repo "bench/rds-invariants.sql") -Raw
    $sql += @'

DO $$ BEGIN
 IF (SELECT coalesce(sum(amount_minor),0) FROM postings) <> 0
 OR EXISTS (SELECT 1 FROM account_balances b WHERE b.balance_minor <> (SELECT coalesce(sum(amount_minor),0) FROM postings p WHERE p.account_id=b.account_id))
 OR EXISTS (SELECT 1 FROM account_balances WHERE account_type='CUSTOMER' AND balance_minor<0)
 OR EXISTS (SELECT 1 FROM postings GROUP BY transaction_id HAVING sum(amount_minor)<>0)
 THEN RAISE EXCEPTION 'Cloud monetary invariants failed'; END IF;
END $$;
CREATE TEMP VIEW cloud_projection_checks AS
WITH ordered AS (
 SELECT *,sum(amount_minor) OVER (PARTITION BY account_id ORDER BY created_at,id) expected FROM statement_view
), days AS (
 SELECT account_id,(created_at AT TIME ZONE 'UTC')::date summary_date,
 (array_agg(running_balance_minor-amount_minor ORDER BY created_at,id))[1] opening,
 (array_agg(running_balance_minor ORDER BY created_at DESC,id DESC))[1] closing,
 coalesce(sum(amount_minor) FILTER (WHERE amount_minor>0),0) inflow,
 coalesce(sum(-amount_minor) FILTER (WHERE amount_minor<0),0) outflow,count(*) posting_count
 FROM ordered GROUP BY account_id,(created_at AT TIME ZONE 'UTC')::date
)
SELECT
 (SELECT count(*) FROM postings) ledger_rows,
 (SELECT count(*) FROM statement_view) projection_rows,
 (SELECT count(*) FROM ordered WHERE running_balance_minor<>expected) statement_drift,
 (SELECT count(*) FROM days e FULL JOIN daily_account_summary d USING(account_id,summary_date)
 WHERE e.account_id IS NULL OR d.account_id IS NULL OR d.opening_balance_minor<>e.opening
 OR d.closing_balance_minor<>e.closing OR d.total_inflow_minor<>e.inflow
 OR d.total_outflow_minor<>e.outflow OR d.posting_count<>e.posting_count) daily_drift;
TABLE cloud_projection_checks;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM cloud_projection_checks WHERE ledger_rows<>projection_rows OR statement_drift<>0 OR daily_drift<>0)
 THEN RAISE EXCEPTION 'Cloud projection invariants failed'; END IF;
END $$;
SELECT 'CLOUD_SQL_ASSERTIONS_PASSED' AS verification;
'@
    # Allow a settling period before the global SQL check. Remote invocation adds
    # latency; this is not a measurement of the local 60-second catch-up bound.
    Start-Sleep -Seconds 60
    $sqlPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
    $databaseProbe = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'verify-cloud-database.py')))
    # Base64 avoids multiline SQL/quote interpretation by the Windows CLI shim.
    $cmd = "set -e; printf '%s' '$sqlPayload' | base64 -d > /tmp/invariants.sql; printf '%s' '$databaseProbe' | base64 -d > /tmp/verify-cloud-database.py; sudo python3 /tmp/verify-cloud-database.py /tmp/invariants.sql"
    az vm run-command invoke --resource-group $rg --name $vm --command-id RunShellScript --scripts $cmd --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-postgres-invariants.json")
    Assert-LastExitCode "PostgreSQL invariant query failed"
    $remoteResult = Get-Content (Join-Path $results "03-azure-postgres-invariants.json") -Raw | ConvertFrom-Json
    if (@($remoteResult.value | Where-Object { $_.message -match 'ERROR:|EXCEPTION:|FATAL:' }).Count -gt 0) {
        throw 'Remote SQL command reported an invariant failure'
    }
    if (($remoteResult.value.message -join "`n") -notmatch 'CLOUD_SQL_ASSERTIONS_PASSED') { throw 'Remote SQL assertion completion was not observed' }

    if ($RecoveryChecks) {
        Save-Summary "running-recovery-checks"
        if ($ComparisonBaselineLocalImage) {
            & (Join-Path $PSScriptRoot 'compare-cloud-transfers.ps1') -PublicIp $ip -EvidenceDirectory $results -ResourceGroup $rg -BaselineImage "$acrLoginServer/ledger-baseline:comparison" -OptimizedImage "$acrLoginServer/ledger-service:$tag"
        } else {
            & (Join-Path $PSScriptRoot 'verify-cloud-recovery.ps1') -PublicIp $ip -EvidenceDirectory $results
        }
    }

    Save-Summary "recording-cost"
    try {
        az consumption usage list --start-date $today.ToString("yyyy-MM-dd") --end-date $today.AddDays(1).ToString("yyyy-MM-dd") --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-usage.json")
    } catch {
        $_.Exception.Message | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-usage-warning.txt")
    }

    $summary.verificationPassed = $true
    Save-Summary "success-before-destroy"
}
catch {
    $_ | Out-String | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-error.txt")
    Save-Summary "failed-before-destroy"
    throw
}
finally {
    $env:ACCESS_TOKEN = $oldAccessToken
    if ($sshTunnel -and !$sshTunnel.HasExited) { Stop-Process -Id $sshTunnel.Id }
    if ($cloudInitPath -and (Test-Path -LiteralPath $cloudInitPath)) { Remove-Item -LiteralPath $cloudInitPath -Force }
    Save-Summary "destroying-resource-group"
    try {
        az group delete --name $rg --yes --no-wait
        Assert-LastExitCode "Resource group deletion request failed"
        (Get-Date).ToUniversalTime().ToString("o") | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-delete-requested-at.txt")
        Save-Summary "waiting-for-delete"
        $deleted = $false
        for ($i = 1; $i -le 90; $i++) {
            $exists = (az group exists --name $rg -o tsv).Trim()
            Assert-LastExitCode "Resource group absence check failed"
            "$((Get-Date).ToUniversalTime().ToString("o")) exists=$exists" | Add-Content -Encoding UTF8 (Join-Path $results "03-azure-delete-poll.log")
            if ($exists -eq "false") {
                $deleted = $true
                break
            }
            Start-Sleep -Seconds 20
        }
        $summary.cleanupConfirmedAbsent = $deleted
        if ($deleted) {
            Save-Summary "deleted"
        } else {
            Save-Summary "delete-pending"
            throw "Resource group delete was requested but az group exists did not return false before timeout"
        }
    } catch {
        $_.Exception.Message | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-delete-error.txt")
        Save-Summary "delete-failed"
        throw
    }
}
