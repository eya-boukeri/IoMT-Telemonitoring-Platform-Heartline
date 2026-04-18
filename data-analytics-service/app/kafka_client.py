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
        if 'alert_id' not in alert or alert['alert_id'] is None:
            import uuid
            alert['alert_id'] = str(uuid.uuid4())
        # Utiliser device_id comme clé pour l'ordre
        key = alert['device_id'].encode('utf-8')
        future = producer.send(topic, key=key, value=alert)
        # Attendre l'acquittement (timeout 5 secondes)
        future.get(timeout=5)
        logger.debug(f"Alerte envoyée sur {topic} : {alert['alert_id']}")
        return True
    except Exception as e:
        logger.error(f"Échec envoi alerte : {e}")
        return False