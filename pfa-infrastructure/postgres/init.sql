-- ============================================================
-- Base de données principale : gestion des utilisateurs,
-- médecins, patients, devices et assignations.
-- ============================================================

-- Crée les bases nécessaires au stack si elles n'existent pas.
SELECT 'CREATE DATABASE keycloak'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec
SELECT 'CREATE DATABASE notification_db'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'notification_db')\gexec

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ------------------------------------------------------------
-- Table centrale des utilisateurs (miroir Keycloak)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    keycloak_id   VARCHAR(255) UNIQUE NOT NULL,   -- sub du JWT Keycloak
    username      VARCHAR(100) UNIQUE NOT NULL,   -- preferred_username
    email         VARCHAR(255),
    role          VARCHAR(50) NOT NULL CHECK (role IN ('ADMIN','MEDECIN','PATIENT','DEVICE')),
    created_at    TIMESTAMP DEFAULT NOW(),
    active        BOOLEAN DEFAULT TRUE
);

-- ------------------------------------------------------------
-- Médecins
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS medecins (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    nom          VARCHAR(100) NOT NULL,
    prenom       VARCHAR(100) NOT NULL,
    specialite   VARCHAR(100),
    telephone    VARCHAR(20),
    UNIQUE(user_id)
);

-- ------------------------------------------------------------
-- Patients
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS patients (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    nom             VARCHAR(100) NOT NULL,
    prenom          VARCHAR(100) NOT NULL,
    date_naissance  DATE,
    telephone       VARCHAR(20),
    adresse         TEXT,
    UNIQUE(user_id)
);

-- ------------------------------------------------------------
-- Devices (montres connectées autorisées)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS devices (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    keycloak_client_id  VARCHAR(255) UNIQUE NOT NULL,  -- ex: "smartwatch-client"
    patient_id          UUID REFERENCES patients(id) ON DELETE SET NULL,
    modele              VARCHAR(100),
    statut              VARCHAR(20) DEFAULT 'ACTIF' CHECK (statut IN ('ACTIF','INACTIF','SUSPENDU')),
    enregistre_le       TIMESTAMP DEFAULT NOW()
);

-- ------------------------------------------------------------
-- Table de jointure médecin ↔ patient
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS assignations (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    patient_id       UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    medecin_id       UUID NOT NULL REFERENCES medecins(id) ON DELETE CASCADE,
    date_assignation TIMESTAMP DEFAULT NOW(),
    actif            BOOLEAN DEFAULT TRUE,
    UNIQUE(patient_id, medecin_id)
);

-- ------------------------------------------------------------
-- Index pour les recherches fréquentes
-- ------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_users_keycloak_id ON users(keycloak_id);
CREATE INDEX IF NOT EXISTS idx_users_username    ON users(username);
CREATE INDEX IF NOT EXISTS idx_assignations_medecin ON assignations(medecin_id) WHERE actif = TRUE;
CREATE INDEX IF NOT EXISTS idx_assignations_patient ON assignations(patient_id) WHERE actif = TRUE;
CREATE INDEX IF NOT EXISTS idx_devices_patient   ON devices(patient_id);

-- ------------------------------------------------------------
-- Données de démarrage (exemples)
-- Remplacer les keycloak_id par les vrais "sub" du token JWT Keycloak
-- ------------------------------------------------------------
INSERT INTO users (keycloak_id, username, email, role) VALUES
    ('KEYCLOAK_SUB_DR_FARAH',   'dr-farah',   'dr.farah@medtech.tn', 'MEDECIN'),
    ('KEYCLOAK_SUB_EYA',        'eya',        'eya@patient.tn',      'PATIENT'),
    ('KEYCLOAK_SUB_ADMIN',      'admin-medtech', 'admin@medtech.tn', 'ADMIN')
ON CONFLICT (keycloak_id) DO NOTHING;

INSERT INTO medecins (user_id, nom, prenom, specialite)
SELECT id, 'Ben Ali', 'Farah', 'Cardiologie'
FROM users WHERE username = 'dr-farah'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO patients (user_id, nom, prenom, date_naissance, telephone, adresse)
SELECT id, 'Trabelsi', 'Eya', '1990-05-12', '+21620123456', 'Tunis, Tunisie'
FROM users WHERE username = 'eya'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO devices (keycloak_client_id, patient_id, modele)
SELECT 'smartwatch-client',
       (SELECT id FROM patients p JOIN users u ON p.user_id = u.id WHERE u.username = 'eya'),
       'SenserApp v1.0'
WHERE EXISTS (SELECT 1 FROM patients p JOIN users u ON p.user_id = u.id WHERE u.username = 'eya')
ON CONFLICT (keycloak_client_id) DO NOTHING;

-- ============================================================
-- Base de données : notification_db
-- Tables pour l'ingestion des signaux et les métriques dérivées
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ------------------------------------------------------------
-- Signaux bruts (données entrantes des montres)
-- ------------------------------------------------------------
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

-- ------------------------------------------------------------
-- Métriques calculées par fenêtre de temps (analyse du signal)
-- ------------------------------------------------------------
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