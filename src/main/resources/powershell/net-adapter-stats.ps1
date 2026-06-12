param([string]$AdapterName)

try {
    $stats = Get-NetAdapterStatistics -Name $AdapterName -ErrorAction Stop
    $output = @{
        adapterName = $stats.Name
        bytesSent = [long]$stats.BytesSent
        bytesReceived = [long]$stats.BytesReceived
        unicastPacketsSent = [long]$stats.UnicastPacketsSent
        unicastPacketsReceived = [long]$stats.UnicastPacketsReceived
        multicastPacketsSent = [long]$stats.MulticastPacketsSent
        multicastPacketsReceived = [long]$stats.MulticastPacketsReceived
        broadcastPacketsSent = [long]$stats.BroadcastPacketsSent
        broadcastPacketsReceived = [long]$stats.BroadcastPacketsReceived
        discardedPackets = [long]$stats.DiscardedPackets
        errorPackets = [long]$stats.ErrorPackets
    }
    ConvertTo-Json -Compress $output
} catch {
    $output = @{
        adapterName = $AdapterName
        bytesSent = 0
        bytesReceived = 0
        unicastPacketsSent = 0
        unicastPacketsReceived = 0
        multicastPacketsSent = 0
        multicastPacketsReceived = 0
        broadcastPacketsSent = 0
        broadcastPacketsReceived = 0
        discardedPackets = 0
        errorPackets = 0
        error = $_.Exception.Message
    }
    ConvertTo-Json -Compress $output
}
