package com.medtech.vitalsmanagement.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.vitalsmanagement.model.VitalData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class KafkaVitalsConsumer {

    private final VitalStreamService vitalStreamService;
    private final ObjectMapper objectMapper;

    public KafkaVitalsConsumer(VitalStreamService vitalStreamService, ObjectMapper objectMapper) {
        this.vitalStreamService = vitalStreamService;
        this.objectMapper = objectMapper;
    }

    /**
     * Listen to vital data from Kafka and push to all connected SSE clients
     * Topics: vitals-data, vitals-aggregated
     * Note: medical-alerts are handled by notification-service
     */
    @KafkaListener(
        topics = {"${kafka.topic.vitals:vitals-data}", "${kafka.topic.aggregated:signals.aggregated}"},
        groupId = "sse-stream-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleVitalEvent(String message) {
        try {
            log.debug("📨 Kafka message received: {}", message);

            // Parse JSON to VitalData
            VitalData vital = objectMapper.readValue(message, VitalData.class);

            if (vital.getPatientId() == null || vital.getPatientId().isBlank()) {
                log.warn("⚠️  Vital data without patientId, skipping");
                return;
            }

            // Broadcast to all connected SSE clients for this patient
            vitalStreamService.broadcastVital(vital);
            
            log.info("🔄 Vital data pushed to SSE clients - Patient: {}, HR: {}",
                vital.getPatientId(),
                vital.getHeartRate());

        } catch (Exception e) {
            log.error("❌ Error processing Kafka vital event: {}", message, e);
        }
    }

    /**
     * Alternative: Listen to raw MQTT messages (stored by InfluxDBService.saveRawPayload)
     * This is optional if you want to process raw payloads from MQTT
     */
    @KafkaListener(
        topics = {"vitals-raw"},
        groupId = "sse-stream-raw-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleRawVitalEvent(String rawPayload) {
        try {
            log.debug("📨 Raw Kafka message received: {}", rawPayload);

            // Attempt to parse as VitalData
            VitalData vital = objectMapper.readValue(rawPayload, VitalData.class);
            vitalStreamService.broadcastVital(vital);

        } catch (Exception e) {
            log.warn("⚠️  Could not parse raw vital event, skipping: {}", rawPayload);
        }
    }
}
