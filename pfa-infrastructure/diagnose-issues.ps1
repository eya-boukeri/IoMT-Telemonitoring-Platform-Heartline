# Script de Diagnostic - Identifie les problèmes dans la chaîne
# Usage: ./diagnose-issues.ps1

Write-Host ""
Write-Host "╔════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║              DIAGNOSTIC DE LA PLATEFORME                 ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

$issues = @()
$warnings = @()

# ==================== DOCKER ====================
Write-Host "1️⃣  Vérification Docker..." -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray

$containers = @(
    "mosquitto-pfa-v2",
    "kafka-pfa-v2",
    "zookeeper-pfa-v2",
    "postgres-pfa",
    "influxdb-pfa",
    "ingestion-service-pfa",
    "data-analytics-service-pfa",
    "notification-service-pfa",
    "api-gateway-pfa",
    "medical-dashboard-pfa"
)

$runningContainers = docker ps --format "{{.Names}}" 2>/dev/null

foreach ($container in $containers) {
    if ($runningContainers -contains $container) {
        Write-Host "✓ $container" -ForegroundColor Green
    } else {
        Write-Host "❌ $container - NOT RUNNING" -ForegroundColor Red
        $issues += "Container non actif: $container"
    }
}

Write-Host ""

# ==================== MQTT ====================
Write-Host "2️⃣  Vérification MQTT..." -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray

try {
    $mqttTest = docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "test/diagnostic" -m "test" 2>&1
    Write-Host "✓ MQTT Publishing fonctionne" -ForegroundColor Green
} catch {
    Write-Host "❌ MQTT Publishing échoue" -ForegroundColor Red
    $issues += "MQTT publishing défaillant"
}

Write-Host ""

Write-Host ""

# ==================== KAFKA ====================
Write-Host "3️⃣  Vérification Kafka..." -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray

try {
    $kafkaTopics = docker exec kafka-pfa-v2 rpk topic list 2>/dev/null
    $requiredTopics = @("vitals-raw", "medical-alerts")
    
    foreach ($topic in $requiredTopics) {
        if ($kafkaTopics -contains $topic) {
            Write-Host "✓ Topic Kafka: $topic" -ForegroundColor Green
        } else {
            Write-Host "⚠️  Topic Kafka manquant: $topic" -ForegroundColor Yellow
            $warnings += "Topic Kafka '$topic' manquant - les données ne seront pas persistées"
        }
    }
} catch {
    Write-Host "❌ Kafka inaccessible" -ForegroundColor Red
    $issues += "Kafka non accessible"
}

Write-Host ""

# ==================== BASE DE DONNÉES ====================
Write-Host "4️⃣  Vérification Bases de Données..." -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray

# PostgreSQL
try {
    $pgTest = docker exec postgres-pfa psql -U postgres -d medical_db -c "SELECT version();" 2>/dev/null
    if ($pgTest) {
        Write-Host "✓ PostgreSQL connecté" -ForegroundColor Green
    } else {
        Write-Host "❌ PostgreSQL erreur de requête" -ForegroundColor Red
        $issues += "PostgreSQL requête échouée"
    }
} catch {
    Write-Host "❌ PostgreSQL inaccessible" -ForegroundColor Red
    $issues += "PostgreSQL non accessible"
}

# InfluxDB
try {
    $influxTest = Invoke-RestMethod -Method GET -Uri "http://localhost:8086/health" -ErrorAction Stop
    if ($influxTest.status -eq "pass") {
        Write-Host "✓ InfluxDB en ligne" -ForegroundColor Green
    } else {
        Write-Host "⚠️  InfluxDB - Status: $($influxTest.status)" -ForegroundColor Yellow
        $warnings += "InfluxDB status non 'pass'"
    }
} catch {
    Write-Host "❌ InfluxDB inaccessible (http://localhost:8086)" -ForegroundColor Red
    $issues += "InfluxDB non accessible sur http://localhost:8086"
}
Write-Host ""

Write-Host ""

# ==================== LOGS DES SERVICES ====================
Write-Host "5️⃣  Vérification des Logs des Services..." -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray

