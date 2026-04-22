# Script Setup 7 Terminaux de Monitoring
# Exécution: ./setup-monitoring.ps1

Write-Host "================================" -ForegroundColor Cyan
Write-Host "SETUP - 7 Terminaux de Monitoring" -ForegroundColor Cyan
Write-Host "================================" -ForegroundColor Cyan
Write-Host ""

# Vérifier que Docker est actif
Write-Host "✓ Vérification Docker..." -ForegroundColor Yellow
docker ps > $null 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Docker n'est pas actif!" -ForegroundColor Red
    exit 1
}
Write-Host "✓ Docker est opérationnel" -ForegroundColor Green
Write-Host ""

# Liste des commandes pour les 7 terminaux
$commands = @(
    @{
        id = "T1"
        name = "MQTT Subscriber (vitals/#)"
        cmd = 'docker exec -it mosquitto-pfa-v2 mosquitto_sub -t "vitals/#" -v'
    },
    @{
        id = "T2"
        name = "Ingestion Service Logs"
        cmd = 'docker logs ingestion-service-pfa --tail 30 -f'
    },
    @{
        id = "T3"
        name = "Kafka Raw Vitals"
        cmd = 'docker exec kafka-pfa-v2 rpk topic consume vitals-raw --brokers localhost:9092'
    },
    @{
        id = "T4"
        name = "Kafka Medical Alerts"
        cmd = 'docker exec kafka-pfa-v2 rpk topic consume medical-alerts --brokers localhost:9092'
    },
    @{
        id = "T5"
        name = "Data Analytics Service"
        cmd = 'docker logs data-analytics-service-pfa --tail 20 -f'
    },
    @{
        id = "T6"
        name = "Notification Service"
        cmd = 'docker logs notification-service-pfa --tail 20 -f'
    },
    @{
        id = "T7"
        name = "InfluxDB Latest Data"
        cmd = @'
while ($true) {
    $q='from(bucket:"medical_data") |> range(start: -1m) |> filter(fn:(r)=>r._measurement=="vitals") |> last()'
    $b=@{query=$q}|ConvertTo-Json
    try {
        $response = Invoke-RestMethod -Method POST -Uri "http://localhost:8088/api/v2/query?org=myorg" `
            -Headers @{Authorization="Token my-secret-token"} `
            -Body $b
        $response | ConvertTo-Json -Depth 3
    } catch {
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] ℹ️  Attente InfluxDB..." -ForegroundColor Gray
    }
    Start-Sleep 2
}
'@
    }
)

Write-Host "Commandes de monitoring prêtes:" -ForegroundColor Green
Write-Host ""
foreach ($cmd in $commands) {
    Write-Host "$($cmd.id) - $($cmd.name)" -ForegroundColor Cyan
}

Write-Host ""
Write-Host "Pour ouvrir les 7 terminaux:" -ForegroundColor Yellow
Write-Host "1. Ouvrez 7 PowerShell (View > Terminal > ou Ctrl+`) " -ForegroundColor White
Write-Host "2. Arrangez-les en Split (Terminal > Split Terminal)" -ForegroundColor White
Write-Host "3. Collez les commandes ci-dessous dans chaque terminal" -ForegroundColor White
Write-Host ""

foreach ($cmd in $commands) {
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
    Write-Host "$($cmd.id) - $($cmd.name)" -ForegroundColor Green
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
    Write-Host ""
    Write-Host $cmd.cmd -ForegroundColor Cyan
    Write-Host ""
}

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
Write-Host "Instructions:" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
Write-Host "1️⃣  Copiez/collez chaque commande dans le terminal correspondant" -ForegroundColor White
Write-Host "2️⃣  Attendez que les logs s'affichent (30 sec environ)" -ForegroundColor White
Write-Host "3️⃣  Lancez le 8ème terminal pour les tests de data" -ForegroundColor White
Write-Host "4️⃣  Exécutez: ./test-scenarios.ps1" -ForegroundColor White
Write-Host ""
