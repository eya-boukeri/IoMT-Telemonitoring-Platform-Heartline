import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import NormalPatientVisualization from './NormalPatientVisualization';
import './App.css';

const API_ROOT = import.meta.env.REACT_APP_API_URL || '/api';
const API_BASE_URL = `${API_ROOT}/vitals`;
const SSE_BASE_URL = API_BASE_URL;
// Direct service endpoints (bypass gateway authentication for local dev)
const NOTIFICATION_API_ROOT = import.meta.env.VITE_NOTIFICATION_API_URL || 
                              import.meta.env.REACT_APP_NOTIFICATION_API_URL || 
                              (window.location.hostname === 'localhost' ? 'http://localhost:9095/api' : '/api');
const NOTIFICATION_SSE_BASE_URL = `${NOTIFICATION_API_ROOT}/notifications`;
const INGESTION_API_ROOT = import.meta.env.VITE_INGESTION_API_URL ||
                           import.meta.env.REACT_APP_INGESTION_API_URL ||
                           (window.location.hostname === 'localhost' ? 'http://localhost:8081/api/ingestion' : '/api/ingestion');
const MAX_POINTS = 250;
const AUTO_UPDATE_MS = 3000;
const MAX_ALERTS = 8;
const FALLBACK_PATIENT_ID = 'patient-001';

const DEFAULT_METRICS = {
  ppgSignal: null,
  ppgGreenMin: 1220,
  ppgGreenMax: 1280,
  signalQualityScore: null,
  ppgDataPoints: null,
};

const clamp = (value, min, max) => Math.max(min, Math.min(max, value));

const toNumber = (value) => {
  if (value === null || value === undefined) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
};

const toTimestampMs = (timestamp) => {
  if (!timestamp) return null;
  const parsed = new Date(timestamp).getTime();
  return Number.isFinite(parsed) ? parsed : null;
};

const buildVitalPoint = (raw, previousPoint) => {
  const previous = previousPoint || DEFAULT_METRICS;
  const base = raw || {};
  const timestamp = base.timestamp || new Date().toISOString();

  const resolvedPpg =
    toNumber(base.ppgFilteredSignal) ??
    toNumber(base.ppgFilteredMean) ??
    toNumber(base.ppgSignal) ??
    toNumber(base.ppg) ??
    toNumber(base.greenAvg) ??
    toNumber(base.ppgGreenAverage) ??
    toNumber(base.ppgMean) ??
    DEFAULT_METRICS.ppgSignal;

  const ppgSignal = resolvedPpg === null ? null : clamp(resolvedPpg, 0, 6000);

  return {
    timestamp,
    ppgSignal,
    ppgGreenMin: clamp(
      toNumber(base.ppgGreenMin) ??
        toNumber(base.ppgMin) ??
        previous.ppgGreenMin ??
        (ppgSignal ?? DEFAULT_METRICS.ppgGreenMin) - 20,
      0,
      6000
    ),
    ppgGreenMax: clamp(
      toNumber(base.ppgGreenMax) ??
        toNumber(base.ppgMax) ??
        previous.ppgGreenMax ??
        (ppgSignal ?? DEFAULT_METRICS.ppgGreenMax) + 20,
      0,
      6000
    ),
    ppgDataPoints: toNumber(base.ppgDataPoints) ?? previous.ppgDataPoints ?? null,
    signalQualityScore: toNumber(base.signalQualityScore) ?? toNumber(base.signalQuality) ?? previous.signalQualityScore ?? null,
  };
};

