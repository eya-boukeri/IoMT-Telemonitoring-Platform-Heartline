import os

# ── Kafka ──────────────────────────────────────────────────────────────────────
KAFKA_BROKERS    = os.getenv("KAFKA_BROKERS", "localhost:9093")
KAFKA_TOPIC_OUT  = os.getenv("KAFKA_TOPIC_OUT", "alerts")
KAFKA_GROUP_ID   = os.getenv("GROUP_ID", "data-analytics-group")

# ── InfluxDB ───────────────────────────────────────────────────────────────────
INFLUXDB_URL     = os.getenv("INFLUXDB_URL", "http://localhost:8088")
INFLUXDB_TOKEN   = os.getenv("INFLUXDB_TOKEN", "my-secret-token")
INFLUXDB_ORG     = os.getenv("INFLUXDB_ORG", "myorg")
INFLUXDB_BUCKET  = os.getenv("INFLUXDB_BUCKET", "medical_data")

# ── Query parameters ───────────────────────────────────────────────────────────
QUERY_WINDOW_SECONDS  = int(os.getenv("QUERY_WINDOW_SECONDS", "30"))
POLL_INTERVAL_SECONDS = int(os.getenv("POLL_INTERVAL_SECONDS", "10"))

# ── Model ──────────────────────────────────────────────────────────────────────
MODEL_PATH = os.getenv("MODEL_PATH", "/app/models/ppg_model.joblib")

# ── Business rules ─────────────────────────────────────────────────────────────
CONFIDENCE_THRESHOLD = float(os.getenv("CONFIDENCE_THRESHOLD", "0.7"))
COOLDOWN_SECONDS     = int(os.getenv("COOLDOWN_SECONDS", "300"))