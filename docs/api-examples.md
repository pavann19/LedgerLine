# API usage

Run the local demo, then load its ignored summary. These examples use PowerShell objects to avoid shell-specific JSON quoting.

```powershell
$summary = Get-Content bench/results/local-demo-summary.json | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($summary.customerToken)" }
$base = 'http://localhost:8080/api/v1'
Invoke-RestMethod -Method Post -Uri "$base/accounts" -Headers $headers -ContentType 'application/json' -Body (@{currency='USD';type='CUSTOMER'} | ConvertTo-Json)
$key = 'example-' + [guid]::NewGuid()
$headers['Idempotency-Key'] = $key
$body = @{fromAccountId=$summary.checkingAccountId;toAccountId=$summary.savingsAccountId;amountMinor=2500;currency='USD'} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/transfers" -Headers $headers -ContentType 'application/json' -Body $body
# Replay exactly the same key/body after a timeout or lost response:
Invoke-RestMethod -Method Post -Uri "$base/transfers" -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "$base/accounts/$($summary.checkingAccountId)" -Headers $headers
Invoke-RestMethod -Uri "$base/accounts/$($summary.checkingAccountId)/postings?limit=10" -Headers $headers
$projectionHeaders = @{ Authorization = "Bearer $($summary.projectionToken)" }
Invoke-RestMethod -Uri "http://localhost:8081/api/v1/projections/accounts/$($summary.checkingAccountId)/statement?limit=10" -Headers $projectionHeaders
```

| Method/path | Permission/contract |
|---|---|
| POST /api/v1/accounts | CUSTOMER creation for customers; OPERATOR for other types; zero balance; not idempotent |
| GET /api/v1/accounts/{id} | Owner or operator |
| GET /api/v1/accounts/{id}/postings | Owner or operator; limit 1-100; optional cursor |
| POST /api/v1/transfers | Source owner or operator; mandatory key; positive integer amount; matching currency |
| GET /api/v1/projections/accounts/{id}/statement | Signed account claim or operator; limit/cursor |
| GET /api/v1/projections/accounts/{id}/summary | Signed account claim or operator; optional ISO date; defaults UTC |
| GET /actuator/health and /v3/api-docs | Public |

Transfer response status is 201 initially, 200 on replay, 422 for a key/body conflict or insufficient funds, 400 for invalid inputs or broken variant 0, 403 for forbidden access, and 404 for missing ledger accounts. Database constraint rejection is 409; unexpected errors are 500 with generic details. Application/authorization errors use problem+json. No automatic retry with a new key is safe after an ambiguous response.

Safe isolation comparison headers remain available; omit X-Isolation-Variant to use the default. Variant 0 is prohibited for all HTTP callers. Currency labels do not perform conversion. Account closure, transfer editing, refund, and withdrawal endpoints do not exist. Examples require fresh runtime verification for this tree.