const formatTime = (timestamp) => {
  if (!timestamp) return '';
  return new Date(timestamp).toLocaleTimeString('fr-FR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
};

const avatarClassPool = ['av-jd', 'av-ml', 'av-ra'];

const getAvatarClass = (value) => {
  if (!value) return avatarClassPool[0];

  const hash = String(value)
    .split('')
    .reduce((sum, char) => sum + char.charCodeAt(0), 0);

  return avatarClassPool[hash % avatarClassPool.length];
};

const getAvatarLabel = (value) => {
  if (!value) return 'PT';

  const tokens = String(value).trim().split(/\s+/);
  if (tokens.length === 1) {
    return tokens[0].slice(0, 2).toUpperCase();
  }

  return `${tokens[0][0]}${tokens[1][0]}`.toUpperCase();
};

const getSeverityClass = (severity) => {
  const normalized = String(severity || 'WARNING').toUpperCase();

  if (normalized === 'CRITICAL') {
    return 'critical';
  }

  if (normalized === 'WARNING' || normalized === 'MEDIUM') {
    return 'monitor';
  }

  return 'stable';
};

const safeParseJson = (value) => {
  if (typeof value !== 'string') {
    return value;
  }

  try {
    return JSON.parse(value);
  } catch (_error) {
    return null;
  }
};

const extractSnapshotSeries = (snapshot) => {
  const rawData = safeParseJson(snapshot?.rawData);

  if (!rawData) {
    return [];
  }

  const points = Array.isArray(rawData)
    ? rawData
    : Array.isArray(rawData.points)
      ? rawData.points
      : [];

  const series = points.map((point, index) => {
    const numericValue = toNumber(point?.value);
    if (numericValue === null) {
      return null;
    }

    return {
      index,
      value: Number(numericValue.toFixed(2)),
      timestamp: point?.timestamp || null,
    };
  }).filter(Boolean);

  return series.slice(-200);
};

function App() {
  const [patients, setPatients] = useState([]);
  const [selectedPatient, setSelectedPatient] = useState(null);
  const [vitalData, setVitalData] = useState([]);
  const [alerts, setAlerts] = useState([]);
  const [snapshotSeries, setSnapshotSeries] = useState([]);
  const [snapshotMeta, setSnapshotMeta] = useState(null);
  const [snapshotLoading, setSnapshotLoading] = useState(false);
  const [snapshotError, setSnapshotError] = useState('');
  const [isConnected, setIsConnected] = useState(false);
  const [lastRefreshAt, setLastRefreshAt] = useState(null);
  const [showNormalPatientViz, setShowNormalPatientViz] = useState(false); // Masquer par défaut
  const eventSourceRef = useRef(null);
  const notificationEventSourcesRef = useRef([]);
  const lastPointAtRef = useRef(0);

  const pushAlert = useCallback((rawAlert) => {
    if (!rawAlert || typeof rawAlert !== 'object') {
      return;
    }

    const normalized = {
      id: rawAlert.alertId || rawAlert.alert_id || `${Date.now()}-${Math.random()}`,
      patientId: rawAlert.patientId || rawAlert.patient_id || 'Patient inconnu',
      alertType: rawAlert.alertType || rawAlert.alert_type || 'notification',
      message: rawAlert.message || 'Nouvelle alerte recue',
      severity: String(rawAlert.severity || 'WARNING').toUpperCase(),
      timestamp: rawAlert.timestamp || new Date().toISOString(),
      snapshotId: rawAlert.snapshotId || rawAlert.snapshot_id || null,
    };

    setAlerts((previous) => {
      const withoutDuplicate = previous.filter((item) => item.id !== normalized.id);
      return [normalized, ...withoutDuplicate].slice(0, MAX_ALERTS);
    });
  }, []);

  const closeSnapshotModal = useCallback(() => {
    setSnapshotMeta(null);
    setSnapshotSeries([]);
    setSnapshotError('');
    setSnapshotLoading(false);
  }, []);

  const openSnapshotForAlert = useCallback(async (alert) => {
    if (!alert) {
      return;
    }

    setSnapshotLoading(true);
    setSnapshotError('');
    setSnapshotSeries([]);
    setSnapshotMeta({
      alertId: alert.id,
      snapshotId: alert.snapshotId || null,
      patientId: alert.patientId,
      message: alert.message,
      severity: alert.severity,
      timestamp: alert.timestamp,
    });

    try {
      const preferredUrl = alert.snapshotId
        ? `${INGESTION_API_ROOT}/anomaly-snapshots/${alert.snapshotId}`
        : `${INGESTION_API_ROOT}/anomaly-snapshots/by-alert/${alert.id}`;

      let response = await fetch(preferredUrl);

      if (!response.ok && alert.snapshotId) {
        response = await fetch(`${INGESTION_API_ROOT}/anomaly-snapshots/by-alert/${alert.id}`);
      }

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const snapshot = await response.json();
      const series = extractSnapshotSeries(snapshot);

      if (!series.length) {
        throw new Error('Snapshot vide ou format non reconnu');
      }

      setSnapshotSeries(series);
      setSnapshotMeta((previous) => ({
        ...(previous || {}),
        snapshotId: snapshot?.id || previous?.snapshotId || null,
      }));
    } catch (error) {
      setSnapshotError(error?.message || 'Impossible de charger le snapshot brut');
    } finally {
      setSnapshotLoading(false);
    }
  }, []);

  const pushVitalPoint = useCallback((rawPoint) => {
    setVitalData((previous) => {
      const previousPoint = previous.length ? previous[previous.length - 1] : null;
      const nextPoint = buildVitalPoint(rawPoint, previousPoint);

      if (nextPoint.ppgSignal === null) {
        return previous;
      }

      // Keep a strictly increasing timeline to avoid chart compression caused by
      // historical SSE events arriving after fresh points.
      const nextMs = toTimestampMs(nextPoint.timestamp);
      const previousMs = toTimestampMs(previousPoint?.timestamp);
      
      // Reject if timestamps are way too old (e.g. historical data arriving late)
      // but allow slight out-of-order points (e.g. within same second) from the watch
      if (previousMs !== null && nextMs !== null && nextMs < previousMs - 5000) {
        return previous;
      }

      // Reject exact duplicates
      if (previousPoint && previousPoint.timestamp === nextPoint.timestamp) {
        return previous;
      }

      lastPointAtRef.current = Date.now();
      setLastRefreshAt(nextPoint.timestamp);
      
      // Ensure strict FIFO: append new point and trim from start if needed
      const result = [...previous, nextPoint];
      return result.length > MAX_POINTS ? result.slice(-MAX_POINTS) : result;
    });
  }, []);

  const fetchStats = useCallback(async (patientId) => {
    try {
      await fetch(`${API_BASE_URL}/stats/${patientId}`);
    } catch (error) {
      console.error('Error refreshing stats snapshot:', error);
    }
  }, []);

  const fetchLatestPoint = useCallback(async (patientId) => {
    try {
      const response = await fetch(`${API_BASE_URL}/latest/${patientId}?limit=1`);
      const data = await response.json();
      if (!Array.isArray(data) || !data.length) {
        return null;
      }
      return data[0];
    } catch (error) {
      console.error('Error fetching latest point:', error);
      return null;
    }
  }, []);

  const fetchLatestBatch = useCallback(async (patientId) => {
    try {
      const response = await fetch(`${API_BASE_URL}/latest/${patientId}?limit=${MAX_POINTS}`);
      const data = await response.json();

      if (!Array.isArray(data) || !data.length) {
        return;
      }

      // Sort incoming data strictly ascending by timestamp to prevent chart compression
      const sorted = [...data].sort((a, b) => {
        const aMs = toTimestampMs(a.timestamp);
        const bMs = toTimestampMs(b.timestamp);
        if (aMs === null) return 1;
        if (bMs === null) return -1;
        return aMs - bMs;
      });

      const normalized = sorted.reduce((accumulator, point) => {
        const previous = accumulator.length ? accumulator[accumulator.length - 1] : null;
        accumulator.push(buildVitalPoint(point, previous));
        return accumulator;
      }, []);

      // Merge with existing data: only add batch points that are older than our current data
      setVitalData((existing) => {
        if (!existing.length) {
          return normalized.slice(-MAX_POINTS);
        }

        // If batch data is all newer than existing, use it directly
        const lastExistingMs = toTimestampMs(existing[existing.length - 1].timestamp);
        const firstNewMs = toTimestampMs(normalized[0].timestamp);

        if (firstNewMs > lastExistingMs) {
          return normalized.slice(-MAX_POINTS);
        }

        // Otherwise, keep existing and ignore batch (SSE is fresher)
        return existing;
      });

      const latest = normalized[normalized.length - 1];
      if (latest) {
        lastPointAtRef.current = Date.now();
        setLastRefreshAt(latest.timestamp);
      }
    } catch (error) {
      console.error('Error fetching latest data:', error);
    }
  }, []);

  const fetchPatients = useCallback(async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/patients?days=30`);
      const data = await response.json();
      // Ajouter temporairement patient-normal à la liste
      const patientList = Array.isArray(data) ? [...data] : [];
      if (!patientList.includes('patient-normal')) {
        patientList.push('patient-normal');
      }
      setPatients(patientList);
      if (patientList.length > 0 && !selectedPatient) {
        setSelectedPatient(patientList[0]);
      }
    } catch (error) {
      console.error('Error fetching patients:', error);
      setPatients([FALLBACK_PATIENT_ID, 'patient-normal']);
      setSelectedPatient((currentSelectedPatient) => currentSelectedPatient || FALLBACK_PATIENT_ID);
    }
  }, [selectedPatient]);

  useEffect(() => {
    fetchPatients();
  }, [fetchPatients]);

  useEffect(() => {
    if (!selectedPatient) return;

    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }

    fetchLatestBatch(selectedPatient);
    fetchStats(selectedPatient);

    const eventSource = new EventSource(`${SSE_BASE_URL}/stream/${selectedPatient}`);
    eventSourceRef.current = eventSource;

    eventSource.onopen = () => {
      setIsConnected(true);
    };

    eventSource.onmessage = (event) => {
      try {
        const parsed = JSON.parse(event.data);
        if (!parsed || typeof parsed !== 'object') {
          return;
        }
        pushVitalPoint(parsed);
      } catch (_error) {
        // Ignore non-JSON messages.
      }
    };

    eventSource.onerror = () => {
      setIsConnected(false);
    };

    return () => {
      if (eventSource) {
        eventSource.close();
        setIsConnected(false);
      }
    };
  }, [selectedPatient, fetchLatestBatch, fetchStats, pushVitalPoint]);

  useEffect(() => {
    const validPatients = patients.filter(Boolean);

    // Cleanup previous notification streams before creating new ones.
    notificationEventSourcesRef.current.forEach((source) => {
      try {
        source.close();
      } catch (_error) {
        // Ignore close errors.
      }
    });
    notificationEventSourcesRef.current = [];

    if (!validPatients.length) {
      return;
    }

    const handleNotificationEvent = (event) => {
      try {
        const parsed = JSON.parse(event.data);
        // Only process objects that look like alerts (have alertType or severity or message)
        if (parsed && typeof parsed === 'object' && (parsed.alertType || parsed.severity || parsed.message)) {
          pushAlert(parsed);
        }
      } catch (_error) {
        // Ignore non-JSON housekeeping events.
      }
    };

    const connectNotificationSSE = (patientId) => {
      const source = new EventSource(`${NOTIFICATION_SSE_BASE_URL}/stream/${patientId}`);
      source.addEventListener('alert', handleNotificationEvent);
      source.onmessage = handleNotificationEvent;

      source.onerror = () => {
        // Auto-reconnect: EventSource reconnects automatically by default,
        // but we log for visibility.
        console.warn(`Notification SSE error for ${patientId}, reconnecting...`);
      };

      return source;
    };

    validPatients.forEach((patientId) => {
      const source = connectNotificationSSE(patientId);
      notificationEventSourcesRef.current.push(source);
    });

    return () => {
      notificationEventSourcesRef.current.forEach((source) => {
        try {
          source.close();
        } catch (_error) {
          // Ignore close errors.
        }
      });
      notificationEventSourcesRef.current = [];
    };
  }, [patients, pushAlert]);

  useEffect(() => {
    if (!selectedPatient) return;

    const interval = setInterval(async () => {
      await fetchStats(selectedPatient);

      const isStale = Date.now() - lastPointAtRef.current > AUTO_UPDATE_MS + 500;
      if (isStale) {
        const latestFromApi = await fetchLatestPoint(selectedPatient);
        if (latestFromApi) {
          pushVitalPoint(latestFromApi);
        }
      }
    }, AUTO_UPDATE_MS);

    return () => clearInterval(interval);
  }, [selectedPatient, fetchLatestPoint, fetchStats, pushVitalPoint]);

  const chartData = useMemo(
    () => {
      const latest = vitalData.slice(-MAX_POINTS);
      if (!latest.length) {
        return [];
      }

      const firstTimestampMs = toTimestampMs(latest[0].timestamp) ?? Date.now();

      return latest.map((vital, index) => {
        if (vital.ppgSignal === null || vital.ppgSignal === undefined) {
          return null;
        }

        const timestampMs = toTimestampMs(vital.timestamp);
        const timeSec = timestampMs
          ? (timestampMs - firstTimestampMs) / 1000
          : index / 50;

        return {
          timeSec: Number(timeSec.toFixed(3)),
          ppgFiltered: Number((vital.ppgSignal ?? 0).toFixed(2)),
        };
      }).filter(Boolean);
    },
    [vitalData]
  );

  const ppgDomain = useMemo(() => {
    if (chartData.length < 2) {
      return [-1, 1];
    }

    const values = chartData.map((point) => point.ppgFiltered);
    const min = Math.min(...values);
    const max = Math.max(...values);

    if (!Number.isFinite(min) || !Number.isFinite(max)) {
      return [-1, 1];
    }

    const span = Math.max(max - min, 0.2);
    const padding = span * 0.15;
    return [Number((min - padding).toFixed(2)), Number((max + padding).toFixed(2))];
  }, [chartData]);

  const latestPoint = vitalData.length ? vitalData[vitalData.length - 1] : null;
  const latestSignalQuality = latestPoint?.signalQualityScore;
  const latestDataPoints = latestPoint?.ppgDataPoints;

  const tooltipStyle = {
    background: '#161b22',
    border: '1px solid #1c2128',
    color: '#e6edf3',
    borderRadius: '12px',
    boxShadow: '0 10px 25px rgba(0, 0, 0, 0.35)',
  };

  const currentPatientLabel = selectedPatient || 'Patient inconnu';

  return (
    <div className="dashboard medical-shell">
      <aside className="side-nav">
        <div className="side-logo">Rx</div>
        <button type="button" className="nav-btn active">▦</button>
        <button type="button" className="nav-btn">♡</button>
        <button type="button" className="nav-btn">◷</button>
        <button type="button" className="nav-btn">⊕</button>
        <button type="button" className="nav-btn">☰</button>
        <button
          type="button"
          className={`nav-btn ${showNormalPatientViz ? 'active' : ''}`}
          onClick={() => setShowNormalPatientViz(!showNormalPatientViz)}
          title={showNormalPatientViz ? "Masquer référence patient normal" : "Afficher référence patient normal"}
          style={{ opacity: showNormalPatientViz ? 1 : 0.5 }}
        >
          {showNormalPatientViz ? '📊' : '⚕️'}
        </button>
      </aside>

      <div className="main-panel">
        <header className="top-overview">
          <div className="overview-title-block">
            <h1>Heartline</h1>
            <p>DASHBOARD MEDICAL · SURVEILLANCE TEMPS REEL</p>
          </div>

          <div className="selector-inline">
            <label htmlFor="patient-select">Patient:</label>
            <select
              id="patient-select"
              value={selectedPatient || ''}
              onChange={(event) => setSelectedPatient(event.target.value)}
            >
              {patients.map((patient) => (
                <option key={patient} value={patient}>{patient}</option>
              ))}
            </select>
            <span>Derniere donnee: {lastRefreshAt ? formatTime(lastRefreshAt) : '--:--:--'}</span>
          </div>
        </header>

        <section className="hero-monitor panel">
          <div className="panel-head">
            <h2>🫀 Signal PPG - Temps Réel</h2>
            <p>
              {currentPatientLabel} · Données live · 
              {latestDataPoints ? ` ${latestDataPoints} points` : ''}
              {Number.isFinite(latestSignalQuality) ? ` · qualité ${Math.round(latestSignalQuality)}%` : ''}
              {isConnected ? ' · 🔴 Connecté' : ' · ⚪ Déconnecté'}
            </p>
          </div>

          <div className="hero-graph">
            <span className="ecg-tag">PPG</span>
            <div className="chart-canvas">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={chartData}>
                  <CartesianGrid stroke="#1b2635" strokeDasharray="3 3" vertical={false} />
                  <XAxis
                    dataKey="timeSec"
                    type="number"
                    domain={['dataMin', 'dataMax']}
                    tickFormatter={(value) => `${value.toFixed(1)}`}
                    tick={{ fill: '#9fb3c8', fontSize: 11 }}
                    label={{ value: 'Temps (s)', fill: '#9fb3c8', position: 'insideBottom', offset: -4 }}
                  />
                  <YAxis
                    type="number"
                    domain={ppgDomain}
                    tickFormatter={(value) => value.toFixed(1)}
                    tick={{ fill: '#9fb3c8', fontSize: 11 }}
                    label={{ value: 'Intensite PPG', angle: -90, fill: '#9fb3c8', position: 'insideLeft' }}
                  />
                  <Tooltip
                    contentStyle={tooltipStyle}
                    labelFormatter={(value) => `Temps: ${Number(value).toFixed(2)} s`}
                    formatter={(value) => [`${Number(value).toFixed(2)}`, 'Amplitude PPG']}
                  />
                  <Line
                    type="monotone"
                    dataKey="ppgFiltered"
                    stroke="#2f81f7"
                    strokeWidth={2.5}
                    dot={false}
                    isAnimationActive={false}
                    name="Signal PPG filtre"
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </div>

          <div className="hero-stats">
            <div className={`connection-inline ${isConnected ? 'stable' : 'critical'}`}>
              <span className={`status-dot ${isConnected ? 'connected' : 'disconnected'}`}></span>
              {isConnected ? 'Connecte en temps reel' : 'Connexion perdue'}
            </div>
          </div>
        </section>

        <section className="lower-grid">
          <article className="panel patients-panel">
            <div className="panel-head with-link">
              <div>
                <h3>Active Patients</h3>
                <p>SELECTION PATIENT</p>
              </div>
            </div>

            <div className="patient-list">
              {patients.map((patient) => (
                <button
                  key={patient}
                  type="button"
                  className={`patient-row ${selectedPatient === patient ? 'active' : ''}`}
                  onClick={() => setSelectedPatient(patient)}
                >
                  <span className={`avatar-pill ${getAvatarClass(patient)}`}>{getAvatarLabel(patient)}</span>
                  <span className="patient-id">
                    <strong>{patient}</strong>
                    <small>{selectedPatient === patient ? 'Patient actif' : 'Cliquez pour selectionner'}</small>
                  </span>
                  <span className={`status-chip ${selectedPatient === patient ? 'stable' : 'monitor'}`}>
                    {selectedPatient === patient ? 'Actif' : 'Standby'}
                  </span>
                </button>
              ))}
            </div>
          </article>

          <article className="panel schedule-panel">
            <div className="panel-head">
              <h3>Alertes{alerts.length > 0 && <span className="alert-count-badge">{alerts.length}</span>}</h3>
              <p>FLUX NOTIFICATION</p>
            </div>

            <div className="schedule-list">
              {alerts.slice(0, 4).map((alert) => {
                const severityClass = getSeverityClass(alert.severity);
                const severityLabel = alert.severity || 'INFO';
                const severityBadgeClass = `severity-badge severity-${severityLabel.toLowerCase()}`;
                return (
                  <button
                    key={alert.id}
                    type="button"
                    className="schedule-item schedule-item-button"
                    onClick={() => openSnapshotForAlert(alert)}
                    title="Cliquer pour voir la courbe brute"
                  >
                    <span className="schedule-time">{formatTime(alert.timestamp)}</span>
                    <span className={`schedule-dot ${severityClass}`}></span>
                    <div className={severityBadgeClass}>{severityLabel}</div>
                    <div className="schedule-text">
                      <strong>{alert.alertType}</strong>
                      <p>{alert.message}</p>
                      <small>
                        Patient: {alert.patientId}
                        {alert.snapshotId ? ' · snapshot disponible' : ' · snapshot en attente'}
                      </small>
                    </div>
                  </button>
                );
              })}
              {!alerts.length && <p className="no-data">Aucune alerte pour le moment.</p>}
            </div>
          </article>
        </section>

        {showNormalPatientViz && (
          <section className="normal-patient-section">
            <NormalPatientVisualization />
          </section>
        )}
      </div>

      {snapshotMeta && (
        <div className="snapshot-modal-backdrop" onClick={closeSnapshotModal}>
          <div className="snapshot-modal" onClick={(event) => event.stopPropagation()}>
            <div className="snapshot-modal-header">
              <div>
                <h3>Courbe brute de l'alerte</h3>
                <p>
                  {snapshotMeta.patientId} · {formatTime(snapshotMeta.timestamp)} · {snapshotMeta.severity}
                </p>
              </div>
              <button type="button" className="snapshot-close" onClick={closeSnapshotModal}>Fermer</button>
            </div>

            {snapshotLoading && <p className="no-data">Chargement du snapshot...</p>}
            {!snapshotLoading && snapshotError && <p className="no-data">{snapshotError}</p>}

            {!snapshotLoading && !snapshotError && !!snapshotSeries.length && (
              <div className="snapshot-chart-wrap">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={snapshotSeries}>
                    <CartesianGrid stroke="#1b2635" strokeDasharray="3 3" vertical={false} />
                    <XAxis
                      dataKey="index"
                      tick={{ fill: '#9fb3c8', fontSize: 11 }}
                      label={{ value: 'Point brut', fill: '#9fb3c8', position: 'insideBottom', offset: -4 }}
                    />
                    <YAxis
                      tick={{ fill: '#9fb3c8', fontSize: 11 }}
                      label={{ value: 'Amplitude', angle: -90, fill: '#9fb3c8', position: 'insideLeft' }}
                    />
                    <Tooltip
                      contentStyle={tooltipStyle}
                      labelFormatter={(value) => `Point: ${value}`}
                      formatter={(value) => [`${Number(value).toFixed(2)}`, 'PPG brut']}
                    />
                    <Line
                      type="monotone"
                      dataKey="value"
                      stroke="#f97316"
                      strokeWidth={2}
                      dot={false}
                      isAnimationActive={false}
                    />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
