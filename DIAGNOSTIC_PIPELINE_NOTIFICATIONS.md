# Diagnostic Complet - Pipeline des Notifications
## Data Analytics → Notification Service → Dashboard

**Date**: 20 Avril 2026  
**Statut**: ⚠️ **PIPELINE DÉFAILLANT - Problèmes critiques détectés**

---

## 1. Vue d'ensemble du pipeline attendu

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PIPELINE DES NOTIFICATIONS                          │
└─────────────────────────────────────────────────────────────────────────────┘

Step 1: DATA ANALYTICS SERVICE (Python)
    ├─ Lit InfluxDB (PPG Window)
    ├─ Extraction features PPG
    ├─ Prédiction ML (XGBoost)
    └─ ✅ Publie sur topic Kafka: "alerts" (config.py ligne 16)
         │
         v
Step 2: KAFKA (Redpanda)
    ├─ Topic: ???
    └─ Consumer Group: notification-group
         │
         v
Step 3: NOTIFICATION SERVICE (Java)
    ├─ Consumer Kafka (AlertConsumer.java)
    ├─ Distribution multi-canal:
    │  ├─ SSE (temps réel)
    │  ├─ Email (WARNING/CRITICAL)
    │  └─ SMS (CRITICAL)
    └─ Persistence PostgreSQL (notification_logs)
         │
         v
Step 4: MEDICAL DASHBOARD (React)
    ├─ EventSource SSE
    └─ Affichage temps réel des alertes
```

---

## 2. 🔴 PROBLÈME CRITIQUE #1: Désalignement des topics Kafka

### Configuration détectée:

| Service | Topic Publié | Topic Écouté | Fichier |
|---------|-------------|--------------|---------|
| **data-analytics-service** | `alerts` | N/A | `app/config.py:16` |
| **notification-service** | N/A | `medical-alerts` | `application.properties:21` |
| **ingestion-service** | `medical-alerts` | N/A | `application.properties:24` |
| **vitals-management** | `medical-alerts` | `vitals-events`, `signals.aggregated` | `application.properties:9-10` |

### ❌ **Le problème:**

```python
# data-analytics-service publie sur:
TOPIC_OUT = os.getenv("KAFKA_TOPIC_OUT", "alerts")  ← ❌ MAUVAIS TOPIC

# notification-service écoute sur:
kafka.topic.alerts=medical-alerts  ← ❌ TOPIC DIFFÉRENT
```

**Résultat**: Les alertes générées par le service d'analyse ML ne sont **JAMAIS reçues** par le service de notification.

### ✅ **Solution:**

Aligner tous les services sur le même topic: `medical-alerts`

```bash
# Changement dans data-analytics-service
TOPIC_OUT = "medical-alerts"  (au lieu de "alerts")
```

---

## 3. 🟡 PROBLÈME #2: Configuration InfluxDB - URL mismatch

### Configuration détectée:

**data-analytics-service** (app/config.py:36):
```python
INFLUXDB_URL = os.getenv('INFLUXDB_URL', 'http://influxdb:8086')
```

**Problème**: Le service s'attend à `http://influxdb:8086` (nom du service Docker)  
**Situation réelle**: Dans docker-compose, InfluxDB est sur `http://localhost:8088` ou `http://influxdb:8086`

### 📋 **Vérification:**

- En **Docker Compose**: Le service `data-analytics-service` doit utiliser le nom du service: `http://influxdb:8086`
- En **localhost**: Doit être `http://localhost:8088`

**⚠️ Important:** Le service data-analytics-service **n'est pas encore dans docker-compose.yml** (confirmé dans GUIDE_COMPLET_PROJET.md ligne 207)

---

## 4. 🟡 PROBLÈME #3: data-analytics-service non déployé en Docker

Tiré de GUIDE_COMPLET_PROJET.md (ligne 207):

> "Important: ce service n est pas encore branche dans pfa-infrastructure/docker-compose.yml."

### Conséquences:

- ❌ Le service Python ne s'exécute pas dans le stack Docker
- ❌ Les alertes ne sont jamais générées
- ❌ Le pipeline de notifications est **INACTIF**

### ✅ **Solution:**

Ajouter le service au docker-compose.yml

---

## 5. 🟢 Pipeline SSE → Dashboard (Vérification)

### Configuration détectée:

