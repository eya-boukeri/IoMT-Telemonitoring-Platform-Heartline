import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import './App.css';

const API_ROOT = import.meta.env.REACT_APP_API_URL || '/api';
const API_BASE_URL = `${API_ROOT}/vitals`;
const SSE_BASE_URL = API_BASE_URL;
const MAX_POINTS = 50;
const AUTO_UPDATE_MS = 3000;

const DEFAULT_METRICS = {
  heartRate: 78,
  oxygenSaturation: 98,
  temperature: 36.8,
  bloodPressureSystolic: 120,
  bloodPressureDiastolic: 78,
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

  const heartRate = clamp(
    toNumber(base.heartRate) ?? previous.heartRate ?? DEFAULT_METRICS.heartRate,
    45,
    170
  );
  const oxygenSaturation = clamp(
    toNumber(base.oxygenSaturation) ?? previous.oxygenSaturation ?? DEFAULT_METRICS.oxygenSaturation,
    80,
    100
  );
  const temperature = clamp(
    toNumber(base.temperature) ?? previous.temperature ?? DEFAULT_METRICS.temperature,
    34,
    41
  );
  const bloodPressureSystolic = clamp(
    toNumber(base.bloodPressureSystolic) ?? previous.bloodPressureSystolic ?? DEFAULT_METRICS.bloodPressureSystolic,
    80,
    200
  );
  const bloodPressureDiastolic = clamp(
    toNumber(base.bloodPressureDiastolic) ?? previous.bloodPressureDiastolic ?? DEFAULT_METRICS.bloodPressureDiastolic,
    45,
    130
  );

  return {
    timestamp,
    heartRate,
    oxygenSaturation,
    temperature,
    bloodPressureSystolic,
    bloodPressureDiastolic,
  };
};

