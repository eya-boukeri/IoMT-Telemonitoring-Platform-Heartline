#!/usr/bin/env python3
"""Simulateur MQTT multi-patients (PPG/ACC) avec anomalies optionnelles."""

from __future__ import annotations

import argparse
import json
import math
import random
import signal
import sys
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

import paho.mqtt.client as mqtt


@dataclass
class PatientConfig:
    patient_id: str
    heart_rate_bpm: float
    profile: str  # 'normal' or 'active'
    anomaly_mode: str
    anomaly_interval: float
    anomaly_duration: float
    device_id: str = "simulator-mqtt"
    sample_rate: float = 50.0
    qos: int = 1
    retain: bool = False


@dataclass
class RuntimeStats:
    published: int = 0
    failed: int = 0
    started_at: float = 0.0


@dataclass
class ConnectionState:
    connected: bool = False


def iso_utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def apply_anomaly(
    green_raw: float,
    red_raw: float,
    anomaly_mode: str,
    anomaly_active: bool,
) -> Tuple[float, float]:
    """Applique l'anomalie sur l'amplitude PPG."""
    green, red = green_raw, red_raw
    if not anomaly_active:
        return green, red

    if anomaly_mode == "dropout":
        green = green * 0.05 + random.uniform(-5, 5)
        red = red * 0.05 + random.uniform(-3, 3)
    elif anomaly_mode == "motion":
        if random.random() < 0.1:
            green += random.uniform(-300, 300)
            red += random.uniform(-200, 200)
        else:
            green += random.gauss(0, 50)
            red += random.gauss(0, 40)
    elif anomaly_mode == "tachy":
        green = green * 1.5
        red = red * 1.3
    elif anomaly_mode == "brady":
        green = green * 0.8
        red = red * 0.7
    elif anomaly_mode == "arrhythmia":
        green += random.uniform(-80, 80)
        red += random.uniform(-60, 60)

    if anomaly_mode not in ("dropout", "motion"):
        green += random.gauss(0, 15)
        red += random.gauss(0, 10)

    return green, red


def build_payload(
    sample_index: int,
    sample_rate_hz: float,
    config: PatientConfig,
    anomaly_active: bool,
    base_hr_bpm: float,
    ppg_noise_std: float,
    motion_scale: float,
) -> Dict[str, Any]:
    t = sample_index / sample_rate_hz

    # Anomalies modifiant la fréquence cardiaque
    hr_instant = base_hr_bpm
    if anomaly_active:
        if config.anomaly_mode == "tachy":
            hr_instant = base_hr_bpm * 1.8
        elif config.anomaly_mode == "brady":
            hr_instant = base_hr_bpm * 0.4
        elif config.anomaly_mode == "arrhythmia":
            hr_instant = base_hr_bpm * (1 + 0.2 * math.sin(2 * math.pi * 0.5 * t))

    heart_freq_hz = hr_instant / 60.0
    phase = 2.0 * math.pi * heart_freq_hz * t
    resp_mod = 1.0 + 0.03 * math.sin(2.0 * math.pi * 0.22 * t)
    ppg_wave = 1200.0 + (240.0 * resp_mod) * math.sin(phase) + 35.0 * math.sin(2.0 * phase)
    green = ppg_wave + random.gauss(0.0, ppg_noise_std)
    red = (ppg_wave * 0.82) + random.gauss(0.0, max(1.0, ppg_noise_std * 0.9))

    green, red = apply_anomaly(green, red, config.anomaly_mode, anomaly_active)

    # Accéléromètre
    ax = motion_scale * (0.20 * math.sin(2.0 * math.pi * 0.90 * t) + random.gauss(0.0, 0.03))
    ay = motion_scale * (0.12 * math.sin(2.0 * math.pi * 1.10 * t) + random.gauss(0.0, 0.04))
    az = motion_scale * (0.18 * math.cos(2.0 * math.pi * 0.70 * t) + random.gauss(0.0, 0.03))

    timestamp = iso_utc_now()

    return {
        "patientId": config.patient_id,
        "deviceId": config.device_id,
        "startTime": timestamp,
        "endTime": timestamp,
        "sampleRateHz": sample_rate_hz,
        "ppgData": [{"timestamp": timestamp, "green": round(green, 3), "red": round(red, 3)}],
        "accelerometerData": [{"timestamp": timestamp, "x": round(ax, 4), "y": round(ay, 4), "z": round(az, 4)}],
    }


