# Guide de Démarrage - Dashboard Médical

## Architecture
- **Backend** : Spring Boot (port 9090)
- **Frontend** : React + Vite (port 5173)
- **Base de données** : InfluxDB (port 8088)
- **MQTT Broker** : Mosquitto (port 1885)

## 🚀 Démarrage Rapide

### 1. Démarrer l'Infrastructure (Docker)
```powershell
cd pfa-infrastructure
docker-compose up -d
```

### 2. Démarrer le Backend Spring Boot
```powershell
cd vitals-management
mvn spring-boot:run
```
Le backend sera accessible sur **http://localhost:9090**

### 3. Démarrer le Dashboard React
```powershell
cd medical-dashboard
npm install  # Si pas déjà fait
npm run dev
```
Le dashboard sera accessible sur **http://localhost:5173**

## 📊 Fonctionnalités du Dashboard

### Données en Temps Réel
- **Connexion SSE (Server-Sent Events)** : Mise à jour automatique des données
- **Sélection de patient** : Choisir le patient à surveiller
- **Graphiques interactifs** :
  - Rythme cardiaque (bpm)
  - Signal PPG (morphologie)
  - Activité accéléromètre (niveau de mouvement)
  - Tension artérielle (systolique/diastolique)

### Statistiques
- Valeurs actuelles, min, max, moyenne pour chaque paramètre
- Mise à jour en temps réel

### Alertes Automatiques
- ⚠️ Rythme cardiaque anormal (< 60 ou > 100 bpm)
- ⚠️ Qualité de signal faible (PPG bruité)
- ⚠️ Mouvement excessif détecté (accéléromètre)
- ⚠️ Tension artérielle hors limites

## 🔌 API Backend Disponibles

### Endpoints REST
- `GET /api/vitals/patients` - Liste des patients
- `GET /api/vitals/latest/{patientId}` - Dernières mesures
- `GET /api/vitals/history/{patientId}` - Historique complet
- `GET /api/vitals/stats/{patientId}` - Statistiques
- `GET /api/vitals/recent` - Données récentes (tous patients)

### Stream SSE
- `GET /api/vitals/stream/{patientId}` - Flux temps réel pour un patient

## 🛠️ Configuration

### Backend (application.properties)
```properties
server.port=9090
influxdb.url=http://localhost:8088
mqtt.broker.url=tcp://127.0.0.1:1885
```

### Frontend (vite.config.js)
```javascript
server: {
  port: 5173,
  proxy: {
    '/api': {
      target: 'http://localhost:9090'
    }
  }
}
```

## 🔧 Dépannage

### Le dashboard ne reçoit pas de données
1. Vérifier que le backend est démarré : http://localhost:9090/api/vitals/patients
2. Vérifier la connexion SSE (indicateur vert dans le header)
3. Vérifier les logs du backend pour les erreurs MQTT
4. Tester l'envoi de données depuis l'app mobile

### Erreur CORS
- Assurez-vous que `CorsConfig.java` est présent dans le backend
- Redémarrer le backend Spring Boot

### Pas de données dans InfluxDB
1. Vérifier que le container InfluxDB est en cours d'exécution
2. Tester la connexion MQTT avec `test-mqtt.ps1`
3. Vérifier les logs du service MQTT

## 📱 Envoi de Données depuis l'Application Mobile

L'application Android doit envoyer les données au format suivant sur le topic `health/sensorData` :

```json
{
  "patientId": "PATIENT001",
  "timestamp": "2026-03-05T22:30:00Z",
  "heartRate": 75.5,
  "ppgGreenAverage": 1200.3,
  "accelerometerMagnitudeAverage": 0.84,
  "bloodPressureSystolic": 120,
  "bloodPressureDiastolic": 80
}
```

Configuration MQTT pour l'application :
- **Broker** : `tcp://[IP_DU_PC]:1885`
- **Topic** : `health/sensorData`
- **QoS** : 2

## 📝 Notes

- Les données sont conservées dans InfluxDB
- Le dashboard affiche les 50 dernières mesures
- Les alertes sont conservées (max 10 dernières)
- La reconnexion SSE est automatique en cas de déconnexion
