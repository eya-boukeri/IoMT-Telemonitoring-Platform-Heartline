# 🔍 Guide de Diagnostic MQTT - Architecture Correcte

## 📋 Architecture cible:

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ANDROID PHONE (SensorApp)                 DESKTOP PC      │
│  ────────────────────────────────────────────────────      │
│                                                             │
│  CLIENT #1 (PUBLISHER)          BROKER (Mosquitto)         │
│  ──────────────────────         ────────────────           │
│  tcp://192.168.68.151:1885      Port 1885 (exposed)        │
│  → Publishes to:                Port 1883 (internal)       │
│    health/sensorData                                       │
│                                                             │
│                                        ↓                    │
│                                     BACKEND                │
│                                     ──────────             │
│                                 CLIENT #2 (SUBSCRIBER)     │
│                                 tcp://127.0.0.1:1885       │
│                                 Subscribes to:              │
│                                 health/sensorData          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## ✅ Test 1: Vérifier la connexion du Backend au Broker

**Attendu au démarrage du backend:**

```
═══════════════════════════════════════════════════════════════
🔌 MQTT CLIENT #1: BACKEND - MODE SUBSCRIBER (Réception)
  Broker URL: tcp://127.0.0.1:1885
   Client ID: vitals-management-backend-client-...
   Subscribe Topic: health/sensorData
   QoS Level: 1
═══════════════════════════════════════════════════════════════

─────────────────────────────────────────────────────────────
🔧 MQTT CLIENT FACTORY Configuration
  Broker URL: tcp://127.0.0.1:1885
   AutoReconnect: true
   CleanSession: true
   ConnectionTimeout: 10 sec
   KeepAliveInterval: 60 sec
─────────────────────────────────────────────────────────────

✅ MQTT Adapter configured - awaiting messages on topic: health/sensorData
```

---

## ✅ Test 2: Vérifier que Mosquitto reçoit les messages d'Android

### Terminal PowerShell (écoutez Mosquitto):

```powershell
# Étape 1: S'abonner au topic health/sensorData
docker exec -it mosquitto-pfa mosquitto_sub -h localhost -p 1883 -t "health/sensorData" -v

# Résultat attendu (quand Android envoie):
# health/sensorData {"patientId":"patient123","ppgData":[...],"accelerometerData":[...]}
```

**Si vous voyez des messages JSON** → ✅ Android envoie correctement  
**Si rien n'apparaît** → ❌ Android ne se connecte pas réellement

---

## ✅ Test 3: Simuler l'envoi depuis l'app Android (Manual Publish)

```powershell
# Publier un message test comme le fait SensorApp
docker exec -it mosquitto-pfa mosquitto_pub \
  -h localhost \
  -p 1883 \
  -t "health/sensorData" \
  -q 2 \
  -r \
  -m '{
    "patientId": "test-android-123",
    "startTime": "2026-03-04T10:30:00",
    "endTime": "2026-03-04T10:30:30",
    "ppgData": [
      {"timestamp": "2026-03-04T10:30:01", "green": 1234.5, "red": 987.3},
      {"timestamp": "2026-03-04T10:30:02", "green": 1240.2, "red": 990.1}
    ],
    "accelerometerData": [
      {"timestamp": "2026-03-04T10:30:01", "x": 0.1, "y": -0.9, "z": 0.2},
      {"timestamp": "2026-03-04T10:30:02", "x": 0.15, "y": -0.85, "z": 0.25}
    ]
  }'
```

**Attendu dans les logs du Backend:**

```
📨 ✅ MESSAGE MQTT REÇU!
   Topic: health/sensorData
   Payload Size: XXX bytes
   Headers: {...}

💾 ✅ SUCCESS: Données sauvegardées dans InfluxDB
   Patient: test-android-123
  HR: XX bpm, PPG: actif, ACC: actif
```

---

## ✅ Test 4: Procédure complète pas à pas

### Étape 1: Préparer l'environnement
```powershell
# Arrêter tous les services
docker-compose -f C:\Users\Admin\Desktop\platformeIOT\pfa-infrastructure\docker-compose.yml down

# Nettoyer
docker system prune -f

# Redémarrer
cd C:\Users\Admin\Desktop\platformeIOT\pfa-infrastructure
docker-compose up -d

# Attendre 10 secondes que Mosquitto démarre
Start-Sleep -Seconds 10

# Supprimer les messages retained précédents
docker exec -it mosquitto-pfa mosquitto_pub -h localhost -p 1883 -t "health/sensorData" -m "" -r
```

