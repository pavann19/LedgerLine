param(
    [Parameter(Mandatory=$true)][string]$PublicIp,
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$target = "azureuser@$PublicIp"
$options = @('-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ServerAliveInterval=15')

# Only use the disposable Azure host already provisioned by the evidence runner.
# The host must already contain /tmp/invariants.sql from its baseline verification.
& scp.exe @options (Join-Path $repo 'bench/k6-cloud-load.js') "${target}:/tmp/k6-cloud-load.js"
if ($LASTEXITCODE -ne 0) { throw 'Workload upload failed' }
& scp.exe @options (Join-Path $PSScriptRoot 'verify-cloud-recovery.py') "${target}:/tmp/verify-cloud-recovery.py"
if ($LASTEXITCODE -ne 0) { throw 'Recovery probe upload failed' }
& ssh.exe @options $target 'sudo python3 /tmp/verify-cloud-recovery.py' *> (Join-Path $EvidenceDirectory '04-recovery.log')
$probeExit = $LASTEXITCODE
& ssh.exe @options $target 'sudo chmod -R a+rX /var/tmp/ledgerline-acceptance; docker stats --no-stream; docker ps --format "{{.Names}} {{.Status}}"' *> (Join-Path $EvidenceDirectory '04-host-state.log')
& scp.exe @options -r "${target}:/var/tmp/ledgerline-acceptance" $EvidenceDirectory *> (Join-Path $EvidenceDirectory '04-receipt-download.log')
if ($LASTEXITCODE -ne 0) { throw 'Recovery receipts download failed' }
if ($probeExit -ne 0) { throw "Recovery probe failed; retained exit $probeExit" }
$report = Get-Content (Join-Path $EvidenceDirectory 'ledgerline-acceptance/report.json') -Raw | ConvertFrom-Json
if ($report.result -ne 'PASS') { throw "Additional acceptance result: $($report.result)" }
$global:LASTEXITCODE = 0
