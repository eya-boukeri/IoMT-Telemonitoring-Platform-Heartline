# Guide d'Intégration - Smartwatch IoT Platform

## Vue d'ensemble

Ce document décrit l'intégration complète entre l'application smartwatch (SensorApp) et le backend de gestion des données vitales (vitals-management) via MQTT broker Mosquitto.

## Architecture du Système

```
┌─────────────────┐      MQTT (port 1884)      ┌──────────────────┐
│   SensorApp     │ ────────────────────────────> │   Mosquitto      │
│  (Smartwatch)   │   Topic: health/sensorData   │  MQTT Broker     │
│  Android Kotlin │                              │  (Docker)        │
└─────────────────┘                              └──────────────────┘
                                                           │
                                                           │ Subscribe
                                                           v
                                                  ┌──────────────────┐
                                                  │ vitals-management│
                                                  │  Spring Boot     │
                                                  └──────────────────┘
                                                           │
                                    ┌──────────────────────┼──────────────────────┐
                                    │                      │                      │
                                    v                      v                      v
                              ┌──────────┐          ┌──────────┐          ┌──────────┐
                              │ InfluxDB │          │  Kafka   │          │Dashboard │
                              │Time-Series│          │Event Bus │          │   Web    │
                              └──────────┘          └──────────┘          └──────────┘
```

## Configuration MQTT

### 1. Mosquitto Broker (Docker)

Le broker MQTT tourne dans un conteneur Docker avec la configuration suivante:

- **Container**: `mosquitto-pfa`
- **Image**: `eclipse-mosquitto:latest`
- **Port mapping**: `1884:1883` (host:container)
- **Commande pour démarrer**:
  ```bash
  docker start mosquitto-pfa
  ```

### 2. SensorApp (Android)

**Fichier**: `SensorApp/app/src/main/java/com/example/sensorapp/presentation/SensorDataService.kt`

Configuration MQTT:
```kotlin
private const val MQTT_BROKER = "tcp://10.0.2.2:1884"  // Android emulator to host
private const val MQTT_TOPIC = "health/sensorData"
```

**Note importante**: L'émulateur Android utilise `10.0.2.2` pour accéder à `localhost` de la machine hôte.

### 3. vitals-management (Backend Spring Boot)

**Fichier**: `vitals-management/src/main/resources/application.properties`

Configuration MQTT:
```properties
mqtt.broker.url=tcp://127.0.0.1:1884
mqtt.client.id=spring-backend
mqtt.topic.prefix=health/sensorData
mqtt.qos=1
```

## Format des Données

### Format ObservationData (Smartwatch → Backend)

```json
{
  "patientId": "patient-001",
  "timestamp": "2025-02-01T15:30:00",
  "ppgData": [
    {
      "timestamp": 1643731800000,
      "green": 1234.5,
      "red": 987.3
    },
    {
      "timestamp": 1643731850000,
      "green": 1245.8,
      "red": 995.1
    }
  ],
  "accelerometerData": [
    {
      "timestamp": 1643731800000,
      "accelerometerPoint": {
        "x": 0.15,
        "y": -0.98,
        "z": 0.05
      }
    }
  ]
}
```

### Format VitalData (Backend après conversion - ENRICHI)

```json
{
  "patientId": "patient-001",
  "timestamp": "2025-02-01T15:30:00Z",
  "startTime": "2025-02-01T15:25:00",
  "endTime": "2025-02-01T15:30:00",
  "collectionDurationSeconds": 300,
  
  "heartRate": 72.5,
  "heartRateMin": 68.0,
  "heartRateMax": 78.5,
  "heartRateVariability": 4.2,
  "detectedPeaks": 361,
  
  "oxygenSaturation": 98.0,
  
  "ppgGreenMin": 890.2,
  "ppgGreenMax": 1456.8,
  "ppgGreenAverage": 1200.5,
  "ppgRedMin": 750.1,
  "ppgRedMax": 1350.4,
  "ppgRedAverage": 1050.3,
  "ppgDataPoints": 6000,
  
  "accelerometerMagnitudeAverage": 0.85,
  "accelerometerMagnitudeMax": 2.15,
  "accelerometerVariance": 0.42,
  "accelerometerDataPoints": 1500,
  
  "signalQuality": "excellent",
  "signalQualityScore": 95.5,
  
  "bloodPressure": null,
  "temperature": null
}
```

**Améliorations clés:**
- ✅ Préservation de la fenêtre temporelle (startTime/endTime)
- ✅ Variabilité cardiaque complète (HRV avec min/max)
- ✅ Statistiques PPG brutes (min/max/moyenne par canal)
- ✅ Analyse d'activité depuis accéléromètre
- ✅ Score et évaluation qualité du signal

