param([string]$AdapterName = "")

if (-not $AdapterName) {
    ConvertTo-Json -Compress @{ error = "AdapterName is required" }
    exit 1
}

try {
    $props = Get-NetAdapterAdvancedProperty -Name $AdapterName -ErrorAction Stop |
        Select-Object DisplayName, DisplayValue |
        ForEach-Object { @{ Name = $_.DisplayName; Value = $_.DisplayValue } }

    ConvertTo-Json -Compress @{ adapter = $AdapterName; properties = $props }
} catch {
    ConvertTo-Json -Compress @{ error = $_.Exception.Message }
}
