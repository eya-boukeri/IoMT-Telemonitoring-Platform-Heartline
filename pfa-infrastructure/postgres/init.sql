-- Initialisation PostgreSQL pour ingestion-service
-- Ce script est execute automatiquement au premier demarrage du conteneur.

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
