param()
$ErrorActionPreference = 'Stop'
Get-CimInstance -Namespace 'root\default' -Class SystemRestore |
    Select-Object Description, @{N='CreationTime';E={$_.CreationTime.ToString('yyyy-MM-dd HH:mm:ss')}}, EventType, SequenceNumber |
    ConvertTo-Csv -NoTypeInformation
