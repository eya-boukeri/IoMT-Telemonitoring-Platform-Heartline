package com.medtech.ingestion.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.ingestion.model.IngestionMetrics;
import com.medtech.ingestion.repository.IngestionMetricsRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionMetricsService {

    private final IngestionMetricsRepository ingestionMetricsRepository;
    private final ObjectMapper objectMapper;

    public IngestionMetrics save(IngestionMetrics metrics, List<Double> rrIntervalsMs) {
        if (metrics == null) {
            return null;
        }

        metrics.setRrIntervalsMs(serializeRrIntervals(rrIntervalsMs));
        return ingestionMetricsRepository.save(metrics);
    }

    private String serializeRrIntervals(List<Double> rrIntervalsMs) {
        try {
            return objectMapper.writeValueAsString(rrIntervalsMs == null ? List.of() : rrIntervalsMs);
        } catch (JsonProcessingException ex) {
            log.warn("Could not serialize RR intervals, storing empty list", ex);
            return "[]";
        }
    }
}
