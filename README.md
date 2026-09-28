# 🩺 IoMT Telemonitoring Platform

<p align="center">
  <strong>Real-Time IoT Medical Platform for Cardiac Remote Monitoring</strong>
</p>

<p align="center">
  Event-driven microservices architecture for the acquisition, processing,
  analysis and monitoring of physiological signals.
</p>

<p align="center">

![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![Kafka](https://img.shields.io/badge/Apache%20Kafka-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Python](https://img.shields.io/badge/Python-3776AB?style=for-the-badge&logo=python&logoColor=white)
![XGBoost](https://img.shields.io/badge/XGBoost-FF6600?style=for-the-badge)
![React](https://img.shields.io/badge/React-20232A?style=for-the-badge&logo=react&logoColor=61DAFB)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![InfluxDB](https://img.shields.io/badge/InfluxDB-22ADF6?style=for-the-badge&logo=influxdb&logoColor=white)

</p>

---

## 📌 Overview

The **IoMT Telemonitoring Platform** is a proof-of-concept platform designed for **remote cardiac monitoring** using physiological signals collected from a smartwatch-based sensor application.

The platform transforms raw physiological measurements into structured data, performs signal preprocessing and analysis, detects potential cardiac anomalies using Machine Learning, and communicates relevant alerts to monitoring interfaces.

The system is built around an **event-driven microservices architecture**, combining MQTT, Apache Kafka, Spring Boot, FastAPI, XGBoost, PostgreSQL, InfluxDB, Keycloak and React.

---

## 🎯 Project Context

This project was developed as part of **PFA2 at ENIT**, within the **RISC Laboratory**, under the supervision of **Prof. Soumaya Meherzi**.

The project addresses several challenges related to remote cardiac monitoring:

- Continuous acquisition of physiological data
- Reliability of PPG signals in the presence of motion artifacts
- Real-time processing of IoT data streams
- Detection of abnormal cardiac patterns
- Secure access to sensitive medical information
- Scalable communication between distributed services

The proposed solution combines **IoT, Edge Computing, Distributed Systems and Machine Learning** in a unified medical monitoring platform.

---

# 💡 Solution

The platform follows a complete processing pipeline:

```text
Smartwatch / Sensor Application
              │
              │ MQTT
              ▼
        MQTT Broker
         Mosquitto
              │
              ▼
     Ingestion Service
              │
              │ Kafka
              ▼
        Apache Kafka
              │
      ┌───────┴────────┐
      │                │
      ▼                ▼
Signal Processing   Data Pipeline
      │
      ▼
PPG + ACC Fusion
      │
      ▼
Signal Quality Score
      │
      ▼
    InfluxDB
      │
      ▼
Data Analytics Service
      │
      ▼
14 Physiological Features
      │
      ▼
     XGBoost
      │
      ▼
Normal / Anomaly
      │
      ▼
  Alert Generation
      │
      ├──────────────► React Dashboard
      │
      └──────────────► Notification Service
```

---

# ✨ Key Features

## 📡 Real-Time Vital Data Acquisition

The platform receives physiological measurements from a smartwatch sensor application through **MQTT**.

The Edge/Sensor application acts as an acquisition layer between the physical sensors and the backend infrastructure.

The collected data includes physiological signals such as:

- ❤️ PPG
- 📱 ACC / accelerometer data
- 🫀 Heart-related measurements
- 🩸 Other structured vital-sign measurements

The sensor data is encapsulated in structured JSON messages before being transmitted through MQTT.

---

## 🔄 Event-Driven Data Processing

The platform uses **Apache Kafka** as the central event bus.

The ingestion layer receives MQTT messages and publishes them to Kafka topics, allowing downstream services to process the data independently.

The pipeline includes topics such as:

```text
signals.raw
signals.filtered
signals.aggregated
```

This architecture allows the different services to consume the appropriate level of information without tightly coupling the entire system.

---

# 🧠 Signal Processing & Sensor Fusion

One of the key aspects of the platform is the combination of **PPG and ACC signals**.

PPG signals can be affected by motion artifacts. The accelerometer data is therefore used to provide contextual information about the patient's movement.

The preprocessing pipeline aligns the PPG and ACC streams and computes a **Signal Quality Score (SQS)**.

```text
PPG Signal ──────────┐
                     │
                     ▼
              Temporal Alignment
                     ▲
                     │
ACC Signal ──────────┘
                     │
                     ▼
            Signal Quality Score
                     │
                     ▼
          Validated Physiological Data
```

This preprocessing layer helps prevent unreliable measurements from being directly used by the anomaly detection pipeline.

---

# 🤖 AI-Based Anomaly Detection

The platform includes a dedicated **Data Analytics Service** implemented with FastAPI.

The service extracts **14 physiological features** from the processed PPG signal and uses an **XGBoost** model to classify the resulting data.

The classification produces two main outcomes:

```text
             PPG / Physiological Data
                       │
                       ▼
               Feature Extraction
                       │
                       ▼
                14 Features
                       │
                       ▼
                    XGBoost
                       │
              ┌────────┴────────┐
              ▼                 ▼
           NORMAL            ANOMALY
                                │
                                ▼
                           Alert Event
```

The reported inference time is **less than 50 ms**, making the model suitable for the real-time monitoring requirements targeted by the project. :contentReference[oaicite:2]{index=2}

---

# 🚨 Alert Management

Detected anomalies are propagated through the event-driven architecture.

The notification service manages alerts according to their severity.

### Normal / Warning

The React dashboard can receive real-time updates through **Server-Sent Events (SSE)**.

### Critical

Critical events can trigger external notification mechanisms through:

- 📱 Twilio
- 📧 SMTP / Email

The notification history is stored in PostgreSQL.

```text
XGBoost
   │
   │ Anomaly
   ▼
Kafka
   │
   ▼
Notification Service
   │
   ├──────────────► React Dashboard
   │
   ├──────────────► SMS / WhatsApp
   │
   └──────────────► Email
```

---

# 🔐 Security

Medical data requires controlled and authenticated access.

The platform therefore integrates **Keycloak** with an API Gateway for centralized authentication and authorization.

### Security components

- 🔐 Keycloak
- 🔑 OAuth2 / OpenID Connect
- 🎫 JWT
- 👥 Role-Based Access Control (RBAC)
- 🚪 API Gateway

The API Gateway validates access tokens before forwarding requests to the protected microservices.

This avoids duplicating authentication logic across individual services. :contentReference[oaicite:3]{index=3}

---

# 🏗️ System Architecture

The platform follows a distributed, event-driven architecture organized into several layers.

```text
┌───────────────────────────────────────────────────────────────┐
│                     PRESENTATION LAYER                        │
│                                                               │
│                  React Monitoring Dashboard                   │
└───────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────┐
│                       SECURITY LAYER                           │
│                                                               │
│              API Gateway + Keycloak + OAuth2/JWT              │
└───────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────┐
│                    MICROSERVICES LAYER                         │
│                                                               │
│  Ingestion Service     Vitals Management     Data Analytics   │
│  Spring / MQTT         Spring Boot           FastAPI          │
└───────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────┐
│                     EVENT / STREAMING LAYER                    │
│                                                               │
│                         Apache Kafka                           │
│                                                               │
│  signals.raw → signals.filtered → signals.aggregated          │
└───────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────┐
│                    DATA & ANALYTICS LAYER                       │
│                                                               │
│        InfluxDB              PostgreSQL          XGBoost       │
│    Time-Series Data       Structured Data       ML Model      │
└───────────────────────────────────────────────────────────────┘
```

The architecture is orchestrated using **Docker Compose** and a dedicated Docker network. :contentReference[oaicite:4]{index=4}

---

# 🗄️ Data Storage

The platform uses two complementary databases.

## PostgreSQL

PostgreSQL is used for structured information requiring strong consistency and long-term persistence, including:

- Patient-related structured data
- Sensor metadata
- Alert history
- Notification records

## InfluxDB

InfluxDB is used for high-volume physiological time-series data.

It is used to efficiently store and query measurements such as:

- PPG
- ACC
- Aggregated physiological measurements

This combination separates structured transactional information from high-frequency time-series data. :contentReference[oaicite:5]{index=5}

---

# 📊 Monitoring Dashboard

The React dashboard provides a monitoring interface for medical users.

It is designed to display:

- ❤️ Latest vital measurements
- 📈 Historical trends
- 🚨 Detected anomalies
- ⚠️ Warning events
- 🔴 Critical alerts
- 📡 Real-time updates



```

---

# 🛠️ Technology Stack

| Category | Technologies |
|---|---|
| 🏗️ Architecture | Microservices, Event-Driven Architecture |
| 📡 IoT Communication | MQTT |
| 📨 Messaging | Apache Kafka |
| ☕ Backend | Spring Boot, Spring WebFlux |
| 🐍 AI Service | FastAPI, Python |
| 🤖 Machine Learning | XGBoost |
| 📊 Signal Processing | PPG, ACC, Feature Engineering |
| 🗄️ Relational Database | PostgreSQL |
| ⏱️ Time-Series Database | InfluxDB |
| 🎨 Frontend | React |
| 🔐 Authentication | Keycloak, OAuth2, OIDC, JWT |
| 📡 Real-Time Updates | Server-Sent Events (SSE) |
| 📱 Notifications | Twilio, SMTP |
| 🐳 Deployment | Docker, Docker Compose |
| 🔧 Development | Git, REST APIs |

The selected technologies and their architectural roles are documented in the project report. :contentReference[oaicite:6]{index=6}

---

# 🔄 End-to-End Data Flow

The complete data lifecycle can be summarized as follows:

```text
1. Sensor Acquisition
        │
        ▼
2. MQTT Transmission
        │
        ▼
3. Ingestion Service
        │
        ▼
4. Kafka Event Streaming
        │
        ▼
5. PPG / ACC Preprocessing
        │
        ▼
6. Signal Quality Assessment
        │
        ▼
7. Time-Series Persistence
        │
        ▼
8. Feature Extraction
        │
        ▼
9. XGBoost Classification
        │
        ▼
10. Alert Generation
        │
        ├──────────────► Dashboard
        │
        └──────────────► Notifications
```

---

# 📈 Performance Validation

The platform was evaluated under simulated multi-patient workloads.

The validation campaign included tests with multiple simultaneous patients and different durations.

One reported validation scenario reached:

| Metric | Result |
|---|---:|
| 👥 Simulated patients | **50** |
| 📨 Messages processed | **4,780** |
| ⚡ Average throughput | **12.2 msg/s** |
| ✅ Success rate | **100%** |
| ⏱️ Test duration | **300 seconds** |
| 💻 Ingestion CPU | **4.54%** |
| 🧠 Memory | **536.3 MiB** |

The 50-patient / 300-second endurance test maintained a 100% success rate while processing 4,780 messages. :contentReference[oaicite:7]{index=7}

A separate validation reported **46,258 messages/hour**, **100% success rate** and **340 ms average latency** for 50 simultaneous patients. These figures are also reflected in the project's current CV description. :contentReference[oaicite:8]{index=8}

> **Note:** Performance values depend on the test scenario and hardware configuration. The figures above should therefore be interpreted as experimental validation results rather than universal system limits.

---

# 🐳 Deployment

The platform is designed to run as a set of containerized services using **Docker Compose**.

The environment can include:

```text
Docker Compose
│
├── API Gateway
├── Keycloak
├── MQTT Broker
├── Kafka
├── Ingestion Service
├── Vitals Management
├── Data Analytics
├── Notification Service
├── PostgreSQL
├── InfluxDB
└── React Dashboard
```

Containerization provides:

- Reproducible environments
- Service isolation
- Simplified deployment
- Easier local development
- Independent service scaling

---

# 🚀 Getting Started

## 📋 Prerequisites

Before running the project, make sure you have:

- [Git](https://git-scm.com/)
- [Docker Desktop](https://www.docker.com/products/docker-desktop/)
- Docker Compose

The project is designed to run using containerized services.

---

## 1️⃣ Clone the Repository

```bash
git clone <(https://github.com/eya-boukeri/pfa/)>
cd <YOUR_REPOSITORY_NAME>
```

---

## 2️⃣ Start the Infrastructure

From the project root:

```bash
docker compose up -d
```

---

## 3️⃣ Check Running Services

```bash
docker compose ps
```

---

## 4️⃣ View Logs

For all services:

```bash
docker compose logs -f
```

For a specific service:

```bash
docker compose logs -f <service-name>
```

---

## 5️⃣ Stop the Platform

```bash
docker compose stop
```

---

## 6️⃣ Remove Containers

```bash
docker compose down
```

---


---

# 🎯 Project Contributions

The project focuses on three main technical contributions:

### 1. Intelligent Data Ingestion

A pipeline combining PPG and ACC signals to assess signal quality before downstream analysis.

### 2. Context-Aware AI Analysis

An XGBoost-based analytics service using 14 physiological features to classify cardiac anomalies.

### 3. Secure Alert Orchestration

An event-driven alert system integrating Kafka, the React dashboard and external notification mechanisms, protected by Keycloak and JWT-based authentication. :contentReference[oaicite:10]{index=10}

---

# 🔮 Future Improvements

Potential extensions of the platform include:

- 📈 More advanced physiological signal analysis
- 🤖 Further improvement of anomaly detection models
- 🧠 More extensive model validation
- ☁️ Cloud-based deployment of selected services
- 📊 Advanced monitoring and analytics
- 🔐 Further security hardening
- 📡 Integration of additional wearable sensors
- ⚙️ More extensive scalability testing

---

# 👩‍💻 Project

### PFA2 — ENIT RISC Laboratory

**IoMT Telemonitoring Platform for Cardiac Remote Monitoring**

**Institution:** National Engineering School of Tunis (ENIT)  
**Laboratory:** RISC Laboratory  
**Supervisor:** Prof. Soumaya Meherzi  
**Period:** 2025–2026

---

# 👩‍💻 Author

### Eya Boukari

**Computer Engineering Student — ENIT, Tunisia**  
**Concurrent Master's Student in TICV**

Interested in:

- 🤖 Artificial Intelligence
- ⚙️ Distributed Systems
- 📡 IoT & Edge Computing
- 🩺 AI for Healthcare
- 📊 Signal Processing
- 💻 Software Engineering

<p align="center">
  <a href="https://github.com/eya-boukeri">
    <img src="https://img.shields.io/badge/GitHub-eya--boukeri-181717?style=for-the-badge&logo=github" alt="GitHub">
  </a>
  <a href="https://linkedin.com/in/aya-boukari">
    <img src="https://img.shields.io/badge/LinkedIn-Eya%20Boukari-0A66C2?style=for-the-badge&logo=linkedin" alt="LinkedIn">
  </a>
</p>

---

<p align="center">
  <strong>🩺 From wearable signals to intelligent medical monitoring.</strong>
</p>

<p align="center">
  <em>Building distributed systems for real-time healthcare applications.</em>
</p>
