# Guide Complet - Tests de la Plateforme PFA

## 🎯 Objectif
Vérifier que la chaîne complète de traitement fonctionne:
- **MQTT** → Réception données brutes patients
- **Kafka** → Transport de données
- **InfluxDB** → Stockage séries temporelles  
- **Data Analytics** → Détection anomalies
- **Notifications** → Envoi alertes
- **Dashboard** → Visualisation

---

## 📋 Phase 1: Préparation (5 minutes)

### ✅ Étape 1 - Vérifier que tout fonctionne
```powershell
cd C:\Users\USER\Desktop\pfa-main\pfa-main\pfa-infrastructure
.\diagnose-issues.ps1
```

**Si des ❌ s'affichent:** Redémarrer Docker Compose
```powershell
docker-compose -f docker-compose.yml restart
Start-Sleep 30  # Attendre 30 secondes
.\diagnose-issues.ps1
```

### ✅ Étape 2 - Préparer VS Code pour 7 terminaux
1. **Ouvrir 7 terminaux PowerShell:**
   - Ctrl+` (Backtick) pour ouvrir le 1er terminal
   - Cliquer "+" pour chaque nouveau terminal
   - Total: 7 terminaux horizontalement ou en grid

2. **Arranger les terminaux visibles** (View → Terminal → Split Terminal multiple fois)

### ✅ Étape 3 - Démarrer les 7 moniteurs

**Dans chaque terminal, copier-coller la commande correspondante:**

---

## 🟢 Phase 2: Lancer les 7 Moniteurs (2 minutes)

### Terminal T1 - MQTT Subscriber
```powershell
cd pfa-infrastructure
docker exec -it mosquitto-pfa-v2 mosquitto_sub -t "vitals/#" -v
```
**Montre:** Messages bruts reçus par MQTT

---

### Terminal T2 - Ingestion Service Logs
```powershell
docker logs ingestion-service-pfa --tail 30 -f
```
**Montre:** Parsing JSON et traitement des données

---

### Terminal T3 - Kafka Raw Vitals
```powershell
docker exec kafka-pfa-v2 rpk topic consume vitals-raw --brokers localhost:9092
```
**Montre:** Données après ingestion, avant analyse

---

### Terminal T4 - Kafka Medical Alerts
```powershell
docker exec kafka-pfa-v2 rpk topic consume medical-alerts --brokers localhost:9092
```
**Montre:** Alertes générées par l'analyse (NORMAL, WARNING, CRITICAL)

---

### Terminal T5 - Data Analytics Service
```powershell
docker logs data-analytics-service-pfa --tail 20 -f
```
**Montre:** Détection d'anomalies et publication d'alertes

---

### Terminal T6 - Notification Service
```powershell
docker logs notification-service-pfa --tail 20 -f
```
**Montre:** Envoi d'emails aux médecins

---

### Terminal T7 - InfluxDB Latest Data
```powershell
while ($true) {
    $q='from(bucket:"medical_data") |> range(start: -1m) |> filter(fn:(r)=>r._measurement=="vitals") |> last()'
    $b=@{query=$q}|ConvertTo-Json
    try {
        $response = Invoke-RestMethod -Method POST -Uri "http://localhost:8088/api/v2/query?org=myorg" `
            -Headers @{Authorization="Token my-secret-token"} `
            -Body $b
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Latest InfluxDB:" -ForegroundColor Cyan
        $response | ConvertTo-Json -Depth 3
    } catch {
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Attente InfluxDB..." -ForegroundColor Gray
    }
    Start-Sleep 2
}
```
**Montre:** Dernières données stockées (avec timestamps)

---

## 🔴 Phase 3: Tests des Scénarios

### Option A - Automatique (Recommandé)
```powershell
# Dans un 8ème terminal
cd pfa-infrastructure
.\test-scenarios.ps1
```
Exécute automatiquement les deux scénarios avec les bonnes données.

### Option B - Manuel
Voir ci-dessous les payloads à copier-coller.

---

## 🟢 Scénario 1: Patient Normal

**Attendez que tous les 7 terminaux affichent les logs en continu.**

```powershell
# Terminal 8 - Dans pfa-infrastructure

