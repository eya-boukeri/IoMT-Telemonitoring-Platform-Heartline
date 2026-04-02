"""
influxdb_client.py
Reads raw PPG signal windows from InfluxDB 2.x.
Provides:
  - list_active_devices() → list of device_id strings with recent data
  - get_ppg_window(device_id) → list of float values for the last QUERY_WINDOW_SECONDS
"""

import logging
from typing import List, Optional

from influxdb_client import InfluxDBClient as _InfluxDBClient
from influxdb_client.client.exceptions import InfluxDBError

from app.config import (
    INFLUXDB_URL,
    INFLUXDB_TOKEN,
    INFLUXDB_ORG,
    INFLUXDB_BUCKET,
    QUERY_WINDOW_SECONDS,
)

logger = logging.getLogger(__name__)

# ── Measurement / field / tag names (must match the ingestion service schema) ──
MEASUREMENT  = "ppg"
FIELD_VALUE  = "value"
TAG_DEVICE   = "device_id"


class InfluxDBReader:
    """Thread-safe reader for PPG data stored in InfluxDB 2.x."""

    def __init__(self):
        self._client      = _InfluxDBClient(
            url=INFLUXDB_URL,
            token=INFLUXDB_TOKEN,
            org=INFLUXDB_ORG,
            timeout=10_000,         # ms
        )
        self._query_api   = self._client.query_api()
        self._org         = INFLUXDB_ORG
        self._bucket      = INFLUXDB_BUCKET
        self._window_secs = QUERY_WINDOW_SECONDS
        logger.info(
            f"InfluxDB reader ready — url={INFLUXDB_URL}  "
            f"bucket={INFLUXDB_BUCKET}  window={QUERY_WINDOW_SECONDS}s"
        )

    # ── Public API ─────────────────────────────────────────────────────────────

    def list_active_devices(self) -> List[str]:
        """
        Return device_ids that have PPG data within the last QUERY_WINDOW_SECONDS.
        Returns [] on any error.
        """
        flux = f"""
            from(bucket: "{self._bucket}")
              |> range(start: -{self._window_secs}s)
              |> filter(fn: (r) => r._measurement == "{MEASUREMENT}")
              |> filter(fn: (r) => r._field == "{FIELD_VALUE}")
              |> keep(columns: ["{TAG_DEVICE}"])
              |> distinct(column: "{TAG_DEVICE}")
        """
        try:
            tables  = self._query_api.query(flux, org=self._org)
            devices = [
                record.values.get(TAG_DEVICE)
                for table in tables
                for record in table.records
                if record.values.get(TAG_DEVICE)
            ]
            logger.debug(f"Active devices: {devices}")
            return devices
        except InfluxDBError as exc:
            logger.error(f"InfluxDB query error (list_active_devices): {exc}")
            return []
        except Exception as exc:
            logger.error(f"Unexpected error (list_active_devices): {exc}")
            return []

    def get_ppg_window(self, device_id: str) -> Optional[List[float]]:
        """
        Fetch the last QUERY_WINDOW_SECONDS of raw PPG values for device_id.

        Returns a list of floats sorted by time (oldest → newest),
        or None if the query fails or returns no data.
        """
        flux = f"""
            from(bucket: "{self._bucket}")
              |> range(start: -{self._window_secs}s)
              |> filter(fn: (r) => r._measurement == "{MEASUREMENT}")
              |> filter(fn: (r) => r._field == "{FIELD_VALUE}")
              |> filter(fn: (r) => r["{TAG_DEVICE}"] == "{device_id}")
              |> sort(columns: ["_time"], desc: false)
              |> keep(columns: ["_value"])
        """
        try:
            tables = self._query_api.query(flux, org=self._org)
            values = [
                float(record.get_value())
                for table in tables
                for record in table.records
                if record.get_value() is not None
            ]
            if not values:
                logger.debug(f"No PPG data for device {device_id} in last {self._window_secs}s")
                return None
            logger.debug(f"device={device_id}  samples={len(values)}")
            return values
        except InfluxDBError as exc:
            logger.error(f"InfluxDB query error (get_ppg_window) device={device_id}: {exc}")
            return None
        except Exception as exc:
            logger.error(f"Unexpected error (get_ppg_window) device={device_id}: {exc}")
            return None

    def close(self):
        """Release the underlying HTTP connection pool."""
        try:
            self._client.close()
        except Exception:
            pass