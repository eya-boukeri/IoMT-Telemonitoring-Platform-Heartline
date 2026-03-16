package com.medtech.ingestion.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.medtech.ingestion.repository.RawSignalRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class HealthCheckService {

    private final RawSignalRepository rawSignalRepository;
    private final AggregationService aggregationService;
    private final KafkaProducerService kafkaProducerService;

    public Map<String, Object> getHealthStatus() {
        Map<String, Object> health = new LinkedHashMap<>();

        boolean databaseUp = isDatabaseUp();
        boolean kafkaActive = kafkaProducerService.getPublishedCount() >= 0;

        health.put("service", "ingestion-service");
        health.put("timestamp", Instant.now());
        health.put("status", databaseUp && kafkaActive ? "UP" : "DEGRADED");

        health.put("databaseUp", databaseUp);
        health.put("bufferedWindows", aggregationService.getBufferedWindowCount());
        health.put("bufferedSignals", aggregationService.getBufferedSignalCount());
        health.put("emittedWindows", aggregationService.getEmittedWindowCount());
        health.put("lastAggregationFlushAt", aggregationService.getLastFlushAt());

        health.put("kafkaPublishedCount", kafkaProducerService.getPublishedCount());
        health.put("lastKafkaPublishAt", kafkaProducerService.getLastPublishedAt());

        return health;
    }

    public boolean isHealthy() {
        return isDatabaseUp();
    }

    private boolean isDatabaseUp() {
        try {
            rawSignalRepository.count();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