# Mesure 1: Normal
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-normal/data" -m '{"patientId":"patient-normal","deviceId":"simulator","heartRate":72,"spo2":99,"temperature":36.6,"timestamp":1713600000000}'
Start-Sleep 3

# Mesure 2: Normal
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-normal/data" -m '{"patientId":"patient-normal","deviceId":"simulator","heartRate":75,"spo2":98,"temperature":36.5,"timestamp":1713600000000}'
Start-Sleep 3

# Mesure 3: Normal
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-normal/data" -m '{"patientId":"patient-normal","deviceId":"simulator","heartRate":70,"spo2":99,"temperature":36.7,"timestamp":1713600000000}'
Start-Sleep 3
```

### 📊 Observations attendues pour Scénario 1

| Terminal | Observation | Statut |
|----------|-------------|--------|
| **T1** | 3 messages JSON `vitals/patient-normal/data` | ✅ DOIT s'afficher |
| **T2** | `Processing vitals for patient patient-normal...` (3x) | ✅ DOIT s'afficher |
| **T3** | 3 messages dans `vitals-raw` | ✅ DOIT s'afficher |
| **T4** | Aucun message | ✅ DOIT être vide |
| **T5** | Pas de logs d'anomalie | ✅ DOIT être silencieux |
| **T6** | Pas d'envoi d'email | ✅ DOIT être silencieux |
| **T7** | Données avec valeurs normales | ✅ DOIT s'actualiser |

**Résultat:** ✅ Pas d'alerte = Comportement correct

---

## 🔴 Scénario 2: Patient Critique

```powershell
# Terminal 8

# Mesure 1: Normal
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":80,"spo2":97,"temperature":36.8,"timestamp":1713600000000}'
Start-Sleep 3

# Mesure 2: Anomalie légère
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":55,"spo2":94,"temperature":37.0,"timestamp":1713600000000}'
Start-Sleep 3

# Mesure 3: 🔴 CRITIQUE
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":155,"spo2":82,"temperature":39.2,"timestamp":1713600000000}'
Start-Sleep 3