## Logique de Conversion Améliorée

Le backend `vitals-management` implémente une conversion optimisée des données time-series (ObservationData) en métriques vitales (VitalData) **en préservant le maximum de détails**.

**Fichier**: [vitals-management/src/main/java/com/medtech/vitalsmanagement/config/MqttConfig.java](vitals-management/src/main/java/com/medtech/vitalsmanagement/config/MqttConfig.java)

### Étapes de Traitement (v2.0)

1. **Réception MQTT**: Le message arrive sur le topic `health/sensorData`
2. **Parsing Dual**: Tentative de parsing comme `ObservationData` (smartwatch) ou `VitalData` (simple)
3. **Extraction Fenêtre Temporelle**: Récupération startTime/endTime pour contexte
4. **Traitement PPG** (voir `processPPGData()`):
   - Extraction statistiques brutes (min/max/moyenne par canal)
   - Détection de pics améliorée (seuil adaptatif)
   - Calcul HRV (Heart Rate Variability) à partir des intervalles
   - Estimation SpO2 avec filtrage des outliers
5. **Traitement Accéléromètre** (voir `processAccelerometerData()`):
   - Calcul magnitude: sqrt(x² + y² + z²)
   - Analyse d'activité (variabilité des mouvements)
6. **Évaluation Qualité Signal** (voir `assessSignalQuality()`):
   - Score basé sur: volume données, stabilité signal, validité métriques
   - Classification: excellent (≥90%), good (75-89%), fair (50-74%), poor (<50%)
7. **Stockage**: Insertion dans InfluxDB + publication Kafka

### Méthodes de Conversion Améliorées

#### `processPPGData()` - Extraction Détaillée PPG
**Entrée**: List<Map> des données PPG (green, red)  
**Traitement**:
- Extraction séparée canaux vert/rouge
- Statistiques: min, max, moyenne par canal
- Appel `calculateHeartRateWithVariability()` pour HR/HRV
- Appel `estimateSpO2FromPPG()` pour SpO2

**Sortie**: VitalData enrichie avec:
```
heartRate, heartRateMin, heartRateMax, heartRateVariability, detectedPeaks
ppgGreenMin/Max/Average, ppgRedMin/Max/Average, ppgDataPoints
oxygenSaturation
```

#### `processAccelerometerData()` - Analyse d'Activité
**Entrée**: List<Map> des données accéléromètre (x, y, z)  
**Traitement**:
- Calcul magnitude pour chaque point
- Extract min/max/moyenne magnitude
- Calcul variance des magnitudes (= variabilité activité)

**Sortie**:
```
accelerometerMagnitudeAverage, accelerometerMagnitudeMax
accelerometerVariance, accelerometerDataPoints
```

#### `calculateHeartRateWithVariability()` - Pic Detection Adaptative
**Algorithme Amélioré**:
```
1. Calcul moyenne + σ (écart-type) des valeurs PPG
2. Seuil Adaptatif = Moyenne + 0.5 × σ (au lieu de simple moyenne)
3. Détection de flan montant (intersection seuil) = pic potentiel
4. Stockage des intervalles entre pics
5. Conversion en BPM: HR = (peakCount / duration) × 60
6. Calcul HRV = écart-type des intervalles convertis en BPM
7. Validation: 40-200 bpm (sinon null)
```

**Sortie**: `HeartRateAnalysis` avec:
```
averageHeartRate, minHeartRate, maxHeartRate
variability (écart-type en BPM), peakCount
```

#### `estimateSpO2FromPPG()` - Ratio-of-Ratios Amélioré
**Algorithme**:
```
1. Filtrage outliers: ±2σ de la moyenne (évite données aberrantes)
2. Moyenne filtrée des canaux vert et rouge
3. Ratio = greenFiltered / redFiltered
4. SpO2 = 110 - (25 × ratio)
5. Validation:
   - 95-100% → retourner valeur
   - 85-95% → retourner + avertissement (faible saturé)
   - <85% ou >100% → retourner default 98%
```

#### `assessSignalQuality()` - Évaluation Qualité
**Critères d'évaluation** (base 100):
- PPG datapoints < 100 → -20 (données insuffisantes)
- PPG range (max-min) < 100 → -15 (signal faible)
- HR hors 40-200 bpm → -30 (invalide)
- HRV > 30 bpm → -10 (très variable)
- SpO2 hors 85-100% → -25 (invalide)

