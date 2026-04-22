# Script de Test - Scénarios 1 et 2
# Prérequis: 7 terminaux de monitoring en cours d'exécution
# Usage: ./test-scenarios.ps1

Write-Host ""
Write-Host "╔════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║     TESTS DE SCÉNARIOS - Plateforme de Télésurveillance   ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# Vérifications préalables
Write-Host "📋 Vérifications préalables..." -ForegroundColor Yellow
Write-Host ""

$checks = @(
    @{name="Docker actif"; cmd="docker ps > `$null 2>&1"},
    @{name="MQTT actif"; cmd="docker exec mosquitto-pfa-v2 mosquitto_pub -t 'test' -m 'test' 2>&1 | grep -q 'Client received PUBACK' || echo 'OK'"},
    @{name="Kafka actif"; cmd="docker exec kafka-pfa-v2 rpk broker list > `$null 2>&1"}
)

foreach ($check in $checks) {
    Write-Host "Vérification: $($check.name)..." -ForegroundColor Gray
    Invoke-Expression $check.cmd > $null 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✓ $($check.name)" -ForegroundColor Green
    } else {
        Write-Host "⚠️  $($check.name) - Continuons..." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "══════════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "🟢 SCÉNARIO 1: PATIENT NORMAL (patient-normal)" -ForegroundColor Green
Write-Host "══════════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

Write-Host "Description:" -ForegroundColor Yellow
Write-Host "  • Patient avec des signes vitaux normaux" -ForegroundColor Gray
Write-Host "  • FC: 70-75 bpm, SpO2: 98-99%, Temp: 36.5-36.7°C" -ForegroundColor Gray
Write-Host "  • Résultat attendu: Aucune alerte" -ForegroundColor Gray
Write-Host ""

Write-Host "Attendez avant d'appuyer sur ENTRÉE (utilisateurs devraient voir les logs):" -ForegroundColor Yellow
Read-Host "Appuyez sur ENTRÉE pour commencer"
Write-Host ""

# Mesure 1
Write-Host "[1/3] Mesure 1 - FC:72, SpO2:99, Temp:36.6°C" -ForegroundColor Cyan
$payload1 = '{"patientId":"patient-normal","deviceId":"simulator","heartRate":72,"spo2":99,"temperature":36.6,"timestamp":'+(Get-Date -UFormat %s000)+'}'
Write-Host "Envoi: $payload1" -ForegroundColor Gray
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-normal/data" -m $payload1
Write-Host "✓ Publié" -ForegroundColor Green
Start-Sleep 3

# Mesure 2
Write-Host "[2/3] Mesure 2 - FC:75, SpO2:98, Temp:36.5°C" -ForegroundColor Cyan
$payload2 = '{"patientId":"patient-normal","deviceId":"simulator","heartRate":75,"spo2":98,"temperature":36.5,"timestamp":'+(Get-Date -UFormat %s000)+'}'
Write-Host "Envoi: $payload2" -ForegroundColor Gray
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-normal/data" -m $payload2
Write-Host "✓ Publié" -ForegroundColor Green
Start-Sleep 3

# Mesure 3
Write-Host "[3/3] Mesure 3 - FC:70, SpO2:99, Temp:36.7°C" -ForegroundColor Cyan
$payload3 = '{"patientId":"patient-normal","deviceId":"simulator","heartRate":70,"spo2":99,"temperature":36.7,"timestamp":'+(Get-Date -UFormat %s000)+'}'
Write-Host "Envoi: $payload3" -ForegroundColor Gray
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-normal/data" -m $payload3
Write-Host "✓ Publié" -ForegroundColor Green
Write-Host ""

Write-Host "📊 Observations attendues pour patient-normal:" -ForegroundColor Yellow
Write-Host "  T1 ✓ Voir 3 messages JSON dans MQTT" -ForegroundColor Gray
Write-Host "  T2 ✓ 'Processing vitals for patient patient-normal...' (3x)" -ForegroundColor Gray
Write-Host "  T3 ✓ Messages dans vitals-raw" -ForegroundColor Gray
Write-Host "  T4 ✗ Pas d'alerte (medical-alerts vide)" -ForegroundColor Gray
Write-Host "  T5 ✗ Pas de logs d'anomalie" -ForegroundColor Gray
Write-Host "  T6 ✗ Pas d'email" -ForegroundColor Gray
Write-Host ""

Write-Host "Attendez 10 secondes pour que les données se propagent..." -ForegroundColor Yellow
Start-Sleep 10

Write-Host ""
Write-Host "══════════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "🔴 SCÉNARIO 2: PATIENT CRITIQUE (patient-urgent)" -ForegroundColor Red
Write-Host "══════════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

Write-Host "Description:" -ForegroundColor Yellow
Write-Host "  • Patient avec des signes vitaux critiques" -ForegroundColor Gray
Write-Host "  • FC: 80→55→155→152 (tachycardie), SpO2: 97→94→82→80 (hypoxie)" -ForegroundColor Gray
Write-Host "  • Temp: 36.8→37.0→39.2→39.5 (fièvre)" -ForegroundColor Gray
Write-Host "  • Résultat attendu: Alerte CRITICAL + Email" -ForegroundColor Gray
Write-Host ""

Write-Host "IMPORTANT: Regardez surtout T4, T5, T6 !" -ForegroundColor Red
Write-Host ""

Write-Host "Attendez avant d'appuyer sur ENTRÉE:" -ForegroundColor Yellow
Read-Host "Appuyez sur ENTRÉE pour commencer"
Write-Host ""

# Mesure 1 - Normal
Write-Host "[1/4] Mesure 1 - NORMAL - FC:80, SpO2:97, Temp:36.8°C" -ForegroundColor Cyan
$p1 = '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":80,"spo2":97,"temperature":36.8,"timestamp":'+(Get-Date -UFormat %s000)+'}'
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m $p1
Write-Host "✓ Publié" -ForegroundColor Green
Start-Sleep 3

# Mesure 2 - Anomalie commence
Write-Host "[2/4] Mesure 2 - ANOMALIE - FC:55, SpO2:94, Temp:37.0°C" -ForegroundColor Yellow
$p2 = '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":55,"spo2":94,"temperature":37.0,"timestamp":'+(Get-Date -UFormat %s000)+'}'
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m $p2
Write-Host "✓ Publié" -ForegroundColor Green
Start-Sleep 3

# Mesure 3 - CRITIQUE
Write-Host "[3/4] Mesure 3 - ⚠️ CRITIQUE ⚠️  - FC:155, SpO2:82, Temp:39.2°C" -ForegroundColor Red
$p3 = '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":155,"spo2":82,"temperature":39.2,"timestamp":'+(Get-Date -UFormat %s000)+'}'
Write-Host "⚠️  Ceci devrait déclencher une alerte CRITICAL" -ForegroundColor Red
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m $p3
Write-Host "✓ Publié" -ForegroundColor Green
Start-Sleep 3

# Mesure 4 - Persistance
Write-Host "[4/4] Mesure 4 - PERSISTANCE - FC:152, SpO2:80, Temp:39.5°C" -ForegroundColor Red
$p4 = '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":152,"spo2":80,"temperature":39.5,"timestamp":'+(Get-Date -UFormat %s000)+'}'
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m $p4
Write-Host "✓ Publié" -ForegroundColor Green
Write-Host ""

Write-Host "📊 Observations attendues pour patient-urgent:" -ForegroundColor Yellow
Write-Host "  T1 ✓ 4 messages JSON" -ForegroundColor Gray
Write-Host "  T2 ✓ Processing logs" -ForegroundColor Gray
Write-Host "  T3 ✓ Messages dans vitals-raw" -ForegroundColor Gray
Write-Host "  T4 🔴 [IMPORTANT] Alerte JSON 'CRITICAL' après T3 (10-15s) → {'severity':'CRITICAL','...}" -ForegroundColor Red
Write-Host "  T5 🔴 'Anomaly detected' et 'Publishing alert to medical-alerts'" -ForegroundColor Red
Write-Host "  T6 🔴 'Alert received' et 'Email sent to farah.attia21@gmail.com'" -ForegroundColor Red
Write-Host "  T7 ✓ Données InfluxDB" -ForegroundColor Gray
Write-Host ""

Write-Host "⏱️  Timing attendu:" -ForegroundColor Yellow
Write-Host "  0-2s   : Données publiées à MQTT" -ForegroundColor Gray
Write-Host "  2-5s   : Traitement Ingestion (T2)" -ForegroundColor Gray
Write-Host "  5-8s   : Apparition dans Kafka vitals-raw (T3)" -ForegroundColor Gray
Write-Host "  10-15s : Alerte CRITICAL dans T4 et T5/T6" -ForegroundColor Gray
Write-Host ""

Start-Sleep 15

Write-Host ""
Write-Host "══════════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "🔍 VÉRIFICATION FINALE" -ForegroundColor Yellow
Write-Host "══════════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

Write-Host "✅ Checklist à valider:" -ForegroundColor Green
Write-Host "  ☐ T1: Tous les messages MQTT affichés?" -ForegroundColor White
Write-Host "  ☐ T2: Pas d'erreur JSON parsing?" -ForegroundColor White
Write-Host "  ☐ T3: Vitals dans Kafka?" -ForegroundColor White
Write-Host "  ☐ T4: Alerte CRITICAL pour patient-urgent?" -ForegroundColor White
Write-Host "  ☐ T5: Anomaly detected et alert published?" -ForegroundColor White
Write-Host "  ☐ T6: Email sent confirmé?" -ForegroundColor White
Write-Host "  ☐ T7: Données InfluxDB actualisées?" -ForegroundColor White
Write-Host ""

Write-Host "📧 Vérification Email:" -ForegroundColor Yellow
Write-Host "  Ouvrir: https://mail.google.com" -ForegroundColor Gray
Write-Host "  Compte: farah.attia21@gmail.com" -ForegroundColor Gray
Write-Host "  Sujet attendu: [CRITICAL] Alerte: ANOMALY" -ForegroundColor Gray
Write-Host ""

Write-Host "📊 Dashboard:" -ForegroundColor Yellow
Write-Host "  URL: http://localhost:5173" -ForegroundColor Gray
Write-Host "  Login: dr-farah / farah1234" -ForegroundColor Gray
Write-Host "  Patient: patient-urgent" -ForegroundColor Gray
Write-Host "  Voir: Pic FC (155), Creux SpO2 (82), Alerte CRITICAL" -ForegroundColor Gray
Write-Host ""

Write-Host "════════════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "Vous pouvez relancer ce script avec: ./test-scenarios.ps1" -ForegroundColor Gray
Write-Host "════════════════════════════════════════════════════════════════════" -ForegroundColor Cyan
