"""
main.py
Main polling loop for the Data Analytics microservice.

Pipeline (every POLL_INTERVAL_SECONDS):
  1. List active device_ids from InfluxDB
  2. For each device:
       a. Fetch PPG window (last QUERY_WINDOW_SECONDS)
       b. Extract 14 features
       c. Run XGBoost inference
       d. If anomaly detected AND confidence >= threshold AND cooldown elapsed:
            → Build structured alert
            → Publish to Kafka topic 'alerts'
"""

import logging
import signal
import sys
import time
from datetime import datetime, timezone
from typing import Dict

from app.config import (
    CONFIDENCE_THRESHOLD,
    COOLDOWN_SECONDS,
    POLL_INTERVAL_SECONDS,
)
from app.feature_extractor import extract_features
from app.influxdb_client import InfluxDBReader
from app.kafka_client import KafkaAlertProducer
from app.model import PPGModel

# ── Logging ────────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(name)s — %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
    stream=sys.stdout,
)
logger = logging.getLogger("data-analytics")


def _severity(confidence: float) -> str:
    """Map confidence score to alert severity label."""
    if confidence >= 0.95:
        return "CRITICAL"
    if confidence >= 0.85:
        return "HIGH"
    return "MODERATE"


def _build_alert(
    device_id: str,
    confidence: float,
    features: list,
    model_version: str,
) -> dict:
    """Construct the structured alert payload published to Kafka."""
    return {
        "device_id":     device_id,
        "timestamp":     datetime.now(timezone.utc).isoformat(),
        "alert_type":    "CARDIAC_ANOMALY",
        "severity":      _severity(confidence),
        "confidence":    round(confidence, 4),
        "model_version": model_version,
        "features": {
            "mean":               round(features[0],  4),
            "std":                round(features[1],  4),
            "rms":                round(features[2],  4),
            "skewness":           round(features[3],  4),
            "kurtosis":           round(features[4],  4),
            "dominant_freq":      round(features[5],  4),
            "lf_power":           round(features[6],  4),
            "hf_power":           round(features[7],  4),
            "lf_hf_ratio":        round(features[8],  4),
            "mean_rr_interval":   round(features[9],  4),
            "std_rr_interval":    round(features[10], 4),
            "mean_peak_amplitude":round(features[11], 4),
            "num_peaks":          int(features[12]),
            "valid_peaks_ratio":  round(features[13], 4),
        },
    }


class AnalyticsPipeline:
    """Orchestrates the full analysis loop."""

    def __init__(self):
        logger.info("Initialising Data Analytics pipeline …")
        self._model         = PPGModel()
        self._influx        = InfluxDBReader()
        self._kafka         = KafkaAlertProducer()
        # Per-device cooldown tracking: device_id → last alert epoch (float)
        self._last_alert: Dict[str, float] = {}
        self._running       = True
        logger.info("Pipeline ready.")

    # ── Graceful shutdown ──────────────────────────────────────────────────────

    def stop(self):
        logger.info("Stopping pipeline …")
        self._running = False

    def _cleanup(self):
        try:
            self._kafka.close()
        except Exception:
            pass
        try:
            self._influx.close()
        except Exception:
            pass
        logger.info("Resources released. Goodbye.")

    # ── Core logic ─────────────────────────────────────────────────────────────

    def _cooldown_ok(self, device_id: str) -> bool:
        last = self._last_alert.get(device_id, 0.0)
        return (time.monotonic() - last) >= COOLDOWN_SECONDS

    def _process_device(self, device_id: str) -> None:
        # 1. Fetch raw PPG window
        window = self._influx.get_ppg_window(device_id)
        if window is None:
            return

        # 2. Feature extraction
        features = extract_features(window)

        # 3. Inference
        predicted_class, confidence = self._model.predict(features)

        logger.debug(
            f"device={device_id}  class={predicted_class}  "
            f"confidence={confidence:.3f}  samples={len(window)}"
        )

        # 4. Threshold + cooldown check
        if predicted_class == 0:
            return

        if confidence < CONFIDENCE_THRESHOLD:
            logger.debug(
                f"device={device_id}  anomaly detected but confidence "
                f"{confidence:.3f} < threshold {CONFIDENCE_THRESHOLD} — skipped"
            )
            return

        if not self._cooldown_ok(device_id):
            logger.debug(f"device={device_id}  alert suppressed by cooldown")
            return

        # 5. Build and send alert
        alert = _build_alert(
            device_id    = device_id,
            confidence   = confidence,
            features     = features,
            model_version= self._model.model_version,
        )
        self._kafka.send_alert(alert)
        self._last_alert[device_id] = time.monotonic()

        logger.info(
            f"ALERT sent  device={device_id}  "
            f"severity={alert['severity']}  confidence={confidence:.3f}"
        )

    # ── Main loop ──────────────────────────────────────────────────────────────

    def run(self) -> None:
        logger.info(
            f"Starting polling loop — interval={POLL_INTERVAL_SECONDS}s  "
            f"confidence_threshold={CONFIDENCE_THRESHOLD}  "
            f"cooldown={COOLDOWN_SECONDS}s"
        )
        try:
            while self._running:
                loop_start = time.monotonic()

                devices = self._influx.list_active_devices()
                logger.info(f"Active devices this cycle: {len(devices)}")

                for device_id in devices:
                    try:
                        self._process_device(device_id)
                    except Exception as exc:
                        # Isolate per-device errors so one bad device
                        # does not block the rest
                        logger.error(f"Error processing device {device_id}: {exc}", exc_info=True)

                # Flush delivered callbacks
                self._kafka.flush(timeout=5)

                # Sleep for the remainder of the poll interval
                elapsed = time.monotonic() - loop_start
                sleep_time = max(0.0, POLL_INTERVAL_SECONDS - elapsed)
                time.sleep(sleep_time)

        except KeyboardInterrupt:
            logger.info("Received KeyboardInterrupt.")
        finally:
            self._cleanup()


# ── Entry point ────────────────────────────────────────────────────────────────

def main():
    pipeline = AnalyticsPipeline()

    # Graceful SIGTERM / SIGINT handling (Docker stop, k8s eviction …)
    def _handle_signal(signum, frame):  # noqa: ARG001
        logger.info(f"Signal {signum} received — initiating shutdown.")
        pipeline.stop()

    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT,  _handle_signal)

    pipeline.run()


if __name__ == "__main__":
    main()