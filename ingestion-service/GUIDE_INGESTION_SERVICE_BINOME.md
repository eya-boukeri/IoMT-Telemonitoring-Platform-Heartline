# Guide Simple - ingestion-service (a envoyer au binome)

## 1. C est quoi ce microservice ?

`ingestion-service` est la porte d entree des donnees capteurs.

Il fait 4 choses:
1. recoit les messages MQTT,
2. sauvegarde le message brut en base PostgreSQL,
3. nettoie/filtre le signal,
4. publie les donnees vers Kafka.

## 2. Sa place dans l architecture

Flux simplifie:

Capteur/App mobile
-> Mosquitto (MQTT)
-> ingestion-service
-> PostgreSQL (stockage brut)
-> Kafka (raw, filtered, aggregated)
-> vitals-management (consomme aggregated)
-> dashboard

Important:
- `ingestion-service` n est pas encore lance dans docker-compose.
- On le lance en local avec Maven.

## 3. Config actuelle (locale)

Fichier: `src/main/resources/application.properties`

- Port service: `8081`
- MQTT broker: `tcp://127.0.0.1:1885`
- MQTT topic: `health/sensorData`
- Kafka: `localhost:9093`
- PostgreSQL: `localhost:5432/ingestion_db`

Endpoint utile:
- `GET /api/ingestion/health`

## 4. Comment le lancer rapidement

1. Demarrer l infrastructure:

```powershell
cd pfa-infrastructure
docker-compose up -d
```

2. Demarrer ingestion-service:

```powershell
cd ingestion-service
mvn spring-boot:run
```

## 5. Comment verifier que ca marche

1. Health check:

```powershell
curl http://localhost:8081/api/ingestion/health
```

2. Verifier les lignes inserees dans PostgreSQL:

```powershell
docker exec -i postgres-pfa psql -U postgres -d ingestion_db -c "SELECT id, device_id, patient_id, signal_type, created_at FROM raw_signals ORDER BY created_at DESC LIMIT 5;"
```

3. Ecouter les messages MQTT dans Mosquitto:

```powershell
docker exec -i mosquitto-pfa-v2 mosquitto_sub -h localhost -p 1883 -t "health/#" -v
```

## 6. Fichiers code a connaitre (minimum)

- `config/MqttConfig.java`: reception MQTT + orchestration pipeline
- `service/RawSignalService.java`: sauvegarde brute en base
- `service/SignalProcessingService.java`: filtrage/outliers
- `service/AggregationService.java`: agregation fenetree (30s)
- `service/KafkaProducerService.java`: publication Kafka
- `controller/IngestionController.java`: endpoint health
`

## 7. Resume en une phrase

`ingestion-service` prend les donnees brutes des capteurs, les rend fiables, les stocke et les diffuse pour le reste de la plateforme.




