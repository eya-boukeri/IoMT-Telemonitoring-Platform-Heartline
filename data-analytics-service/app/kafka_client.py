"""
kafka_client.py
Kafka producer built on confluent-kafka.
Publishes JSON alert messages to the configured output topic.
Messages are keyed by device_id to guarantee per-device ordering.
"""

import json
import logging
import time
from typing import Dict, Any

from confluent_kafka import Producer, KafkaException
from confluent_kafka.admin import AdminClient, NewTopic

from app.config import KAFKA_BROKERS, KAFKA_TOPIC_OUT

logger = logging.getLogger(__name__)

# ── Retry settings ─────────────────────────────────────────────────────────────
_MAX_CONNECT_RETRIES = 10
_RETRY_DELAY_SECONDS = 5


class KafkaAlertProducer:
    """
    Wraps a confluent_kafka.Producer with:
      - At-startup topic auto-creation
      - Idempotent delivery (acks=all, enable.idempotence=True)
      - Per-message delivery callback logging
      - JSON serialisation
    """

    def __init__(self):
        self._topic    = KAFKA_TOPIC_OUT
        self._producer = self._build_producer()
        self._ensure_topic()
        logger.info(
            f"Kafka producer ready — brokers={KAFKA_BROKERS}  topic={self._topic}"
        )

    # ── Internals ──────────────────────────────────────────────────────────────

    def _build_producer(self) -> Producer:
        conf = {
            "bootstrap.servers":  KAFKA_BROKERS,
            "acks":               "all",
            "retries":            5,
            "retry.backoff.ms":   500,
            "enable.idempotence": True,
            # Wait up to 10 s for the broker to be available at startup
            "socket.timeout.ms":  10_000,
        }
        for attempt in range(1, _MAX_CONNECT_RETRIES + 1):
            try:
                p = Producer(conf)
                # Trigger metadata fetch to validate broker connectivity
                p.list_topics(timeout=8)
                logger.info(f"Connected to Kafka on attempt {attempt}.")
                return p
            except KafkaException as exc:
                logger.warning(
                    f"Kafka not reachable (attempt {attempt}/{_MAX_CONNECT_RETRIES}): {exc}"
                )
                if attempt < _MAX_CONNECT_RETRIES:
                    time.sleep(_RETRY_DELAY_SECONDS)
        # Last attempt without a guard – let it raise if it fails
        return Producer(conf)

    def _ensure_topic(self):
        """Create the output topic if it does not already exist."""
        admin_conf = {"bootstrap.servers": KAFKA_BROKERS}
        admin      = AdminClient(admin_conf)
        existing   = admin.list_topics(timeout=10).topics

        if self._topic not in existing:
            new_topic = NewTopic(
                self._topic,
                num_partitions=3,
                replication_factor=1,
            )
            fs = admin.create_topics([new_topic])
            for topic, future in fs.items():
                try:
                    future.result()
                    logger.info(f"Created Kafka topic: {topic}")
                except Exception as exc:
                    # Topic may have been created by another instance concurrently
                    logger.warning(f"Topic creation warning for {topic}: {exc}")
        else:
            logger.info(f"Kafka topic '{self._topic}' already exists.")

    @staticmethod
    def _delivery_callback(err, msg):
        if err:
            logger.error(
                f"Delivery failed — topic={msg.topic()}  "
                f"partition={msg.partition()}  error={err}"
            )
        else:
            logger.debug(
                f"Delivered — topic={msg.topic()}  "
                f"partition={msg.partition()}  offset={msg.offset()}"
            )

    # ── Public API ─────────────────────────────────────────────────────────────

    def send_alert(self, alert: Dict[str, Any]) -> None:
        """
        Serialise alert dict as JSON and publish to the alerts topic.
        The message key is the device_id (bytes) to guarantee ordering.

        Args:
            alert: dict containing at minimum 'device_id', 'timestamp', etc.
        """
        device_id = str(alert.get("device_id", "unknown"))
        payload   = json.dumps(alert, default=str).encode("utf-8")
        key       = device_id.encode("utf-8")

        try:
            self._producer.produce(
                topic    = self._topic,
                key      = key,
                value    = payload,
                callback = self._delivery_callback,
            )
            # Poll to trigger delivery callbacks (non-blocking)
            self._producer.poll(0)
        except KafkaException as exc:
            logger.error(f"Failed to produce alert for device {device_id}: {exc}")
            raise

    def flush(self, timeout: float = 10.0) -> None:
        """Block until all outstanding messages are delivered."""
        remaining = self._producer.flush(timeout=timeout)
        if remaining:
            logger.warning(f"{remaining} message(s) were not delivered within {timeout}s.")

    def close(self):
        self.flush()