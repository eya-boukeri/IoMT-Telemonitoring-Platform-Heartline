import React, { useState, useEffect, useRef } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from 'recharts';

const API_ROOT = import.meta.env.REACT_APP_API_URL || '/api';
const API_BASE_URL = `${API_ROOT}/vitals`;
const SSE_BASE_URL = API_BASE_URL;

const NormalPatientVisualization = () => {
  const [ppgData, setPpgData] = useState([]);
  const [heartRateData, setHeartRateData] = useState([]);
  const [isRealTime, setIsRealTime] = useState(true);
  const [isConnected, setIsConnected] = useState(false);
  const eventSourceRef = useRef(null);
  const maxPoints = 250;

  // Générer des données statiques (comme avant)
  const generateStaticData = () => {
    setIsRealTime(false);
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      setIsConnected(false);
    }

    // Paramètres pour un patient normal
    const duration = 60; // 60 secondes
    const ppgSamplingRate = 100; // 100 Hz pour PPG
    const hrSamplingRate = 1; // 1 Hz pour rythme cardiaque

    const ppgPoints = [];
    const hrPoints = [];

    // Rythme cardiaque de base (72 bpm)
    const baseHeartRate = 72;
    const heartRateFreq = baseHeartRate / 60; // Convertir en Hz

    // Générer données PPG (100 échantillons par seconde)
    for (let i = 0; i < duration * ppgSamplingRate; i++) {
      const time = i / ppgSamplingRate;

      // Signal PPG avec harmoniques pour réalisme
      const ppgSignal =
        Math.sin(2 * Math.PI * heartRateFreq * time) +
        0.3 * Math.sin(2 * Math.PI * 2 * heartRateFreq * time) +
        0.1 * Math.sin(2 * Math.PI * 3 * heartRateFreq * time) +
        (Math.random() - 0.5) * 0.1; // Bruit léger

      // Normaliser et ajuster l'échelle
      const normalizedPpg = ((ppgSignal + 1.4) / 2.8) * 50 + 100;

      ppgPoints.push({
        time: time.toFixed(2),
        ppg: Math.round(normalizedPpg * 100) / 100,
        timestamp: time * 1000
      });
    }

    // Générer données rythme cardiaque (1 échantillon par seconde)
    for (let i = 0; i < duration * hrSamplingRate; i++) {
      const time = i / hrSamplingRate;

      // Rythme cardiaque avec variations naturelles
      const variation = Math.sin(2 * Math.PI * 0.01 * time) * 2;
      const noise = (Math.random() - 0.5) * 2;
      const heartRate = baseHeartRate + variation + noise;

      hrPoints.push({
        time: time.toFixed(1),
        heartRate: Math.round(Math.max(60, Math.min(85, heartRate))),
        timestamp: time * 1000
      });
    }

    setPpgData(ppgPoints);
    setHeartRateData(hrPoints);
  };

  // Fonction pour traiter les données temps réel
  const processRealTimeData = (rawData) => {
    if (!rawData || typeof rawData !== 'object') return;

    const timestamp = new Date(rawData.timestamp || Date.now()).getTime();
    const baseTime = Math.floor(timestamp / 1000); // Temps en secondes

    // Traiter les données PPG si disponibles
    if (rawData.ppgData && Array.isArray(rawData.ppgData)) {
      const newPpgPoints = rawData.ppgData.map((value, index) => ({
        time: (baseTime + index * 0.01).toFixed(2), // 100 Hz
        ppg: Math.round(value * 100) / 100,
        timestamp: timestamp + index * 10
      }));

      setPpgData(prev => {
        const combined = [...prev, ...newPpgPoints];
        return combined.slice(-maxPoints); // Garder seulement les derniers points
      });
    }

    // Traiter le rythme cardiaque
    if (rawData.heartRate) {
      const newHrPoint = {
        time: baseTime.toFixed(1),
        heartRate: Math.round(rawData.heartRate),
        timestamp: timestamp
      };

      setHeartRateData(prev => {
        const combined = [...prev, newHrPoint];
        return combined.slice(-maxPoints); // Garder seulement les derniers points
      });
    }
  };

  // Démarrer la connexion temps réel
  const startRealTimeMode = () => {
    setIsRealTime(true);

    // Fermer la connexion existante si elle existe
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }

    // Créer une nouvelle connexion SSE
    const eventSource = new EventSource(`${SSE_BASE_URL}/stream/patient-normal`);
    eventSourceRef.current = eventSource;

    eventSource.onopen = () => {
      setIsConnected(true);
      console.log('Connecté aux données temps réel pour patient-normal');
    };

    eventSource.onmessage = (event) => {
      try {
        const parsed = JSON.parse(event.data);
        processRealTimeData(parsed);
      } catch (error) {
        console.error('Erreur lors du parsing des données SSE:', error);
      }
    };

    eventSource.onerror = (error) => {
      console.error('Erreur SSE:', error);
      setIsConnected(false);
    };
  };

  // Effet pour démarrer le mode temps réel au montage
  useEffect(() => {
    startRealTimeMode();

    // Cleanup à la destruction du composant
    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
    };
  }, []);

  const formatTooltip = (value, name) => {
    if (name === 'ppg') {
      return [value, 'Signal PPG'];
    }
    if (name === 'heartRate') {
      return [`${value} bpm`, 'Rythme Cardiaque'];
    }
    return [value, name];
  };

  return (
    <div className="normal-patient-viz panel">
      <div className="panel-head">
        <h2>📊 Patient Normal - Référence Temps Réel</h2>
        <p>
          Visualisation live des signes vitaux d'un patient en bonne santé
          <span className={`connection-status ${isConnected ? 'connected' : 'disconnected'}`}>
            {isConnected ? ' 🔴 Connecté' : ' ⚪ Déconnecté'}
          </span>
        </p>
      </div>

      <div className="viz-content">
        {/* Graphique PPG */}
        <div className="chart-section">
          <h3>Signal PPG (Photopléthysmographie)</h3>
          <div className="chart-container">
            <ResponsiveContainer width="100%" height={200}>
              <LineChart data={ppgData.slice(-1000)}> {/* Afficher les 1000 derniers points */}
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis
                  dataKey="time"
                  tick={{ fill: '#9fb3c8', fontSize: 11 }}
                  label={{ value: 'Temps (secondes)', position: 'insideBottom', offset: -5 }}
                />
                <YAxis
                  label={{ value: 'Amplitude PPG', angle: -90, position: 'insideLeft' }}
                  domain={['dataMin - 10', 'dataMax + 10']}
                  tick={{ fill: '#9fb3c8', fontSize: 11 }}
                />
                <Tooltip
                  contentStyle={{
                    background: '#161b22',
                    border: '1px solid #1c2128',
                    color: '#e6edf3',
                    borderRadius: '12px'
                  }}
                  labelFormatter={(value) => `Temps: ${Number(value).toFixed(2)} s`}
                  formatter={formatTooltip}
                />
                <Line
                  type="monotone"
                  dataKey="ppg"
                  stroke="#2563eb"
                  strokeWidth={2}
                  dot={false}
                  isAnimationActive={false}
                  name="ppg"
                />
                <ReferenceLine y={125} stroke="#10b981" strokeDasharray="5 5" label="Moyenne" />
              </LineChart>
            </ResponsiveContainer>
          </div>
          <div className="chart-info">
            <span>Points affichés: {ppgData.length}</span>
            <span>Fréquence: ~100 Hz</span>
          </div>
        </div>

        {/* Graphique Rythme Cardiaque */}
        <div className="chart-section">
          <h3>Rythme Cardiaque</h3>
          <div className="chart-container">
            <ResponsiveContainer width="100%" height={200}>
              <LineChart data={heartRateData.slice(-60)}> {/* Afficher les 60 dernières secondes */}
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis
                  dataKey="time"
                  tick={{ fill: '#9fb3c8', fontSize: 11 }}
                  label={{ value: 'Temps (secondes)', position: 'insideBottom', offset: -5 }}
                />
                <YAxis
                  label={{ value: 'Rythme Cardiaque (bpm)', angle: -90, position: 'insideLeft' }}
                  domain={[55, 90]}
                  tick={{ fill: '#9fb3c8', fontSize: 11 }}
                />
                <Tooltip
                  contentStyle={{
                    background: '#161b22',
                    border: '1px solid #1c2128',
                    color: '#e6edf3',
                    borderRadius: '12px'
                  }}
                  labelFormatter={(value) => `Temps: ${Number(value).toFixed(1)} s`}
                  formatter={formatTooltip}
                />
                <Line
                  type="monotone"
                  dataKey="heartRate"
                  stroke="#dc2626"
                  strokeWidth={2}
                  dot={{ fill: '#dc2626', strokeWidth: 2, r: 3 }}
                  name="heartRate"
                />
                <ReferenceLine y={72} stroke="#10b981" strokeDasharray="5 5" label="Normal" />
              </LineChart>
            </ResponsiveContainer>
          </div>
          <div className="chart-info">
            <span>Points affichés: {heartRateData.length}</span>
            <span>Plage normale: 60-100 bpm</span>
          </div>
        </div>
      </div>

      <div className="viz-footer">
        <div className="mode-controls">
          <button
            type="button"
            className={`btn-mode ${isRealTime ? 'active' : ''}`}
            onClick={startRealTimeMode}
          >
            🔴 Temps Réel
          </button>
          <button
            type="button"
            className={`btn-mode ${!isRealTime ? 'active' : ''}`}
            onClick={generateStaticData}
          >
            📊 Données Statiques
          </button>
        </div>
        <p className="viz-note">
          {isRealTime
            ? "Visualisation en temps réel des données du patient-normal. Les graphiques se mettent à jour automatiquement."
            : "Visualisation de données générées statiquement. Cliquez sur 'Temps Réel' pour voir les données live."
          }
        </p>
      </div>
    </div>
  );
};

export default NormalPatientVisualization;