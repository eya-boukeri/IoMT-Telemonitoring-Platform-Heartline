param(
    [string]$PatientId = "P001",
    [int]$DurationSeconds = 60,
    [int]$IntervalMs = 1000
)

$endTime = (Get-Date).AddSeconds($DurationSeconds)

Write-Host "📤 Simulation du patient $PatientId pour $DurationSeconds secondes..." -ForegroundColor Green

while ((Get-Date) -lt $endTime) {
    $baseHR = switch ($PatientId) {
        "P001" { 72 }
        "P002" { 82 }
        "P003" { 62 }
        "CRITICAL" { 160 }
        default { 70 }
    }
    
    $hrVariation = if ($PatientId -eq "CRITICAL") { Get-Random -Minimum -15 -Maximum 16 } else { Get-Random -Minimum -3 -Maximum 4 }
    $currentHR = $baseHR + $hrVariation
    
    $ppgData = @()
    for ($i = 0; $i -lt 15; $i++) {
        $ppgData += $baseHR + (Get-Random -Minimum -5 -Maximum 6)
    }
    
    $message = @{
        patientId = $PatientId
        deviceId = "watch-samsung-$PatientId"
        ppgData = $ppgData
        heartRate = $currentHR
        timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    } | ConvertTo-Json -Compress
    
    Write-Host "$(Get-Date -Format 'HH:mm:ss') ❤️ $currentHR bpm" -ForegroundColor Yellow
    
    $message | Out-File -FilePath temp_msg.json -Encoding UTF8 -Force
    type temp_msg.json | docker exec -i mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/$PatientId/data" -l
    
    Start-Sleep -Milliseconds $IntervalMs
}

Remove-Item temp_msg.json -ErrorAction SilentlyContinue
