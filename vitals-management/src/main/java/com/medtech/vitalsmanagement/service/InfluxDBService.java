package com.medtech.vitalsmanagement.service;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.medtech.vitalsmanagement.model.VitalData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class InfluxDBService {

    @Autowired
    private InfluxDBClient influxDBClient;

    public boolean saveVitalData(VitalData vitalData) {
        if (vitalData == null) {
            log.warn("InfluxDB: vitalData is null, skipping write");
            return false;
        }

        WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();

        Instant timestamp = vitalData.getTimestamp() != null
                ? vitalData.getTimestamp()
                : Instant.now();

        Point point = Point.measurement("vitals").time(timestamp, WritePrecision.NS);

        if (vitalData.getPatientId() != null && !vitalData.getPatientId().isBlank()) {
            point.addTag("patientId", vitalData.getPatientId());
        }

        boolean hasField = false;
        if (vitalData.getHeartRate() != null) {
            point.addField("heartRate", vitalData.getHeartRate());
            hasField = true;
        }
        if (vitalData.getBloodPressureSystolic() != null) {
            point.addField("bloodPressureSystolic", vitalData.getBloodPressureSystolic());
            hasField = true;
        }
        if (vitalData.getBloodPressureDiastolic() != null) {
            point.addField("bloodPressureDiastolic", vitalData.getBloodPressureDiastolic());
            hasField = true;
        }
        if (vitalData.getTemperature() != null) {
            point.addField("temperature", vitalData.getTemperature());
            hasField = true;
        }
        if (vitalData.getOxygenSaturation() != null) {
            point.addField("oxygenSaturation", vitalData.getOxygenSaturation());
            hasField = true;
        }

        if (!hasField) {
            log.warn("InfluxDB: no fields to write for payload: {}", vitalData);
            return false;
        }

        try {
            writeApi.writePoint(point);
            log.debug("InfluxDB: write success for patientId={} timestamp={}",
                    vitalData.getPatientId(), timestamp);
            return true;
        } catch (Exception e) {
            log.error("InfluxDB: write failed for payload: {}", vitalData, e);
            return false;
        }
    }

    public void saveRawPayload(String topic, String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("InfluxDB: raw payload is empty, skipping write");
            return;
        }

        WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
        Instant timestamp = Instant.now();

        Point point = Point.measurement("vitals_raw")
                .time(timestamp, WritePrecision.NS)
                .addField("payload", payload);

        if (topic != null && !topic.isBlank()) {
            point.addTag("topic", topic);
        }

        try {
            writeApi.writePoint(point);
            log.debug("InfluxDB: raw payload write success topic={} timestamp={}", topic, timestamp);
        } catch (Exception e) {
            log.error("InfluxDB: raw payload write failed topic={} payload={}", topic, payload, e);
        }
    }
}