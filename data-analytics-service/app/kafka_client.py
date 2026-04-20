import logging
from kafka import KafkaProducer
import json

logger = logging.getLogger(__name__)

def create_producer(brokers):
    """Crée un producteur Kafka avec configuration de base"""
    try:
        producer = KafkaProducer(
            bootstrap_servers=brokers,
            value_serializer=lambda v: json.dumps(v).encode('utf-8'),
            acks='all',          # attente que tous les réplicas aient reçu le message
            retries=3,
            max_in_flight_requests_per_connection=1  # maintient l'ordre
        )
        logger.info(f"Producteur Kafka connecté à {brokers}")
        return producer
    except Exception as e:
        logger.error(f"Erreur création producteur Kafka : {e}")
        raise

def send_alert(producer, topic, alert):
    """Envoie une alerte sur le topic Kafka, retourne True si réussi"""
    try:
        # Générer un ID unique si non fourni
        alert_id = alert.get('alertId') or alert.get('alert_id')
        if not alert_id:
            import uuid
            alert_id = str(uuid.uuid4())
        alert['alertId'] = alert_id
        alert.pop('alert_id', None)

        # Utiliser patientId comme clé pour l'ordre, avec fallback legacy.
        key_source = alert.get('patientId') or alert.get('device_id') or 'unknown'
        key = key_source.encode('utf-8')
        future = producer.send(topic, key=key, value=alert)
        # Attendre l'acquittement (timeout 5 secondes)
        future.get(timeout=5)
        logger.debug(f"Alerte envoyée sur {topic} : {alert['alertId']}")
        return True
    except Exception as e:
        logger.error(f"Échec envoi alerte : {e}")
        return False