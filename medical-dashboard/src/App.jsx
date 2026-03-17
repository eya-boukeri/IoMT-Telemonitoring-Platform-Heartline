import React, { useState, useEffect, useRef } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { Activity, Bell, Heart, Thermometer, Wind, Users, AlertTriangle } from 'lucide-react';
import './App.css';

const API_ROOT = import.meta.env.REACT_APP_API_URL || '/api';
const API_BASE_URL = `${API_ROOT}/vitals`;
const SSE_BASE_URL = API_BASE_URL;

function App() {
  const [patients, setPatients] = useState([]);
  const [selectedPatient, setSelectedPatient] = useState(null);
  const [vitalData, setVitalData] = useState([]);
  const [stats, setStats] = useState(null);
  const [alerts, setAlerts] = useState([]);
  const [isConnected, setIsConnected] = useState(false);
  const eventSourceRef = useRef(null);

  // Charger la liste des patients au démarrage
  useEffect(() => {
    fetchPatients();
  }, []);

  // Connexion SSE pour le patient sélectionné
  useEffect(() => {
    if (!selectedPatient) return;

    // Fermer la connexion précédente
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }

    // Charger l'historique initial
    fetchLatestData(selectedPatient);
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
        const newVital = JSON.parse(event.data);
        console.log('📊 New vital data received:', newVital);
        
        // Ajouter les nouvelles données
        setVitalData(prev => {
          const updated = [...prev, newVital].slice(-50); // Garder les 50 dernières
          return updated;
        });

        // Vérifier les alertes
        checkAlerts(newVital);
      } catch (error) {
        console.error('Error parsing SSE data:', error);
      }
    };

    eventSource.onerror = (error) => {
      console.error('❌ SSE Error:', error);
      setIsConnected(false);
      eventSource.close();
    };

    return () => {
      if (eventSource) {
        eventSource.close();
        setIsConnected(false);
      }
    };
  }, [selectedPatient]);

  const fetchPatients = async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/patients?days=30`);
      const data = await response.json();
      setPatients(data);
      if (data.length > 0 && !selectedPatient) {
        setSelectedPatient(data[0]);
      }
    } catch (error) {
      console.error('Error fetching patients:', error);
    }
  };

  const fetchLatestData = async (patientId) => {
    try {
      const response = await fetch(`${API_BASE_URL}/latest/${patientId}?limit=30`);
      const data = await response.json();
      setVitalData(data);
    } catch (error) {
      console.error('Error fetching latest data:', error);
    }
  };

  const fetchStats = async (patientId) => {
    try {
      const response = await fetch(`${API_BASE_URL}/stats/${patientId}`);
      const data = await response.json();
      setStats(data);
    } catch (error) {
      console.error('Error fetching stats:', error);
    }
  };

  const checkAlerts = (vital) => {
    const newAlerts = [];
    
    if (vital.heartRate && (vital.heartRate < 60 || vital.heartRate > 100)) {
      newAlerts.push({
        id: Date.now() + '-hr',
        type: vital.heartRate < 60 ? 'warning' : 'danger',
        message: `Rythme cardiaque ${vital.heartRate < 60 ? 'bas' : 'élevé'}: ${vital.heartRate?.toFixed(1)} bpm`,
        timestamp: vital.timestamp
      });
    }
    
    if (vital.temperature && (vital.temperature < 36 || vital.temperature > 38)) {
      newAlerts.push({
        id: Date.now() + '-temp',
        type: 'warning',
        message: `Température anormale: ${vital.temperature?.toFixed(1)}°C`,
        timestamp: vital.timestamp
      });
    }
    
    if (vital.oxygenSaturation && vital.oxygenSaturation < 95) {
      newAlerts.push({
        id: Date.now() + '-spo2',
        type: 'danger',
        message: `Saturation basse: ${vital.oxygenSaturation?.toFixed(1)}%`,
        timestamp: vital.timestamp
      });
    }
    
    if (vital.bloodPressureSystolic && (vital.bloodPressureSystolic > 140 || vital.bloodPressureSystolic < 90)) {
      newAlerts.push({
        id: Date.now() + '-bp',
        type: 'warning',
        message: `Tension artérielle: ${vital.bloodPressureSystolic?.toFixed(0)}/${vital.bloodPressureDiastolic?.toFixed(0)} mmHg`,
        timestamp: vital.timestamp
      });
    }
    
    if (newAlerts.length > 0) {
      setAlerts(prev => [...newAlerts, ...prev].slice(0, 10));
    }
  };

  const formatTime = (timestamp) => {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    return date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  };

  const formatChartData = () => {
    return vitalData.map(vital => ({
      time: formatTime(vital.timestamp),
      heartRate: vital.heartRate,
      temperature: vital.temperature,
      spo2: vital.oxygenSaturation,
      systolic: vital.bloodPressureSystolic,
      diastolic: vital.bloodPressureDiastolic
    }));
  };

  return (
    <div className="dashboard">
      <header className="dashboard-header">
        <h1><Activity size={32} color="red" /> Tableau de Bord Médical</h1>
        <div className="connection-status">
          <span className={`status-indicator ${isConnected ? 'connected' : 'disconnected'}`}></span>
          {isConnected ? 'Connecté' : 'Déconnecté'}
        </div>
      </header>

      {/* Sélection du patient */}
      <div className="patient-selector">
        <Users size={20} />
        <label>Patient: </label>
        <select 
          value={selectedPatient || ''} 
          onChange={(e) => setSelectedPatient(e.target.value)}
        >
          {patients.map(patient => (
            <option key={patient} value={patient}>{patient}</option>
          ))}
        </select>
      </div>

      {/* Statistiques */}
      {stats && stats.stats && (
        <div className="stats-grid">
          {stats.stats.heartRate && (
            <div className="stat-card">
              <Heart color="#e74c3c" />
              <div>
                <h4>Rythme Cardiaque</h4>
                <p className="stat-value">{stats.stats.heartRate.last?.toFixed(1)} <span>bpm</span></p>
                <p className="stat-range">Min: {stats.stats.heartRate.min?.toFixed(1)} | Max: {stats.stats.heartRate.max?.toFixed(1)}</p>
              </div>
            </div>
          )}
          
          {stats.stats.temperature && (
            <div className="stat-card">
              <Thermometer color="#3498db" />
              <div>
                <h4>Température</h4>
                <p className="stat-value">{stats.stats.temperature.last?.toFixed(1)} <span>°C</span></p>
                <p className="stat-range">Min: {stats.stats.temperature.min?.toFixed(1)} | Max: {stats.stats.temperature.max?.toFixed(1)}</p>
              </div>
            </div>
          )}
          
          {stats.stats.oxygenSaturation && (
            <div className="stat-card">
              <Wind color="#2ecc71" />
              <div>
                <h4>SpO₂</h4>
                <p className="stat-value">{stats.stats.oxygenSaturation.last?.toFixed(1)} <span>%</span></p>
                <p className="stat-range">Min: {stats.stats.oxygenSaturation.min?.toFixed(1)} | Max: {stats.stats.oxygenSaturation.max?.toFixed(1)}</p>
              </div>
            </div>
          )}
          
          {stats.stats.bloodPressureSystolic && (
            <div className="stat-card">
              <Activity color="#9b59b6" />
              <div>
                <h4>Tension Artérielle</h4>
                <p className="stat-value">{stats.stats.bloodPressureSystolic.last?.toFixed(0)}/{stats.stats.bloodPressureDiastolic?.last?.toFixed(0)} <span>mmHg</span></p>
              </div>
            </div>
          )}
        </div>
      )}

      <div className="main-content">
        {/* Graphiques */}
        <div className="charts-section">
          <div className="chart-container">
            <h3>Rythme Cardiaque</h3>
            <ResponsiveContainer width="100%" height={250}>
              <LineChart data={formatChartData()}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="time" />
                <YAxis domain={[40, 120]} />
                <Tooltip />
                <Legend />
                <Line type="monotone" dataKey="heartRate" stroke="#e74c3c" strokeWidth={2} name="FC (bpm)" />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="chart-container">
            <h3>Température & SpO₂</h3>
            <ResponsiveContainer width="100%" height={250}>
              <LineChart data={formatChartData()}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="time" />
                <YAxis yAxisId="left" domain={[35, 40]} />
                <YAxis yAxisId="right" orientation="right" domain={[90, 100]} />
                <Tooltip />
                <Legend />
                <Line yAxisId="left" type="monotone" dataKey="temperature" stroke="#3498db" strokeWidth={2} name="Temp (°C)" />
                <Line yAxisId="right" type="monotone" dataKey="spo2" stroke="#2ecc71" strokeWidth={2} name="SpO₂ (%)" />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="chart-container">
            <h3>Tension Artérielle</h3>
            <ResponsiveContainer width="100%" height={250}>
              <LineChart data={formatChartData()}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="time" />
                <YAxis domain={[60, 160]} />
                <Tooltip />
                <Legend />
                <Line type="monotone" dataKey="systolic" stroke="#9b59b6" strokeWidth={2} name="Systolique" />
                <Line type="monotone" dataKey="diastolic" stroke="#8e44ad" strokeWidth={2} name="Diastolique" />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* Alertes */}
        <div className="alerts-section">
          <h3><Bell size={20} /> Alertes Récentes</h3>
          <div className="alerts-list">
            {alerts.length === 0 ? (
              <p className="no-alerts">✅ Aucune alerte</p>
            ) : (
              alerts.map(alert => (
                <div key={alert.id} className={`alert alert-${alert.type}`}>
                  <AlertTriangle size={16} />
                  <div>
                    <p>{alert.message}</p>
                    <small>{formatTime(alert.timestamp)}</small>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

export default App;