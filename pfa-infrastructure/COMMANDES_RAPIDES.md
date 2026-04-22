# COMMANDES RAPIDES - Affichage pour les 7 Terminaux

## 🟢 COPIER-COLLER DIRECT DANS CHAQUE TERMINAL

### ╔════ TERMINAL T1 ════╗
### MQTT Messages Bruts
```
docker exec -it mosquitto-pfa-v2 mosquitto_sub -t "vitals/#" -v
```

---

### ╔════ TERMINAL T2 ════╗
### Ingestion Service Logs
```
docker logs ingestion-service-pfa --tail 30 -f
```

---

### ╔════ TERMINAL T3 ════╗
### Kafka Raw Vitals Topic
```
docker exec kafka-pfa-v2 rpk topic consume vitals-raw --brokers localhost:9092
```

---

### ╔════ TERMINAL T4 ════╗
### Kafka Medical Alerts Topic  
```
docker exec kafka-pfa-v2 rpk topic consume medical-alerts --brokers localhost:9092
```

---

### ╔════ TERMINAL T5 ════╗
### Data Analytics Service Logs
```
docker logs data-analytics-service-pfa --tail 20 -f
```

---

### ╔════ TERMINAL T6 ════╗
### Notification Service Logs
```
docker logs notification-service-pfa --tail 20 -f
```

---

### ╔════ TERMINAL T7 ════╗
### InfluxDB Latest Data (Copy entire block below)
```powershell
while ($true) {
    $q='from(bucket:"medical_data") |> range(start: -1m) |> filter(fn:(r)=>r._measurement=="vitals") |> last()'
    $b=@{query=$q}|ConvertTo-Json
    try {
        $response = Invoke-RestMethod -Method POST -Uri "http://localhost:8088/api/v2/query?org=myorg" `
            -Headers @{Authorization="Token my-secret-token"} `
            -Body $b
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Latest Data:" -ForegroundColor Green
        $response | ConvertTo-Json -Depth 3
    } catch {
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] ⏳ Waiting for InfluxDB..." -ForegroundColor Gray
    }
    Start-Sleep 2
}
```

---

## 🟢 SCENARIO 1: Patient Normal (Terminal 8)

```powershell
cd pfa-infrastructure

# Measure 1
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-normal/data" -m '{"patientId":"patient-normal","deviceId":"simulator","heartRate":72,"spo2":99,"temperature":36.6,"timestamp":1713600000000}'
Start-Sleep 3

# Measure 2
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-normal/data" -m '{"patientId":"patient-normal","deviceId":"simulator","heartRate":75,"spo2":98,"temperature":36.5,"timestamp":1713600000000}'
Start-Sleep 3

# Measure 3
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-normal/data" -m '{"patientId":"patient-normal","deviceId":"simulator","heartRate":70,"spo2":99,"temperature":36.7,"timestamp":1713600000000}'
```

### Expected in Scenario 1:
- ✅ T1: 3 MQTT messages
- ✅ T2: Processing logs (no errors)
- ✅ T3: 3 messages in vitals-raw
- ✅ T4: (Empty - no alert)
- ✅ T5: (Silent - no anomaly)
- ✅ T6: (Silent - no email)

---

## 🔴 SCENARIO 2: Critical Patient (Terminal 8)

```powershell
cd pfa-infrastructure

# Measure 1 - Normal
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":80,"spo2":97,"temperature":36.8,"timestamp":1713600000000}'
Start-Sleep 3

# Measure 2 - Anomaly beginning
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":55,"spo2":94,"temperature":37.0,"timestamp":1713600000000}'
Start-Sleep 3

# Measure 3 - CRITICAL 🔴
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":155,"spo2":82,"temperature":39.2,"timestamp":1713600000000}'
Start-Sleep 3

# Measure 4 - Persistence
docker exec mosquitto-pfa-v2 mosquitto_pub -h localhost -p 1883 -t "vitals/patient-urgent/data" -m '{"patientId":"patient-urgent","deviceId":"simulator","heartRate":152,"spo2":80,"temperature":39.5,"timestamp":1713600000000}'
```

### Expected in Scenario 2:
- ✅ T1: 4 MQTT messages
- ✅ T2: Processing logs
- ✅ T3: 4 messages in vitals-raw  
- 🔴 **T4: CRITICAL alert JSON (10-15s after publish)**
- 🔴 **T5: "Anomaly detected" + "Publishing alert"**
- 🔴 **T6: "Alert received" + "Email sent"**
- ✅ T7: InfluxDB data with HR:155, SpO2:82

### Email Verification:
- Check: farah.attia21@gmail.com
- Look for: Subject = `[CRITICAL] Alerte: ANOMALY` or similar
- Contains: patient-urgent details, vital values, timestamp

### Dashboard:
- URL: http://localhost:5173
- Login: dr-farah / farah1234
- Patient: patient-urgent
- See: FC spike to 155, SpO2 drop to 82, CRITICAL alert

---

## 🔧 Quick Diagnostics

### Check all containers running:
```powershell
docker ps --format "{{.Names}}" | Select-String "pfa"
```

### Restart specific service:
```powershell
docker restart ingestion-service-pfa
docker restart data-analytics-service-pfa
docker restart notification-service-pfa
```

### View specific service logs (last 100 lines):
```powershell
docker logs ingestion-service-pfa --tail 100
docker logs data-analytics-service-pfa --tail 100  
docker logs notification-service-pfa --tail 100
```

### Check Kafka topics:
```powershell
docker exec kafka-pfa-v2 rpk topic list
```

### Test InfluxDB:
```powershell
docker exec influxdb-pfa influx bucket list
```

---

**Ready? Start with Scenario 1 then Scenario 2! 🚀**
