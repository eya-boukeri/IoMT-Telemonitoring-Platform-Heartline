-- Initialisation PostgreSQL pour ingestion-service
-- Ce script est execute automatiquement au premier demarrage du conteneur.

-- POSTGRES_DB cree ingestion_db uniquement; on cree notification_db explicitement.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'notification_db') THEN
        CREATE DATABASE notification_db;
    END IF;
END
$$;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS raw_signals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp TIMESTAMPTZ NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    patient_id VARCHAR(255),
    raw_payload JSONB NOT NULL,
    signal_type VARCHAR(100),
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_raw_signals_device_timestamp
    ON raw_signals (device_id, timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_raw_signals_patient_processed
    ON raw_signals (patient_id, processed);

CREATE INDEX IF NOT EXISTS idx_raw_signals_created_at
    ON raw_signals (created_at DESC);

CREATE TABLE IF NOT EXISTS ingestion_metrics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id VARCHAR(255) NOT NULL,
    device_id VARCHAR(255),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    sample_count INTEGER NOT NULL,
    estimated_heart_rate DOUBLE PRECISION,
    respiratory_rate DOUBLE PRECISION,
    rr_mean_ms DOUBLE PRECISION,
    rr_intervals_ms JSONB,
    hrv_sdnn DOUBLE PRECISION,
    hrv_rmssd DOUBLE PRECISION,
    hrv_lf_hf DOUBLE PRECISION,
    vascular_index DOUBLE PRECISION,
    perfusion_index DOUBLE PRECISION,
    signal_quality_score DOUBLE PRECISION,
    signal_quality_label VARCHAR(32),
    stress_index DOUBLE PRECISION,
    stress_level VARCHAR(32),
    ppg_mean DOUBLE PRECISION,
    ppg_min DOUBLE PRECISION,
    ppg_max DOUBLE PRECISION,
    ppg_std_dev DOUBLE PRECISION,
    ppg_variance DOUBLE PRECISION,
    activity_mean DOUBLE PRECISION,
    activity_max DOUBLE PRECISION,
    activity_variance DOUBLE PRECISION,
    valid_samples INTEGER,
    outlier_samples INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ingestion_metrics_patient_window
    ON ingestion_metrics (patient_id, window_end DESC);