# Mesure 4: Persistance critique
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":152,"spo2":80,"temperature":39.5,"timestamp":1713600000000}'
Start-Sleep 3
```

### 📊 Observations attendues pour Scénario 2

| Terminal | Observation | Timeline | Statut |
|----------|-------------|----------|--------|
| **T1** | 4 messages JSON reçus | 0-3s | ✅ |
| **T2** | Processing logs | 1-6s | ✅ |
| **T3** | 4 messages dans vitals-raw | 2-8s | ✅ |
| **T4** | 🔴 **Alerte JSON CRITICAL** | **10-15s** | 🔴 **CRITIQUE** |
| **T5** | `Anomaly detected` + `Publishing alert` | 8-15s | 🔴 **CRITIQUE** |
| **T6** | `Alert received` + `Email sent` | 12-20s | 🔴 **CRITIQUE** |
| **T7** | Données avec FC:155, SpO2:82 | 15-20s | ✅ |

**Résultat:** 🔴 Alerte CRITICAL = Comportement correct

### ⏱️ Timing attendu complet
```
T0   : Publier données anomales
T1-3 : MQTT reçoit (T1)
T2-5 : Ingestion traite (T2)
T5-8 : Kafka vitals-raw (T3)
T10-15: Data Analytics détecte (T5)
T12-20: Alerte Kafka (T4) + Email (T6)
```

---

## 📧 Vérification Email

1. **Ouvrir Gmail:** https://mail.google.com
2. **Compte:** `farah.attia21@gmail.com`
3. **Mot de passe:** (Vous le connaissez)
4. **Chercher l'email avec:**
   - **Sujet:** `[CRITICAL] Alerte: ANOMALY` OU `[CRITICAL] Alerte Médicale`
   - **Contient:** `patient-urgent`, valeurs de FC/SpO2, timestamp

**SI pas d'email après 30 secondes:**
- Vérifier les SPAM/Promotions
- Vérifier les logs de T6 pour erreurs d'envoi

---

## 🎨 Vérification Dashboard

1. **Ouvrir:** http://localhost:5173
2. **Se connecter:**
   - Email: `dr-farah`
   - Password: `farah1234`
3. **Sélectionner patient:** `patient-urgent`
4. **Vérifier:**
   - ✅ Graphique FC montre pic à 155 bpm
   - ✅ Graphique SpO2 montre creux à 82%
   - ✅ Alerte CRITICAL visible (rouge)
   - ✅ Timestamp correct

---

## 🔧 Dépannage - Si quelque chose ne fonctionne pas

### ❌ T1 - Pas de messages MQTT
```powershell
# Vérifier MQTT
docker logs mosquitto-pfa-v2 --tail 20
# Relancer
docker restart mosquitto-pfa-v2
```

### ❌ T2 - Erreurs JSON parsing
```powershell
# Vérifier ingestion
docker logs ingestion-service-pfa --tail 50
# Regarder exactement quelle donnée échoue
```

### ❌ T3 - Pas de données dans Kafka
```powershell
# Vérifier que le topic existe
docker exec kafka-pfa-v2 rpk topic list | grep vitals
# Créer le topic si absent
docker exec kafka-pfa-v2 rpk topic create vitals-raw
```

### ❌ T4/T5 - Pas d'alerte générée
```powershell
# Vérifier data-analytics logs
docker logs data-analytics-service-pfa --tail 100
# Chercher "Anomaly detected" ou erreurs Python
```

### ❌ T6 - Email non envoyé
```powershell
# Vérifier notification service logs
docker logs notification-service-pfa --tail 100
# Chercher "Email sent" ou erreurs SMTP/Brevo
```

### ❌ T7 - Pas de données InfluxDB
```powershell
# Vérifier que le bucket existe
docker exec influxdb-pfa influx bucket list
# Vérifier les données
curl -X POST "http://localhost:8086/api/v2/query" \
  -H "Authorization: Token my-secret-token" \
  -d '{"query":"from(bucket:\"medical_data\") |> range(start: -30m)"}'
```

---

## 📊 Checklist Finale

```
✅ Phase 1: Diagnostic
  ☐ .\diagnose-issues.ps1 ne montre pas d'erreur critique
  
✅ Phase 2: Monitoring actif
  ☐ T1 affiche des logs MQTT
  ☐ T2 affiche logs Ingestion
  ☐ T3 affiche messages Kafka
  ☐ T4 vide (en attente)
  ☐ T5 affiche logs Analytics
  ☐ T6 affiche logs Notification
  ☐ T7 affiche données InfluxDB
  
✅ Scénario 1: Patient normal
  ☐ T1-T3 reçoivent les données
  ☐ T4 reste vide (pas d'alerte)
  ☐ T6 silencieux (pas d'email)
  
✅ Scénario 2: Patient critique
  ☐ T4 affiche alerte CRITICAL
  ☐ T5 affiche "Anomaly detected"
  ☐ T6 affiche "Email sent"
  ☐ Email reçu dans farah.attia21@gmail.com
  ☐ Dashboard montre l'alerte et les pics
```

---

## 🎉 Si tout est ✅ 

**Votre plateforme fonctionne complètement!**

Vous pouvez now:
- ✅ Montrer la détection d'anomalies temps réel
- ✅ Montrer l'intégration MQTT-Kafka-InfluxDB
- ✅ Montrer les alertes critiques instantanées
- ✅ Montrer les notifications email
- ✅ Faire la démo du dashboard

**Prêt pour la soutenance! 🚀**

---

**Créé le 22 Avril 2026**
**Projet: PFA - Plateforme de Télésurveillance Médicale**
