#!/usr/bin/env pwsh
# 🧪 Script de Test MQTT - Architecture Correcte
# Ce script automatise tous les tests de diagnostique MQTT

param(
    [switch]$TestAndroid,
    [switch]$CleanUp,
    [switch]$Full
)

Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "🧪 MQTT Architecture - Test & Diagnostic Script" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor Cyan

# Colors for output
$Success = @{ ForegroundColor = 'Green' }
$Error_ = @{ ForegroundColor = 'Red' }
$Info = @{ ForegroundColor = 'Yellow' }
$Title = @{ ForegroundColor = 'Cyan' }

# ═══════════════════════════════════════════════════════════════════════════════
# TEST 1: Vérifier que Docker Compose est en place
# ═══════════════════════════════════════════════════════════════════════════════
Write-Host "`n[TEST 1]" @Title -NoNewline
Write-Host " Vérification de Docker et Mosquitto" @Title

$dockerStatus = docker ps --filter "name=mosquitto-pfa" --format "{{.Status}}" 2>$null
if ($dockerStatus) {
    Write-Host "✅ Mosquitto est en cours d'exécution" @Success
    Write-Host "   Status: $dockerStatus" @Success
} else {
    Write-Host "❌ Mosquitto n'est pas en cours d'exécution" @Error_
    Write-Host ""
    Write-Host "Démarrage de Docker Compose..." @Info
    
    $infraPath = "C:\Users\Admin\Desktop\platformeIOT\pfa-infrastructure"
    if (Test-Path $infraPath) {
        Push-Location $infraPath
        docker-compose up -d
        Start-Sleep -Seconds 5
        Pop-Location
    } else {
        Write-Host "❌ Impossible de trouver le répertoire pfa-infrastructure" @Error_
        exit 1
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# TEST 2: Vérifier que Mosquitto écoute sur les bons ports
# ═══════════════════════════════════════════════════════════════════════════════
Write-Host "`n[TEST 2]" @Title -NoNewline
Write-Host " Vérification des ports Mosquitto" @Title

$mosquittoLogs = docker logs mosquitto-pfa 2>&1 | Select-String "listening"
if ($mosquittoLogs) {
    Write-Host "✅ Mosquitto est en écoute" @Success
    Write-Host "   Port interne: 1883 (MQTT)" @Success
    Write-Host "   Port externe: 1885 (mappé)" @Success
} else {
    Write-Host "⚠️  Impossible de confirmer que Mosquitto écoute" @Info
}

# ═══════════════════════════════════════════════════════════════════════════════
# TEST 3: Tester la publication manuelle (simule SensorApp)
# ═══════════════════════════════════════════════════════════════════════════════
Write-Host "`n[TEST 3]" @Title -NoNewline
Write-Host " Test de publication manuelle (simule SensorApp)" @Title

$testPayload = '{
  "patientId": "test-mqtt-123",
  "startTime": "2026-03-04T10:30:00",
  "endTime": "2026-03-04T10:30:30",
  "ppgData": [
    {"timestamp": "2026-03-04T10:30:01", "green": 1234.5, "red": 987.3},
    {"timestamp": "2026-03-04T10:30:02", "green": 1240.2, "red": 990.1}
  ],
  "accelerometerData": [
    {"timestamp": "2026-03-04T10:30:01", "x": 0.1, "y": -0.9, "z": 0.2}
  ]
}'

Write-Host "`n📤 Publication du message test..." @Info
try {
    docker exec -it mosquitto-pfa mosquitto_pub `
        -h localhost `
        -p 1883 `
        -t "health/sensorData" `
        -q 2 `
        -r `
        -m $testPayload
    
    Write-Host "✅ Message publié avec succès" @Success
    Write-Host "   Topic: health/sensorData" @Success
    Write-Host "   QoS: 2 (garantie de livraison)" @Success
    Write-Host "   Retained: true" @Success
} catch {
    Write-Host "❌ Erreur lors de la publication: $_" @Error_
}

# ═══════════════════════════════════════════════════════════════════════════════
# TEST 4: Écouter les messages sur le topic
# ═══════════════════════════════════════════════════════════════════════════════
Write-Host "`n[TEST 4]" @Title -NoNewline
Write-Host " Écoute des messages sur le topic" @Title

Write-Host "`n📨 Abonnement au topic health/sensorData..." @Info
Write-Host "⏱️  Écout pendant 10 secondes (Ctrl+C pour arrêter)..." @Info
Write-Host "────────────────────────────────────────────────────────────────" -ForegroundColor Gray

try {
    $receivedMessages = $false
    $output = docker exec -it mosquitto-pfa timeout 10 mosquitto_sub `
        -h localhost `
        -p 1883 `
        -t "health/sensorData" `
        -v 2>&1
    
    if ($output) {
        Write-Host $output -ForegroundColor Green
        $receivedMessages = $true
    }
    
    Write-Host "────────────────────────────────────────────────────────────────" -ForegroundColor Gray
    
    if ($receivedMessages) {
        Write-Host "✅ Messages reçus sur le broker Mosquitto" @Success
    } else {
        Write-Host "⚠️  Aucun message reçu (c'est normal si SensorApp n'est pas actif)" @Info
    }
} catch {
    Write-Host "ℹ️  Écoute interactive (timeout ou interruption)" @Info
}

# ═══════════════════════════════════════════════════════════════════════════════
# TEST 5: Vérifier la configuration du Backend
# ═══════════════════════════════════════════════════════════════════════════════
Write-Host "`n[TEST 5]" @Title -NoNewline
Write-Host " Vérification de la configuration Backend" @Title

$appPropsPath = "C:\Users\Admin\Desktop\platformeIOT\vitals-management\src\main\resources\application.properties"
if (Test-Path $appPropsPath) {
    $content = Get-Content $appPropsPath
    $brokerUrl = ($content | Select-String "mqtt.broker.url=").Line
    $topicPrefix = ($content | Select-String "mqtt.topic.prefix=").Line
    
    Write-Host "✅ Fichier de configuration trouvé" @Success
    Write-Host "   $brokerUrl" @Success
    Write-Host "   $topicPrefix" @Success
} else {
    Write-Host "❌ Fichier de configuration introuvable" @Error_
}

# ═══════════════════════════════════════════════════════════════════════════════
# RÉSUMÉ
# ═══════════════════════════════════════════════════════════════════════════════
Write-Host "`n═══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "📊 RÉSUMÉ DU TEST" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor Cyan

Write-Host @"
🏗️  Architecture MQTT:

   Android SensorApp (CLIENT #1 - PUBLISHER)
    ↓ tcp://192.168.68.151:1885
   ↓ Publishes: health/sensorData
   ↓
   ┌─────────────────────────────────────┐
   │  Mosquitto Broker (Docker)          │
    │  Port 1883 (interne) / 1885 (externe)
   └─────────────────────────────────────┘
   ↓
   ↓ Subscribes: health/sensorData
    ↓ tcp://127.0.0.1:1885
   Backend Spring Boot (CLIENT #2 - SUBSCRIBER)

✅ Prochaines étapes:

1. Redémarrez le Backend Spring Boot
   - Vous devriez voir: "MQTT CLIENT #1: BACKEND - MODE SUBSCRIBER"
   
2. Lancez SensorApp sur Android
   - Vérifiez "Statut MQTT: ✅ Connecté"
   - Démarrez le tracking
   
3. Observez les logs du Backend
   - Vous devriez voir: "📨 ✅ MESSAGE MQTT REÇU!"
   - Puis: "💾 ✅ SUCCESS: Données sauvegardées"

4. Consultez le diagnostic complet:
   - $PSScriptRoot\MQTT_DIAGNOSTIC.md
"@

Write-Host "`n═══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
