param([string]$Preset = "Default")

$results = @()

function Add-Result($key, $value) {
    $script:results += [PSCustomObject]@{ Key = $key; Value = $value }
}

switch ($Preset) {
    "MaxPerformance" {
        Add-Result "TCP AutoTuning" "normal"
        netsh int tcp set global autotuninglevel=normal
        Add-Result "RSS" "enabled"
        netsh int tcp set global rss=enabled
        Add-Result "Chimney" "enabled"
        netsh int tcp set global chimney=enabled
        Add-Result "RSC" "enabled"
        netsh int tcp set global rsc=enabled
        Add-Result "ECN" "disabled"
        netsh int tcp set global ecncapability=disabled
        Add-Result "Source Address Prefix" "disabled"
        netsh int ip set global sourceaddressprefixstore=disabled
        break
    }
    "MaxStability" {
        Add-Result "TCP AutoTuning" "disabled"
        netsh int tcp set global autotuninglevel=disabled
        Add-Result "ECN" "enabled"
        netsh int tcp set global ecncapability=enabled
        Add-Result "RSS" "enabled"
        netsh int tcp set global rss=enabled
        Add-Result "Chimney" "disabled"
        netsh int tcp set global chimney=disabled
        Add-Result "Source Address Prefix" "disabled"
        netsh int ip set global sourceaddressprefixstore=disabled
        break
    }
    "Gaming" {
        Add-Result "TCP AutoTuning" "disabled"
        netsh int tcp set global autotuninglevel=disabled
        Add-Result "Chimney" "disabled"
        netsh int tcp set global chimney=disabled
        Add-Result "RSS" "enabled"
        netsh int tcp set global rss=enabled
        Add-Result "ECN" "disabled"
        netsh int tcp set global ecncapability=disabled
        Add-Result "Source Address Prefix" "disabled"
        netsh int ip set global sourceaddressprefixstore=disabled
        Add-Result "TCP Ack Frequency" "1"
        Add-Result "TCP No Delay" "1"
        break
    }
    default {
        Add-Result "TCP AutoTuning" "normal"
        netsh int tcp set global autotuninglevel=normal
        Add-Result "Chimney" "normal"
        netsh int tcp set global chimney=normal
        Add-Result "RSS" "normal"
        netsh int tcp set global rss=normal
        Add-Result "ECN" "normal"
        netsh int tcp set global ecncapability=normal
        Add-Result "RSC" "normal"
        netsh int tcp set global rsc=normal
        Add-Result "Source Address Prefix" "enabled"
        netsh int ip set global sourceaddressprefixstore=enabled
        break
    }
}

ConvertTo-Json -Compress $results
