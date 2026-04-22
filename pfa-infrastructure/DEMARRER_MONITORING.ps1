# ============================================
# GUIDE DEMARRAGE - 7 TERMINAUX DE MONITORING
# ============================================

Write-Host ""
Write-Host "====== PLATEFORME PFA ======" -ForegroundColor Cyan
Write-Host ""

Write-Host "ETAPE 1: Ouvrir 7 terminaux PowerShell" -ForegroundColor Yellow
Write-Host "  - Ctrl+` pour ouvrir le premier"
Write-Host "  - Cliquer '+' pour ajouter les autres"
Write-Host ""

Write-Host "ETAPE 2: Copier-coller chaque commande dans le terminal correspondant:" -ForegroundColor Yellow
Write-Host ""

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "TERMINAL 1 - MQTT Messages" -ForegroundColor Green  
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host 'docker exec -it mosquitto-pfa-v2 mosquitto_sub -t "vitals/#" -v' -ForegroundColor Cyan
Write-Host ""

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "TERMINAL 2 - Ingestion Service" -ForegroundColor Green  
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "docker logs ingestion-service-pfa --tail 30 -f" -ForegroundColor Cyan
Write-Host ""

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "TERMINAL 3 - Kafka Raw Vitals" -ForegroundColor Green  
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "docker exec kafka-pfa-v2 rpk topic consume vitals-raw --brokers localhost:9092" -ForegroundColor Cyan
Write-Host ""

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "TERMINAL 4 - Kafka Alerts" -ForegroundColor Green  
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "docker exec kafka-pfa-v2 rpk topic consume medical-alerts --brokers localhost:9092" -ForegroundColor Cyan
Write-Host ""

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "TERMINAL 5 - Data Analytics" -ForegroundColor Green  
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "docker logs data-analytics-service-pfa --tail 20 -f" -ForegroundColor Cyan
Write-Host ""

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "TERMINAL 6 - Notification Service" -ForegroundColor Green  
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "docker logs notification-service-pfa --tail 20 -f" -ForegroundColor Cyan
Write-Host ""

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "TERMINAL 7 - InfluxDB Latest Data" -ForegroundColor Green  
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Green
Write-Host "Copier-coller le code complet ci-dessous:" -ForegroundColor Gray
Write-Host ""
$code = @"
`$q='from(bucket:"medical_data") |> range(start: -1m) |> filter(fn:(r)=>r._measurement=="vitals") |> last()'
`$b=@{query=`$q}|ConvertTo-Json
while (`$true) {
    try {
        `$response = Invoke-RestMethod -Method POST -Uri "http://localhost:8088/api/v2/query?org=myorg" -Headers @{Authorization="Token my-secret-token"} -Body `$b
        Write-Host "[`$(Get-Date -Format 'HH:mm:ss')] Latest Data:" -ForegroundColor Green
        `$response | ConvertTo-Json -Depth 3
    } catch {
        Write-Host "[`$(Get-Date -Format 'HH:mm:ss')] Waiting..." -ForegroundColor Gray
    }
    Start-Sleep 2
}
"@
Write-Host $code -ForegroundColor Cyan
Write-Host ""

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host "ETAPE 3: Attendre que tous les logs s'affichent (30 secondes)" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host ""

Write-Host "ETAPE 4: Lancer les tests" -ForegroundColor Yellow
Write-Host ""
Write-Host "  Option A (Automatique - RECOMMANDE):" -ForegroundColor Green
Write-Host "    .\test-scenarios.ps1" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Option B (Manuel - voir COMMANDES_RAPIDES.md):" -ForegroundColor Green
Write-Host "    Copier les payloads de COMMANDES_RAPIDES.md" -ForegroundColor Cyan
Write-Host ""

Write-Host ""
Write-Host "Fichiers d'aide disponibles:" -ForegroundColor Cyan
Write-Host "  - COMMANDES_RAPIDES.md (commandes pour chaque terminal)" -ForegroundColor Gray
Write-Host "  - GUIDE_TESTS_COMPLETS.md (guide détaillé avec dépannage)" -ForegroundColor Gray
Write-Host "  - test-scenarios.ps1 (tests automatiques)" -ForegroundColor Gray
Write-Host ""
