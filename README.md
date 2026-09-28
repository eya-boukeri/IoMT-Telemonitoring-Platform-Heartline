# 🩺 IoMT Telemonitoring Platform — Heartline

<p align="center">
  <strong>Real-Time Distributed IoT Medical Platform for Cardiac Remote Monitoring</strong>
  <br>
  <em>Edge Sensor Fusion (PPG + ACC) • Apache Kafka Event Bus • AI Anomaly Detection (XGBoost) • Cloudflare R2 Storage • React Live Telemetry</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Status-Production%20Ready-success?style=for-the-badge&logo=statuspage&logoColor=white" alt="Status" />
  <img src="https://img.shields.io/badge/Architecture-Event--Driven%20Microservices-informational?style=for-the-badge&logo=microgenetics&logoColor=white" alt="Architecture" />
  <img src="https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker" />
  <img src="https://img.shields.io/badge/Apache%20Kafka-Redpanda-231F20?style=for-the-badge&logo=apachekafka&logoColor=white" alt="Kafka" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/FastAPI-Python%203.11-009688?style=for-the-badge&logo=fastapi&logoColor=white" alt="FastAPI" />
  <img src="https://img.shields.io/badge/XGBoost-ML%20Inference-FF6600?style=for-the-badge&logo=scikitlearn&logoColor=white" alt="XGBoost" />
  <img src="https://img.shields.io/badge/React-19%20%2B%20Vite-20232A?style=for-the-badge&logo=react&logoColor=61DAFB" alt="React" />
  <img src="https://img.shields.io/badge/PostgreSQL-15-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL" />
  <img src="https://img.shields.io/badge/InfluxDB-Time--Series-22ADF6?style=for-the-badge&logo=influxdb&logoColor=white" alt="InfluxDB" />
  <img src="https://img.shields.io/badge/Cloudflare%20R2-Object%20Storage-F38020?style=for-the-badge&logo=cloudflare&logoColor=white" alt="Cloudflare R2" />
  <img src="https://img.shields.io/badge/Keycloak-OAuth2%20%2F%20OIDC-000000?style=for-the-badge&logo=keycloak&logoColor=white" alt="Keycloak" />
</p>

---

## 📑 Table of Contents

