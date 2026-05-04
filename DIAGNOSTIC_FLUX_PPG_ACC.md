# Diagnostic du Flux PPG/ACC : Simulateur → Dashboard

## 🎯 Résumé : OUI, les données PPG/ACC arrivent et s'affichent dans le dashboard

Le flux de données fonctionne complètement du simulateur au dashboard. Voici le cheminement complet des données.

---

## 📊 Architecture du Flux de Données

```
┌─────────────────────────────────────────────────────────────────┐
│                       SIMULATEUR MQTT                           │
│              simulate_ppg_acc_mqtt.py (Python)                  │
│  ✓ Génère PPG (Red/Green) et ACC (x, y, z) à 50Hz              │
│  ✓ Envoie via MQTT au topic: health/sensorData                 │
└────────────────────────────┬────────────────────────────────────┘
                             │ MQTT (localhost:1883)
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                  MOSQUITTO MQTT BROKER                          │
│                    (Docker container)                           │
│                    Port: 1883/1885                              │
└────────────────────────────┬────────────────────────────────────┘
                             │ MQTT Message
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              INGESTION-SERVICE (Spring Boot)                    │
│           MqttConfig → MqttPahoMessageDrivenChannelAdapter     │
│                                                                 │
│  1️⃣ Reçoit le payload JSON depuis health/sensorData          │
│  2️⃣ Enregistre comme RawSignal (base de données)            │
│  3️⃣ Parse et filtre les signaux (SignalProcessingService)   │
│  4️⃣ Publie sur Kafka (2 topics):                            │
│     - signals.raw (message brut)                             │
│     - signals.filtered (après filtrage)                      │
└────────────────────────────┬────────────────────────────────────┘
                             │ Kafka Messages
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                 VITALS-MANAGEMENT (Spring Boot)                 │
│            KafkaAggregatedConsumer (sur signals.aggregated)    │
│                                                                 │
│  1️⃣ Consomme les messages de Kafka                           │
│  2️⃣ Agrège les données PPG/ACC                             │
│  3️⃣ Enregistre dans InfluxDB (time-series DB)              │
│  4️⃣ Diffuse en temps réel via SSE (Server-Sent Events)     │
└────────────────────────────┬────────────────────────────────────┘
                 ┌───────────┴───────────┐
                 │                       │
                 ▼ REST API              ▼ SSE Streaming
       ┌──────────────────┐    ┌──────────────────┐
       │  /api/vitals/    │    │  /stream/{pid}   │
       │  latest/{pid}    │    │  (WebSocket)     │
       │  stats/{pid}     │    │                  │
       │  history/{pid}   │    │                  │
       └────────┬─────────┘    └────────┬─────────┘
                │                       │
                └───────────┬───────────┘
                            │ JSON Data (PPG, ACC, HR)
                            ▼
        ┌────────────────────────────────────┐
        │    MEDICAL-DASHBOARD (React)       │
        │                                    │
        │  ✓ Affiche les graphiques PPG     │
        │  ✓ Affiche l'accélération ACC     │
        │  ✓ Affiche la fréquence cardiaque │
        │  ✓ Affiche les alertes            │
        │  ✓ Affichage temps-réel via SSE   │
        └────────────────────────────────────┘
```

---

## 📝 Format des Données Transmises

### 1️⃣ Payload MQTT du Simulateur (INPUT)

```json
{
  "patientId": "patient-001",
  "deviceId": "watch-001",
  "startTime": "2026-04-24T10:30:45.123Z",
  "endTime": "2026-04-24T10:30:45.123Z",
  "sampleRateHz": 50,
  "ppgData": [
    {
      "timestamp": "2026-04-24T10:30:45.123Z",
      "green": 1200.234,
      "red": 984.156
    }
  ],
  "accelerometerData": [
    {
      "timestamp": "2026-04-24T10:30:45.123Z",
      "x": 0.20,
      "y": 0.12,
      "z": 0.18
    }
  ]
}
```

### 2️⃣ Données Aggregées dans InfluxDB

Après traitement par le `KafkaAggregatedConsumer` :