**notification-service** (NotificationController.java):
```java
@GetMapping(value = "/stream/{patientId}", produces = "text/event-stream")
public SseEmitter streamNotifications(@PathVariable String patientId) {
    return sseService.createEmitter(patientId);
}
```

**medical-dashboard** (App.jsx:258-262):
```javascript
const notificationEventSource = new EventSource(
    `${NOTIFICATION_SSE_BASE_URL}/stream/${selectedPatient}`
);
notificationEventSource.addEventListener('alert', handleNotificationEvent);
```

✅ **Statut**: La connexion SSE est **correctement configurée**

### Flux SSE Vérifié:

```
notification-service ──SSE──> medical-dashboard (Browser)
    ↑
    │ AlertConsumer.consumeAlert()
    │ sseService.sendToPatient()
    │
Kafka: medical-alerts
```

---

## 6. 🟡 PROBLÈME #4: Redondance de listeners Kafka

**vitals-management** écoute AUSSI sur `medical-alerts`:

```java
@KafkaListener(
    topics = {"medical-alerts", "vitals-topic", "vitals-events"},
    groupId = "sse-stream-group",
    containerFactory = "kafkaListenerContainerFactory"
)
public void handleVitalEvent(String message) { ... }
```

### Problème:

- `medical-alerts` est destiné aux alertes (WARNING/CRITICAL/anomalies)
- `vitals-management` devrait écouter les **données vitales**, pas les alertes
- Cela crée de la confusion dans la sémantique des topics

### ✅ **Recommandation:**

Créer des topics distincts:
- `medical-alerts` → pour les alertes cliniques
- `vitals-data` → pour les données vitales brutes
- `ppg-processed` → pour les données PPG traitées

---

## 7. 📊 Flux de données réel (État actuel vs Attendu)

### État actuel (DÉFAILLANT):

```
┌────────────────────┐
│ data-analytics     │
│ ❌ INACTIVE        │  (pas en docker-compose)
│                    │
│ Publie sur: "alerts"
└────────────────────┘
        │
        ↓ (MESSAGE PERDU)
┌────────────────────────────────────────────┐
│ Kafka Broker (Redpanda)                    │
├────────────────────────────────────────────┤
│ Topic: "alerts"      [VIDE - aucun consumer]  │
│ Topic: "medical-alerts" [SANS PRODUCTEUR]    │
└────────────────────────────────────────────┘
        │
        ↓
┌────────────────────────────┐
│ notification-service       │
│ ❌ PAS DE MESSAGES REÇUS   │
│ Écoute: "medical-alerts"   │
└────────────────────────────┘
        │
        ↓ (SSE vide)
┌────────────────────────────┐
│ medical-dashboard          │
│ ❌ AUCUNE ALERTE AFFICHÉE  │
└────────────────────────────┘
```

### État attendu (CORRECT):

```
┌────────────────────┐
│ data-analytics     │
│ ✅ ACTIVE          │ (lancée via docker-compose)
│                    │
│ Publie sur: "medical-alerts"  ← ✅ CORRECT
└────────────────────┘
        │
        ↓
┌────────────────────────────────────────────┐
│ Kafka Broker (Redpanda)                    │
├────────────────────────────────────────────┤
│ Topic: "medical-alerts"  [ALERTES ACTIVES] │
└────────────────────────────────────────────┘
        │
        ↓
┌────────────────────────────────────────────┐
│ notification-service                       │
│ ✅ REÇOIT LES ALERTES                      │
│ ├─ SSE → Dashboard (temps réel)            │
│ ├─ Email → Médecin                         │
│ └─ SMS → Urgences                          │
└────────────────────────────────────────────┘
        │
        ↓
┌────────────────────────────────────────────┐
│ medical-dashboard                          │
│ ✅ AFFICHE LES ALERTES EN TEMPS RÉEL       │
└────────────────────────────────────────────┘
```

---

## 8. 🔧 Plan de correction par priorité

### **PRIORITÉ 1 (CRITIQUE) ⚠️**

#### 1.1 Fixer le topic Kafka dans data-analytics-service

**Fichier**: `data-analytics-service/app/config.py`

```python
# Ligne 16 - AVANT:
TOPIC_OUT = os.getenv("KAFKA_TOPIC_OUT", "alerts")

# APRÈS:
TOPIC_OUT = os.getenv("KAFKA_TOPIC_OUT", "medical-alerts")
```

#### 1.2 Ajouter data-analytics-service au docker-compose.yml

**Fichier**: `pfa-infrastructure/docker-compose.yml`

