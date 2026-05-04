param(
    [int]$NumPatients = 3,
    [int]$DurationSeconds = 30,
    [int]$IntervalMs = 500
)

Write-Host "=== Test de montee en charge: $NumPatients patients simultanes ===" -ForegroundColor Green
Write-Host "Duree: $DurationSeconds secondes" -ForegroundColor Cyan
Write-Host ""

$startTime = Get-Date
$jobs = @()

# Lance les simulations en parallele
for ($i = 1; $i -le $NumPatients; $i++) {
    $patientId = "P" + $i.ToString("000")
    Write-Host "[INFO] Lancement patient $patientId..." -ForegroundColor Yellow

    $job = Start-Job -ScriptBlock {
        param($patientID, $duration, $interval)

        Set-Location "C:\Users\Admin\Desktop\platformeIOT\pfa-infrastructure"

        $endTime = (Get-Date).AddSeconds($duration)
        $messageCount = 0

        while ((Get-Date) -lt $endTime) {
            $baseHR = 70 + (Get-Random -Minimum -10 -Maximum 11)
            $hrVariation = Get-Random -Minimum -3 -Maximum 4
            $currentHR = $baseHR + $hrVariation

            $ppgData = @()
            for ($j = 0; $j -lt 15; $j++) {
                $ppgData += $baseHR + (Get-Random -Minimum -5 -Maximum 6)
            }

            $message = @{
                patientId = $patientID
                deviceId = "watch-samsung-$patientID"
                ppgData = $ppgData
                heartRate = $currentHR
                timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
            } | ConvertTo-Json -Compress

            try {
                $message | Out-File -FilePath "temp_msg_$patientID.json" -Encoding UTF8 -Force
                Get-Content "temp_msg_$patientID.json" | docker exec -i mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/$patientID/data" -l | Out-Null
                $messageCount++
            } catch {
                Write-Host "Erreur pour patient $patientID : $($_.Exception.Message)" -ForegroundColor Red
            }

            Start-Sleep -Milliseconds $interval
        }

        Remove-Item "temp_msg_$patientID.json" -ErrorAction SilentlyContinue -Force

        return @{
            PatientId = $patientID
            MessagesSent = $messageCount
            Duration = $duration
            Success = $true
        }

    } -ArgumentList $patientId, $DurationSeconds, $IntervalMs

    $jobs += $job
}

Write-Host ""
Write-Host "[INFO] Attente de la fin des simulations..." -ForegroundColor Cyan

# Attend que tous les jobs se terminent
$results = @()
foreach ($job in $jobs) {
    $result = Receive-Job -Job $job -Wait
    $results += $result
    Remove-Job -Job $job
}

$endTime = Get-Date
$totalTime = ($endTime - $startTime).TotalSeconds

# Analyse des resultats
$successfulJobs = ($results | Where-Object { $_.Success }).Count
$successRate = ($successfulJobs / $NumPatients) * 100
$totalMessages = 0
foreach ($result in $results) {
    if ($result.MessagesSent) {
        $totalMessages += $result.MessagesSent
    }
}

Write-Host ""
Write-Host "========== RESULTATS DU TEST ==========" -ForegroundColor Green
Write-Host "Patients simules: $NumPatients" -ForegroundColor White
Write-Host "Duree totale: $($totalTime.ToString("F2"))s" -ForegroundColor White
Write-Host "Messages envoyes: $totalMessages" -ForegroundColor White
Write-Host "Taux de succes: $($successRate.ToString("F1"))%" -ForegroundColor $(if ($successRate -ge 95) { "Green" } elseif ($successRate -ge 80) { "Yellow" } else { "Red" })
Write-Host "Messages/seconde: $(($totalMessages / $totalTime).ToString("F1"))" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Green

# Verifie les anomalies detectees
Write-Host ""
Write-Host "[INFO] Verification des anomalies detectees..." -ForegroundColor Cyan
try {
    $anomalyCount = docker exec postgres-pfa psql -U postgres -d ingestion_db -t -c "SELECT COUNT(*) FROM anomaly_snapshots WHERE created_at > NOW() - INTERVAL '2 minutes';" 2>$null
    if ($LASTEXITCODE -eq 0) {
        $anomalyCount = $anomalyCount.Trim()
        Write-Host "Anomalies detectees: $anomalyCount" -ForegroundColor $(if ([int]$anomalyCount -gt 0) { "Green" } else { "Yellow" })
    }
} catch {
    Write-Host "Warning: Impossible de verifier les anomalies" -ForegroundColor Yellow
}

# Metriques systeme
Write-Host ""
Write-Host "[INFO] Metriques systeme:" -ForegroundColor Cyan
try {
    $stats = docker stats --no-stream --format "table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}" 2>$null | Select-String "ingestion-service|kafka|postgres"
    if ($stats) {
        Write-Host $stats -ForegroundColor White
    }
} catch {
    Write-Host "Warning: Impossible de recuperer les metriques Docker" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "[DONE] Test termine!" -ForegroundColor Green