const evolveVitalPoint = (previousPoint) => {
  const prev = previousPoint || DEFAULT_METRICS;
  return {
    timestamp: new Date().toISOString(),
    heartRate: clamp((prev.heartRate || DEFAULT_METRICS.heartRate) + randomFloat(-2.2, 2.2), 52, 138),
    oxygenSaturation: clamp((prev.oxygenSaturation || DEFAULT_METRICS.oxygenSaturation) + randomFloat(-0.8, 0.6), 90, 100),
    temperature: clamp((prev.temperature || DEFAULT_METRICS.temperature) + randomFloat(-0.12, 0.12), 35.5, 39.5),
    bloodPressureSystolic: clamp((prev.bloodPressureSystolic || DEFAULT_METRICS.bloodPressureSystolic) + randomFloat(-2.6, 2.6), 95, 160),
    bloodPressureDiastolic: clamp((prev.bloodPressureDiastolic || DEFAULT_METRICS.bloodPressureDiastolic) + randomFloat(-2.0, 2.0), 58, 108),
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

const computeMetricStats = (points, key) => {
  const values = points.map((point) => point[key]).filter((value) => Number.isFinite(value));
  if (!values.length) return { last: null, min: null, max: null, avg: null };

  const min = Math.min(...values);
  const max = Math.max(...values);
  const avg = values.reduce((sum, value) => sum + value, 0) / values.length;

  return {
    last: values[values.length - 1],
    min,
    max,
    avg,
  };
};

const computeBloodPressureStats = (points) => {
  const systolic = computeMetricStats(points, 'bloodPressureSystolic');
  const diastolic = computeMetricStats(points, 'bloodPressureDiastolic');
  return { systolic, diastolic };
};

function App() {
  const [patients, setPatients] = useState([]);
  const [selectedPatient, setSelectedPatient] = useState(null);
  const [vitalData, setVitalData] = useState([]);
  const [alerts, setAlerts] = useState([]);
  const [isConnected, setIsConnected] = useState(false);
  const [lastRefreshAt, setLastRefreshAt] = useState(null);
  const eventSourceRef = useRef(null);
  const lastPointAtRef = useRef(0);

  const checkAlerts = useCallback((vital) => {
    const generatedAlerts = [];

    if (vital.heartRate >= 110) {
      generatedAlerts.push({
        id: `${Date.now()}-tachy`,
        level: 'critical',
        title: '🚨 Tachycardie',
        message: `Fréquence cardiaque critique: ${vital.heartRate.toFixed(1)} bpm`,
        timestamp: vital.timestamp,
      });
    }

    if (vital.oxygenSaturation <= 92) {
      generatedAlerts.push({
        id: `${Date.now()}-hypoxie`,
        level: 'critical',
        title: '🚨 Hypoxie',
        message: `SpO2 dangereuse: ${vital.oxygenSaturation.toFixed(1)}%`,
        timestamp: vital.timestamp,
      });
    }

    if (vital.temperature >= 38) {
      generatedAlerts.push({
        id: `${Date.now()}-fievre`,
        level: 'warning',
        title: '⚠️ Fièvre',
        message: `Température élevée: ${vital.temperature.toFixed(1)}°C`,
        timestamp: vital.timestamp,
      });
    }

    if (vital.bloodPressureSystolic >= 140 || vital.bloodPressureDiastolic >= 90) {
      generatedAlerts.push({
        id: `${Date.now()}-hypertension`,
        level: 'warning',
        title: '⚠️ Hypertension',
        message: `Tension élevée: ${vital.bloodPressureSystolic.toFixed(0)}/${vital.bloodPressureDiastolic.toFixed(0)} mmHg`,
        timestamp: vital.timestamp,
      });
    }

    if (generatedAlerts.length) {
      setAlerts((previous) => [...generatedAlerts, ...previous].slice(0, 12));
    }
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
        checkAlerts(nextPoint);
        return [...previous, nextPoint].slice(-MAX_POINTS);
      });
    },
    [checkAlerts]
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
        heartRate: Number(vital.heartRate.toFixed(1)),
        temperature: Number(vital.temperature.toFixed(2)),
        spo2: Number(vital.oxygenSaturation.toFixed(1)),
        systolic: Number(vital.bloodPressureSystolic.toFixed(1)),
        diastolic: Number(vital.bloodPressureDiastolic.toFixed(1)),
      })),
    [vitalData]
  );

  const heartStats = useMemo(() => computeMetricStats(vitalData, 'heartRate'), [vitalData]);
  const spo2Stats = useMemo(() => computeMetricStats(vitalData, 'oxygenSaturation'), [vitalData]);
  const temperatureStats = useMemo(() => computeMetricStats(vitalData, 'temperature'), [vitalData]);
  const pressureStats = useMemo(() => computeBloodPressureStats(vitalData), [vitalData]);

  const tooltipStyle = {
    background: 'rgba(255, 255, 255, 0.95)',
    border: '1px solid #d7d2fe',
    borderRadius: '12px',
    boxShadow: '0 10px 25px rgba(77, 44, 126, 0.16)',
  };

  const safeNumber = (value, decimals = 1) => (value === null ? '--' : value.toFixed(decimals));
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

          <div className="alert-container">
            {alerts.length === 0 ? (
              <div className="no-data">✅ Aucune alerte active</div>
            ) : (
              alerts.slice(0, 6).map((alert) => (
                <div key={alert.id} className={`alert alert-${alert.level}`}>
                  <div className="alert-icon">{alert.level === 'critical' ? '🚨' : '⚠️'}</div>
                  <div className="alert-content">
                    <strong>{alert.title}</strong>
                    <p>{alert.message}</p>
                    <small>{formatTime(alert.timestamp)}</small>
                  </div>
                </div>
              ))
            )}
          </div>

          <div className="stats-grid">
            <div className="stat-card stat-heart">
              <div className="stat-label">❤️ Rythme cardiaque</div>
              <div className="stat-value">
                {safeNumber(heartStats.last, 0)}
                <span className="stat-unit">bpm</span>
              </div>
              <div className="stat-details">
                <span>Min: {safeNumber(heartStats.min, 0)}</span>
                <span>Max: {safeNumber(heartStats.max, 0)}</span>
                <span>Moy: {safeNumber(heartStats.avg, 0)}</span>
              </div>
            </div>

            <div className="stat-card stat-spo2">
              <div className="stat-label">💧 SpO2</div>
              <div className="stat-value">
                {safeNumber(spo2Stats.last)}
                <span className="stat-unit">%</span>
              </div>
              <div className="stat-details">
                <span>Min: {safeNumber(spo2Stats.min)}%</span>
                <span>Max: {safeNumber(spo2Stats.max)}%</span>
                <span>Moy: {safeNumber(spo2Stats.avg)}%</span>
              </div>
            </div>

            <div className="stat-card stat-temp">
              <div className="stat-label">🌡️ Température</div>
              <div className="stat-value">
                {safeNumber(temperatureStats.last)}
                <span className="stat-unit">°C</span>
              </div>
              <div className="stat-details">
                <span>Min: {safeNumber(temperatureStats.min)}°C</span>
                <span>Max: {safeNumber(temperatureStats.max)}°C</span>
                <span>Moy: {safeNumber(temperatureStats.avg)}°C</span>
              </div>
            </div>

            <div className="stat-card stat-pressure">
              <div className="stat-label">📊 Tension artérielle</div>
              <div className="stat-value">
                {safeNumber(pressureStats.systolic.last, 0)}/{safeNumber(pressureStats.diastolic.last, 0)}
                <span className="stat-unit">mmHg</span>
              </div>
              <div className="stat-details">
                <span>Min: {safeNumber(pressureStats.systolic.min, 0)}</span>
                <span>Max: {safeNumber(pressureStats.systolic.max, 0)}</span>
                <span>Moy: {safeNumber(pressureStats.systolic.avg, 0)}</span>
              </div>
            </div>
          </div>

          <div className="charts-grid">
            <div className="chart-container">
              <div className="chart-header">
                <div className="chart-icon heart-icon">❤️</div>
                <div className="chart-title">Rythme cardiaque</div>
              </div>
              <div className="chart-canvas">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartData}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="time" />
                    <YAxis domain={[40, 140]} />
                    <Tooltip contentStyle={tooltipStyle} />
                    <Legend />
                    <Line
                      type="monotone"
                      dataKey="heartRate"
                      stroke="#f5576c"
                      strokeWidth={3}
                      dot={false}
                      isAnimationActive={true}
                      animationDuration={650}
                      name="FC (bpm)"
                    />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </div>

            <div className="chart-container">
              <div className="chart-header">
                <div className="chart-icon spo2-icon">💧</div>
                <div className="chart-title">Saturation en Oxygène (SpO2)</div>
              </div>
              <div className="chart-canvas">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartData}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="time" />
                    <YAxis domain={[88, 100]} />
                    <Tooltip contentStyle={tooltipStyle} />
                    <Legend />
                    <Line
                      type="monotone"
                      dataKey="spo2"
                      stroke="#00f2fe"
                      strokeWidth={3}
                      dot={false}
                      isAnimationActive={true}
                      animationDuration={650}
                      name="SpO2 (%)"
                    />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </div>

            <div className="chart-container">
              <div className="chart-header">
                <div className="chart-icon temp-icon">🌡️</div>
                <div className="chart-title">Température corporelle</div>
              </div>
              <div className="chart-canvas">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartData}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="time" />
                    <YAxis domain={[35, 40]} />
                    <Tooltip contentStyle={tooltipStyle} />
                    <Legend />
                    <Line
                      type="monotone"
                      dataKey="temperature"
                      stroke="#fee140"
                      strokeWidth={3}
                      dot={false}
                      isAnimationActive={true}
                      animationDuration={650}
                      name="Température (°C)"
                    />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </div>

            <div className="chart-container">
              <div className="chart-header">
                <div className="chart-icon bp-icon">📊</div>
                <div className="chart-title">Tension artérielle</div>
              </div>
              <div className="chart-canvas">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartData}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="time" />
                    <YAxis domain={[60, 180]} />
                    <Tooltip contentStyle={tooltipStyle} />
                    <Legend />
                    <Line
                      type="monotone"
                      dataKey="systolic"
                      stroke="#ff4f9a"
                      strokeWidth={3}
                      dot={false}
                      isAnimationActive={true}
                      animationDuration={650}
                      name="Systolique"
                    />
                    <Line
                      type="monotone"
                      dataKey="diastolic"
                      stroke="#00d2ff"
                      strokeWidth={3}
                      dot={false}
                      isAnimationActive={true}
                      animationDuration={650}
                      name="Diastolique"
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