**Score Final**:
```
Score ≥ 90  → "excellent"
Score 75-89 → "good"
Score 50-74 → "fair"
Score < 50  → "poor"
```

## Guide de Test

### Prérequis

1. **Docker Desktop** installé et en cours d'exécution
2. **Android Studio** avec émulateur ou appareil physique
3. **Java 17+** pour Spring Boot
4. **Maven** pour build Spring Boot

### Étape 1: Démarrer Mosquitto

```bash
docker start mosquitto-pfa

# Vérifier que le broker est actif
docker ps | grep mosquitto-pfa
```

### Étape 2: Démarrer le Backend vitals-management

```bash
cd c:\Users\Admin\Desktop\platformeIOT\vitals-management

# Build et démarrage
mvn spring-boot:run
```

**Vérifications**:
- Port Spring Boot: `8080` (ou selon configuration)
- Logs MQTT: Rechercher `✅ MQTT connected`, `📡 Subscribed to topic`

### Étape 3: Déployer SensorApp

#### Sur Émulateur Android

1. Ouvrir Android Studio
2. Charger le projet `PROJET PLATEFORME IOT/SensorApp/`
3. Démarrer l'émulateur
4. Run > Run 'app'
5. Accorder les permissions nécessaires (Samsung Health)
6. Appuyer sur "Start Tracking"

#### Sur Appareil Physique

**Important**: Modifier `SensorDataService.kt` pour utiliser l'adresse IP de votre machine:

```kotlin
// Remplacer 10.0.2.2 par l'IP de votre PC (ex: 192.168.1.100)
private const val MQTT_BROKER = "tcp://192.168.1.100:1884"
```

### Étape 4: Vérifier les Données

#### Via Logs Backend

```
📡 MQTT message received - Topic: health/sensorData, payload_size=2847
📊 Parsed as ObservationData (smartwatch format), converting to VitalData
📊 PPG Processed: 6000 datapoints, HR=72.5 bpm (±4.2), SpO2=98.0%
🏃 Activity Detected: avg=0.85, max=2.15, variance=0.42
📈 Signal Quality: excellent (score=95.5%)
✅ Converted ObservationData to VitalData - patientId=patient-001, HR=72.5 (range: 68.0-78.5), SpO2=98.0, activity=0.85
💾 InfluxDB: Patient patient-001 - HR=72.5 Temp=null SpO2=98.0 @ 2025-02-01T15:30:00Z
✉️  Kafka published - patient=patient-001 partition=0 offset=123
```

**Métriques à observer:**
- `PPG Processed` → nombre de datapoints capturés
- `HR=X bpm (±Y)` → fréquence cardiaque + variabilité
- `Signal Quality` → évaluation automatique qualité capture
- `activity=Z` → intensité moyenne des mouvements détectés

#### Via MQTT Client (Test)

Installer mosquitto-clients:
```bash
# Windows (Chocolatey)
choco install mosquitto

# Subscribe au topic
mosquitto_sub -h localhost -p 1884 -t "health/sensorData" -v
```

#### Via InfluxDB

```bash
# Connexion à InfluxDB (selon votre config)
influx -host localhost -port 8086

# Query
USE vitals_db
SELECT * FROM vitals ORDER BY time DESC LIMIT 10
```

## Fichiers Modifiés (v2.0 - Enhanced Data Preservation)

### Backend vitals-management

1. **[VitalData.java](vitals-management/src/main/java/com/medtech/vitalsmanagement/model/VitalData.java)** ⭐ ENRICHI
   - Ajout Heart Rate Variability: `heartRateMin`, `heartRateMax`, `heartRateVariability`, `detectedPeaks`
   - Ajout PPG Statistics: `ppgGreenMin/Max/Average`, `ppgRedMin/Max/Average`, `ppgDataPoints`
   - Ajout Activity Analysis: `accelerometerMagnitudeAverage/Max`, `accelerometerVariance`, `accelerometerDataPoints`
   - Ajout Time Window: `startTime`, `endTime`, `collectionDurationSeconds`
   - Ajout Signal Quality: `signalQuality`, `signalQualityScore`

2. **[MqttConfig.java](vitals-management/src/main/java/com/medtech/vitalsmanagement/config/MqttConfig.java)** ⭐ AMÉLIORÉ
   - Remplace `convertObservationDataToVitalData()` par version améliorée
   - Nouvelle méthode `processPPGData()` pour extraction statistiques PPG
   - Nouvelle méthode `processAccelerometerData()` pour analyse d'activité
   - Nouvelle méthode `calculateHeartRateWithVariability()` avec seuil adaptatif
   - Nouvelle méthode `estimateSpO2FromPPG()` avec filtrage outliers
   - Nouvelle méthode `assessSignalQuality()` pour scoring qualité signal
   - Nouvelle classe interne `HeartRateAnalysis` pour encapsuler résultats HR/HRV