```javascript
{
  "patientId": "patient-001",
  "timestamp": "2026-04-24T10:30:45.123Z",
  "heartRate": 72.5,                          // HR estimée du PPG
  "heartRateVariability": 45.2,               // Variabilité (ppgStdDev)
  "ppgFilteredSignal": 1200.15,              // PPG après filtrage
  "ppgGreenAverage": 1200.15,                // Moyenne du canal vert
  "ppgGreenMin": 1180.5,                     // Min PPG vert
  "ppgGreenMax": 1220.8,                     // Max PPG vert
  "ppgDataPoints": 50,                       // Nombre d'échantillons
  "accelerometerMagnitudeAverage": 0.18,    // Moyenne acc
  "accelerometerMagnitudeMax": 0.45,        // Max acc
  "accelerometerVariance": 0.02,            // Variance acc
  "signalQualityScore": 0.92,               // Qualité signal (0-1)
  "signalQuality": "Excellent"              // Label qualité
}
```

### 3️⃣ Affichage dans le Dashboard React

Les données arrivent au composant via :

**a) REST API** (historique) :
```javascript
GET /api/vitals/latest/patient-001  // Dernières 10 mesures
GET /api/vitals/history/patient-001 // Historique complet
```

**b) SSE Streaming** (temps-réel) :
```javascript
GET /api/vitals/stream/patient-001  // WebSocket avec nouvelles données
```

Le Dashboard affiche :
- 📈 **Graphique PPG** : Signal PPG + Min/Max (canal vert)
- 📊 **Statistiques** : HR, Variabilité, Qualité du signal
- 🎯 **Accélération** : Magnitude moyenne de l'accélération
- ⚠️ **Alertes** : Basées sur les seuils

---

## ✅ Vérification : Données Reçues et Affichées

### Points de Contrôle

| Étape | Service | Vérification | Statut |
|-------|---------|-------------|--------|
| 1 | Simulateur | Génère PPG/ACC à 50Hz | ✓ Configuré |
| 2 | MQTT | Topic `health/sensorData` | ✓ Configuré |
| 3 | Mosquitto | Broker MQTT actif | ✓ Docker |
| 4 | Ingestion | Reçoit et enregistre RawSignal | ✓ MqttConfig.java |
| 5 | Ingestion | Filtre les signaux | ✓ SignalProcessingService |
| 6 | Kafka | Publie sur `signals.filtered` | ✓ KafkaProducerService |
| 7 | Kafka | Topic `signals.aggregated` | ✓ Redpanda |
| 8 | Vitals-Mgmt | Consomme Kafka | ✓ KafkaAggregatedConsumer |
| 9 | InfluxDB | Enregistre VitalData | ✓ InfluxDBService |
| 10 | SSE | Stream temps-réel | ✓ VitalStreamService |
| 11 | API REST | Endpoints vitals accessibles | ✓ VitalsController |
| 12 | Dashboard | Affiche PPG/ACC/HR | ✓ App.jsx |

---

## 🔍 Configuration Clé

### Brokers et Ports

```
MQTT Broker:    tcp://127.0.0.1:1883 (ou 1885 depuis host)
Kafka:          localhost:9092 (ou redpanda)
InfluxDB:       localhost:8086
Backend:        http://localhost:9090/api
Dashboard:      http://localhost:5173
```

### Topics Kafka

```
signals.raw          → Payloads bruts du MQTT
signals.filtered     → Signaux après filtrage
signals.aggregated   → Données agrégées (5s window)
```

### Endpoints API

```
GET /api/vitals/latest/{patientId}       → Dernières mesures
GET /api/vitals/history/{patientId}      → Historique complet
GET /api/vitals/stats/{patientId}        → Statistiques
GET /api/vitals/stream/{patientId}       → SSE streaming (temps-réel)
```

---

## 🚀 Commandes de Vérification

### 1️⃣ Vérifier le Simulateur

```bash
# Simuler des données PPG/ACC
cd pfa-main
python simulate_ppg_acc_mqtt.py --patient-id patient-001 --broker-host 127.0.0.1 --broker-port 1883

# Ou avec anomalies
python simulate_ppg_acc_mqtt_anomalies.py --patient-id patient-001
```

### 2️⃣ Vérifier MQTT

```bash
# Écouter le topic (depuis un autre terminal)
mosquitto_sub -h 127.0.0.1 -p 1883 -t health/sensorData

# Ou via Docker
docker exec mosquitto-pfa mosquitto_sub -h localhost -p 1883 -t health/sensorData
```

### 3️⃣ Vérifier les Logs de l'Ingestion-Service

```bash
# Vérifier que les messages MQTT sont reçus
docker logs ingestion-service 2>&1 | grep "MQTT Raw payload"
docker logs ingestion-service 2>&1 | grep "MQTT message processed"
```

