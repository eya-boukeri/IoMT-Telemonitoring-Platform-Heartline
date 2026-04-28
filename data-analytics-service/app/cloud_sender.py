"""
cloud_sender.py — Module HTTP pour l'extraction des données brutes et l'envoi
des snapshots d'anomalie vers l'ingestion-service (PostgreSQL).

Flux :
  1. Quand une anomalie est détectée, on récupère les 200 derniers points bruts
     depuis PostgreSQL via l'ingestion-service (GET).
  2. On crée un snapshot structuré et on l'envoie via POST.
  3. L'ingestion-service le stocke dans la table `anomaly_snapshots`.
  4. Le snapshotId retourné est inclus dans l'alerte Kafka pour que le
     dashboard puisse afficher la courbe brute.
"""

import logging
import json
import requests

logger = logging.getLogger(__name__)


def _to_number(value):
    try:
        num = float(value)
        if num != num:  # NaN
            return None
        return num
    except (TypeError, ValueError):
        return None


def _extract_ppg_points_from_payload(payload, base_timestamp=None):
    """
    Extrait des points PPG [{timestamp, value}] depuis un payload brut.
    Tolère plusieurs formats de payload Android/legacy.
    """
    data = payload
    if isinstance(data, str):
        try:
            data = json.loads(data)
        except json.JSONDecodeError:
            return []

    if not isinstance(data, dict):
        return []

    points = []

    ppg_array = data.get("ppgData")
    if isinstance(ppg_array, list):
        for idx, value in enumerate(ppg_array):
            number = _to_number(value)
            if number is not None:
                points.append({
                    "timestamp": data.get("timestamp") or base_timestamp,
                    "value": number,
                    "offset": idx,
                })

    ppg_map = data.get("ppg")
    if isinstance(ppg_map, dict):
        for ts, value in ppg_map.items():
            number = _to_number(value)
            if number is not None:
                points.append({"timestamp": str(ts), "value": number})

    direct = (
        data.get("ppgGreenAverage")
        if data.get("ppgGreenAverage") is not None
        else data.get("ppgSignal")
    )
    number = _to_number(direct)
    if number is not None:
        points.append({
            "timestamp": data.get("timestamp") or base_timestamp,
            "value": number,
        })

    return points


def build_snapshot_payload(raw_signals, max_points=200):
    """
    Construit un payload de snapshot compact à partir des signaux bruts PostgreSQL.
    Le résultat contient exactement les derniers `max_points` points quand possible.
    """
    collected = []

    for item in raw_signals or []:
        if not isinstance(item, dict):
            continue

        base_timestamp = item.get("timestamp")
        payload = item.get("rawPayload")
        points = _extract_ppg_points_from_payload(payload, base_timestamp=base_timestamp)
        if points:
            collected.extend(points)

    if not collected:
        return {
            "source": "postgres.raw_signals",
            "format": "ppg_points_v1",
            "pointCount": 0,
            "points": [],
        }

    if len(collected) > max_points:
        collected = collected[-max_points:]

    return {
        "source": "postgres.raw_signals",
        "format": "ppg_points_v1",
        "pointCount": len(collected),
        "points": collected,
    }


def fetch_raw_points(ingestion_url, patient_id, limit=200, timeout=10):
    """
    Récupère les `limit` derniers points bruts d'un patient
    depuis PostgreSQL via l'ingestion-service.

    Args:
        ingestion_url: URL de base de l'ingestion-service (ex: http://ingestion-service:8081/api/ingestion)
        patient_id: ID du patient
        limit: Nombre de points à récupérer (défaut: 200)
        timeout: Timeout HTTP en secondes

    Returns:
        Liste de dicts [{timestamp, rawPayload}, ...] ou liste vide en cas d'erreur
    """
    url = f"{ingestion_url}/raw-signals/{patient_id}/latest?limit={limit}"
    try:
        response = requests.get(url, timeout=timeout)
        if response.status_code == 200:
            data = response.json()
            logger.info(f"Fetched {len(data)} raw points for patient {patient_id}")
            return data
        else:
            logger.warning(f"Failed to fetch raw points: HTTP {response.status_code} - {response.text[:200]}")
            return []
    except requests.exceptions.ConnectionError:
        logger.warning(f"Cannot connect to ingestion-service at {url}")
        return []
    except requests.exceptions.Timeout:
        logger.warning(f"Timeout fetching raw points from {url}")
        return []
    except Exception as e:
        logger.error(f"Error fetching raw points: {e}")
        return []


def send_anomaly_snapshot(ingestion_url, alert_id, patient_id, detected_at,
                          severity, confidence, model_version, message, raw_points,
                          timeout=10, max_points=200):
    """
    Envoie un snapshot d'anomalie vers l'ingestion-service pour stockage
    dans PostgreSQL (table anomaly_snapshots).

    Args:
        ingestion_url: URL de base de l'ingestion-service
        alert_id: ID de l'alerte associée
        patient_id: ID du patient
        detected_at: Timestamp ISO de la détection
        severity: Niveau de sévérité (WARNING, CRITICAL)
        confidence: Score de confiance du modèle
        model_version: Version du modèle ML
        message: Message descriptif de l'alerte
        raw_points: Liste des points bruts récupérés via fetch_raw_points()
        timeout: Timeout HTTP en secondes

    Returns:
        snapshotId (str) si succès, None sinon
    """
    url = f"{ingestion_url}/anomaly-snapshots"

    snapshot_data = build_snapshot_payload(raw_points, max_points=max_points)

    payload = {
        "alertId": alert_id,
        "patientId": patient_id,
        "detectedAt": detected_at,
        "severity": severity,
        "confidence": confidence,
        "modelVersion": model_version,
        "message": message,
        "rawData": json.dumps(snapshot_data)
    }

    try:
        response = requests.post(url, json=payload, timeout=timeout)
        if response.status_code == 201:
            result = response.json()
            snapshot_id = result.get("snapshotId")
            logger.info(f"Anomaly snapshot created: snapshotId={snapshot_id}, alertId={alert_id}")
            return snapshot_id
        else:
            logger.warning(f"Failed to create snapshot: HTTP {response.status_code} - {response.text[:200]}")
            return None
    except requests.exceptions.ConnectionError:
        logger.warning(f"Cannot connect to ingestion-service at {url}")
        return None
    except requests.exceptions.Timeout:
        logger.warning(f"Timeout sending snapshot to {url}")
        return None
    except Exception as e:
        logger.error(f"Error sending anomaly snapshot: {e}")
        return None
