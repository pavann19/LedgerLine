param(
    [string]$ComposeFile = "deploy/compose.yaml",
    [string]$JwtSecret = "",
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($JwtSecret)) {
    $secretBytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($secretBytes) } finally { $random.Dispose() }
    $JwtSecret = [Convert]::ToBase64String($secretBytes)
}

function ConvertTo-Base64Url {
    param([byte[]]$Bytes)
    return [Convert]::ToBase64String($Bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function New-DemoJwt {
    param(
        [string]$Subject,
        [string[]]$Roles,
        [string[]]$Accounts = @()
    )

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = @{ alg = "HS256"; typ = "JWT" } | ConvertTo-Json -Compress
    $payload = @{
        sub = $Subject
        roles = $Roles
        accounts = $Accounts
        iat = $now
        exp = $now + 3600
    } | ConvertTo-Json -Compress

    $encodedHeader = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header))
    $encodedPayload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payload))
    $signingInput = "$encodedHeader.$encodedPayload"
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($JwtSecret))
    $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($signingInput)))
    return "$signingInput.$signature"
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Uri,
        [string]$Token,
        [object]$Body = $null,
        [hashtable]$Headers = @{}
    )

    $requestHeaders = @{ Authorization = "Bearer $Token" }
    foreach ($key in $Headers.Keys) {
        $requestHeaders[$key] = $Headers[$key]
    }

    $params = @{
        Method = $Method
        Uri = $Uri
        Headers = $requestHeaders
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = ($Body | ConvertTo-Json -Compress)
    }

    return Invoke-RestMethod @params
}