3. **[ObservationData.java](vitals-management/src/main/java/com/medtech/vitalsmanagement/model/ObservationData.java)**
   - Modèle pour recevoir les données time-series de la smartwatch
   - Champs: `patientId`, `startTime`, `endTime`, `ppgData[]`, `accelerometerData[]`

4. **[application.properties](vitals-management/src/main/resources/application.properties)**
   - Changé `mqtt.topic.prefix` de `sensors/vitals/` à `health/sensorData`

### Android SensorApp

1. **[SensorDataService.kt](PROJET PLATEFORME IOT/SensorApp/SensorApp/app/src/main/java/com/example/sensorapp/presentation/SensorDataService.kt)**
   - Changé `MQTT_BROKER` de `tcp://broker.emqx.io:1883` à `tcp://10.0.2.2:1884`

### DashboardApi (si utilisé en parallèle)

1. **[MqttConfig.java](PROJET PLATEFORME IOT/DashboardApi/src/main/java/com/example/dashboardapi/Config/MqttConfig.java)**
   - Externalisé la configuration avec `@Value`

2. **[application.properties](PROJET PLATEFORME IOT/DashboardApi/src/main/resources/application.properties)**
   - Ajouté `mqtt.broker.url=tcp://localhost:1884`

## Dépannage

### Problème: Backend ne reçoit pas de messages

**Vérifications**:
1. Mosquitto est actif: `docker ps | grep mosquitto`
2. Firewall autorise le port 1884
3. Topic correspond exactement: `health/sensorData` (case-sensitive)

### Problème: SensorApp ne se connecte pas (émulateur)

**Solutions**:
- Vérifier que `10.0.2.2` est bien utilisé (pas `localhost`)
- Tester la connectivité: `adb shell ping -c 3 10.0.2.2`

### Problème: Erreur de parsing JSON

**Logs à rechercher**:
- `❌ Error parsing MQTT payload`
- Vérifier le format du JSON envoyé par SensorApp

**Solution**: Le backend utilise un ObjectMapper lenient qui accepte:
- Champs non quotés
- Guillemets simples
- Virgules finales

### Problème: Conversion de fréquence cardiaque incorrecte

**Causes possibles**:
- Données PPG insuffisantes
- Algorithme de détection de pics nécessite calibration
- Fréquence d'échantillonnage différente de 20 Hz

**Solution temporaire**: Modifier `calculateHeartRateFromPPG()` pour ajuster le seuil de détection

## Améliorations Implémentées (v2.0)

✅ **Détection de Pics Améliorée** - Seuil adaptatif au lieu de simple moyenne  
✅ **Heart Rate Variability (HRV)** - Calcul complet avec min/max/écart-type  
✅ **Statistiques PPG Complètes** - Extraction min/max/moyenne par canal  
✅ **Analyse d'Activité** - À partir des données accéléromètre  
✅ **Filtrage des Outliers** - Pour SpO2 estimation (±2σ)  
✅ **Évaluation Qualité Signal** - Score et classification automatique  
✅ **Préservation Fenêtre Temporelle** - startTime/endTime/duration  

## Améliorations Futures

1. **Algorithme FFT** pour analyse fréquentielle plus avancée des PPG
2. **Machine Learning** pour estimation SpO2 calibrée par patient
3. **Détection d'Arythmies** basée sur HRV patterns
4. **Agrégation Temporelle Intelligente** pour réduire volume données (downsampling adaptatif)
5. **Notifications en Temps Réel** via WebSocket pour le dashboard
6. **Support Multi-Capteurs** (température, pression artérielle) si disponibles sur smartwatch
7. **Persistance Données Brutes** option - stockage PPG/accéléro pour analyse future

## Références

- **Samsung Health SDK**: [https://developer.samsung.com/health](https://developer.samsung.com/health)
- **Eclipse Paho MQTT**: [https://www.eclipse.org/paho/](https://www.eclipse.org/paho/)
- **Spring Integration MQTT**: [https://docs.spring.io/spring-integration/reference/html/mqtt.html](https://docs.spring.io/spring-integration/reference/html/mqtt.html)
- **InfluxDB Time Series**: [https://docs.influxdata.com/](https://docs.influxdata.com/)

---

**Date de création**: 2025-02-01  
**Date dernière mise à jour**: 2026-02-28  
**Version**: 2.0 (Enhanced Data Preservation)  
**Auteur**: Integration Team
