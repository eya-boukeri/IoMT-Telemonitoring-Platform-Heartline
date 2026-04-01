import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
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
      message: rawAlert.message || 'Nouvelle alerte reçue',
      severity: String(rawAlert.severity || 'WARNING').toUpperCase(),
      timestamp: rawAlert.timestamp || new Date().toISOString(),
    };

    setAlerts((previous) => {
      const withoutDuplicate = previous.filter((item) => item.id !== normalized.id);
      return [normalized, ...withoutDuplicate].slice(0, MAX_ALERTS);
    });
  }, []);

  const pushVitalPoint = useCallback(
    (rawPoint, synthetic = false) => {
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
    },
    []
  );

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

  // Charger la liste des patients au démarrage
  useEffect(() => {
    fetchPatients();
  }, [fetchPatients]);

  // Connexion SSE pour le patient sélectionné
  useEffect(() => {
    if (!selectedPatient) return;

    // Fermer la connexion précédente
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }

    // Charger l'historique initial
    fetchLatestBatch(selectedPatient);
    fetchStats(selectedPatient);

    // Ouvrir le stream SSE
    const eventSource = new EventSource(`${SSE_BASE_URL}/stream/${selectedPatient}`);
    eventSourceRef.current = eventSource;

    eventSource.onopen = () => {
      console.log('✅ SSE Connected for patient:', selectedPatient);
      setIsConnected(true);
    };

    eventSource.onmessage = (event) => {
      try {
        const parsed = JSON.parse(event.data);
        if (!parsed || typeof parsed !== 'object') {
          return;
        }
        pushVitalPoint(parsed);
      } catch (error) {
        // Ignore les messages non JSON comme les événements ready
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

    notificationEventSource.onerror = () => {
      // Notification stream may be transient; keep vitals stream independent.
    };

    return () => {
      if (notificationEventSource) {
        notificationEventSource.close();
      }
    };
  }, [selectedPatient, pushAlert]);

  // Rafraîchissement auto toutes les 3 secondes
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
    background: 'rgba(255, 255, 255, 0.95)',
    border: '1px solid #d7d2fe',
    borderRadius: '12px',
    boxShadow: '0 10px 25px rgba(77, 44, 126, 0.16)',
  };

  const currentPatientLabel = selectedPatient || 'Patient inconnu';

  return (
    <div className="dashboard">
      <div className="app-container">
        <header className="app-header">
          <h1>
            <span className="header-icon">🏥</span>
            Dashboard Médical - Surveillance Temps Réel
          </h1>
        </header>

        <div className="patient-selector">
          <span className="selector-emoji">🧑‍⚕️</span>
          <label>Patient:</label>
          <select
            value={selectedPatient || ''}
            onChange={(e) => setSelectedPatient(e.target.value)}
          >
            {patients.map((patient) => (
              <option key={patient} value={patient}>{patient}</option>
            ))}
          </select>
          <span className="refresh-time">
            Dernière donnée: {lastRefreshAt ? formatTime(lastRefreshAt) : '--:--:--'}
          </span>
        </div>

        {!!patients.length && (
          <div className="patient-cards">
            {patients.map((patient) => (
              <button
                key={patient}
                type="button"
                className={`patient-card-chip ${selectedPatient === patient ? 'active' : ''}`}
                onClick={() => setSelectedPatient(patient)}
              >
                <span>👤</span>
                <span>{patient}</span>
              </button>
            ))}
          </div>
        )}

        <section className="patient-card">
          <div className="patient-header">
            <div className="patient-title">
              <span className="patient-icon">👤</span>
              {currentPatientLabel}
            </div>
            <div className={`connection-status ${isConnected ? 'online' : 'offline'}`}>
              <span className={`status-dot ${isConnected ? 'connected' : 'disconnected'}`}></span>
              {isConnected ? 'Connecté en Temps Réel' : 'Connexion perdue'}
            </div>
          </div>

          {alerts.length > 0 && (
            <div className="alert-container">
              {alerts.map((alert) => {
                const isCritical = alert.severity === 'CRITICAL';
                return (
                  <div
                    key={alert.id}
                    className={`alert ${isCritical ? 'alert-critical' : 'alert-warning'}`}
                  >
                    <div className="alert-icon">{isCritical ? '🚨' : '⚠️'}</div>
                    <div className="alert-content">
                      <strong>{alert.alertType}</strong>
                      <p>{alert.message}</p>
                      <small>{formatTime(alert.timestamp)}</small>
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          <div className="charts-grid">
            <div className="chart-container">
              <div className="chart-header">
                <div className="chart-icon heart-icon">🫀</div>
                <div className="chart-title">Signal PPG (Photopléthysmogramme)</div>
              </div>
              <div className="chart-canvas">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartData}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="time" />
                    <YAxis />
                    <Tooltip contentStyle={tooltipStyle} />
                    <Legend />
                    <Line
                      type="monotone"
                      dataKey="ppgSignal"
                      stroke="#1f8ef1"
                      strokeWidth={3}
                      dot={false}
                      isAnimationActive={true}
                      animationDuration={650}
                      name="PPG"
                    />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}

export default App;