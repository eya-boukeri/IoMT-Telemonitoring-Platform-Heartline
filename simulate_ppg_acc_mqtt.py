#!/usr/bin/env python3
"""Simulateur MQTT PPG/ACC a 50 Hz."""

from __future__ import annotations

import argparse
import json
import math
import random
import signal
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict

import paho.mqtt.client as mqtt


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


def build_payload(
    sample_index: int,
    sample_rate_hz: float,
    patient_id: str,
    device_id: str,
    base_hr_bpm: float,
    ppg_noise_std: float,
    motion_scale: float,
) -> Dict[str, Any]:
    t = sample_index / sample_rate_hz

    heart_freq_hz = base_hr_bpm / 60.0
    phase = 2.0 * math.pi * heart_freq_hz * t
    # Add light respiratory modulation to keep the waveform realistic but stable.
    resp_mod = 1.0 + 0.03 * math.sin(2.0 * math.pi * 0.22 * t)
    ppg_wave = 1200.0 + (240.0 * resp_mod) * math.sin(phase) + 35.0 * math.sin(2.0 * phase)
    green = ppg_wave + random.gauss(0.0, ppg_noise_std)
    red = (ppg_wave * 0.82) + random.gauss(0.0, max(1.0, ppg_noise_std * 0.9))

    # Provide linear acceleration around 0 so motion filtering can distinguish true movement
    # instead of permanently flagging gravity as artifact.
    ax = motion_scale * (0.20 * math.sin(2.0 * math.pi * 0.90 * t) + random.gauss(0.0, 0.03))
    ay = motion_scale * (0.12 * math.sin(2.0 * math.pi * 1.10 * t) + random.gauss(0.0, 0.04))
    az = motion_scale * (0.18 * math.cos(2.0 * math.pi * 0.70 * t) + random.gauss(0.0, 0.03))

    timestamp = iso_utc_now()

    return {
        "patientId": patient_id,
        "deviceId": device_id,
        "startTime": timestamp,
        "endTime": timestamp,
        "sampleRateHz": sample_rate_hz,
        "ppgData": [
            {
                "timestamp": timestamp,
                "green": round(green, 3),
                "red": round(red, 3),
            }
        ],
        "accelerometerData": [
            {
                "timestamp": timestamp,
                "x": round(ax, 4),
                "y": round(ay, 4),
                "z": round(az, 4),
            }
        ],
    }


def build_client(args: argparse.Namespace) -> mqtt.Client:
    if hasattr(mqtt, "CallbackAPIVersion"):
        client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=args.client_id,
            clean_session=True,
            protocol=mqtt.MQTTv311,
        )
    else:
        client = mqtt.Client(client_id=args.client_id, clean_session=True, protocol=mqtt.MQTTv311)

    if args.username:
        client.username_pw_set(args.username, args.password)

    if args.tls:
        client.tls_set()

    return client


def resolve_publish_topic(topic_template: str, patient_id: str) -> str:
    # MQTT wildcard '+' is valid for subscriptions, not for publishing.
    if "{patient_id}" in topic_template:
        return topic_template.replace("{patient_id}", patient_id)
    if "+" in topic_template:
        return topic_template.replace("+", patient_id)
    return topic_template