Ajouter ce service:

```yaml
data-analytics-service:
    build:
      context: ../data-analytics-service
      dockerfile: dockerfile
    container_name: data-analytics-pfa
    depends_on:
      - kafka-pfa-v2
      - influxdb
    environment:
      KAFKA_BROKERS: kafka-pfa-v2:9092
      KAFKA_TOPIC_OUT: medical-alerts
      INFLUXDB_URL: http://influxdb:8086
      INFLUXDB_TOKEN: my-secret-token
      INFLUXDB_ORG: myorg
      INFLUXDB_BUCKET: medical_data
      CONFIDENCE_THRESHOLD: 0.7
      COOLDOWN_SECONDS: 300
    networks:
      - pfa-network
    restart: unless-stopped
```

---

### **PRIORITÉ 2 (IMPORTANT) 🟡**

#### 2.1 Nettoyer la redondance des topics Kafka

**Fichier**: `vitals-management/src/main/resources/application.properties`

```properties
# AVANT:
kafka.topic.vitals=vitals-events
kafka.topic.aggregated=signals.aggregated
spring.kafka.template.default-topic=medical-alerts

# APRÈS:
kafka.topic.vitals=vitals-data
kafka.topic.aggregated=vitals-aggregated
spring.kafka.template.default-topic=vitals-aggregated

# Ne pas écouter medical-alerts si ce n'est pas pertinent
```

Mettre à jour le listener dans [KafkaVitalsConsumer.java](vitals-management/src/main/java/com/medtech/vitalsmanagement/service/KafkaVitalsConsumer.java):

```java
// AVANT:
@KafkaListener(
    topics = {"medical-alerts", "vitals-topic", "vitals-events"},
    groupId = "sse-stream-group",
    ...
)

// APRÈS:
@KafkaListener(
    topics = {"vitals-data", "vitals-aggregated"},
    groupId = "sse-stream-group",
    ...
)
```

---

### **PRIORITÉ 3 (OPTIMISATION) 🔧**

#### 3.1 Aligner InfluxDB URL

Vérifier que `data-analytics-service` peut atteindre InfluxDB:

**En Docker Compose**: `http://influxdb:8086`  
**En local**: `http://localhost:8088`

---

## 9. 🧪 Checklist de vérification après corrections

- [ ] **Kafka topics corrects**
  - [ ] Vérifier: `docker exec kafka-pfa-v2 rpk topic list | grep medical-alerts`
  - [ ] Doit afficher: `medical-alerts`

- [ ] **data-analytics-service lancé**
  - [ ] Vérifier: `docker ps | grep data-analytics`
  - [ ] Doit voir le container actif

- [ ] **Kafka Producer actif**
  - [ ] Lancer: `docker exec kafka-pfa-v2 rpk topic consume medical-alerts`
  - [ ] Doit montrer les messages en temps réel

- [ ] **notification-service reçoit les alertes**
  - [ ] Vérifier logs: `docker logs notification-service | grep "Alert received"`

- [ ] **SSE connecté au dashboard**
  - [ ] Ouvrir: `http://localhost:5173` (medical-dashboard)
  - [ ] Vérifier les connexions actives: `curl http://localhost:9095/api/notifications/health`

- [ ] **Alertes affichées en temps réel**
  - [ ] Simuler une alerte
  - [ ] Vérifier qu'elle apparaît dans le dashboard en < 1 sec

---

## 10. 📋 Fichiers impactés

| Fichier | Action | Priorité |
|---------|--------|----------|
| `data-analytics-service/app/config.py` | Changer topic `alerts` → `medical-alerts` | ⚠️ P1 |
| `pfa-infrastructure/docker-compose.yml` | Ajouter service data-analytics-service | ⚠️ P1 |
| `vitals-management/application.properties` | Réorganiser topics Kafka | 🟡 P2 |
| `vitals-management/src/.../KafkaVitalsConsumer.java` | Mettre à jour listeners | 🟡 P2 |

---

## 11. 📝 Recommandations supplémentaires

1. **Observabilité**: Ajouter une métrique Prometheus pour tracker les alertes publiées/consommées
2. **Résilience**: Implémenter une DLQ (Dead Letter Queue) pour les messages non-traités
3. **Sécurité**: Activer SASL/SSL pour Kafka en production
4. **Documentation**: Créer un diagramme de flow complet avec les topics Kafka

---

**Fin du diagnostic**
