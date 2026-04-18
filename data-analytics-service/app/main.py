import time
import logging
from datetime import datetime
from app.config import *
from app.influxdb_client import PPGInfluxReader
from app.feature_extractor import PPGFeatureExtractor
from app.model import PPGModel
from app.kafka_client import create_producer, send_alert

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def main():
    # Initialisations
    model = PPGModel(MODEL_PATH)
    feature_extractor = PPGFeatureExtractor(sampling_rate=100)   # à ajuster
    producer = create_producer(KAFKA_BROKERS)

    influx_reader = PPGInfluxReader(
        url=INFLUXDB_URL,
        token=INFLUXDB_TOKEN,
        org=INFLUXDB_ORG,
        bucket=INFLUXDB_BUCKET
    )

    logger.info(f"Data Analytics service started. Reading from InfluxDB, sending alerts to {TOPIC_OUT}")

    # Pour éviter les alertes multiples
    last_alert = {}

    try:
        while True:
            devices = influx_reader.get_devices()
            if not devices:
                logger.debug("Aucun device trouvé")
                time.sleep(POLL_INTERVAL_SECONDS)
                continue

            for device_id in devices:
                signal = influx_reader.get_ppg_window(device_id, QUERY_WINDOW_SECONDS)
                if not signal or len(signal) < 100:   # minimum de points requis
                    continue

                # Extraction des features
                try:
                    features = feature_extractor.extract(signal)
                except Exception as e:
                    logger.error(f"Erreur extraction features {device_id}: {e}")
                    continue

                # Prédiction
                try:
                    is_anomaly, confidence = model.predict(features)
                except Exception as e:
                    logger.error(f"Erreur prédiction {device_id}: {e}")
                    continue

                # Alerte
                if is_anomaly == 1 and confidence >= CONFIDENCE_THRESHOLD:
                    now = time.monotonic()
                    if now - last_alert.get(device_id, 0) >= COOLDOWN_SECONDS:
                        alert = {
                            'alert_id': None,
                            'device_id': device_id,
                            'timestamp': datetime.utcnow().isoformat(),
                            'type': 'anomaly',
                            'severity': 'warning' if confidence < 0.9 else 'critical',
                            'value': is_anomaly,
                            'confidence': confidence,
                            'ml_model_version': model.version
                        }
                        if send_alert(producer, TOPIC_OUT, alert):
                            last_alert[device_id] = now
                            logger.info(f"Alerte envoyée pour {device_id}")

            time.sleep(POLL_INTERVAL_SECONDS)

    except KeyboardInterrupt:
        logger.info("Arrêt demandé")
    finally:
        influx_reader.close()
        producer.close()

if __name__ == '__main__':
    main()