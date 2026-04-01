# Architecture detaillee du projet actuel

## 1. Objectif du projet
Ce projet met en place une plateforme IoT medicale pour:
- recevoir des donnees vitales depuis une application smartwatch/mobile via MQTT,
- transformer et persister ces donnees dans InfluxDB,
- diffuser les evenements vers Kafka,
- afficher les donnees en quasi temps reel dans un dashboard React.

Le workspace est organise autour de 3 briques principales:
- `pfa-infrastructure/`: infrastructure locale Docker (Mosquitto, InfluxDB, Kafka/Redpanda),
- `vitals-management/`: backend Spring Boot (ingestion, transformation, stockage, API, streaming SSE),
- `medical-dashboard/`: frontend React + Vite (visualisation, stats, alertes).

## 2. Vue d ensemble de l architecture

### 2.1 Architecture logique
1. Producteur IoT (SensorApp Android) publie sur le topic MQTT `health/sensorData`.
2. Broker Mosquitto recoit les messages.
3. Backend Spring Boot (subscriber MQTT) consomme le topic.
4. Backend parse et convertit les payloads (ObservationData -> VitalData si necessaire).
5. Backend ecrit les mesures dans InfluxDB.
6. Backend publie les mesures normalisees dans Kafka (`vitals-events`).
7. Consumer Kafka backend pousse les donnees aux clients web via SSE.
8. Dashboard React consomme REST + SSE pour affichage live.

### 2.2 Architecture physique locale (ports)
- Mosquitto: container `mosquitto-pfa`
  - port interne: `1883`
  - port expose host: `1885`
- InfluxDB: container `influxdb-pfa`
  - port expose: `8088`
- Kafka/Redpanda: container `kafka-pfa`
  - port expose: `9093`
- Backend Spring Boot: `9090`
- Frontend Vite: `5173`

## 3. Description des composants

### 3.1 Infrastructure Docker (`pfa-infrastructure/`)
Fichier principal: `pfa-infrastructure/docker-compose.yml`

Services:
- `mosquitto` (image `eclipse-mosquitto:latest`)
- `influxdb` (image `influxdb:2.7`, bucket `medical_data`)
- `kafka` (image Redpanda)

Configuration broker: `pfa-infrastructure/mosquitto/config/mosquitto.conf`
- `allow_anonymous true`
- `listener 1883 0.0.0.0`
- persistence desactivee (`persistence false`)

### 3.2 Backend Spring Boot (`vitals-management/`)

#### Stack
- Spring Boot 3.5.9
- Spring Web
- Spring Integration MQTT
- Spring Kafka
- Client Java InfluxDB

#### Config principale
Fichier: `vitals-management/src/main/resources/application.properties`
- `server.port=9090`
- MQTT broker: `tcp://127.0.0.1:1885`
- topic MQTT: `health/sensorData`
- Kafka bootstrap: `localhost:9093`
- topic vitals: `vitals-events`
- InfluxDB URL: `http://localhost:8088`

#### Modules backend
- `config/MqttConfig.java`
  - configure client MQTT subscriber,
  - reception et traitement asynchrone via `ExecutorChannel`,
  - parsing tolérant JSON,
  - deduplication (TTL configurable),
  - publication Kafka apres ecriture InfluxDB.
- `service/InfluxDBService.java`
  - ecriture des mesures (`measurement=vitals`),
  - sauvegarde payload brut (`measurement=vitals_raw`) en cas d erreur,
  - requetes Flux pour historique/latest/stats/patients.
- `service/KafkaVitalsConsumer.java`
  - consomme `medical-alerts`, `vitals-topic`, `vitals-events`,
  - push SSE via `VitalStreamService`.
- `service/VitalStreamService.java`
  - gestion des connexions SSE par patient + global,
  - diffusion d evenements et nettoyage des emitters.
- `controller/VitalsController.java`
  - expose API REST et endpoints SSE.

### 3.3 Frontend React (`medical-dashboard/`)

#### Stack
- React 19
- Vite
- Recharts (graphiques)
- lucide-react (icones)

#### Fonctionnement
- recupere les patients via REST,
- charge historique + stats,
- ouvre une connexion SSE par patient,
- maintient une fenetre de donnees recente (jusqu a 50 points),
- calcule des alertes UI (FC, qualite PPG, mouvement, tension).

#### Proxy et reseau
Fichier: `medical-dashboard/vite.config.js`
- proxy `/api` vers backend `http://localhost:9090`.
- SSE utilise URL complete backend (`http://localhost:9090/api/vitals`).

## 4. Modeles de donnees et transformation

### 4.1 Payload d entree (ObservationData)
Modele: `vitals-management/src/main/java/com/medtech/vitalsmanagement/model/ObservationData.java`
- `patientId`
- `startTime`, `endTime`
- `ppgData` (formats legacy et timestamp dynamique)
- `accelerometerData` (formats legacy et timestamp dynamique)

### 4.2 Modele interne/normalise (VitalData)
Modele: `vitals-management/src/main/java/com/medtech/vitalsmanagement/model/VitalData.java`
- mesures principales: `heartRate`, `bloodPressure...`, metriques PPG/ACC
- metriques enrichies:
  - HRV (`heartRateMin`, `heartRateMax`, `heartRateVariability`, `detectedPeaks`),
  - statistiques PPG (`ppgGreen*`, `ppgRed*`, `ppgDataPoints`),
  - activite accelerometre (`accelerometerMagnitudeAverage`, etc.),
  - qualite signal (`signalQuality`, `signalQualityScore`).