### Étape 2: Lancer le Backend avec logs visibles
```powershell
cd C:\Users\Admin\Desktop\platformeIOT\vitals-management

# En mode development avec logs
.\mvnw spring-boot:run -Dspring-boot.run.arguments="--logging.level.root=WARN --logging.level.com.medtech=DEBUG"

# Observez ces logs au démarrage:
# ✅ "MQTT CLIENT #1: BACKEND - MODE SUBSCRIBER"
# ✅ "MQTT Adapter configured"
```

### Étape 3: Tester avec un message manuel
```powershell
# Dans un terminal séparé
docker exec -it mosquitto-pfa mosquitto_pub \
  -h localhost -p 1883 -t "health/sensorData" -q 2 -r \
  -m '{"patientId":"test123","heartRate":75,"ppgGreenAverage":1200,"accelerometerMagnitudeAverage":0.85}'

# Vérifiez les logs du Backend:
# ✅ "MESSAGE MQTT REÇU!"
# ✅ "SUCCESS: Données sauvegardées"
```

### Étape 4: Lancer SensorApp et observer
```
1. Assurez-vous que l'IP Android est: tcp://192.168.68.151:1885
2. Lancez l'app et vérifiez "Statut MQTT: ✅ Connecté"
3. Commencez le tracking
4. Observez le terminal mosquitto_sub pour voir les messages
5. Vérifiez les logs du Backend pour voir l'arrivée des messages
```

---

## 🔧 Checkpoint: Configuration à vérifier

### Android SensorApp (MqttManager.kt):
```kotlin
// Doit écouter les ACKNOWLEDGEMENTS du broker
MqttManager.initialize(
    context, 
    serverUri = "tcp://192.168.68.151:1885",  // ✅ Correct (IP externe PC)
    clientId = "SensorApp-${Build.MODEL}"
)

// Doit publier avec QoS=2 et retained=true
MqttManager.publish(
    topic = "health/sensorData",    // ✅ Exact
    message = jsonData.toString(),
    qos = 2,                          // ✅ Garantie
    retained = true                   // ✅ Conserve message
)
```

### Backend Spring Boot (application.properties):
```properties
mqtt.broker.url=tcp://127.0.0.1:1885        # ✅ localhost (Docker container visible)
mqtt.topic.prefix=health/sensorData         # ✅ Topic exact
mqtt.qos=1                                  # ✅ At-least-once
logging.level.com.medtech=DEBUG             # ✅ Logs visibles
```

### Docker Mosquitto (docker-compose.yml):
```yaml
ports:
  - "1885:1883"  # ✅ Port 1885 exposé en externe, 1883 en interne
```

---

## ❌ Troubleshooting

### Problème: "MQTT Adapter configured" ne s'affiche pas
**Solution:** Vérifiez que `@Configuration` et `@Bean` sont présents

### Problème: "MESSAGE MQTT REÇU!" ne s'affiche pas
**Solution:** 
- ✓ Vérifiez le topic exact: `health/sensorData` (pas `health/sensorData/`)
- ✓ Vérifiez que le broker est accessible: `docker ps` montre mosquitto-pfa
- ✓ Vérifiez la connexion: `docker logs mosquitto-pfa`

### Problème: Android affiche "Connecté" mais rien n'arrive
**Solution:**
- ✓ Testez la connectivité réseau: ping `192.168.68.151` depuis le téléphone
- ✓ Testez avec MQTT Dashboard (app Android) pour confirmer la connectivité
- ✓ Vérifiez les logs Android via adb: `adb logcat | grep MQTT`

---

## 📊 Format des données - Validation

**Format attendu (ObservationData format):**
```json
{
  "patientId": "patient123",              // ✅ Obligatoire
  "startTime": "2026-03-04T10:30:00",     // ✅ ISO format
  "endTime": "2026-03-04T10:30:30",       // ✅ ISO format
  "ppgData": [
    {"green": 1234.5, "red": 987.3},
    {"timestamp": "2026-03-04T10:30:01", "green": 1234.5}
  ],
  "accelerometerData": [
    {"accelerometerPoint": {"x": 0.1, "y": -0.9, "z": 0.2}},
    {"timestamp": "2026-03-04T10:30:01", "x": 0.1, "y": -0.9, "z": 0.2}
  ]
}
```

**Conversion automatique:** Le backend convertit ce format en `VitalData` et sauvegarde dans InfluxDB. ✅

---

## ✅ Succès = Observer ces logs

```
📨 ✅ MESSAGE MQTT REÇU!
💾 ✅ SUCCESS: Données sauvegardées dans InfluxDB
   Patient: patient123
  HR: 75 bpm, PPG: 1200, ACC: 0.85
```

Si vous voyez ces logs → ✅ **L'architecture MQTT fonctionne correctement!**
