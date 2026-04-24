package com.medtech.vitalsmanagement.service;

import java.io.IOException;
import java.time.Instant;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.vitalsmanagement.model.VitalData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class KafkaAggregatedConsumer {

    private static final String AGGREGATED_TOPIC = "signals.aggregated";

    private final ObjectMapper objectMapper;
    private final InfluxDBService influxDBService;
    private final VitalStreamService vitalStreamService;

    public KafkaAggregatedConsumer(
            ObjectMapper objectMapper,
            InfluxDBService influxDBService,
            VitalStreamService vitalStreamService) {
        this.objectMapper = objectMapper;
        this.influxDBService = influxDBService;
        this.vitalStreamService = vitalStreamService;
    }

    @KafkaListener(
        topics = "${kafka.topic.aggregated:signals.aggregated}",
        groupId = "${spring.kafka.consumer.group-id:vitals-management-consumer-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleAggregatedSignal(String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        try {
            JsonNode root = extractPayloadNode(message);
            String patientId = readText(root, "patientId");

            if (patientId == null || patientId.isBlank()) {
                log.warn("Aggregated event without patientId skipped: {}", message);
                influxDBService.saveRawPayload(AGGREGATED_TOPIC, message);
                return;
            }

            VitalData vital = new VitalData();
            vital.setPatientId(patientId);
            vital.setTimestamp(resolveTimestamp(root));

            Double estimatedHeartRate = readDouble(root, "estimatedHeartRate");
            vital.setHeartRate(estimatedHeartRate);
            vital.setHeartRateVariability(readDouble(root, "ppgStdDev"));

            Double filteredMean = firstNonNull(
                readDouble(root, "ppgFilteredMean"),
                readDouble(root, "ppgMean")
            );
            vital.setPpgFilteredSignal(filteredMean);
            vital.setPpgGreenAverage(filteredMean);
            vital.setPpgGreenMin(readDouble(root, "ppgMin"));
            vital.setPpgGreenMax(readDouble(root, "ppgMax"));
            vital.setPpgDataPoints(readInteger(root, "sampleCount"));

            vital.setAccelerometerMagnitudeAverage(readDouble(root, "activityMean"));
            vital.setAccelerometerMagnitudeMax(readDouble(root, "activityMax"));
            vital.setAccelerometerVariance(readDouble(root, "activityVariance"));

            Double signalQualityScore = readDouble(root, "signalQuality");
            vital.setSignalQualityScore(signalQualityScore);
            vital.setSignalQuality(toSignalQualityLabel(signalQualityScore));

                log.debug("Mapped aggregated event to VitalData - patientId={} timestamp={} heartRate={} ppgMean={} quality={}",
                    vital.getPatientId(),
                    vital.getTimestamp(),
                    vital.getHeartRate(),
                    vital.getPpgGreenAverage(),
                    vital.getSignalQualityScore());

            boolean saved = influxDBService.saveVitalData(vital);
            if (!saved) {
                // Keep aggregated event traceability even if no primary vital field was persisted.
                influxDBService.saveRawPayload(AGGREGATED_TOPIC, message);
            }

            vitalStreamService.broadcastVital(vital);

            log.info("Aggregated signal consumed - patientId={} estimatedHeartRate={} quality={}",
                    patientId, estimatedHeartRate, signalQualityScore);
        } catch (IOException ex) {
            log.error("Invalid aggregated Kafka JSON payload: {}", message, ex);
            influxDBService.saveRawPayload(AGGREGATED_TOPIC, message);
        } catch (RuntimeException ex) {
            log.error("Failed to process aggregated Kafka event: {}", message, ex);
            influxDBService.saveRawPayload(AGGREGATED_TOPIC, message);
        }
    }

    private JsonNode extractPayloadNode(String message) throws IOException {
        JsonNode root = objectMapper.readTree(message);

        if (root != null && root.isTextual()) {
            return objectMapper.readTree(root.asText());
        }

        JsonNode valueNode = root != null ? root.get("value") : null;
        if (valueNode != null && valueNode.isTextual()) {
            return objectMapper.readTree(valueNode.asText());
        }

        return root;
    }

    private Instant resolveTimestamp(JsonNode root) {
        Instant windowEnd = readInstant(root, "windowEnd");
        if (windowEnd != null) {
            return windowEnd;
        }

        Instant windowStart = readInstant(root, "windowStart");
        if (windowStart != null) {
            return windowStart;
        }

        return Instant.now();
    }

    private Instant readInstant(JsonNode root, String field) {
        if (root == null || field == null) {
            return null;
        }

        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isNumber()) {
            double numericValue = node.asDouble();
            long wholePart = (long) numericValue;
            double fractionalPart = numericValue - wholePart;
            long nanos = Math.round(fractionalPart * 1_000_000_000d);
            return Instant.ofEpochSecond(wholePart, nanos);
        }

        String text = node.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String readText(JsonNode root, String field) {
        if (root == null || field == null) {
            return null;
        }

        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }

        String value = node.asText(null);
        return (value == null || value.isBlank()) ? null : value;
    }

    private Double readDouble(JsonNode root, String field) {
        if (root == null || field == null) {
            return null;
        }

        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isNumber()) {
            return node.asDouble();
        }

        String text = node.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            return Double.valueOf(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer readInteger(JsonNode root, String field) {
        if (root == null || field == null) {
            return null;
        }

        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }

        String text = node.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String toSignalQualityLabel(Double score) {
        if (score == null) {
            return null;
        }
        if (score >= 85.0) {
            return "excellent";
        }
        if (score >= 70.0) {
            return "good";
        }
        if (score >= 50.0) {
            return "fair";
        }
        return "poor";
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}