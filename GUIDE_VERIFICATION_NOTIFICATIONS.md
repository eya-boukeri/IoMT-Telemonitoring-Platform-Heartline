# Guide de Vérification - Pipeline des Notifications
## Après application des corrections

**Objectif**: Vérifier que les alertes circulent correctement de data-analytics-service → notification-service → dashboard

---

## 1. Vérifications préalables

### 1.1 Services Docker lancés

```bash
# Vérifier que tous les services sont en cours d'exécution
docker ps

# Résultat attendu: voir les containers:
# - mosquitto-pfa-v2
# - influxdb-pfa-v2
# - kafka-pfa-v2
# - postgres-pfa
# - keycloak-pfa
# - vitals-management-pfa
# - ingestion-service-pfa
# - api-gateway
# - medical-dashboard-pfa
# - notification-service-pfa
# - data-analytics-service-pfa  ← NE DOIT PAS ÊTRE ABSENT!
```

### 1.2 Vérifier Kafka

```bash
# Lister les topics Kafka
docker exec kafka-pfa-v2 rpk topic list

# ✅ Résultat attendu:
# medical-alerts         ← Les alertes de data-analytics
# vitals-data            ← Les données vitales brutes
# vitals-aggregated      ← Les données vitales agrégées
# signals.raw            ← Les signaux bruts (legacyy)
# signals.filtered       ← Les signaux filtrés (legacy)
# ... autres topics ...
```

### 1.3 Vérifier la connectivité InfluxDB

```bash
# Tester l'accès à InfluxDB
curl -s http://localhost:8088/api/v2/health | jq .

# ✅ Résultat attendu: {"status":"healthy"}
```

---

## 2. Vérifications des services

### 2.1 Notification Service - SSE disponible

```bash
# Vérifier l'état du service
curl http://localhost:9095/api/notifications/health | jq .

# ✅ Résultat attendu:
# {
#   "status": "UP",
#   "service": "notification-service",
#   "activeConnections": 0,
#   "totalNotifications": 0
# }
```

### 2.2 Vitals Management - Service actif

```bash
# Vérifier la santé
curl http://localhost:9090/health | jq .

# ✅ Résultat attendu: {"status":"UP"} ou status avec components
```

### 2.3 API Gateway - Routage

```bash
# Vérifier l'API gateway
curl http://localhost:8080/health | jq .

# ✅ Résultat attendu: réponse 200 OK
```

---

## 3. Test du flux d'alerte complet

### 3.1 Ouvrir le flux SSE dans le terminal

**Terminal 1 - Simuler un client SSE (patient-001)**:

```bash
curl -N http://localhost:9095/api/notifications/stream/patient-001
```

Vous devez voir:
```
:
data: {"event":"connected"}

# Puis rien jusqu'à la première alerte...
```

### 3.2 Publier une alerte de test dans Kafka

**Terminal 2 - Injecter une alerte test**:

```bash
# Créer une alerte test en JSON
cat > /tmp/alert.json << 'EOF'
{
  "alertId": "alert-test-001",
  "patientId": "patient-001",
  "patientName": "Test Patient",
  "alertType": "anomaly",
  "severity": "WARNING",
  "priority": "HIGH",
  "timestamp": "2025-04-20T14:30:00Z",
  "message": "Test anomaly detected for patient patient-001 with confidence 0.85",
  "detectionScore": 0.85,
  "heartRate": 95.5,
  "activity": 0.2
}
EOF

# Publier sur le topic medical-alerts
docker exec -i kafka-pfa-v2 rpk topic produce medical-alerts < /tmp/alert.json

# ✅ Résultat attendu: message publié avec succès
```

### 3.3 Vérifier la réception dans Terminal 1

**Terminal 1** doit maintenant afficher quelque chose comme:

```
:
data: {"event":"connected"}
data: {"alertId":"alert-test-001","patientId":"patient-001","alertType":"anomaly"...}
```

---

## 4. Test complet Dashboard

### 4.1 Ouvrir le dashboard

Naviguez vers: http://localhost:5173

### 4.2 Sélectionner un patient

- Attendre le chargement de la liste des patients
- Cliquer sur "patient-001"

### 4.3 Simuler une alerte

**Terminal 3 - Injecter une alerte CRITICAL**:

```bash
cat > /tmp/alert-critical.json << 'EOF'
{
  "alertId": "alert-critical-001",
  "patientId": "patient-001",
  "patientName": "Test Patient",
  "alertType": "arrhythmia",
  "severity": "CRITICAL",
  "priority": "URGENT",
  "timestamp": "2025-04-20T14:35:00Z",
  "message": "CRITICAL: Arrhythmia detected! Immediate attention required.",
  "detectionScore": 0.95,
  "heartRate": 156.0,
  "activity": 0.8
}
EOF

docker exec -i kafka-pfa-v2 rpk topic produce medical-alerts < /tmp/alert-critical.json
```

