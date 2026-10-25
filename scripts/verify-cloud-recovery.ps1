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
# Native stderr is non-terminating here so failed probes still download their
# reports before the outer evidence runner destroys the disposable resources.
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $launcher = @'
#!/bin/sh
python3 /tmp/verify-cloud-recovery.py > /tmp/verify-cloud-recovery.log 2>&1
printf '%s\n' "$?" > /tmp/verify-cloud-recovery.exit
'@
    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($launcher.Replace("`r", '')))
    $launchCommand = "printf '%s' '$payload' | base64 -d > /tmp/run-cloud-recovery.sh; sudo nohup sh /tmp/run-cloud-recovery.sh < /dev/null > /tmp/cloud-recovery-launcher.log 2>&1 &"
    & ssh.exe @options $target $launchCommand
    if ($LASTEXITCODE -ne 0) { throw 'Detached recovery probe launch failed' }
    $deadline = [DateTime]::UtcNow.AddMinutes(30)
    $probeExit = 124
    do {
        $state = & ssh.exe @options -o ConnectTimeout=10 $target 'if test -f /tmp/verify-cloud-recovery.exit; then cat /tmp/verify-cloud-recovery.exit; else printf RUNNING; fi' 2>> (Join-Path $EvidenceDirectory '04-poll-errors.log')
        $pollExit = $LASTEXITCODE
        "$([DateTime]::UtcNow.ToString('o')) status=$state transportExit=$pollExit" | Add-Content (Join-Path $EvidenceDirectory '04-progress.log')
        if ($pollExit -eq 0 -and "$state" -match '^\d+$') { $probeExit = [int]$state; break }
        if ([DateTime]::UtcNow -ge $deadline) { break }
        Start-Sleep -Seconds 10
    } while ($true)
    & ssh.exe @options $target 'sudo chmod -R a+rX /var/tmp/ledgerline-acceptance; sudo docker stats --no-stream; sudo docker ps --format "{{.Names}} {{.Status}}"' *> (Join-Path $EvidenceDirectory '04-host-state.log')
    & scp.exe @options "${target}:/tmp/verify-cloud-recovery.log" (Join-Path $EvidenceDirectory '04-recovery.log') *> (Join-Path $EvidenceDirectory '04-log-download.log')
    & scp.exe @options -r "${target}:/var/tmp/ledgerline-acceptance" $EvidenceDirectory *> (Join-Path $EvidenceDirectory '04-receipt-download.log')
    if ($LASTEXITCODE -ne 0) { throw 'Recovery receipts download failed' }
    if ($probeExit -ne 0) { throw "Recovery probe failed; retained exit $probeExit" }
    $report = Get-Content (Join-Path $EvidenceDirectory 'ledgerline-acceptance/report.json') -Raw | ConvertFrom-Json
    if ($report.result -ne 'PASS') { throw "Additional acceptance result: $($report.result)" }
    $global:LASTEXITCODE = 0
} finally { $ErrorActionPreference = $previousPreference }
