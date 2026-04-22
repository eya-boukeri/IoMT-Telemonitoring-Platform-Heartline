# Test Simple - Scénarios PFA
# Usage: .\test-simple.ps1

Write-Host ""
Write-Host "╔════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║     TESTS SIMPLES - Plateforme de Télésurveillance       ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# Fonction pour publier un message MQTT
function Publish-MQTT {
    param($payload)
    Write-Host "Envoi: $payload" -ForegroundColor Gray
    docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-test/data" -m $payload
    Write-Host "✓ Publié" -ForegroundColor Green
}

Write-Host "🟢 SCÉNARIO 1: Patient Normal" -ForegroundColor Green
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
Write-Host ""

# Mesure 1
$payload1 = '{"patientId":"patient-normal","deviceId":"simulator","heartRate":72,"spo2":99,"temperature":36.6,"timestamp":1713600000000}'
Publish-MQTT $payload1
Start-Sleep 3

# Mesure 2
$payload2 = '{"patientId":"patient-normal","deviceId":"simulator","heartRate":75,"spo2":98,"temperature":36.5,"timestamp":1713600000000}'
Publish-MQTT $payload2
Start-Sleep 3

# Mesure 3
$payload3 = '{"patientId":"patient-normal","deviceId":"simulator","heartRate":70,"spo2":99,"temperature":36.7,"timestamp":1713600000000}'
Publish-MQTT $payload3
Start-Sleep 3

Write-Host ""
Write-Host "🔴 SCÉNARIO 2: Patient Critique" -ForegroundColor Red
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
Write-Host ""

# Mesure 1 - Normal
$payload4 = '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":80,"spo2":97,"temperature":36.8,"timestamp":1713600000000}'
Publish-MQTT $payload4
Start-Sleep 3

# Mesure 2 - Anomalie
$payload5 = '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":55,"spo2":94,"temperature":37.0,"timestamp":1713600000000}'
Publish-MQTT $payload5
Start-Sleep 3

# Mesure 3 - CRITIQUE
$payload6 = '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":155,"spo2":82,"temperature":39.2,"timestamp":1713600000000}'
Write-Host "⚠️  CRITIQUE - FC:155, SpO2:82, Temp:39.2" -ForegroundColor Red
Publish-MQTT $payload6
Start-Sleep 3

# Mesure 4 - Persistance
$payload7 = '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":152,"spo2":80,"temperature":39.5,"timestamp":1713600000000}'
Publish-MQTT $payload7
Start-Sleep 3

Write-Host ""
Write-Host "✅ Tests terminés!" -ForegroundColor Green
Write-Host ""
Write-Host "📊 Résultats attendus:" -ForegroundColor Yellow
Write-Host "  • T1: 7 messages MQTT reçus" -ForegroundColor Gray
Write-Host "  • T2: Logs de traitement" -ForegroundColor Gray
Write-Host "  • T3: Messages dans vitals-raw" -ForegroundColor Gray
Write-Host "  • T4: Alerte CRITICAL pour patient-urgent (10-15s après mesure 3)" -ForegroundColor Red
Write-Host "  • T5: 'Anomaly detected' et 'Publishing alert'" -ForegroundColor Red
Write-Host "  • T6: 'Email sent to farah.attia21@gmail.com'" -ForegroundColor Red
Write-Host "  • T7: Données InfluxDB" -ForegroundColor Gray
Write-Host ""
Write-Host "📧 Vérifier email: farah.attia21@gmail.com" -ForegroundColor Cyan
Write-Host "🎨 Dashboard: http://localhost:5173 (dr-farah/farah1234)" -ForegroundColor Cyan
Write-Host ""
