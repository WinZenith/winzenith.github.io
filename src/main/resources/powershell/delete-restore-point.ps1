param([int]$SequenceNumber)
$ErrorActionPreference = 'Stop'
$rp = Get-CimInstance -Namespace 'root\default' -Class SystemRestore -Filter "SequenceNumber = $SequenceNumber"
if ($rp) {
    Remove-CimInstance -InputObject $rp
}
@{ deleted = $?; sequenceNumber = $SequenceNumber } | ConvertTo-Json -Compress
