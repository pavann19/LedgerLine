param([string]$SummaryPath = 'bench/results/local-demo-summary.json')
$ErrorActionPreference = 'Stop'
$summary = Get-Content -LiteralPath $SummaryPath | ConvertFrom-Json
$base = 'http://localhost:8080/api/v1'
$checks = 0

function Assert-Status {
    param([string]$Name, [string]$Method, [string]$Uri, [int]$Expected,
        [string]$Token = '', [string]$Body = '', [hashtable]$Extra = @{})
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    foreach ($key in $Extra.Keys) { $headers[$key] = $Extra[$key] }
    $args = @{ Method=$Method; Uri=$Uri; Headers=$headers; UseBasicParsing=$true; TimeoutSec=10 }
    if ($Body) { $args.Body=$Body; $args.ContentType='application/json' }
    try { $status = [int](Invoke-WebRequest @args).StatusCode }
    catch {
        if (-not $_.Exception.Response) { throw }
        $status = [int]$_.Exception.Response.StatusCode
    }
    if ($status -ne $Expected) { throw "$Name expected $Expected but got $status" }
    $script:checks++
    Write-Host "PASS $Name ($status)"
}

$token = $summary.customerToken
$accountUri = "$base/accounts/$($summary.checkingAccountId)"
$before = Invoke-RestMethod -Uri $accountUri -Headers @{Authorization="Bearer $token"}
Assert-Status 'unauthenticated' GET $accountUri 401
Assert-Status 'invalid-token' GET $accountUri 401 'invalid.jwt.token'
$knownKeyHeader = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}')).TrimEnd('=').Replace('+','-').Replace('/','_')
$knownKeyPayload = @{sub='demo-customer';roles=@('CUSTOMER');exp=([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()+300)} | ConvertTo-Json -Compress
$knownKeyPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($knownKeyPayload)).TrimEnd('=').Replace('+','-').Replace('/','_')
$knownKeyInput = "$knownKeyHeader.$knownKeyPayload"
$knownKeySigner = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes('ledgerline-local-demo-secret-32-bytes-min'))
try { $knownKeySignature = [Convert]::ToBase64String($knownKeySigner.ComputeHash([Text.Encoding]::UTF8.GetBytes($knownKeyInput))).TrimEnd('=').Replace('+','-').Replace('/','_') }
finally { $knownKeySigner.Dispose() }
Assert-Status 'previous-known-demo-signing-key' GET $accountUri 401 "$knownKeyInput.$knownKeySignature"
Assert-Status 'foreign-account' GET "$base/accounts/$($summary.fundingAccountId)" 403 $token
Assert-Status 'foreign-postings' GET "$base/accounts/$($summary.fundingAccountId)/postings" 403 $token
Assert-Status 'foreign-projection' GET "http://localhost:8081/api/v1/projections/accounts/$($summary.fundingAccountId)/statement" 403 $summary.projectionToken
foreach ($type in @('OVERDRAFT','INTERNAL')) {
    Assert-Status "customer-provision-$type" POST "$base/accounts" 403 $token (@{currency='USD';type=$type} | ConvertTo-Json)
}
$request = @{fromAccountId=$summary.checkingAccountId;toAccountId=$summary.savingsAccountId;amountMinor=1;currency='USD'}
$key = @{ 'Idempotency-Key' = 'probe-' + [guid]::NewGuid() }
Assert-Status 'broken-strategy' POST "$base/transfers" 400 $token ($request | ConvertTo-Json) @{'Idempotency-Key'=$key['Idempotency-Key'];'X-Isolation-Variant'='VARIANT_0_BROKEN'}
Assert-Status 'missing-key' POST "$base/transfers" 400 $token ($request | ConvertTo-Json)
Assert-Status 'malformed-json' POST "$base/transfers" 400 $token '{' $key
foreach ($amount in @(0,-1,0.29)) {
    $request.amountMinor=$amount
    Assert-Status "invalid-amount-$amount" POST "$base/transfers" 400 $token ($request | ConvertTo-Json) $key
}
$request.amountMinor=1
$request.fromAccountId=$summary.fundingAccountId
Assert-Status 'foreign-debit' POST "$base/transfers" 403 $token ($request | ConvertTo-Json) $key
$request.fromAccountId=$summary.checkingAccountId
if ($summary.transferIdempotencyKey) {
    Assert-Status 'conflicting-replay' POST "$base/transfers" 422 $token ($request | ConvertTo-Json) @{'Idempotency-Key'=$summary.transferIdempotencyKey}
}
$after = Invoke-RestMethod -Uri $accountUri -Headers @{Authorization="Bearer $token"}
if ($before.balanceMinor -ne $after.balanceMinor) { throw 'Rejected API probes changed the balance' }
Write-Host "API regression checks: $checks PASS; balance unchanged"
