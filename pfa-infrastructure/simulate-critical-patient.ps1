param([string]$PatientId = "CRITICAL", [int]$DurationSeconds = 10, [int]$IntervalMs = 1000)
$endTime = (Get-Date).AddSeconds($DurationSeconds)
Write-Host "📤 Simulation du patient $PatientId avec signes vitaux CRITIQUES..." -ForegroundColor Red
while ((Get-Date) -lt $endTime) {
    $criticalHR = Get-Random -Minimum 150 -Maximum 180  # Fréquence cardiaque très élevée
    $ppgData = @()
    for ($i = 0; $i -lt 15; $i++) {
        $ppgData += (Get-Random -Minimum 2.0 -Maximum 3.5)  # Valeurs PPG anormales élevées
    }    
    $message = @{patientId = $PatientId; deviceId = "watch-samsung-$PatientId"; ppgData = $ppgData; heartRate = $criticalHR; timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')} | ConvertTo-Json -Compress
    Write-Host "$(Get-Date -Format 'HH:mm:ss') 🚨 $criticalHR bpm" -ForegroundColor Red
    $message | Out-File -FilePath temp_critical.json -Encoding UTF8 -Force
    type temp_critical.json | docker exec -i mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/$PatientId/data" -l
    Start-Sleep -Milliseconds $IntervalMs
}
Remove-Item temp_critical.json -ErrorAction SilentlyContinue
