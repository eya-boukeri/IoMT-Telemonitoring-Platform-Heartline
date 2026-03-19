param(
    [string]$PatientId = "P001",
    [int]$DurationSeconds = 60,
    [int]$IntervalMs = 1000
)

Write-Host "📤 Simulation du patient $PatientId pour $DurationSeconds secondes..." -ForegroundColor Green

$endTime = (Get-Date).AddSeconds($DurationSeconds)

while ((Get-Date) -lt $endTime) {
    # Générer une fréquence cardiaque réaliste
    $baseHR = switch ($PatientId) {
        "P001" { 72 }  # Patient normal
        "P002" { 82 }  # Légerement élevé
        "P003" { 62 }  # Sportif
        default { 70 }
    }
    
    $hrVariation = Get-Random -Minimum -3 -Maximum 4
    $currentHR = $baseHR + $hrVariation
    
    # Générer des données PPG
    $ppgData = @()
    for ($i = 0; $i -lt 15; $i++) {
        $ppgData += $baseHR + (Get-Random -Minimum -5 -Maximum 6)
    }
    
    # Créer le message JSON
    $message = @{
        patientId = $PatientId
        deviceId = "watch-samsung-$PatientId"
        ppgData = $ppgData
        heartRate = $currentHR
        timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    } | ConvertTo-Json -Compress
    
    # Afficher et envoyer
    Write-Host "$(Get-Date -Format 'HH:mm:ss') ❤️ $currentHR bpm" -ForegroundColor Yellow
    
    $message | Out-File -FilePath temp_msg.json -Encoding UTF8 -Force
    type temp_msg.json | docker exec -i mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "health/sensorData" -l
    
    Start-Sleep -Milliseconds $IntervalMs
}

Remove-Item temp_msg.json -ErrorAction SilentlyContinue
Write-Host "✅ Simulation terminée" -ForegroundColor Green
