# Guide explicatif complet du projet platformeIOT

## 1. Objectif du projet

platformeIOT est une plateforme IoT medicale orientee surveillance de patients.
Elle collecte des signaux vitaux, les traite, les stocke, detecte des alertes et les expose en temps reel sur un dashboard.

Objectifs fonctionnels principaux:
- ingestion de donnees capteurs via MQTT,
- persistance time-series des mesures vitales,
- bus evenementiel pour decoupler les services,
- diffusion temps reel vers le front,
- gestion d alertes (SSE, email, SMS),
- securisation d acces via API Gateway + Keycloak.

## 2. Vue d ensemble de l architecture

### 2.1 Composants

- Infrastructure locale (Docker): Mosquitto, InfluxDB, Redpanda/Kafka, PostgreSQL, pgAdmin, Keycloak.
- Microservices Java (Spring Boot):
  - api-gateway,
  - ingestion-service,
  - vitals-management,
  - notification-service.
- Frontend: medical-dashboard (React + Vite).
- Service ML Python: data-analytics-service (detection d anomalies cardiaques).

### 2.2 Flux principal des donnees

1. Une application capteur publie des mesures sur MQTT (topic health/sensorData).
2. ingestion-service recoit et preprocess les signaux, stocke le brut en PostgreSQL, publie des events Kafka.
3. vitals-management consomme les donnees (MQTT/Kafka selon flux), calcule des metriques, ecrit dans InfluxDB, diffuse en SSE.
4. data-analytics-service lit InfluxDB, extrait des features PPG, applique un modele ML, publie des alertes sur Kafka (topic alerts).
5. notification-service consomme les alertes medicales Kafka, puis notifie via SSE/email/SMS.
6. api-gateway expose les endpoints unifies et applique la couche de securite JWT (Keycloak).
7. medical-dashboard consomme les APIs et flux SSE pour affichage temps reel.

## 3. Structure du repository

### 3.1 Parent Maven

Le fichier racine pom.xml declare un parent multi-module:
- api-gateway
- ingestion-service
- vitals-management
- notification-service

### 3.2 Dossiers principaux

- api-gateway: point d entree API, routage et securite.
- ingestion-service: ingestion MQTT, stockage brut SQL, publication Kafka.
- vitals-management: logique vitale, enrichissement metriques, InfluxDB, SSE.
- notification-service: diffusion alertes multi-canaux.
- medical-dashboard: interface web de supervision.
- data-analytics-service: inferencing ML PPG et emission d alertes.
- pfa-infrastructure: docker-compose, configs broker, init SQL.
- docs racine: guides d architecture, integration, diagnostic, rapport medical.
- scripts racine: simulation patient, tests MQTT, requetes utilitaires.

## 4. Details par service

## 4.1 pfa-infrastructure

Fichier central: pfa-infrastructure/docker-compose.yml

Services et ports exposes:
- Mosquitto: 1885 (host) vers 1883 (container), WebSocket 9002.
- InfluxDB 2.7: 8088 (host) vers 8086 (container).
- Redpanda (Kafka): 9093.
- PostgreSQL: 5432.
- pgAdmin: 5050.
- Keycloak: 8180 (host) vers 8080 (container).
- vitals-management: 9090.
- ingestion-service: 8081.
- api-gateway: 8080.
- medical-dashboard: 5173.
- notification-service: 9095.

Variables notables:
- InfluxDB initialise avec org myorg, bucket medical_data, token my-secret-token.
- Postgres initialise via pfa-infrastructure/postgres/init.sql.
- Keycloak admin: admin/admin123 (en local dev).

## 4.2 api-gateway (Spring Cloud Gateway)

Role:
- unifier les endpoints backend,
- appliquer securite OAuth2 Resource Server JWT,
- centraliser CORS et observabilite gateway.