$services = @(
    @{name="Ingestion Service"; container="ingestion-service-pfa"; errors=@("ERROR", "Exception", "java.lang.")},
    @{name="Data Analytics"; container="data-analytics-service-pfa"; errors=@("ERROR", "Exception", "Traceback")},
    @{name="Notification Service"; container="notification-service-pfa"; errors=@("ERROR", "Exception")}
)

foreach ($service in $services) {
    Write-Host "  Vérifiant: $($service.name)..." -ForegroundColor Gray
    try {
        $logs = docker logs $service.container --tail 50 2>/dev/null
        $hasErrors = $false
        foreach ($errorPattern in $service.errors) {
            if ($logs -match $errorPattern) {
                $hasErrors = $true
                break
            }
        }
        
        if ($hasErrors) {
            Write-Host "  ⚠️  $($service.name) - Erreurs détectées dans les logs" -ForegroundColor Yellow
            $warnings += "$($service.name) contient des erreurs"
            
            # Afficher les dernières erreurs
            $errorLines = $logs | Select-String $errorPattern | Select-Object -Last 3
            foreach ($line in $errorLines) {
                Write-Host "     > $($line.Line.Substring(0, [Math]::Min(80, $line.Line.Length)))..." -ForegroundColor DarkYellow
            }
        } else {
            Write-Host "  ✓ $($service.name) - OK" -ForegroundColor Green
        }
    } catch {
        Write-Host "  ❌ $($service.name) - Impossible de lire les logs" -ForegroundColor Red
        $issues += "Impossible de lire logs de $($service.name)"
    }
}

Write-Host ""

# ==================== ENDPOINTS ====================
Write-Host "6️⃣  Vérification des Endpoints..." -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray

$endpoints = @(
    @{url="http://localhost:5173"; name="Medical Dashboard"},
    @{url="http://localhost:8000"; name="API Gateway"},
    @{url="http://localhost:8088"; name="InfluxDB Query API"},
    @{url="http://localhost:3000"; name="Keycloak"}
)

foreach ($endpoint in $endpoints) {
    try {
        $response = Invoke-WebRequest -Uri $endpoint.url -TimeoutSec 2 -ErrorAction Stop
        Write-Host "✓ $($endpoint.name) - HTTP $($response.StatusCode)" -ForegroundColor Green
    } catch {
        Write-Host "❌ $($endpoint.name) - Inaccessible" -ForegroundColor Red
        $issues += "$($endpoint.name) inaccessible ($($endpoint.url))"
    }
}

Write-Host ""

# ==================== RÉSUMÉ ====================
Write-Host "════════════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "📋 RÉSUMÉ DU DIAGNOSTIC" -ForegroundColor Cyan
Write-Host "════════════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

if ($issues.Count -eq 0 -and $warnings.Count -eq 0) {
    Write-Host "✅ AUCUN PROBLÈME DÉTECTÉ - Plateforme opérationnelle!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Vous pouvez lancer: ./test-scenarios.ps1" -ForegroundColor Green
} else {
    if ($issues.Count -gt 0) {
        Write-Host "❌ PROBLÈMES CRITIQUES ($($issues.Count)):" -ForegroundColor Red
        foreach ($issue in $issues) {
            Write-Host "  • $issue" -ForegroundColor Red
        }
        Write-Host ""
    }
    
    if ($warnings.Count -gt 0) {
        Write-Host "⚠️  AVERTISSEMENTS ($($warnings.Count)):" -ForegroundColor Yellow
        foreach ($warning in $warnings) {
            Write-Host "  • $warning" -ForegroundColor Yellow
        }
        Write-Host ""
    }
    
    Write-Host "🔧 ACTIONS RECOMMANDÉES:" -ForegroundColor Yellow
    Write-Host "  1. Vérifier docker-compose:" -ForegroundColor White
    Write-Host "     docker-compose -f pfa-infrastructure/docker-compose.yml ps" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  2. Redémarrer les services:" -ForegroundColor White
    Write-Host "     docker-compose -f pfa-infrastructure/docker-compose.yml restart" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  3. Voir les logs:" -ForegroundColor White
    Write-Host "     docker logs <container-name> --tail 100" -ForegroundColor Cyan
    Write-Host ""
}

Write-Host ""
