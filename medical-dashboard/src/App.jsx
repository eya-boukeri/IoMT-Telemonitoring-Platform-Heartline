import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import './App.css';

const API_ROOT = import.meta.env.REACT_APP_API_URL || '/api';
const API_BASE_URL = `${API_ROOT}/vitals`;
const SSE_BASE_URL = API_BASE_URL;
const NOTIFICATION_SSE_BASE_URL = `${API_ROOT}/notifications`;
const MAX_POINTS = 50;
const AUTO_UPDATE_MS = 3000;
const MAX_ALERTS = 8;

const DEFAULT_METRICS = {
  ppgSignal: 1250,
};

const clamp = (value, min, max) => Math.max(min, Math.min(max, value));

const toNumber = (value) => {
  if (value === null || value === undefined) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
};

const randomFloat = (min, max) => Math.random() * (max - min) + min;

const buildVitalPoint = (raw, previousPoint) => {
  const previous = previousPoint || DEFAULT_METRICS;
  const base = raw || {};
  const timestamp = base.timestamp || new Date().toISOString();

  const ppgSignal = clamp(
    toNumber(base.ppgSignal) ??
      toNumber(base.ppg) ??
      toNumber(base.ppgGreenAverage) ??
      toNumber(base.ppgMean) ??
      previous.ppgSignal ??
      DEFAULT_METRICS.ppgSignal,
    0,
    6000
  );

  return {
    timestamp,
    ppgSignal,
  };
};

const evolveVitalPoint = (previousPoint) => {
  const prev = previousPoint || DEFAULT_METRICS;
  const timestamp = new Date().toISOString();
  const ppgSignal = clamp((prev.ppgSignal || DEFAULT_METRICS.ppgSignal) + randomFloat(-45, 45), 700, 3200);

  return {
    timestamp,
    ppgSignal,
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

function App() {
  const [patients, setPatients] = useState([]);
  const [selectedPatient, setSelectedPatient] = useState(null);
  const [vitalData, setVitalData] = useState([]);
  const [alerts, setAlerts] = useState([]);
  const [isConnected, setIsConnected] = useState(false);
  const [lastRefreshAt, setLastRefreshAt] = useState(null);
  const eventSourceRef = useRef(null);
  const notificationEventSourceRef = useRef(null);
  const lastPointAtRef = useRef(0);

  const pushAlert = useCallback((rawAlert) => {
    if (!rawAlert || typeof rawAlert !== 'object') {
      return;
    }

    const normalized = {
      id: rawAlert.alertId || `${Date.now()}-${Math.random()}`,
      alertType: rawAlert.alertType || 'notification',
      message: rawAlert.message || 'Nouvelle alerte recue',
      severity: String(rawAlert.severity || 'WARNING').toUpperCase(),
      timestamp: rawAlert.timestamp || new Date().toISOString(),
    };

    setAlerts((previous) => {
      const withoutDuplicate = previous.filter((item) => item.id !== normalized.id);
      return [normalized, ...withoutDuplicate].slice(0, MAX_ALERTS);
    });
  }, []);

  const pushVitalPoint = useCallback((rawPoint, synthetic = false) => {
    setVitalData((previous) => {
      const previousPoint = previous.length ? previous[previous.length - 1] : null;
      const nextPoint = synthetic ? evolveVitalPoint(previousPoint) : buildVitalPoint(rawPoint, previousPoint);

      if (previousPoint && previousPoint.timestamp === nextPoint.timestamp) {
        return previous;
      }

      lastPointAtRef.current = Date.now();
      setLastRefreshAt(nextPoint.timestamp);
      return [...previous, nextPoint].slice(-MAX_POINTS);
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

      const normalized = data.reduce((accumulator, point) => {
        const previous = accumulator.length ? accumulator[accumulator.length - 1] : null;
        accumulator.push(buildVitalPoint(point, previous));
        return accumulator;
      }, []);

      setVitalData(normalized.slice(-MAX_POINTS));
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
      setPatients(Array.isArray(data) ? data : []);
      if (Array.isArray(data) && data.length > 0 && !selectedPatient) {
        setSelectedPatient(data[0]);
      }
    } catch (error) {
      console.error('Error fetching patients:', error);
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
    if (!selectedPatient) {
      return;
    }

    if (notificationEventSourceRef.current) {
      notificationEventSourceRef.current.close();
    }

    const notificationEventSource = new EventSource(
      `${NOTIFICATION_SSE_BASE_URL}/stream/${selectedPatient}`
    );
    notificationEventSourceRef.current = notificationEventSource;

    notificationEventSource.onmessage = (event) => {
      try {
        const parsed = JSON.parse(event.data);
        pushAlert(parsed);
      } catch (_error) {
        // Ignore non-JSON housekeeping events.
      }
    };

    return () => {
      if (notificationEventSource) {
        notificationEventSource.close();
      }
    };
  }, [selectedPatient, pushAlert]);

  useEffect(() => {
    if (!selectedPatient) return;

    const interval = setInterval(async () => {
      await fetchStats(selectedPatient);

      const isStale = Date.now() - lastPointAtRef.current > AUTO_UPDATE_MS + 500;
      if (isStale) {
        const latestFromApi = await fetchLatestPoint(selectedPatient);
        if (latestFromApi) {
          pushVitalPoint(latestFromApi);
        } else {
          pushVitalPoint(null, true);
        }
      }
    }, AUTO_UPDATE_MS);

    return () => clearInterval(interval);
  }, [selectedPatient, fetchLatestPoint, fetchStats, pushVitalPoint]);

  const chartData = useMemo(
    () =>
      vitalData.slice(-MAX_POINTS).map((vital) => ({
        time: formatTime(vital.timestamp),
        ppgSignal: Number(vital.ppgSignal.toFixed(1)),
      })),
    [vitalData]
  );

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
        <button type="button" className="nav-btn">☆</button>
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
            <h2>Signal PPG</h2>
            <p>{currentPatientLabel}</p>
          </div>

          <div className="hero-graph">
            <span className="ecg-tag">PPG</span>
            <div className="chart-canvas">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={chartData}>
                  <CartesianGrid stroke="#1b2635" strokeDasharray="3 3" vertical={false} />
                  <XAxis hide dataKey="time" />
                  <YAxis hide />
                  <Tooltip contentStyle={tooltipStyle} />
                  <Line
                    type="monotone"
                    dataKey="ppgSignal"
                    stroke="#2f81f7"
                    strokeWidth={2.5}
                    dot={false}
                    isAnimationActive={true}
                    animationDuration={650}
                    name="PPG"
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
              <h3>Alertes</h3>
              <p>FLUX NOTIFICATION</p>
            </div>

            <div className="schedule-list">
              {alerts.slice(0, 4).map((alert) => {
                const severityClass = getSeverityClass(alert.severity);
                return (
                  <div key={alert.id} className="schedule-item">
                    <span className="schedule-time">{formatTime(alert.timestamp)}</span>
                    <span className={`schedule-dot ${severityClass}`}></span>
                    <div className="schedule-text">
                      <strong>{alert.alertType}</strong>
                      <p>{alert.message}</p>
                    </div>
                  </div>
                );
              })}
              {!alerts.length && <p className="no-data">Aucune alerte pour le moment.</p>}
            </div>
          </article>
        </section>
      </div>
    </div>
  );
}

export default App;
