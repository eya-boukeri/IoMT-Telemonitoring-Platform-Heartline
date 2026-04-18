# Guide Explicatif - Notification Service

## 1. Objectif du microservice

Le microservice `notification-service` centralise la diffusion des alertes medicales.
Il recoit les alertes depuis Kafka, puis envoie les notifications selon la severite via:

1. SSE (temps reel) vers le dashboard patient.
2. Email (SMTP) pour les alertes de niveau WARNING et CRITICAL.
3. SMS (Twilio) pour les alertes CRITICAL.

Il enregistre aussi un journal des envois en base PostgreSQL.

---

## 2. Position dans l architecture

Flux simplifie:

1. `ingestion-service` detecte une alerte et publie dans Kafka (`medical-alerts`).
2. `notification-service` consomme le message Kafka.
3. Le service diffuse l alerte via SSE, puis escalade vers Email/SMS selon la severite.
4. Le service persiste un log dans `notification_logs`.

---

## 3. Composants principaux

### 3.1 Consumer Kafka

Fichier: `src/main/java/com/medtech/notification/consumer/AlertConsumer.java`

Responsabilites:

1. Lire les messages depuis le topic configure.
2. Convertir JSON -> objet `Alert`.
3. Envoyer en SSE au patient.
4. Selectionner les destinataires (base ou fallback).
5. Envoyer email si severite WARNING/CRITICAL.
6. Envoyer SMS si severite CRITICAL.
7. Sauvegarder un log en base.

### 3.2 API REST

Fichier: `src/main/java/com/medtech/notification/controller/NotificationController.java`

Endpoints:

1. `GET /api/notifications/stream/{patientId}`
   Ouvre un flux SSE pour un patient.
2. `GET /api/notifications/health`
   Etat du service + connexions actives + nombre de notifications.
3. `GET /api/notifications/stats`
   Statistiques globales simplifiees.

### 3.3 Service SSE

Fichier: `src/main/java/com/medtech/notification/service/SseService.java`

Ce service:

1. Cree les `SseEmitter` par patient.
2. Gere la fin de connexion et le timeout.
3. Envoie les alertes en direct au front.

### 3.4 Service Email

Fichier: `src/main/java/com/medtech/notification/service/EmailService.java`

Points importants:

1. Utilise `JavaMailSender`.
2. Envoi asynchrone (`@Async`).
3. Desactive automatiquement le canal email si `spring.mail.username` est vide.

### 3.5 Service SMS

Fichier: `src/main/java/com/medtech/notification/service/SmsService.java`

Points importants:

1. Utilise Twilio si les credentials sont presents.
2. Si Twilio non configure, le service journalise un fallback sans planter le flux.

### 3.6 Service destinataires

Fichier: `src/main/java/com/medtech/notification/service/RecipientService.java`

Ce service:

1. Cherche les destinataires actifs en base (`notification_recipients`).
2. Filtre selon la priorite de l alerte.
3. Utilise des destinataires par defaut si aucun resultat en base.

---

## 4. Modele de donnees

### 4.1 Table notification_logs

Entite: `src/main/java/com/medtech/notification/model/NotificationLog.java`

Champs principaux:

1. `alert_id`
2. `patient_id`
3. `status`
4. `sent_at`
5. `created_at`

### 4.2 Table notification_recipients

Entite: `src/main/java/com/medtech/notification/model/NotificationRecipient.java`

Champs principaux:

1. `patient_id`
2. `recipient_type`
3. `email`
4. `phone`
5. `priority_level`
6. `active`

---

## 5. Configuration (application.properties)

Fichier: `src/main/resources/application.properties`

Sections critiques:

1. Serveur
   `server.port=9095`
2. Base PostgreSQL
   `spring.datasource.*`
3. Kafka
   `spring.kafka.*` + `kafka.topic.alerts=medical-alerts`
4. SMTP
   `spring.mail.*`
5. Twilio
   `twilio.*`
6. Valeurs de fallback
   `notification.default.*`

---

## 6. Regles metier de notification

1. Toute alerte est poussee en SSE au patient.
2. WARNING: envoi Email.
3. CRITICAL: envoi Email + SMS.
4. Si aucun numero SMS valable n est trouve, fallback vers `notification.default.emergency.phone`.

---

## 7. Lancement local

Depuis `notification-service`:

```bash
mvn clean package
mvn spring-boot:run
```

Dependances attendues:

1. Kafka accessible.
2. PostgreSQL accessible et base `notification_db` disponible.
3. Optionnel: SMTP/Twilio selon les canaux actives.

---

## 8. Verification rapide

1. Verifier la sante:
   `GET http://localhost:9095/api/notifications/health`
2. Ouvrir un stream SSE:
   `GET http://localhost:9095/api/notifications/stream/patient-001`
3. Publier un message de test dans Kafka topic `medical-alerts`.
4. Verifier:
   - reception SSE cote client,
   - envoi email/sms selon severite,
   - creation d une entree dans `notification_logs`.

---

## 9. Limites actuelles et recommandations

1. Securite API: prevoir JWT/Keycloak pour les endpoints REST en production.
2. Resilience Kafka: ajouter retry explicite + DLQ pour les erreurs de deserialisation.
3. Observabilite: ajouter metriques (Micrometer/Prometheus) et traces distribuees.
4. Journalisation: enrichir `notification_logs` avec le canal et le destinataire reel.

---

## 10. Resume

`notification-service` est un microservice de diffusion d alertes multi-canaux, centre sur Kafka + SSE + Email + SMS, avec persistance SQL des traces et des destinataires.
Il est adapte pour une architecture event-driven medicale et peut etre renforce facilement pour un usage production (securite, robustesse, observabilite).