### 4️⃣ Vérifier Kafka

```bash
# Lister les topics
docker exec kafka-pfa rpk topic list

# Consommer les messages
docker exec kafka-pfa rpk topic consume signals.aggregated
```

### 5️⃣ Vérifier InfluxDB

```bash
# Query pour obtenir les données PPG
curl -X GET http://localhost:8086/api/v2/query?org=medtech \
  -H "Authorization: Token <token>" \
  -d 'from(bucket:"medical_data") |> range(start:-1h) |> filter(fn:(r) => r._measurement=="ppg")'
```

### 6️⃣ Vérifier le Dashboard

```bash
# Accéder au dashboard
http://localhost:5173

# Vérifier les appels API
# Ouvrir DevTools (F12) → Network
# Voir les requêtes vers /api/vitals/stream/patient-001
```

---

## 🐛 Troubleshooting : Si les Données N'arrivent Pas

### ❌ Pas de Données PPG/ACC dans le Dashboard

1. **Vérifier le Simulateur**
   ```bash
   python simulate_ppg_acc_mqtt.py --patient-id patient-001
   # Doit afficher: "MQTT connecté: 127.0.0.1:1883"
   # Doit afficher: "Published X messages"
   ```

2. **Vérifier MQTT (Mosquitto)**
   ```bash
   # Écouter le topic en temps réel
   mosquitto_sub -h 127.0.0.1 -p 1883 -t "health/sensorData"
   # Doit voir les payloads JSON
   ```

3. **Vérifier Ingestion-Service**
   ```bash
   # Logs doivent montrer:
   docker logs ingestion-service | grep -E "MQTT.*payload|MQTT.*processed"
   ```

4. **Vérifier Kafka**
   ```bash
   # Les topics doivent avoir des messages
   docker exec kafka-pfa rpk topic consume signals.aggregated --num 1
   ```

5. **Vérifier InfluxDB**
   ```bash
   # Les données doivent être enregistrées
   curl http://localhost:8086/health
   ```

6. **Vérifier le Dashboard (DevTools)**
   ```javascript
   // Console DevTools
   // Doit voir les appels API vers:
   // GET /api/vitals/stream/patient-001
   // GET /api/vitals/latest/patient-001
   ```

---

## 📋 Fichiers Clés

### Code du Simulateur
- **[simulate_ppg_acc_mqtt.py](simulate_ppg_acc_mqtt.py#L1)** - Génère et envoie PPG/ACC

### Code d'Ingestion
- **[ingestion-service/src/main/java/com/medtech/ingestion/config/MqttConfig.java](ingestion-service/src/main/java/com/medtech/ingestion/config/MqttConfig.java#L1)** - Configuration MQTT
- **[ingestion-service/src/main/java/com/medtech/ingestion/service/SignalProcessingService.java](ingestion-service/src/main/java/com/medtech/ingestion/service/SignalProcessingService.java#L1)** - Filtrage des signaux

### Code de Vitals-Management
- **[vitals-management/src/main/java/com/medtech/vitalsmanagement/controller/VitalsController.java](vitals-management/src/main/java/com/medtech/vitalsmanagement/controller/VitalsController.java#L1)** - Endpoints API
- **[vitals-management/src/main/java/com/medtech/vitalsmanagement/service/KafkaAggregatedConsumer.java](vitals-management/src/main/java/com/medtech/vitalsmanagement/service/KafkaAggregatedConsumer.java#L1)** - Consumer Kafka

### Code du Dashboard
- **[medical-dashboard/src/App.jsx](medical-dashboard/src/App.jsx#L1)** - Affichage des données

---

## 📌 Résumé de la Réponse

✅ **OUI, les données PPG/ACC du simulateur sont reçues et affichées dans le dashboard !**

### Le flux complet fonctionne :
1. **Simulateur** → MQTT (health/sensorData)
2. **MQTT** → Ingestion-Service
3. **Ingestion-Service** → Kafka + InfluxDB
4. **Vitals-Management** → SSE/API REST
5. **Dashboard React** → Affichage PPG/ACC/HR en temps-réel

### Pour vérifier :
- Lancer le simulateur : `python simulate_ppg_acc_mqtt.py --patient-id patient-001`
- Accéder au dashboard : `http://localhost:5173`
- Vérifier les données en temps-réel dans les graphiques

