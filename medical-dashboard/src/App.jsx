import React from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { Activity, Bell } from 'lucide-react';

function App() {
  // Données de test (à remplacer plus tard par les données du Step 1 via Kafka/WS)
  const data = [
    { time: '10:00', ecg: 75 },
    { time: '10:01', ecg: 82 },
    { time: '10:02', ecg: 78 },
  ];

  return (
    <div style={{ padding: '20px', fontFamily: 'Arial' }}>
      <h1><Activity color="red" /> Tableau de Bord Médical</h1>
      
      <div style={{ display: 'flex', gap: '20px' }}>
        {/* Section Graphique Temps Réel */}
        <div style={{ flex: 2, border: '1px solid #ccc', padding: '15px' }}>
          <h3>Flux ECG en temps réel</h3>
          <ResponsiveContainer width="100%" height={300}>
            <LineChart data={data}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="time" />
              <YAxis />
              <Tooltip />
              <Line type="monotone" dataKey="ecg" stroke="#8884d8" strokeWidth={2} />
            </LineChart>
          </ResponsiveContainer>
        </div>

        {/* Section Alertes (Step 2/3) */}
        <div style={{ flex: 1, border: '1px solid #ccc', padding: '15px' }}>
          <h3><Bell size={20} /> Alertes Récentes</h3>
          <p style={{ color: 'orange' }}>⚠️ Tension élevée - Patient 001</p>
        </div>
      </div>
    </div>
  );
}

export default App;