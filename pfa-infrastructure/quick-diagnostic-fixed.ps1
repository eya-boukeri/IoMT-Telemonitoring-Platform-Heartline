# Quick Diagnostic - Plateforme PFA
# Usage: .\quick-diagnostic.ps1

Write-Host ""
Write-Host "╔════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║         DIAGNOSTIC RAPIDE - Plateforme PFA           ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# Containers
Write-Host "🐳 Containers Docker:" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray

$containers = @(
    "mosquitto-pfa-v2",
    "kafka-pfa-v2",
    "postgres-pfa",
    "influxdb-pfa",
    "ingestion-service-pfa",
    "data-analytics-service-pfa",
    "notification-service-pfa",
    "api-gateway-pfa"
)

$running = docker ps --format "{{.Names}}"

foreach ($c in $containers) {
    if ($running -match $c) {
        Write-Host "✓ $c" -ForegroundColor Green
    } else {
        Write-Host "✗ $c" -ForegroundColor Red
    }
}

Write-Host ""

# Test MQTT
Write-Host "📨 MQTT Test:" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
try {
    docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "test" -m "test" 2>&1 > $null
    Write-Host "✓ MQTT OK" -ForegroundColor Green
}
catch {
    Write-Host "✗ MQTT Problem" -ForegroundColor Red
}

# Test Kafka
Write-Host ""
Write-Host "📤 Kafka Topics:" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
try {
    $topics = docker exec kafka-pfa-v2 rpk topic list 2>/dev/null
    if ($topics -match "vitals-raw") {
        Write-Host "✓ vitals-raw exists" -ForegroundColor Green
    } else {
        Write-Host "✗ vitals-raw missing" -ForegroundColor Red
    }

    if ($topics -match "medical-alerts") {
        Write-Host "✓ medical-alerts exists" -ForegroundColor Green
    } else {
        Write-Host "✗ medical-alerts missing" -ForegroundColor Red
    }
}
catch {
    Write-Host "✗ Kafka Problem" -ForegroundColor Red
}

# Test InfluxDB
Write-Host ""
Write-Host "📊 InfluxDB Health:" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
try {
    $health = curl -s "http://localhost:8086/health" 2>/dev/null | findstr /C:"\"status\"" 2>/dev/null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✓ InfluxDB responding" -ForegroundColor Green
    } else {
        Write-Host "✗ InfluxDB not responding" -ForegroundColor Yellow
    }
}
catch {
    Write-Host "✗ InfluxDB Problem" -ForegroundColor Red
}

# Dashboard
Write-Host ""
Write-Host "🎨 Dashboard:" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
Write-Host "Open: http://localhost:5173" -ForegroundColor Cyan
Write-Host "Login: dr-farah / farah1234" -ForegroundColor Cyan

Write-Host ""
Write-Host "✅ Diagnostic Complete" -ForegroundColor Green
Write-Host ""