function Wait-Health {
    param([string]$Uri, [string]$Name)

    for ($i = 1; $i -le 60; $i++) {
        try {
            $health = Invoke-RestMethod -Uri $Uri -TimeoutSec 3
            if ($health.status -eq "UP") {
                Write-Host "$Name health: UP"
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "$Name did not become healthy at $Uri"
}

function Wait-ProjectionStatement {
    param(
        [string]$AccountId,
        [string]$Token,
        [string]$FundingTransactionId,
        [string]$DebitTransactionId
    )

    $uri = "http://localhost:8081/api/v1/projections/accounts/$AccountId/statement?limit=10"
    for ($i = 1; $i -le 60; $i++) {
        try {
            $statement = Invoke-Json GET $uri $Token
            $fundingEntry = @($statement.items | Where-Object transactionId -eq $FundingTransactionId)
            $debitEntry = @($statement.items | Where-Object transactionId -eq $DebitTransactionId)
            if ($fundingEntry.Count -eq 1 -and $debitEntry.Count -eq 1) {
                if ($fundingEntry[0].amountMinor -ne 100000 -or $fundingEntry[0].runningBalanceMinor -ne 100000) {
                    throw "Funding projection is out of order: expected amount/running balance 100000/100000."
                }
                if ($debitEntry[0].amountMinor -ne -12500 -or $debitEntry[0].runningBalanceMinor -ne 87500) {
                    throw "Debit projection is out of order: expected amount/running balance -12500/87500."
                }
                return $statement
            }
        } catch {
            if ($_.Exception.Message -like "*projection is out of order*") {
                throw
            }
        }
        Start-Sleep -Seconds 1
    }
    throw "Projection statement did not contain both demo transactions within 60 seconds."
}

function Assert-DockerReady {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker CLI was not found. Install Docker Desktop or make docker available on PATH before running the local demo."
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & docker info *> $null
        $dockerInfoExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($dockerInfoExitCode -ne 0) {
        throw "Docker is installed but the daemon is not reachable. Start Docker Desktop and ensure the Linux engine is running before running the local demo."
    }
}

Push-Location (Resolve-Path (Join-Path $PSScriptRoot ".."))
try {
    Assert-DockerReady

    # A failed/rekeyed run must not leave an older summary looking current.
    if (Test-Path -LiteralPath 'bench/results/local-demo-summary.json') {
        Copy-Item -LiteralPath 'bench/results/local-demo-summary.json' -Destination 'bench/results/local-demo-summary.previous.json' -Force
        Remove-Item -LiteralPath 'bench/results/local-demo-summary.json'
    }

    $env:JWT_SECRET = $JwtSecret
    $composeArgs = @("compose", "-f", $ComposeFile, "up", "-d")
    if (-not $NoBuild) {
        $composeArgs += "--build"
    }

    Write-Host "Starting LedgerLine local stack..."
    $previousErrorPreference = $ErrorActionPreference
    try {
        # Windows PowerShell treats Docker's normal progress on stderr as an error when redirected.
        $ErrorActionPreference = 'Continue'
        & docker @composeArgs
        $composeExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorPreference }
    if ($composeExitCode -ne 0) {
        throw "docker $($composeArgs -join ' ') failed with exit code $composeExitCode"
    }

    Wait-Health "http://localhost:8080/actuator/health" "ledger-service"
    Wait-Health "http://localhost:8081/actuator/health" "projection-service"

    $operatorToken = New-DemoJwt -Subject "demo-operator" -Roles @("OPERATOR")
    $customerToken = New-DemoJwt -Subject "demo-customer" -Roles @("CUSTOMER")
    $api = "http://localhost:8080/api/v1"

    Write-Host "Creating seeded demo accounts..."
    $checking = Invoke-Json POST "$api/accounts" $customerToken @{ currency = "USD"; type = "CUSTOMER" }
    $savings = Invoke-Json POST "$api/accounts" $customerToken @{ currency = "USD"; type = "CUSTOMER" }
    $funding = Invoke-Json POST "$api/accounts" $operatorToken @{ currency = "USD"; type = "OVERDRAFT" }

    Write-Host "Seeding starting capital through a real balanced transfer..."
    $fundTransfer = Invoke-Json POST "$api/transfers" $operatorToken @{
        fromAccountId = $funding.id
        toAccountId = $checking.id
        amountMinor = 100000
        currency = "USD"
    } @{ "Idempotency-Key" = "demo-fund-$([Guid]::NewGuid())" }

    Write-Host "Running a customer transfer..."
    $transferKey = "demo-transfer-$([Guid]::NewGuid())"
    $transfer = Invoke-Json POST "$api/transfers" $customerToken @{
        fromAccountId = $checking.id
        toAccountId = $savings.id
        amountMinor = 12500
        currency = "USD"
    } @{ "Idempotency-Key" = $transferKey; "X-Isolation-Variant" = "VARIANT_1_PESSIMISTIC" }

    $replay = Invoke-Json POST "$api/transfers" $customerToken @{
        fromAccountId = $checking.id
        toAccountId = $savings.id
        amountMinor = 12500
        currency = "USD"
    } @{ "Idempotency-Key" = $transferKey; "X-Isolation-Variant" = "VARIANT_1_PESSIMISTIC" }

    $checkingAfter = Invoke-Json GET "$api/accounts/$($checking.id)" $customerToken
    $savingsAfter = Invoke-Json GET "$api/accounts/$($savings.id)" $customerToken
    $fundingAfter = Invoke-Json GET "$api/accounts/$($funding.id)" $operatorToken
    if (-not $replay.idempotentReplay -or $replay.transactionId -ne $transfer.transactionId) {
        throw 'Idempotent replay did not return the original transaction'
    }
    if ($checkingAfter.balanceMinor -ne 87500 -or $savingsAfter.balanceMinor -ne 12500 -or $fundingAfter.balanceMinor -ne -100000) {
        throw 'Demo account balances or signed conservation invariant failed'
    }

    $projectionToken = New-DemoJwt -Subject "demo-customer" -Roles @("CUSTOMER") -Accounts @($checking.id, $savings.id)
    $projectionStatement = Wait-ProjectionStatement `
        -AccountId $checking.id `
        -Token $projectionToken `
        -FundingTransactionId $fundTransfer.transactionId `
        -DebitTransactionId $transfer.transactionId
    Write-Host "Projection statement ordering: VERIFIED"

    $summary = [ordered]@{
        ledgerHealth = "http://localhost:8080/actuator/health"
        projectionHealth = "http://localhost:8081/actuator/health"
        swaggerUi = "http://localhost:8080/swagger-ui.html"
        checkingAccountId = $checking.id
        savingsAccountId = $savings.id
        fundingAccountId = $funding.id
        fundingTransactionId = $fundTransfer.transactionId
        transferTransactionId = $transfer.transactionId
        transferIdempotencyKey = $transferKey
        replayReturnedOriginal = $replay.idempotentReplay
        checkingBalanceMinor = $checkingAfter.balanceMinor
        savingsBalanceMinor = $savingsAfter.balanceMinor
        projectionStatementVerified = $true
        projectionStatement = @($projectionStatement.items | Where-Object transactionId -in @($fundTransfer.transactionId, $transfer.transactionId))
        customerToken = $customerToken
        projectionToken = $projectionToken
    }

    New-Item -ItemType Directory -Force -Path "bench/results" | Out-Null
    $summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 "bench/results/local-demo-summary.json"

    Write-Host ""
    Write-Host "Local demo complete. Summary written to bench/results/local-demo-summary.json"
    [pscustomobject]$summary | Select-Object * -ExcludeProperty customerToken, projectionToken | ConvertTo-Json -Depth 5
} finally {
    Pop-Location
}