Routes configurees (application.yml):
- /api/vitals/** -> vitals-management:9090
- /api/ingestion/** -> ingestion-service:8081
- /api/notifications/** -> notification-service:9095
- /health -> vitals-management:9090

Securite:
- issuer-uri et jwk-set-uri pointent vers Keycloak (service docker keycloak:8080).

Stack:
- Spring Boot 3.2.5
- Spring Cloud 2023.0.1
- Java 17

## 4.3 ingestion-service

Role:
- subscriber MQTT sur health/sensorData,
- stockage brut en PostgreSQL,
- pretraitement signal,
- publication des donnees vers Kafka.

Configuration cle (application.properties):
- server.port=8081
- datasource PostgreSQL: ingestion_db
- MQTT: tcp://127.0.0.1:1885
- Kafka bootstrap: kafka-pfa-v2:9092 (par defaut env)

Topics Kafka utilises:
- signals.raw
- signals.filtered
- signals.aggregated
- health-ingestion-events
- medical-alerts

Dependencies principales:
- Spring Web, Spring Data JPA, Spring Integration MQTT, Spring Kafka.

## 4.4 vitals-management

Role:
- ingestion et normalisation des mesures vitales,
- enrichissement metriques PPG/ACC,
- persistence InfluxDB,
- streaming SSE pour dashboard,
- publication events vitals.

Configuration cle:
- server.port=9090
- MQTT broker: tcp://127.0.0.1:1885
- Kafka bootstrap: kafka-pfa-v2:9092
- InfluxDB: http://localhost:8088
- Bucket: medical_data

Points techniques marquants:
- deduplication MQTT configurable (ttl),
- fallback sauvegarde brute en cas de parsing invalide,
- endpoints REST de consultation + flux SSE.

Endpoints usuels:
- /api/vitals/patients
- /api/vitals/latest/{patientId}
- /api/vitals/history/{patientId}
- /api/vitals/stats/{patientId}
- /api/vitals/stream/{patientId}
- /api/vitals/stream/global

Note de coherence build:
- pom declare java.version=25 mais compilation forcee a 17 via maven-compiler-plugin.

## 4.5 notification-service

Role:
- consommer les alertes medicales depuis Kafka,
- diffuser vers clients SSE,
- envoyer emails (SMTP) pour WARNING/CRITICAL,
- envoyer SMS (Twilio) pour CRITICAL,
- journaliser les notifications en PostgreSQL.

Configuration cle:
- server.port=9095
- datasource PostgreSQL: notification_db
- topic alerte: medical-alerts
- SMTP/Twilio parametrables par variables d environnement.

Endpoints usuels:
- /api/notifications/stream/{patientId}
- /api/notifications/health
- /api/notifications/stats

## 4.6 medical-dashboard (React)

Role:
- supervision des patients,
- affichage en temps reel des metriques,
- alertes visuelles.

Stack:
- React 19, Vite 7, Recharts, lucide-react.

Scripts:
- npm run dev
- npm run build
- npm run lint
- npm run preview

Mode de fonctionnement:
- appels REST pour l historique et les stats,
- SSE pour rafraichissement en continu.

## 4.7 data-analytics-service (Python)

Role:
- polling InfluxDB,
- extraction de features PPG,
- inference modele XGBoost,
- publication d alertes structurees vers Kafka (topic alerts).

Pipeline (main.py):
1. lister les devices actifs,
2. recuperer une fenetre PPG,
3. extraire 14 features,
4. predire anomalie + confiance,
5. appliquer seuil + cooldown,
6. publier alerte Kafka.

Configuration (app/config.py):
- KAFKA_BROKERS par defaut localhost:9093
- INFLUXDB_URL par defaut http://localhost:8088
- CONFIDENCE_THRESHOLD=0.7
- COOLDOWN_SECONDS=300

Dependances:
- xgboost, scikit-learn, scipy, numpy,
- influxdb-client,
- confluent-kafka.

Important:
- ce service n est pas encore branche dans pfa-infrastructure/docker-compose.yml.

## 5. Donnees, topics et persistance

## 5.1 MQTT

Topic principal:
- health/sensorData

Utilisation:
- publication depuis app capteur,
- consommation par ingestion-service et/ou vitals-management selon scenario.

## 5.2 Kafka

Topics observables dans le projet:
- signals.raw
- signals.filtered
- signals.aggregated
- health-ingestion-events
- vitals-events
- medical-alerts
- alerts (cote data-analytics-service)

## 5.3 Bases de donnees

- InfluxDB:
  - bucket medical_data,
  - mesures vitales et donnees time-series.
- PostgreSQL:
  - ingestion_db pour signaux bruts,
  - notification_db pour logs/recipients notifications.

## 6. Guide de demarrage complet

### 6.1 Prerequis

- Docker + Docker Compose
- Java 17+
- Maven
- Node.js + npm
- Python 3.10+ (pour data-analytics-service)

### 6.2 Demarrage full stack (recommande)

1. Infrastructure + microservices dockerises:
   - depuis pfa-infrastructure: docker compose up -d
2. Verification conteneurs:
   - docker ps
3. Tester gateway:
   - http://localhost:8080/actuator/health (si expose)
4. Ouvrir dashboard:
   - http://localhost:5173

### 6.3 Demarrage mixte (local dev)

1. Demarrer uniquement l infra:
   - pfa-infrastructure: docker compose up -d mosquitto influxdb kafka postgres keycloak
2. Lancer les services Java en local:
   - mvn spring-boot:run dans chaque module cible
3. Lancer dashboard:
   - medical-dashboard: npm install puis npm run dev
4. (Optionnel) lancer analytics Python:
   - data-analytics-service: installer requirements puis python -m app.main

## 7. Scripts utilitaires et diagnostic

Fichiers utiles:
- test-mqtt.ps1: test publication/souscription MQTT.
- simulate-patient.ps1: simulation de donnees patient vers topic health/sensorData.
- query_influx.py: interrogation rapide InfluxDB.
- MQTT_DIAGNOSTIC.md: procedure de diagnostic MQTT pas a pas.
- INTEGRATION_GUIDE.md: integration smartwatch/backend detaillee.

## 8. APIs principales exposees

Via api-gateway (port 8080):
- /api/vitals/**
- /api/ingestion/**
- /api/notifications/**

Acces direct services (dev):
- vitals-management: http://localhost:9090
- ingestion-service: http://localhost:8081
- notification-service: http://localhost:9095

## 9. Securite et configuration

Etat actuel:
- JWT configure au niveau gateway (Keycloak).
- plusieurs secrets encore en clair dans les configs (dev/local).

Recommandations prioritaires:
- externaliser tous les secrets via variables d environnement,
- ajouter une politique RBAC explicite sur les routes sensibles,
- durcir CORS (origines strictes par environnement),
- eviter toute credentielle de production dans les fichiers versionnes.

## 10. Risques techniques identifies

- Incoherence version Java dans vitals-management (propriete 25 vs compilation 17).
- data-analytics-service non integre au compose principal.
- overlap fonctionnel potentiel entre ingestion-service et vitals-management sur la consommation MQTT selon le mode d execution.
- couverture de tests a renforcer sur les flux end-to-end et la resilience erreurs.

## 11. Ordre de lecture conseille pour un nouvel arrivant

1. ARCHITECTURE_PROJET_ACTUELLE.md
2. INTEGRATION_GUIDE.md
3. pfa-infrastructure/docker-compose.yml
4. api-gateway/src/main/resources/application.yml
5. ingestion-service/src/main/resources/application.properties
6. vitals-management/src/main/resources/application.properties
7. notification-service/GUIDE_NOTIFICATION_SERVICE.md
8. MQTT_DIAGNOSTIC.md

## 12. Resume executif

Le projet est une plateforme microservices IoT medicale robuste pour l acquisition, le traitement, le stockage et la visualisation temps reel des donnees vitales.
La base technique est saine (event-driven, separation des responsabilites, stack moderne), avec encore des chantiers de finition pour une exploitation production: securite des secrets, harmonisation des flux, integration complete du service ML et renforcement des tests.