def run_simulation(args: argparse.Namespace) -> int:
    stop_requested = False

    def handle_stop(_sig: int, _frame: Any) -> None:
        nonlocal stop_requested
        stop_requested = True

    signal.signal(signal.SIGINT, handle_stop)
    signal.signal(signal.SIGTERM, handle_stop)

    profile_map = {
        "normal": {"ppg_noise_std": 2.0, "motion_scale": 0.20},
        "active": {"ppg_noise_std": 8.0, "motion_scale": 1.00},
    }
    profile_settings = profile_map[args.profile]

    period_s = 1.0 / args.sample_rate
    stats = RuntimeStats(started_at=time.monotonic())
    conn_state = ConnectionState(connected=False)
    client = build_client(args)
    publish_topic = resolve_publish_topic(args.topic, args.patient_id)

    def on_connect(client_obj: mqtt.Client, _userdata: Any, _flags: Any, reason_code: Any, _properties: Any = None) -> None:
        rc = int(reason_code) if isinstance(reason_code, (int, float)) else getattr(reason_code, "value", -1)
        conn_state.connected = rc == 0
        if conn_state.connected:
            print(f"MQTT connecte: {args.broker_host}:{args.broker_port}")
        else:
            print(f"MQTT echec connexion rc={rc}", file=sys.stderr)

    def on_disconnect(client_obj: mqtt.Client, _userdata: Any, _flags_or_rc: Any, reason_code: Any = 0, _properties: Any = None) -> None:
        conn_state.connected = False
        rc = int(reason_code) if isinstance(reason_code, (int, float)) else getattr(reason_code, "value", 0)
        print(f"MQTT deconnecte rc={rc}", file=sys.stderr)

    client.on_connect = on_connect
    client.on_disconnect = on_disconnect

    client.connect(args.broker_host, args.broker_port, keepalive=60)
    client.loop_start()

    wait_deadline = time.monotonic() + args.connect_timeout
    while not conn_state.connected and time.monotonic() < wait_deadline:
        time.sleep(0.05)

    if not conn_state.connected:
        client.loop_stop()
        client.disconnect()
        raise RuntimeError(
            "Connexion MQTT impossible. Verifiez host/port et broker actif (ex: 127.0.0.1:1885)."
        )

    next_tick = time.monotonic()
    next_log = next_tick + args.log_every
    sample_index = 0

    print(
        (
            f"Demarrage simulation MQTT -> {args.broker_host}:{args.broker_port} "
            f"| topic={publish_topic} | patient={args.patient_id} "
            f"| frequence={args.sample_rate:.2f} Hz | duree={args.duration_seconds or 'infinie'}s"
        )
    )

    try:
        while not stop_requested:
            now = time.monotonic()
            elapsed = now - stats.started_at

            if args.duration_seconds > 0 and elapsed >= args.duration_seconds:
                break

            if now < next_tick:
                time.sleep(next_tick - now)

            payload = build_payload(
                sample_index=sample_index,
                sample_rate_hz=args.sample_rate,
                patient_id=args.patient_id,
                device_id=args.device_id,
                base_hr_bpm=args.heart_rate_bpm,
                ppg_noise_std=profile_settings["ppg_noise_std"],
                motion_scale=profile_settings["motion_scale"],
            )
            encoded = json.dumps(payload, separators=(",", ":"))

            info = client.publish(publish_topic, encoded, qos=args.qos, retain=args.retain)

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
                print(
                    (
                        f"[{iso_utc_now()}] total={stats.published + stats.failed} "
                        f"ok={stats.published} fail={stats.failed} "
                        f"freq_reelle={actual_hz:.2f} Hz"
                    )
                )
                next_log += args.log_every
    finally:
        client.loop_stop()
        client.disconnect()

    run_time = max(time.monotonic() - stats.started_at, 1e-6)
    actual_hz = (stats.published + stats.failed) / run_time
    print("Simulation terminee")
    print(
        (
            f"resume: total={stats.published + stats.failed}, ok={stats.published}, "
            f"fail={stats.failed}, duree={run_time:.1f}s, freq_moyenne={actual_hz:.2f} Hz"
        )
    )
    return 0 if stats.published > 0 else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Publie en continu des trames JSON PPG+ACC sur MQTT a 50 Hz."
    )
    parser.add_argument("--broker-host", default="127.0.0.1", help="Host du broker MQTT.")
    parser.add_argument("--broker-port", type=int, default=1885, help="Port du broker MQTT.")
    parser.add_argument(
        "--topic",
        default="vitals/{patient_id}/data",
        help="Topic MQTT cible. Ex: vitals/{patient_id}/data ou vitals/+/data.",
    )
    parser.add_argument("--client-id", default="ppg-acc-simulator", help="Client ID MQTT.")
    parser.add_argument("--patient-id", default="patient-001", help="Identifiant patient.")
    parser.add_argument("--device-id", default="simulator-mqtt", help="Identifiant appareil.")
    parser.add_argument(
        "--sample-rate",
        type=float,
        default=50.0,
        help="Frequence d'envoi en Hz (defaut: 50).",
    )
    parser.add_argument(
        "--duration-seconds",
        type=int,
        default=0,
        help="Duree totale en secondes. 0 = infini.",
    )
    parser.add_argument(
        "--heart-rate-bpm",
        type=float,
        default=74.0,
        help="Frequence cardiaque moyenne simulee.",
    )
    parser.add_argument(
        "--profile",
        choices=["normal", "active"],
        default="normal",
        help="Profil de simulation: normal (calme) ou active (plus de bruit/mouvement).",
    )
    parser.add_argument("--qos", type=int, choices=[0, 1, 2], default=1, help="QoS MQTT.")
    parser.add_argument("--retain", action="store_true", help="Publie les messages en retained.")
    parser.add_argument(
        "--connect-timeout",
        type=float,
        default=5.0,
        help="Temps max d'attente de connexion MQTT en secondes.",
    )
    parser.add_argument(
        "--log-every",
        type=float,
        default=5.0,
        help="Intervalle de log de supervision en secondes.",
    )
    parser.add_argument("--username", default="", help="Username MQTT optionnel.")
    parser.add_argument("--password", default="", help="Password MQTT optionnel.")
    parser.add_argument("--tls", action="store_true", help="Active TLS (configuration systeme).")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    if args.sample_rate <= 0:
        print("Erreur: --sample-rate doit etre > 0", file=sys.stderr)
        return 2
    if args.log_every <= 0:
        print("Erreur: --log-every doit etre > 0", file=sys.stderr)
        return 2

    try:
        return run_simulation(args)
    except Exception as exc:
        print(f"Erreur: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
