import time
import logging
import uuid
from datetime import datetime
from app.config import *
from app.influxdb_client import PPGInfluxReader
from app.feature_extractor import PPGFeatureExtractor
from app.model import PPGModel
from app.kafka_client import create_producer, send_alert
from app.cloud_sender import fetch_raw_points, send_anomaly_snapshot

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


def attach_snapshot_to_alert(alert, patient_id, model_version):
    """Crée un snapshot brut PostgreSQL et ajoute snapshotId à l'alerte si disponible."""
    alert_id = alert.get('alertId') or str(uuid.uuid4())
    alert['alertId'] = alert_id

    raw_points = fetch_raw_points(
        ingestion_url=INGESTION_SERVICE_URL,
        patient_id=patient_id,
        limit=RAW_SNAPSHOT_POINTS,
    )

    snapshot_id = send_anomaly_snapshot(
        ingestion_url=INGESTION_SERVICE_URL,
        alert_id=alert_id,
        patient_id=patient_id,
        detected_at=alert.get('timestamp'),
        severity=alert.get('severity'),
        confidence=alert.get('confidence'),
        model_version=model_version,
        message=alert.get('message'),
        raw_points=raw_points,
        max_points=RAW_SNAPSHOT_POINTS,
    )

    if snapshot_id:
        alert['snapshotId'] = snapshot_id
    else:
        logger.warning(f"Snapshot indisponible pour alertId={alert_id}, patientId={patient_id}")

    return alert

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
            patient_ids = influx_reader.get_patient_ids()
            if not patient_ids:
                logger.debug("Aucun patient trouvé")
                time.sleep(POLL_INTERVAL_SECONDS)
                continue

            for patient_id in patient_ids:
                logger.info(f"Traitement du patient: {patient_id}")
                signal = influx_reader.get_ppg_window(patient_id, QUERY_WINDOW_SECONDS)
                logger.info(f"Signal pour {patient_id}: {len(signal) if signal else 0} points")

                # Fallback rule-based: déclenche une alerte en cas de FC critique
                # même quand la fenêtre PPG est trop courte pour le modèle ML.
                try:
                    heart_rates = influx_reader.get_metric_window(patient_id, "heartRate", QUERY_WINDOW_SECONDS)
                except Exception as e:
                    logger.error(f"Erreur lecture heartRate {patient_id}: {e}")
                    heart_rates = []

                if heart_rates:
                    hr_max = max(heart_rates)
                    hr_min = min(heart_rates)
                    now = time.monotonic()
                    if (hr_max >= 120 or hr_min <= 45) and (now - last_alert.get(patient_id, 0) >= COOLDOWN_SECONDS):
                        severity = 'CRITICAL' if (hr_max >= 140 or hr_min <= 40) else 'WARNING'
                        priority = 'URGENT' if severity == 'CRITICAL' else 'HIGH'
                        alert = {
                            'alertId': None,
                            'patientId': patient_id,
                            'timestamp': datetime.utcnow().isoformat() + 'Z',
                            'alertType': 'anomaly',
                            'severity': severity,
                            'priority': priority,
                            'message': f'Heart-rate rule triggered for patient {patient_id} (min={hr_min:.1f}, max={hr_max:.1f})',
                            'detectionScore': 1.0,
                            'value': 1,
                            'confidence': 1.0,
                            'ml_model_version': model.version
                        }
                        alert = attach_snapshot_to_alert(alert, patient_id, model.version)
                        if send_alert(producer, TOPIC_OUT, alert):
                            last_alert[patient_id] = now
                            logger.info(f"Alerte rule-based envoyée pour {patient_id}")

                if not signal or len(signal) < 3:   # minimum adapté aux points agrégés Influx
                    continue

                # Extraction des features
                try:
                    features = feature_extractor.extract(signal)
                except Exception as e:
                    logger.error(f"Erreur extraction features {patient_id}: {e}")
                    continue

                # Prédiction
                try:
                    is_anomaly, confidence = model.predict(features)
                except Exception as e:
                    logger.error(f"Erreur prédiction {patient_id}: {e}")
                    continue

                # Alerte
                if is_anomaly == 1 and confidence >= CONFIDENCE_THRESHOLD:
                    now = time.monotonic()
                    if now - last_alert.get(patient_id, 0) >= COOLDOWN_SECONDS:
                        alert = {
                            'alertId': None,
                            'patientId': patient_id,
                            'timestamp': datetime.utcnow().isoformat() + 'Z',
                            'alertType': 'anomaly',
                            'severity': 'WARNING' if confidence < 0.9 else 'CRITICAL',
                            'priority': 'HIGH' if confidence < 0.9 else 'URGENT',
                            'message': f'Anomaly detected for patient {patient_id} with confidence {confidence:.3f}',
                            'detectionScore': confidence,
                            'value': is_anomaly,
                            'confidence': confidence,
                            'ml_model_version': model.version
                        }
                        alert = attach_snapshot_to_alert(alert, patient_id, model.version)
                        if send_alert(producer, TOPIC_OUT, alert):
                            last_alert[patient_id] = now
                            logger.info(f"Alerte envoyée pour {patient_id}")

            time.sleep(POLL_INTERVAL_SECONDS)

    except KeyboardInterrupt:
        logger.info("Arrêt demandé")
    finally:
        influx_reader.close()
        producer.close()

if __name__ == '__main__':
    main()