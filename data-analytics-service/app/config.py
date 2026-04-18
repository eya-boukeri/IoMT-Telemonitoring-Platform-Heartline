"""
config.py — Centralise toutes les variables de configuration du service.
Chaque paramètre est lu depuis les variables d'environnement avec une valeur
par défaut pour faciliter les tests en local.
"""

import os

# ── Kafka ──────────────────────────────────────────────────────────────────
# Adresse(s) du broker Kafka. En Docker, "kafka" est le nom du service.
KAFKA_BROKERS = os.getenv("KAFKA_BROKERS", "kafka:9092")

# Topic d'entrée : messages PPG pré-traités publiés par l'ingestion-service
TOPIC_IN = os.getenv("KAFKA_TOPIC_IN", "ppg_processed")

# Topic de sortie : alertes à destination des autres services (notifications, BDD...)
TOPIC_OUT = os.getenv("KAFKA_TOPIC_OUT", "alerts")

# Consumer group : permet à Kafka de mémoriser la position de lecture (offset)
GROUP_ID = os.getenv("GROUP_ID", "data-analytics-group")

# ── Modèle ML ─────────────────────────────────────────────────────────────
# Chemin vers le fichier .joblib du modèle XGBoost pré-entraîné
MODEL_PATH = os.getenv("MODEL_PATH", "/app/models/ppg_model.joblib")

# ── Règles métier ──────────────────────────────────────────────────────────
# Seuil minimum de confiance pour déclencher une alerte (entre 0.0 et 1.0)
CONFIDENCE_THRESHOLD = float(os.getenv("CONFIDENCE_THRESHOLD", "0.7"))

# Délai anti-spam (secondes) : on ne ré-alerte pas le même appareil avant ce délai
COOLDOWN_SECONDS = int(os.getenv("COOLDOWN_SECONDS", "300"))  # 5 minutes

# ── Signal PPG ─────────────────────────────────────────────────────────────
# Fréquence d'échantillonnage du capteur PPG (Hz)
SAMPLING_RATE = int(os.getenv("SAMPLING_RATE", "100"))

# InfluxDB
INFLUXDB_URL = os.getenv('INFLUXDB_URL', 'http://influxdb:8086')
INFLUXDB_TOKEN = os.getenv('INFLUXDB_TOKEN', 'my-secret-token')
INFLUXDB_ORG = os.getenv('INFLUXDB_ORG', 'myorg')
INFLUXDB_BUCKET = os.getenv('INFLUXDB_BUCKET', 'medical_data')
QUERY_WINDOW_SECONDS = int(os.getenv('QUERY_WINDOW_SECONDS', '30'))
POLL_INTERVAL_SECONDS = int(os.getenv('POLL_INTERVAL_SECONDS', '10'))