def resolve_publish_topic(topic_template: str, patient_id: str) -> str:
    if "{patient_id}" in topic_template:
        return topic_template.replace("{patient_id}", patient_id)
    if "+" in topic_template:
        return topic_template.replace("+", patient_id)
    return topic_template


def patient_worker(config: PatientConfig, args: argparse.Namespace, stop_event: threading.Event):
    """Fonction exécutée dans un thread pour un patient."""
    # Profil
    profile_map = {
        "normal": {"ppg_noise_std": 2.0, "motion_scale": 0.20},
        "active": {"ppg_noise_std": 8.0, "motion_scale": 1.00},
    }
    profile = profile_map.get(config.profile, profile_map["normal"])
    period_s = 1.0 / config.sample_rate
    stats = RuntimeStats(started_at=time.monotonic())
    conn_state = ConnectionState(connected=False)

    # Client MQTT
    if hasattr(mqtt, "CallbackAPIVersion"):
        client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"{config.patient_id}-{args.client_id}",
            clean_session=True,
            protocol=mqtt.MQTTv311,
        )
    else:
        client = mqtt.Client(client_id=f"{config.patient_id}-{args.client_id}", clean_session=True, protocol=mqtt.MQTTv311)

    if args.username:
        client.username_pw_set(args.username, args.password)
    if args.tls:
        client.tls_set()

    publish_topic = resolve_publish_topic(args.topic, config.patient_id)

    def on_connect(c, _ud, _fl, rc, _prop=None):
        conn_state.connected = rc == 0
        if conn_state.connected:
            print(f"[{config.patient_id}] MQTT connecté")
        else:
            print(f"[{config.patient_id}] Échec connexion rc={rc}", file=sys.stderr)

    def on_disconnect(c, _ud, _fl, rc, _prop=None):
        conn_state.connected = False
        print(f"[{config.patient_id}] Déconnecté rc={rc}", file=sys.stderr)

    client.on_connect = on_connect
    client.on_disconnect = on_disconnect

    client.connect(args.broker_host, args.broker_port, keepalive=60)
    client.loop_start()

    # Attendre connexion
    deadline = time.monotonic() + args.connect_timeout
    while not conn_state.connected and time.monotonic() < deadline:
        time.sleep(0.05)
    if not conn_state.connected:
        client.loop_stop()
        client.disconnect()
        print(f"[{config.patient_id}] Impossible de se connecter à MQTT", file=sys.stderr)
        return

    next_tick = time.monotonic()
    next_log = next_tick + args.log_every
    sample_index = 0

    # Gestion anomalies cycliques
    anomaly_active = False
    anomaly_timer_end = 0.0
    use_anomalies = config.anomaly_mode != "none"

    print(f"[{config.patient_id}] Démarrage (HR={config.heart_rate_bpm} BPM, profile={config.profile}, anomalies={config.anomaly_mode})")

    try:
        while not stop_event.is_set():
            now = time.monotonic()
            if args.duration_seconds > 0 and (now - stats.started_at) >= args.duration_seconds:
                break

            # Cycle anomalies
            if use_anomalies:
                if not anomaly_active and now >= anomaly_timer_end:
                    anomaly_active = True
                    anomaly_timer_end = now + config.anomaly_duration
                    print(f"[{config.patient_id}] [ANOMALIE] Début {config.anomaly_mode}")
                elif anomaly_active and now >= anomaly_timer_end:
                    anomaly_active = False
                    anomaly_timer_end = now + config.anomaly_interval
                    print(f"[{config.patient_id}] [ANOMALIE] Fin")

            if now < next_tick:
                time.sleep(next_tick - now)

            payload = build_payload(
                sample_index,
                config.sample_rate,
                config,
                anomaly_active,
                base_hr_bpm=config.heart_rate_bpm,
                ppg_noise_std=profile["ppg_noise_std"],
                motion_scale=profile["motion_scale"],
            )
            encoded = json.dumps(payload, separators=(",", ":"))
            info = client.publish(publish_topic, encoded, qos=config.qos, retain=config.retain)

            if info.rc == mqtt.MQTT_ERR_SUCCESS:
                stats.published += 1
            else:
                stats.failed += 1

            sample_index += 1
            next_tick += period_s
            lag = time.monotonic() - next_tick
            if lag > period_s:
                next_tick = time.monotonic() + period_s

            if time.monotonic() >= next_log:
                run_time = max(time.monotonic() - stats.started_at, 1e-6)
                actual_hz = (stats.published + stats.failed) / run_time
                print(f"[{config.patient_id}] ok={stats.published} fail={stats.failed} freq={actual_hz:.2f} Hz {'[ANOMALIE]' if anomaly_active else ''}")
                next_log += args.log_every
    finally:
        client.loop_stop()
        client.disconnect()
        run_time = time.monotonic() - stats.started_at
        print(f"[{config.patient_id}] Arrêt: publiés={stats.published} durée={run_time:.1f}s")


