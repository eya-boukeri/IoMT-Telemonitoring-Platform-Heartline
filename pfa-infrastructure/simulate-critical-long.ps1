param([string]$PatientId = "CRITICAL", [int]$DurationSeconds = 45, [int]$IntervalMs = 500)
$endTime = (Get-Date).AddSeconds($DurationSeconds)
Write-Host "📤 Simulation prolongée du patient $PatientId pour $DurationSeconds secondes..." -ForegroundColor Red
while ((Get-Date) -lt $endTime) {
    $criticalHR = Get-Random -Minimum 160 -Maximum 190  # Fréquence cardiaque très élevée
    $ppgData = @()
    for ($i = 0; $i -lt 30; $i++) {  # Plus de données PPG
        $ppgData += (Get-Random -Minimum 3.0 -Maximum 4.5)  # Valeurs PPG anormales élevées
    }    
    $message = @{patientId = $PatientId; deviceId = "watch-samsung-$PatientId"; ppgData = $ppgData; heartRate = $criticalHR; timestamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')} | ConvertTo-Json -Compress
    Write-Host "$(Get-Date -Format 'HH:mm:ss') 🚨 $criticalHR bpm - $(.Count) points" -ForegroundColor Red
    $message | Out-File -FilePath temp_critical_long.json -Encoding UTF8 -Force
    type temp_critical_long.json | docker exec -i mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/$PatientId/data" -l
    Start-Sleep -Milliseconds $IntervalMs
}
Remove-Item temp_critical_long.json -ErrorAction SilentlyContinue
