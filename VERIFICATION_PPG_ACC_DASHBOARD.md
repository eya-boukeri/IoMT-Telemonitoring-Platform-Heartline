# Guide Rapide : Vérifier que PPG/ACC arrivent au Dashboard

## ✅ Réponse Courte

**OUI** - Les données PPG (Photopléthysmographie) et ACC (Accélération) envoyées par le simulateur arrivent et s'affichent dans le dashboard.

### Flux Complet :
```
Simulateur MQTT → Mosquitto → Ingestion-Service → Kafka → InfluxDB → Vitals-Management → Dashboard React
```

---

## 🚀 Pour Vérifier Rapidement (3 étapes)

### Étape 1️⃣ : Démarrer le Simulateur

```bash
cd c:\Users\USER\Desktop\pfa-main\pfa-main
python simulate_ppg_acc_mqtt.py --patient-id patient-001 --broker-host 127.0.0.1 --broker-port 1883
```

**Doit afficher :**
```
✓ MQTT connecte: 127.0.0.1:1883
✓ Published 1 messages (sample_index=0, topic=health/sensorData)
✓ Published 2 messages
...
```

### Étape 2️⃣ : Vérifier que les Logs de l'Ingestion montrent la réception

```bash
docker logs ingestion-service -f 2>&1 | grep -E "MQTT.*payload|MQTT.*processed"
```

**Doit afficher :**
```
🔍 MQTT Raw payload received - topic=health/sensorData payload={...ppgData...accelerometerData...}
MQTT message processed - topic=health/sensorData patientId=patient-001 deviceId=...
```

### Étape 3️⃣ : Ouvrir le Dashboard et Vérifier les Données

```
http://localhost:5173
```

**Le dashboard doit afficher :**
- 📈 Graphique PPG avec une courbe pulsatile (50-100 mV)
- 📊 Fréquence cardiaque estimée (60-100 bpm)
- 🎯 Accélération de la smartwatch
- 📈 Qualité du signal (90%+)

---

## 📊 Ce qui Arrive à Chaque Étape

### 1. Simulateur Python
```python
# Génère :
{
  "patientId": "patient-001",
  "deviceId": "watch-001",
  "ppgData": [{"timestamp": "...", "green": 1200.23, "red": 984.15}],
  "accelerometerData": [{"timestamp": "...", "x": 0.20, "y": 0.12, "z": 0.18}]
}
# Envoie via MQTT au broker sur le topic: health/sensorData
```

### 2. Ingestion-Service (Java)
```
- Reçoit le JSON du broker MQTT
- Enregistre comme "RawSignal" dans la DB
- Filtre et traite les données
- Publie sur Kafka (topics: signals.raw, signals.filtered)
```

### 3. Vitals-Management (Java)
```
- Consomme les messages Kafka
- Agrège les données PPG/ACC
- Enregistre dans InfluxDB (time-series DB)
- Diffuse en temps réel via SSE (WebSocket)
```

### 4. Dashboard React
```
- Récupère les données via API REST (/api/vitals/latest/{patientId})
- Reçoit les mises à jour temps-réel via SSE (/api/vitals/stream/{patientId})
- Affiche les graphiques avec Recharts
```

---

## 🔧 Configuration Requise

### Services Requis (doivent être actifs)

```bash
# Vérifier Mosquitto
docker ps | grep mosquitto-pfa
# Doit afficher: mosquitto-pfa port 1883

# Vérifier InfluxDB
docker ps | grep influxdb-pfa
# Doit afficher: influxdb-pfa port 8086

# Vérifier Kafka
docker ps | grep kafka-pfa
# Doit afficher: kafka-pfa port 9093

# Vérifier Ingestion-Service
docker ps | grep ingestion-service
# Doit afficher: ingestion-service port 9091

# Vérifier Vitals-Management
docker ps | grep vitals-management
# Doit afficher: vitals-management port 9090
```

---

## 📱 Données Affichées dans le Dashboard

Après démarrage du simulateur, le dashboard affiche automatiquement :

| Métrique | Source | Exemple |
|----------|--------|---------|
| **PPG Signal** | Simulateur PPG (green) | ~1200 mV |
| **Heart Rate** | Calculée à partir du PPG | 72 bpm |
| **PPG Min/Max** | Enveloppe du signal | 1180-1220 mV |
| **Signal Quality** | Calculée par filtrage | 92% |
| **Acceleration** | Simulateur ACC (x,y,z) | 0.18 m/s² |
| **Sample Count** | PPG points par agrégation | 50 |

---

## ⚠️ Si rien n'apparaît

### Checklist

- [ ] Simulateur tourne : `python simulate_ppg_acc_mqtt.py`
- [ ] Mosquitto est actif : `docker ps | grep mosquitto`
- [ ] Ingestion-Service est actif : `docker ps | grep ingestion-service`
- [ ] Vitals-Management est actif : `docker ps | grep vitals-management`
- [ ] InfluxDB est actif : `docker ps | grep influxdb`
- [ ] Dashboard ouvrir dans le navigateur : `http://localhost:5173`
- [ ] DevTools Network montre `/api/vitals/stream/patient-001` (WebSocket)

### Pour Déboguer

```bash
# 1. Vérifier que MQTT reçoit les données
mosquitto_sub -h 127.0.0.1 -p 1883 -t "health/sensorData"

# 2. Vérifier les logs d'ingestion
docker logs ingestion-service -f | grep "MQTT"

# 3. Vérifier que Kafka a les messages
docker exec kafka-pfa rpk topic consume signals.aggregated --num 5

# 4. Vérifier que InfluxDB a les données
curl -s http://localhost:8086/health
```

---

## 📚 Documentation Complète

Pour plus de détails, voir : [DIAGNOSTIC_FLUX_PPG_ACC.md](DIAGNOSTIC_FLUX_PPG_ACC.md)