### 4.3 Pipeline de conversion (dans `MqttConfig`)
1. Parse MQTT payload en `ObservationData` (si possible), sinon `VitalData`.
2. Si ObservationData:
   - extraction fenetre temporelle,
  - traitement PPG (stats + estimation HR/HRV),
   - traitement accelerometre (magnitude, variance),
   - scoring qualite signal.
3. Validation minimale (presence `patientId`, dedup selon timestamp/patient).
4. Sauvegarde InfluxDB.
5. Publication Kafka.

## 5. API exposee

### 5.1 REST (`/api/vitals`)
- `GET /history/{patientId}`
- `GET /latest/{patientId}`
- `GET /recent`
- `GET /stats/{patientId}`
- `GET /patients`

### 5.2 Streaming SSE
- `GET /stream/{patientId}`: flux patient
- `GET /stream/global`: flux global
- `GET /stream/status`: etat des streams

## 6. Deroulement technique de bout en bout

### 6.1 Sequence nominale
1. Les capteurs publient un JSON sur `health/sensorData`.
2. Mosquitto le distribue au subscriber backend.
3. `MqttConfig.handler()` recoit le message.
4. Le payload est parse puis normalise en `VitalData`.
5. `InfluxDBService.saveVitalData()` ecrit la mesure.
6. Si succes: publication Kafka (`vitals-events`).
7. `KafkaVitalsConsumer` lit l evenement et appelle `VitalStreamService.broadcastVital()`.
8. Les clients SSE recoivent les mises a jour.
9. Le dashboard met a jour graphiques, stats visibles et alertes.

### 6.2 Fallbacks et robustesse
- Payload vide: ignore.
- Parse impossible: sauvegarde brute dans `vitals_raw`.
- Echec ecriture InfluxDB: sauvegarde brute et pas de publication Kafka.
- Dedup activee pour limiter doublons MQTT.
- Reconnexion MQTT auto (Paho).

## 7. Phases implementees (etat actuel)

### Phase 1 - Fondation infrastructure locale
- Docker compose avec Mosquitto, InfluxDB, Kafka/Redpanda.
- Mapping ports et initialisation InfluxDB.

### Phase 2 - Ingestion IoT MQTT
- Subscriber Spring Integration MQTT.
- Topic de production `health/sensorData`.
- Logging detaille et gestion d erreurs.

### Phase 3 - Normalisation et enrichissement donnees
- Support double format payload capteurs.
- Conversion ObservationData -> VitalData enrichi.
- Calculs de qualite signal et metriques derivees.

### Phase 4 - Persistance time-series
- Ecriture dans InfluxDB (`vitals`).
- Endpoints de requete historique/latest/stats/patients.

### Phase 5 - Event streaming et distribution
- Publication Kafka apres persistance.
- Consumer Kafka vers SSE.
- Gestion multi-clients SSE (patient/global).

### Phase 6 - Visualisation dashboard medical
- Interface React pour suivi patient.
- Graphiques temps reel et alertes UI.
- Recuperation REST + tentative live via SSE.

### Phase 7 - Outillage diagnostic
- Documentation integration MQTT (`INTEGRATION_GUIDE.md`, `MQTT_DIAGNOSTIC.md`).
- Script de test automatise (`test-mqtt.ps1`).

## 8. Forces de l architecture actuelle
- Separation claire infra / backend / frontend.
- Backbone evenementiel (MQTT + Kafka) adapte au temps reel.
- Time-series DB adaptee aux donnees vitales.
- API REST + SSE pour usages dashboard.
- Mecanismes de resilience (dedup, fallback payload brut, reconnection).

## 9. Limites et points d attention (etat actuel)
1. Securite des secrets:
- token InfluxDB present en clair dans `application.properties`.
- recommandation: variables d environnement + secrets manager.

2. Coherence version Java:
- `pom.xml` declare `java.version=25` mais compilation forcee en 17.
- recommandation: aligner proprietes Maven.

3. Persistance partielle des champs enrichis:
- `InfluxDBService.saveVitalData()` ecrit surtout les metriques principales,
- plusieurs champs enrichis de `VitalData` ne sont pas encore ecrits dans InfluxDB.

4. SSE cote frontend:
- le backend envoie des evenements nommes,
- le frontend ecoute surtout `onmessage`.
- verifier la reception effective des events nommes via `addEventListener(...)`.

5. Structure de repo:
- presence d un sous dossier `vitals-management/vitals-management/` a clarifier (copie/ancien code).

6. Tests automatises:
- tests actuellement minimaux (`contextLoads`).
- peu de couverture sur parsing, conversion, et flux end-to-end.

## 10. Demarrage recommande (ordre d execution)
1. Demarrer infra:
- `cd pfa-infrastructure`
- `docker-compose up -d`

2. Demarrer backend:
- `cd vitals-management`
- `./mvnw.cmd spring-boot:run`

3. Demarrer frontend:
- `cd medical-dashboard`
- `npm install`
- `npm run dev`

4. Injecter des donnees capteurs:
- via application mobile,
- ou via script `test-mqtt.ps1`.

## 11. Proposition de prochaines phases
- Phase 8: securisation (auth MQTT, auth API, secrets, CORS env-specifique).
- Phase 9: observabilite (metrics, traces, dashboards ops, DLQ Kafka).
- Phase 10: fiabilite data (schema validation, contrat evenement, retries).
- Phase 11: tests (unitaires conversion, integration MQTT/Kafka/Influx, e2e dashboard).
- Phase 12: industrialisation (profiles env dev/stage/prod, CI/CD, conteneurisation backend/frontend).

---
Document cree selon l etat actuel du code et des fichiers de configuration presents dans ce workspace.