- [📌 Overview](#-overview)
- [🎯 Project Context & Academic Framework](#-project-context--academic-framework)
- [👥 Functional Architecture & Use Cases](#-functional-architecture--use-cases)
- [🏗️ End-to-End System Architecture](#️-end-to-end-system-architecture)
- [📊 Clinical Monitoring Interface (Heartline Dashboard)](#-clinical-monitoring-interface-heartline-dashboard)
- [🧠 Signal Processing & Sensor Fusion (PPG + ACC)](#-signal-processing--sensor-fusion-ppg--acc)
- [🤖 AI-Based Cardiac Anomaly Detection](#-ai-based-cardiac-anomaly-detection)
- [🗄️ Hybrid Tiered Storage (PostgreSQL, InfluxDB & Cloudflare R2)](#️-hybrid-tiered-storage-postgresql-influxdb--cloudflare-r2)
- [🚨 Multi-Channel Alert & Notification System](#-multi-channel-alert--notification-system)
- [🔐 Security, Authentication & Access Control](#-security-authentication--access-control)
- [🛠️ Microservices Ecosystem & Port Matrix](#️-microservices-ecosystem--port-matrix)
- [📈 Experimental Performance & Load Testing](#-experimental-performance--load-testing)
- [🚀 Quickstart & Deployment Guide](#-quickstart--deployment-guide)
- [👩‍💻 Academic Context & Author](#-academic-context--author)

---

## 📌 Overview

The **IoMT Telemonitoring Platform** is an enterprise-grade, event-driven Internet of Medical Things (IoMT) solution designed for **continuous remote cardiac monitoring**. Leveraging wearable physiological sensors (smartwatch photoplethysmography — PPG — coupled with tri-axial accelerometry — ACC), the platform autonomously captures vital telemetry, mitigates motion artifacts in real time, executes low-latency machine learning inference for arrhythmia and cardiac anomaly detection, and disseminates tiered clinical alerts to healthcare practitioners.

### 🌟 Key Highlights

- **Edge-to-Cloud Ingestion:** Asynchronous MQTT protocol delivering sub-second ingestion from mobile/wearable devices to containerized brokers.
- **Motion-Resistant Signal Processing:** Real-time temporal fusion of optical PPG and 3-axis accelerometer signals computing a dynamic **Signal Quality Score (SQS)** to discard motion artifacts.
- **Ultra-Fast ML Inference:** An **XGBoost** model computing 14 physiological biomarkers in under **50 ms** to identify normal rhythms vs. pathological irregularities.
- **Zero-Egress Anomaly Archiving:** Integrated **Cloudflare R2 Object Storage** archiving high-resolution raw signal segments selectively upon anomaly events for legal and clinical traceability without accumulating excessive cloud storage costs.
- **Live Clinical Telemetry:** Responsive dark-mode React dashboard (*Heartline*) rendering real-time PPG waves, patient triage rosters, and Server-Sent Events (SSE) notification streams.
- **Hospital-Grade Security:** Centralized IAM with **Keycloak 23** (OAuth2/OIDC, JWT) and reactive routing through a secure **Spring Cloud API Gateway**.

---

## 🎯 Project Context & Academic Framework

This platform was designed and engineered as part of **PFA2** at the **National Engineering School of Tunis (ENIT)**, within the **RISC Laboratory** (*Recherche en Ingénierie des Systèmes Complexes*), under the academic supervision of **Prof. Soumaya Meherzi**.

The project directly tackles core medical IoT and telemetry challenges:
1. **Reliability under Movement:** Compensating for optical sensor displacement and physical movements using wearable accelerometers.
2. **Streaming Big Data Scalability:** Managing continuous, multi-patient high-frequency physiological data streams without message loss or queue saturation.
3. **Decoupled Asynchronous Processing:** Ensuring data ingestion remains completely immune to analytics downtime or alerting latencies.
4. **Clinical Actionability:** Providing medical personnel with both real-time alerts and instantaneous deep-dive drill-downs into raw anomaly waveforms.

---

## 👥 Functional Architecture & Use Cases

The platform orchestrates interactions across three primary clinical and administrative actors, organized into modular functional packages:

<p align="center">
  <img width="1034" height="482" alt="image" src="https://github.com/user-attachments/assets/df79e347-9176-47ff-b263-b4992d8c6601" />

  <br>
  <em><b>Figure 1:</b> Diagramme des cas d'utilisation (UML) de la plateforme IoMT de télésurveillance cardiaque.</em>
</p>

### Actors & Functional Scopes

| Actor | Functional Capabilities | Target Components |
|---|---|---|
| **🩺 Médecin (Clinician)** | • Supervise real-time vital telemetry on interactive dashboard<br>• Inspect high-resolution PPG/ACC curves<br>• Receive instant SSE cardiac alerts (Warning / Critical)<br>• Review historical anomaly logs and patient records | React Dashboard, SSE, API Gateway |
| **⌚ Patient** | • Wear smartwatch paired with PPG optical sensor & 3-axis accelerometer<br>• Consult personal vitals through dedicated patient interface<br>• Experience non-intrusive continuous cardiovascular surveillance | Sensor Application, MQTT Broker |
| **⚙️ Administrateur** | • User identity & role-based access management (RBAC)<br>• System health monitoring, broker health checks, microservice lifecycles | Keycloak IAM, Actuator, pgAdmin |

### Core Functional Modules
- **Pipeline IoT & Traitement:** MQTT transmission, broker ingestion, Kafka decoupled topic publication, InfluxDB time-series streaming.
- **Gestion des Alertes:** Multi-level triage (`NORMAL`, `WARNING`, `CRITICAL`), real-time SSE push, urgent SMS (Twilio) and Email dispatch.
- **Sécurité & Authentification:** Centralized token validation (JWT), OAuth2 authorization flow, API Gateway route filtering.

---

## 🏗️ End-to-End System Architecture

The platform implements a multi-tier, distributed, and event-driven architecture containerized with Docker:

<p align="center">
  <img width="812" height="434" alt="image" src="https://github.com/user-attachments/assets/679aa9ff-5867-481c-b5a2-d3f441e91752" />

  <br>
  <em><b>Figure 2:</b> Architecture globale distribuée en couches (Edge MQTT, Apache Kafka, Microservices Spring Boot & FastAPI, Cloudflare R2, et Dashboard React).</em>
</p>

### Detailed Architectural Layers

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 1. ACQUISITION LAYER (EDGE)                                                            │
│    Smartwatch Sensor Application (PPG Optical Sensor + 3-Axis ACC Accelerometer)       │
│    └─────────► Protocol: MQTT (Topic: vitals/+/data)                                  │
└────────────────────────────────────────┬───────────────────────────────────────────────┘
                                         ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 2. INGESTION & EVENT STREAMING LAYER (MESSAGING BUS)                                   │
│    Eclipse Mosquitto Broker (Port 1885) ──► Spring Boot Ingestion Service (Port 8081)  │
│    └─────────► Apache Kafka / Redpanda Bus (Topics: vitals-data, medical-alerts)       │
└──────────────────┬─────────────────────────────────────────────────┬───────────────────┘
                   │                                                 │
                   ▼                                                 ▼
┌──────────────────────────────────────┐  ┌──────────────────────────────────────────────┐
│ 3. PERSISTENCE & VITALS SERVICE      │  │ 4. AI ANALYTICS & ANOMALY DETECTION          │
│    Spring Boot Vitals Management     │  │    Python 3.11 / FastAPI Service             │
│    • Real-time SQS calculation       │  │    • 14 Physiological Features Extraction    │
│    • InfluxDB Time-Series Storage    │  │    • XGBoost Classification (< 50 ms)        │
│    • PostgreSQL Relational Storage   │  │    • Kafka Event Emitter (medical-alerts)    │
└──────────────────┬───────────────────┘  └──────────────────────────┬───────────────────┘
                   │                                                 │
                   ▼                                                 ▼ (Only on Anomaly)
┌──────────────────────────────────────┐  ┌──────────────────────────────────────────────┐
│ 5. NOTIFICATION SERVICE              │  │ 6. CLOUD OBJECT STORAGE                      │
│    Spring Boot Notification Service  │  │    Cloudflare R2 Object Storage              │
│    • SSE Live Event Dispatch         │  │    • Bucket: snapshots-medicaux              │
│    • Twilio SMS & Emergency Email    │  │    • Selective Anomaly Windows Storage       │
└──────────────────┬───────────────────┘  └──────────────────────────────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 7. SECURITY & EXPOSITION LAYER                                                         │
│    Spring Cloud API Gateway (Port 8080) ◄──► Keycloak IAM 23 (OAuth2 / JWT / RBAC)     │
└────────────────────────────────────────┬───────────────────────────────────────────────┘
                                         ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 8. CLINICAL PRESENTATION LAYER                                                         │
│    React 19 + Vite Dashboard: "Heartline" (Live PPG Graph, Multi-Patient Roster, SSE)  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

> [!NOTE]
> **Separation of Concerns:** By placing Apache Kafka at the core of the ingestion pipeline, the analytical ML inferencing engine and notification microservices consume events at their own pace without bottlenecking the real-time MQTT sensor ingestion.

---

## 📊 Clinical Monitoring Interface (Heartline Dashboard)

The clinical frontend, named **Heartline**, is a state-of-the-art dark-mode telemetry application built with **React 19**, **Vite**, and **Recharts**. It provides medical specialists with real-time patient status monitoring, live physiological curves, and instant forensic alert evaluation.

<p align="center">
  <img width="613" height="280" alt="image" src="https://github.com/user-attachments/assets/029e9e5f-72e6-46d7-b92a-51bc8ca7b351" />

  <br>
  <em><b>Figure 3:</b> Interface clinique principale Heartline — Suivi télémétrique PPG en temps réel, calcul dynamique du SQS (qualité 98%), statut des patients actifs et panneau de notifications d'alertes SSE.</em>
</p>

### Key Dashboard Capabilities

1. **Real-Time Dynamic Waveform:** Displays the active patient's live PPG curve updated continuously over Server-Sent Events (SSE). High-resolution rendering displays systolic peaks, dicrotic notches, and wave amplitudes.
2. **Real-Time Signal Quality Index:** Real-time SQS gauge (`qualité 98%`) showing signal reliability before medical interpretation.
3. **Multi-Patient Roster:** Side-by-side status tracking of admitted patients categorized dynamically:
   - 🔴 **CRITICAL:** Immediate cardiac anomaly requiring doctor intervention.
   - 🟢 **Actif / Normal:** Stable patient with high-quality vital signals.
   - ⚪ **Standby:** Sensor connected, waiting for stream activation.
4. **Live SSE Notification Feed:** Real-time stream displaying timestamped cardiac events categorized by severity.

### Forensic Anomaly Inspection Modal

When an alert is flagged by the AI engine, the clinician can immediately open the **Courbe brute de l'alerte** modal to examine the high-frequency raw signal segment captured during the event window:

<p align="center">
  <img width="530" height="368" alt="image" src="https://github.com/user-attachments/assets/cfc31078-5dfd-4ed9-805b-6a2614ce69e3" />

  <br>
  <em><b>Figure 4:</b> Modal d'investigation clinique — Inspection détaillée de la courbe brute (Amplitude vs. Point brut) capturée lors de l'anomalie critique (CRITICAL-3).</em>
</p>

> [!TIP]
> **Clinical Utility:** The raw curve viewer plots raw amplitude across discrete sensor points (1 to 164), enabling cardiologists to visually confirm whether an irregularity is a genuine ventricular arrhythmia or sensor motion noise before dispatching emergency medical teams.

---

## 🧠 Signal Processing & Sensor Fusion (PPG + ACC)

Photoplethysmography (PPG) sensors worn on the wrist are susceptible to movement artifacts caused by walking, typing, or gestural shifts. To guarantee high diagnostic accuracy, the platform fuses PPG with 3-axis accelerometer data:

```text
PPG Optical Stream (50 Hz) ─────────┐
                                    ├──► [ Temporal Alignment & Windowing ]
ACC 3-Axis Stream (50 Hz) ──────────┘                    │
                                                         ▼
                                          [ Dynamic Noise Filtering ]
                                                         │
                                                         ▼
                                          [ Signal Quality Score (SQS) ]
                                                         │
                                      ┌──────────────────┴──────────────────┐
                                      ▼ (SQS >= 70%)                        ▼ (SQS < 70%)
                         [ Validated Physiological Stream ]     [ Flagged: Motion Artifact ]
                                      │
                                      ▼
                        [ InfluxDB & Feature Extraction ]
```

1. **Temporal Alignment:** PPG and ACC data packets are matched and aligned on unified sliding timestamp windows.
2. **Signal Quality Score (SQS):** A metric from 0% to 100% computed from signal skewness, baseline wander, and ACC variance. Measurements below the confidence threshold are flagged to avoid false positives.

---

## 🤖 AI-Based Cardiac Anomaly Detection

The **Data Analytics Service** is an asynchronous microservice developed with **FastAPI** and **Python 3.11**.

```text
              PPG / Physiological Window
                         │
                         ▼
        ┌───────────────────────────────────┐
        │  14 Feature Extraction Pipeline   │
        │  • Heart Rate Variability (HRV)   │
        │  • Peak-to-Peak Amplitude         │
        │  • Pulse Transit Interval         │
        │  • Statistical Moments (Skew/Kurt)│
        └─────────────────┬─────────────────┘
                          │
                          ▼
        ┌───────────────────────────────────┐
        │    XGBoost Classifier Model       │
        │    Inference Latency: < 50 ms     │
        └─────────────────┬─────────────────┘
                          │
                 ┌────────┴────────┐
                 ▼                 ▼
             NORMAL             ANOMALY
                                   │
                                   ▼
               ┌───────────────────────────────────────┐
               │ Emit Kafka Event (medical-alerts)     │
               │ Trigger Selective Cloudflare R2 Upload│
               └───────────────────────────────────────┘
```

- **Feature Engineering:** Computes 14 distinct physiological features including systolic/diastolic timing ratios, pulse rate variability, kurtosis, and spectral energy.
- **XGBoost Inference:** Classifies each physiological segment in **< 50 ms**, making it suitable for immediate critical alerting.
- **Event Dispatch:** Detected anomalies are published immediately to the Kafka topic `medical-alerts`.

---

## 🗄️ Hybrid Tiered Storage (PostgreSQL, InfluxDB & Cloudflare R2)

The architecture leverages a hybrid storage strategy where each database technology fulfills a targeted medical data persistence requirement:

<p align="center">
  <img width="564" height="260" alt="image" src="https://github.com/user-attachments/assets/82b0d20d-e879-41b7-8b20-54f2dc096aa1" />

  <br>
  <em><b>Figure 5:</b> Interface Cloudflare R2 (<code>snapshots-medicaux/anomalies/</code>) — Stockage objet sélectif partitionné par dossier patient pour l'archivage médico-légal des anomalies.</em>
</p>

### Storage Strategy Matrix

| Storage Layer | Technology | Data Stored | Access Pattern | Retention |
|---|---|---|---|---|
| **Relational Data** | **PostgreSQL 15** | Patient profiles, medical history, user credentials, alert audit logs | Fast ACID queries, SQL joins | Permanent |
| **Time-Series Telemetry** | **InfluxDB 2.7** | Continuous high-frequency vital metrics (PPG, ACC, heart rate, SQS) | Real-time writes, Flux window aggregations | 30 days |
| **Object Cloud Storage** | **Cloudflare R2** | High-density raw signal windows (`.json` / `.bin`) **captured only during anomalies** | On-demand forensic audit by clinicians | Long-term archival |

> [!IMPORTANT]
> **Cost & Network Optimization (Selective Archiving):** Continuously uploading continuous 50 Hz raw signals to the cloud is bandwidth-prohibitive and expensive. The platform persists continuous data locally in InfluxDB, and **only triggers Cloudflare R2 uploads when an anomaly is confirmed**. Cloudflare R2 provides zero-egress fees and S3 compatibility.

---

## 🚨 Multi-Channel Alert & Notification System

Cardiac safety requires zero-latency alerting across multiple channels depending on severity level:

```text
                          XGBoost Anomaly Event
                                    │
                                    ▼
                         Kafka (medical-alerts)
                                    │
                                    ▼
                       Notification Microservice
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
[ SSE Live Stream ]         [ Twilio SMS API ]          [ SMTP Mail Dispatch ]
Real-time dashboard         Urgent SMS sent to          Emergency report sent to
pop-up and alert log        on-duty cardiologist        clinic emergency team
(NORMAL / WARNING)          (CRITICAL only)             (CRITICAL only)
```

- **Severity Triage:**
  - `NORMAL`: Regular telemetry sync, green status indicator.
  - `WARNING`: Visual notification on the Heartline dashboard via Server-Sent Events.
  - `CRITICAL`: Instant SSE pop-up + automated SMS dispatched via Twilio + urgent email notification dispatched to medical staff.
- **Audit Persistence:** Every dispatched notification is recorded in PostgreSQL with timestamp, delivery status, and patient ID.

---

## 🔐 Security, Authentication & Access Control

Medical telemetry involves sensitive health information (PHI) protected under international data security regulations:

<p align="center">
  <img src="https://img.shields.io/badge/Keycloak-23.0-000000?style=flat-square&logo=keycloak&logoColor=white" alt="Keycloak" />
  <img src="https://img.shields.io/badge/OAuth2-OpenID%20Connect-blue?style=flat-square" alt="OAuth2" />
  <img src="https://img.shields.io/badge/Tokens-Stateless%20JWT-orange?style=flat-square" alt="JWT" />
  <img src="https://img.shields.io/badge/Authorization-RBAC-green?style=flat-square" alt="RBAC" />
</p>

1. **Centralized Identity Provider:** **Keycloak 23** manages user identities, credentials, and realm configurations (`iot-sante`).
2. **Role-Based Access Control (RBAC):**
   - `DOCTOR`: Full access to live telemetry, patient historical records, and alert triage.
   - `PATIENT`: Access restricted strictly to own physiological metrics.
   - `ADMIN`: Infrastructure monitoring and credential administration.
3. **API Gateway Token Verification:** The **Spring Cloud API Gateway** intercepts every inbound request, validates the cryptographic signature of the Bearer JWT token against Keycloak's JWKS endpoint, and enforces route authorizations before proxying.

---

## 🛠️ Microservices Ecosystem & Port Matrix

| Service / Container | Base Technology | Internal Port | Host Port | Role & Key Responsibilities |
|---|---|---|---|---|
| **`api-gateway`** | Spring Cloud Gateway | `8080` | `8080` | Single entry point, JWT validation, service reverse proxy |
| **`medical-dashboard`**| React 19 + Vite | `80` | `5173` | Clinical telemetry frontend (Heartline) |
| **`ingestion-service`** | Spring Boot 3.x | `8081` | `8081` | MQTT subscriber, Kafka producer, R2 anomaly snapshot uploader |
| **`vitals-management`** | Spring Boot 3.x | `9090` | `9090` | Vitals transformation, InfluxDB persistence, SSE server |
| **`data-analytics`** | FastAPI / Python 3.11| `8000` | — | 14-feature extraction, XGBoost anomaly classification |
| **`notification-service`**| Spring Boot 3.x | `9095` | `9095` | Multi-channel alert dispatch (SSE, Twilio SMS, Email) |
| **`mosquitto`** | Eclipse Mosquitto | `1883`, `9001` | `1885`, `9002` | MQTT broker for smartwatch and mobile edge ingest |
| **`kafka`** (Redpanda) | Redpanda / Kafka | `9092` | `9093` | High-throughput distributed event streaming bus |
| **`influxdb`** | InfluxDB 2.7 | `8086` | `8088` | High-frequency physiological time-series database |
| **`postgres`** | PostgreSQL 15 Alpine | `5432` | `5432` | Relational storage for ingestion, notifications & Keycloak |
| **`keycloak`** | Keycloak 23.0 | `8080` | `8180` | OAuth2 / OpenID Connect Identity & Access Management |
| **`pgadmin`** | pgAdmin 4 | `80` | `5050` | Web administration interface for PostgreSQL databases |

---

## 📈 Experimental Performance & Load Testing

The platform was subjected to extensive stress and endurance testing to validate reliability under multi-patient hospital ward conditions:

### 50-Patient Multi-Stream Benchmark (300 Seconds)

| Evaluated Parameter | Measured Value | Operational Assessment |
|---|---:|---|
| 👥 **Simultaneous Patients** | **50 concurrent streams** | Full hospital department load simulated |
| 📨 **Messages Processed** | **4,780 messages** | Continuous ingestion without backpressure |
| ⚡ **Average Ingestion Throughput** | **12.2 msg/sec** | High-throughput steady state |
| 🚀 **Hourly Equivalent Ingestion** | **46,258 msg/hour** | Capable of high sustained clinical traffic |
| ✅ **Packet Delivery Success Rate** | **100.0%** | Zero message drop across Kafka & InfluxDB |
| ⏱️ **Average End-to-End Latency** | **340 ms** | From sensor emission to dashboard rendering |
| 🤖 **AI Model Inference Latency** | **< 50 ms** | Real-time classification by XGBoost |
| 💻 **Ingestion Service CPU Load** | **4.54%** | Highly efficient resource utilization |
| 🧠 **Ingestion Memory Footprint** | **536.3 MiB** | Stable footprint with zero memory leaks |

> [!NOTE]
> Testing was performed using Python load testing scripts (`simulate_multi_patients.py` and `simulate_ppg_acc_mqtt_anomalies.py`) emitting real patient PPG/ACC waveforms with randomized arrhythmic events.

---

## 🚀 Quickstart & Deployment Guide

### 📋 Prerequisites

Ensure the following tools are installed on your workstation:
- [Git](https://git-scm.com/)
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (v24+ with Compose v2)
- Java 17+ & Python 3.11+ (Optional, for bare-metal testing)

---

### 1️⃣ Clone the Repository

```bash
git clone https://github.com/eya-boukeri/pfa.git
cd pfa
```

---

### 2️⃣ Configure Environment & Cloudflare R2 Credentials

To enable cloud storage for anomaly snapshots, verify or configure your credentials in `pfa-infrastructure/.env.r2`:

```env
CLOUDFLARE_R2_ENABLED=true
CLOUDFLARE_R2_ACCOUNT_ID=your_account_id
CLOUDFLARE_R2_ACCESS_KEY_ID=your_access_key
CLOUDFLARE_R2_SECRET_ACCESS_KEY=your_secret_key
CLOUDFLARE_R2_BUCKET_NAME=snapshots-medicaux
CLOUDFLARE_R2_ENDPOINT=https://<your_account_id>.r2.cloudflarestorage.com
CLOUDFLARE_R2_OBJECT_PREFIX=anomalies
```

---

### 3️⃣ Launch the Full Infrastructure with Docker Compose

From the `pfa-infrastructure/` directory:

```bash
cd pfa-infrastructure
docker compose --env-file .env.r2 up -d --build
```

---

### 4️⃣ Verify Container Health

```bash
docker compose ps
```

You should see all containers reported as `healthy` or `running`:
- `mosquitto-pfa-v2`
- `influxdb-pfa-v2`
- `kafka-pfa-v2`
- `postgres-pfa`
- `keycloak-pfa`
- `ingestion-service-pfa`
- `vitals-management-pfa`
- `data-analytics-service-pfa`
- `notification-service-pfa`
- `api-gateway`
- `medical-dashboard-pfa`

---

### 5️⃣ Access Web Interfaces

| Interface | URL | Credentials / Notes |
|---|---|---|
| **Heartline Medical Dashboard** | [http://localhost:5173](http://localhost:5173) | Real-time monitoring UI |
| **Spring Cloud API Gateway** | [http://localhost:8080](http://localhost:8080) | Health endpoint: `/actuator/health` |
| **Keycloak Admin Console** | [http://localhost:8180](http://localhost:8180) | `admin` / `admin123` (Realm: `iot-sante`) |
| **InfluxDB Web Console** | [http://localhost:8088](http://localhost:8088) | `admin` / `password123` (Bucket: `medical_data`) |
| **pgAdmin 4** | [http://localhost:5050](http://localhost:5050) | `eyaboukari91@gmail.com` / `eya` |

---

### 6️⃣ Run Multi-Patient Anomaly Simulation

Inject simulated multi-patient cardiac telemetry with anomalous arrhythmic spikes to test the full pipeline:

```bash
# From the project root
python simulate_ppg_acc_mqtt_anomalies.py
```

Watch the **Heartline** dashboard update live at `http://localhost:5173` with real-time curves and instant alert cards!

---

## 👩‍💻 Academic Context & Author

<table align="center">
  <tr>
    <td align="center" width="160">
      <img src="https://img.icons8.com/color/144/caduceus.png" width="90" alt="MedTech" />
      <br>
      <b>PFA2 — 2025–2026</b>
    </td>
    <td>
      <b>National Engineering School of Tunis (ENIT)</b><br>
      <b>Laboratory:</b> RISC Laboratory (<em>Recherche en Ingénierie des Systèmes Complexes</em>)<br>
      <b>Project:</b> IoMT Telemonitoring Platform for Cardiac Remote Monitoring<br>
      <b>Academic Supervisor:</b> Prof. Soumaya Meherzi
    </td>
  </tr>
</table>

### Author Profile

**Eya Boukari**  
*Computer Engineering Student — National Engineering School of Tunis (ENIT)*  
*Concurrent Master's Degree Student in TICV*

<p align="center">
  <a href="https://github.com/eya-boukeri" target="_blank">
    <img src="https://img.shields.io/badge/GitHub-eya--boukeri-181717?style=for-the-badge&logo=github" alt="GitHub" />
  </a>
  &nbsp;
  <a href="https://linkedin.com/in/aya-boukari" target="_blank">
    <img src="https://img.shields.io/badge/LinkedIn-Eya%20Boukari-0A66C2?style=for-the-badge&logo=linkedin" alt="LinkedIn" />
  </a>
</p>

---

<p align="center">
  <strong>🩺 From Wearable Physiological Signals to Real-Time Medical Intelligence.</strong>
  <br>
  <em>Bridging IoT Edge Computing, Event Streaming & AI for Tomorrow's Connected Healthcare.</em>
</p>