def main():
    parser = argparse.ArgumentParser(description="Simulateur multi-patients MQTT (PPG/ACC)")
    parser.add_argument("--broker-host", default="127.0.0.1")
    parser.add_argument("--broker-port", type=int, default=1885)
    parser.add_argument("--topic", default="vitals/{patient_id}/data")
    parser.add_argument("--client-id", default="multi-simulator")
    parser.add_argument("--duration-seconds", type=int, default=0, help="0 = infini")
    parser.add_argument("--connect-timeout", type=float, default=5.0)
    parser.add_argument("--log-every", type=float, default=5.0)
    parser.add_argument("--username", default="")
    parser.add_argument("--password", default="")
    parser.add_argument("--tls", action="store_true")

    # Multi-patient arguments
    parser.add_argument("--patients", required=True, help="Liste d'IDs patients séparés par virgules (ex: pat1,pat2,pat3)")
    parser.add_argument("--heart-rate-range", default="70,80", help="Plage BPM min,max (ex: 60,100)")
    parser.add_argument("--profile", default="normal", choices=["normal", "active"], help="Profil par défaut (peut être surchargé)")
    parser.add_argument("--anomaly-mode", default="none", choices=["none", "arrhythmia", "dropout", "tachy", "brady", "motion", "random"],
                        help="Mode d'anomalie commun à tous ou 'random' pour mélange")
    parser.add_argument("--anomaly-interval", type=float, default=30.0)
    parser.add_argument("--anomaly-duration", type=float, default=8.0)
    parser.add_argument("--sample-rate", type=float, default=50.0)
    parser.add_argument("--qos", type=int, default=1, choices=[0,1,2])

    args = parser.parse_args()

    # Parse patients list
    patient_ids = [p.strip() for p in args.patients.split(",")]
    if not patient_ids:
        print("Erreur: au moins un patient requis", file=sys.stderr)
        sys.exit(1)

    # Parse HR range
    hr_min, hr_max = map(float, args.heart_rate_range.split(","))
    if hr_min >= hr_max:
        print("Erreur: heart-rate-range invalide", file=sys.stderr)
        sys.exit(1)

    # Préparer les configurations patient
    configs: List[PatientConfig] = []
    for pid in patient_ids:
        hr = random.uniform(hr_min, hr_max)
        # Si anomalies = random, on attribue un type aléatoire différent pour chaque patient
        anomaly_mode = args.anomaly_mode
        if anomaly_mode == "random":
            choices = ["arrhythmia", "dropout", "tachy", "brady", "motion"]
            anomaly_mode = random.choice(choices)
        configs.append(PatientConfig(
            patient_id=pid,
            heart_rate_bpm=hr,
            profile=args.profile,
            anomaly_mode=anomaly_mode,
            anomaly_interval=args.anomaly_interval,
            anomaly_duration=args.anomaly_duration,
            sample_rate=args.sample_rate,
            qos=args.qos,
            retain=False,
        ))

    print(f"Lancement de {len(configs)} patients: {', '.join(p.patient_id for p in configs)}")
    for cfg in configs:
        print(f"  - {cfg.patient_id}: HR={cfg.heart_rate_bpm:.1f} BPM, anomalies={cfg.anomaly_mode}")

    stop_event = threading.Event()
    threads = []

    def signal_handler(sig, frame):
        print("\nArrêt demandé, fermeture des threads...")
        stop_event.set()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    for cfg in configs:
        t = threading.Thread(target=patient_worker, args=(cfg, args, stop_event), name=f"Thread-{cfg.patient_id}")
        t.daemon = True
        t.start()
        threads.append(t)

    # Attendre que tous les threads se terminent (normalement jamais jusqu'à stop)
    for t in threads:
        t.join()

    print("Simulation multi-patients terminée.")


if __name__ == "__main__":
    main()