param(
    [string]$Location = "centralindia",
    [string]$VmSize = "Standard_D2ls_v6",
    [decimal]$BudgetAmount = 25,
    [ValidateSet("correctness", "performance")]
    [string]$K6Profile = "correctness"
)

$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$short = ([guid]::NewGuid().ToString("N")).Substring(0, 10)
$rg = "ledgerline-evidence-$stamp"
$acr = "ll$short"
$pg = "llpg$short"
$vm = "ll-vm-$short"
$tag = (git -C $repo rev-parse --short HEAD).Trim()
$results = Join-Path $repo "bench/results"
New-Item -ItemType Directory -Force -Path $results | Out-Null

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
    budgetAmountUsd = $BudgetAmount
    vmSize = $VmSize
    k6Profile = $K6Profile
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

    try {
        $subscriptionId = (az account show --query id -o tsv).Trim()
        az consumption budget create `
            --budget-name $budgetName `
            --category cost `
            --amount $BudgetAmount `
            --time-grain monthly `
            --start-date $monthStart.ToString("yyyy-MM-dd") `
            --end-date $budgetEnd.ToString("yyyy-MM-dd") `
            --resource-group-filter "/subscriptions/$subscriptionId/resourceGroups/$rg" `
            --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-budget.json")
    } catch {
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

    Save-Summary "creating-postgres"
    az postgres flexible-server create `
        --resource-group $rg `
        --name $pg `
        --location $Location `
        --admin-user ledgeradmin `
        --admin-password $dbPassword `
        --sku-name Standard_B1ms `
        --tier Burstable `
        --storage-size 32 `
        --version 16 `
        --public-access All `
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
  - docker login $acrLoginServer -u $acrUser -p '$acrPass'
  - docker run -d --name ledgerline-kafka --restart unless-stopped --network ledgerline --network-alias kafka -p 9092:9092 --memory=900m --cpus=0.75 -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 -e KAFKA_NUM_PARTITIONS=3 apache/kafka:3.8.0
  - sleep 60
  - docker run -d --name ledgerline-ledger-service --restart unless-stopped --network ledgerline -p 8080:8080 --memory=900m --cpus=0.80 -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=40 -XX:+ExitOnOutOfMemoryError' -e SPRING_DATASOURCE_URL='jdbc:postgresql://${pgHost}:5432/ledgerline?sslmode=require' -e SPRING_DATASOURCE_USERNAME=ledgeradmin -e SPRING_DATASOURCE_PASSWORD='$dbPassword' -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=12 -e SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=4 -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 -e SPRING_KAFKA_PRODUCER_PROPERTIES_LINGER_MS=5 -e SPRING_KAFKA_PRODUCER_PROPERTIES_BATCH_SIZE=32768 -e JWT_SECRET='$jwtSecret' -e LEDGER_OUTBOX_RELAY_ENABLED=true -e LEDGER_OUTBOX_RELAY_DELAY_MS=200 -e LEDGER_OUTBOX_RELAY_BATCH_SIZE=100 $acrLoginServer/ledger-service:$tag
  - docker run -d --name ledgerline-projection-service --restart unless-stopped --network ledgerline -p 8081:8081 --memory=700m --cpus=0.45 -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=35 -XX:+ExitOnOutOfMemoryError' -e SPRING_DATASOURCE_URL='jdbc:postgresql://${pgHost}:5432/ledgerline?sslmode=require' -e SPRING_DATASOURCE_USERNAME=ledgeradmin -e SPRING_DATASOURCE_PASSWORD='$dbPassword' -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=6 -e SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=2 -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 -e SPRING_KAFKA_CONSUMER_PROPERTIES_MAX_POLL_RECORDS=100 -e JWT_SECRET='$jwtSecret' $acrLoginServer/projection-service:$tag
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
        --custom-data $cloudInitPath `
        --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-vm.json")
    Assert-LastExitCode "VM creation failed for size $VmSize in $Location"

    Remove-Item -Force $cloudInitPath
    az vm open-port --resource-group $rg --name $vm --port 8080 --priority 1001 --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-open-port-8080.json")
    Assert-LastExitCode "Opening VM port 8080 failed"
    az vm open-port --resource-group $rg --name $vm --port 8081 --priority 1002 --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-open-port-8081.json")
    Assert-LastExitCode "Opening VM port 8081 failed"

    $ip = (az vm show -d --resource-group $rg --name $vm --query publicIps -o tsv).Trim()
    Assert-LastExitCode "VM public IP lookup failed"
    $summary.publicIp = $ip
    Save-Summary "waiting-for-health"

    try {
        Wait-HttpHealth -Name "Ledger service" -Uri "http://${ip}:8080/actuator/health" -OutputName "03-azure-ledger-health.json"
        Wait-HttpHealth -Name "Projection service" -Uri "http://${ip}:8081/actuator/health" -OutputName "03-azure-projection-health.json"
        Get-Content (Join-Path $results "03-azure-ledger-health.json") -Raw | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-health.json")
    } catch {
        az vm run-command invoke --resource-group $rg --name $vm --command-id RunShellScript --scripts "docker ps -a; docker logs --tail 200 ledgerline-ledger-service; docker logs --tail 100 ledgerline-kafka" --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-health-failure-logs.json")
        throw
    }

    Save-Summary "running-smoke"
    $env:ACCESS_TOKEN = $accessToken
    & "C:\msys64\usr\bin\bash.exe" "bench/smoke-test.sh" "http://${ip}:8080" "http://${ip}:8081" 2>&1 | Tee-Object -FilePath (Join-Path $results "03-azure-smoke.log")
    if ($LASTEXITCODE -ne 0) { throw "Smoke test failed" }

    $smokeText = Get-Content (Join-Path $results "03-azure-smoke.log") -Raw
    $accountA = [regex]::Match($smokeText, "Created Account A: ([0-9a-fA-F-]+)").Groups[1].Value
    $accountB = [regex]::Match($smokeText, "Created Account B: ([0-9a-fA-F-]+)").Groups[1].Value
    if (-not $accountA -or -not $accountB) { throw "Could not parse account IDs from smoke log" }

    Save-Summary "running-k6"
    $benchPath = (Join-Path $repo "bench").Replace("\", "/")
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker run --rm `
        -e ACCESS_TOKEN="$accessToken" `
        -e ACCOUNT_A="$accountA" `
        -e ACCOUNT_B="$accountB" `
        -e CLOUD_URL="http://${ip}:8080/api/v1" `
        -e K6_PROFILE="$K6Profile" `
        -v "${benchPath}:/bench" `
        grafana/k6 run /bench/k6-cloud-load.js --out json=/bench/results/03-azure-k6.json 2>&1 | Tee-Object -FilePath (Join-Path $results "03-azure-k6.log")
    $summary.k6ExitCode = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    Save-Summary "querying-invariants"

    $sql = Get-Content (Join-Path $repo "bench/rds-invariants.sql") -Raw
    $cmd = "cat > /tmp/invariants.sql <<'SQL'`n$sql`nSQL`nPGPASSWORD='$dbPassword' psql -v ON_ERROR_STOP=1 -P pager=off 'host=${pgHost} port=5432 dbname=ledgerline user=ledgeradmin sslmode=require' -f /tmp/invariants.sql 2>&1 | tee /tmp/invariants.out; test `${PIPESTATUS[0]} -eq 0"
    az vm run-command invoke --resource-group $rg --name $vm --command-id RunShellScript --scripts $cmd --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-postgres-invariants.json")
    Assert-LastExitCode "PostgreSQL invariant query failed"

    Save-Summary "recording-cost"
    try {
        az consumption usage list --start-date $today.ToString("yyyy-MM-dd") --end-date $today.AddDays(1).ToString("yyyy-MM-dd") --output json | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-usage.json")
    } catch {
        $_.Exception.Message | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-usage-warning.txt")
    }

    Save-Summary "success-before-destroy"
}
catch {
    $_ | Out-String | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-error.txt")
    Save-Summary "failed-before-destroy"
    throw
}
finally {
    Save-Summary "destroying-resource-group"
    try {
        az group delete --name $rg --yes --no-wait
        (Get-Date).ToUniversalTime().ToString("o") | Set-Content -Encoding UTF8 (Join-Path $results "03-azure-delete-requested-at.txt")
        Save-Summary "waiting-for-delete"
        $deleted = $false
        for ($i = 1; $i -le 90; $i++) {
            $exists = (az group exists --name $rg -o tsv).Trim()
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
    }
}