### 4.4 Vérifier le dashboard

✅ **Vous devez voir:**
- L'alerte apparaître dans la liste des alertes (< 1 sec)
- La sévérité affichée en rouge (CRITICAL)
- Un message clair sur l'anomalie détectée

---

## 5. Vérifications des logs

### 5.1 Logs du notification-service

```bash
docker logs notification-service-pfa | tail -50

# ✅ Rechercher:
# "📥 Alert received"
# "📡 Alert sent via SSE to patient patient-001"
# "📧 Alert sent via email"  (si configuré)
# "📱 Alert sent to fallback emergency phone"  (si CRITICAL)
```

### 5.2 Logs de data-analytics-service

```bash
docker logs data-analytics-service-pfa | tail -50

# ✅ Rechercher:
# "Data Analytics service started"
# "Alerte envoyée pour patient-XXX"
# "Aucun patient trouvé"  (normal si pas de données)
```

### 5.3 Logs de vitals-management

```bash
docker logs vitals-management-pfa | tail -50

# ✅ Rechercher:
# "Vital data pushed to SSE clients"
# "Kafka message received"
```

### 5.4 Vérifier les topics Kafka en temps réel

```bash
# Consumer group pour les alertes
docker exec kafka-pfa-v2 rpk group list

# ✅ Résultat attendu: voir "notification-group"

# Offset du groupe
docker exec kafka-pfa-v2 rpk group describe notification-group

# ✅ Résultat attendu: voir les offsets pour medical-alerts topic
```

---

## 6. Résolution des problèmes

### Problème: Aucune alerte n'apparaît au dashboard

**Checklist:**
1. Vérifier que `data-analytics-service-pfa` est lancé: `docker ps | grep data-analytics`
2. Vérifier que notification-service écoute `medical-alerts`: `docker logs notification-service-pfa | grep medical-alerts`
3. Vérifier les topics Kafka existent: `docker exec kafka-pfa-v2 rpk topic list`
4. Vérifier que Kafka est accessible: `docker logs kafka-pfa-v2`

### Problème: data-analytics-service ne démarre pas

```bash
# Vérifier les logs
docker logs data-analytics-service-pfa

# Problèmes possibles:
# - Module Python manquant (xgboost, influxdb-client)
# - Modèle ML manquant (/app/models/ppg_model.joblib)
# - InfluxDB inaccessible
```

### Problème: Kafka broker inaccessible

```bash
# Vérifier Kafka
docker logs kafka-pfa-v2

# Redémarrer
docker restart kafka-pfa-v2

# Attendre ~15 secondes, puis vérifier:
docker exec kafka-pfa-v2 rpk broker info
```

---

## 7. Test de charge (optionnel)

### Publier 10 alertes d'affilée

```bash
for i in {1..10}; do
  cat > /tmp/alert-$i.json << EOF
{
  "alertId": "alert-batch-$i",
  "patientId": "patient-001",
  "alertType": "test-batch",
  "severity": "WARNING",
  "message": "Batch alert #$i",
  "timestamp": "$(date -u +'%Y-%m-%dT%H:%M:%SZ')",
  "detectionScore": 0.75
}
EOF
  docker exec -i kafka-pfa-v2 rpk topic produce medical-alerts < /tmp/alert-$i.json
  sleep 0.5
done
```

Vérifier que:
- Toutes les alertes sont reçues par notification-service
- Le dashboard affiche toutes les 10 alertes
- Pas d'erreur dans les logs

---

## 8. Statistiques de monitoring

### Compter les alertes publiées

```bash
# Nombre total de messages dans medical-alerts
docker exec kafka-pfa-v2 rpk topic info medical-alerts

# ✅ Résultat attendu: voir le nombre de messages dans "Leader high water mark"
```

### Vérifier les connexions SSE actives

```bash
curl http://localhost:9095/api/notifications/stats | jq .

# ✅ Résultat attendu:
# {
#   "total": 15,              ← nombre total de notifications
#   "activeConnections": 1    ← nombre de clients SSE connectés
# }
```

---

## 9. Nettoyage des données de test

```bash
# Arrêter tous les containers
docker-compose down

# Nettoyer les volumes (attention: supprime les données!)
docker volume prune -f

# Redémarrer
docker-compose up -d
```

---

## ✅ Checklist de validation finale

- [ ] Data-analytics-service est lancé en Docker
- [ ] Kafka topic `medical-alerts` existe
- [ ] notification-service reçoit les messages de `medical-alerts`
- [ ] SSE fonctionne (`/api/notifications/stream/{patientId}`)
- [ ] Une alerte test apparaît dans le dashboard < 1 sec après publication
- [ ] Les logs montrent le flux complet
- [ ] Pas d'erreurs de déserialisation Kafka
- [ ] Pas d'erreurs de connexion InfluxDB

---

**Si tout passe vert: ✅ Pipeline opérationnel!**
