package com.medtech.ingestion.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.ingestion.model.RawSignal;
import com.medtech.ingestion.repository.RawSignalRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RawSignalService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RawSignalService.class);

    private final RawSignalRepository rawSignalRepository;
    private final ObjectMapper objectMapper;

    public RawSignal saveRawSignal(RawSignal rawSignal) {
        if (rawSignal == null || rawSignal.getRawPayload() == null || rawSignal.getRawPayload().isBlank()) {
            throw new IllegalArgumentException("rawSignal and rawPayload are required");
        }

        rawSignal.setRawPayload(normalizeJsonPayload(rawSignal.getRawPayload()));

        if (rawSignal.getTimestamp() == null) {
            rawSignal.setTimestamp(Instant.now());
        }

        if (rawSignal.getCreatedAt() == null) {
            rawSignal.setCreatedAt(Instant.now());
        }

        RawSignal saved = rawSignalRepository.save(rawSignal);
        LOGGER.debug("Saved raw signal id={} patientId={} deviceId={}",
            saved.getId(), saved.getPatientId(), saved.getDeviceId());
        return saved;
    }

    public RawSignal saveRawSignal(String deviceId, String patientId, String rawPayload, String signalType, Instant timestamp) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }

        RawSignal rawSignal = new RawSignal();
        rawSignal.setDeviceId(deviceId);
        rawSignal.setPatientId(patientId);
        rawSignal.setRawPayload(rawPayload);
        rawSignal.setSignalType(signalType);
        rawSignal.setTimestamp(timestamp != null ? timestamp : Instant.now());
        rawSignal.setCreatedAt(Instant.now());

        return saveRawSignal(rawSignal);
    }

    public List<RawSignal> getPendingSignalsByPatient(String patientId) {
        if (patientId == null || patientId.isBlank()) {
            return Collections.emptyList();
        }

        return rawSignalRepository.findByPatientIdAndProcessedFalse(patientId);
    }

    @Transactional
    public void markAsProcessed(UUID rawSignalId) {
        if (rawSignalId == null) {
            return;
        }

        rawSignalRepository.markAsProcessed(rawSignalId);
    }

    @Transactional
    public void deleteOlderThan(Instant cutoffDate) {
        if (cutoffDate == null) {
            return;
        }

        rawSignalRepository.deleteOlderThan(cutoffDate);
    }

    private String normalizeJsonPayload(String rawPayload) {
        try {
            JsonNode node = objectMapper.readTree(rawPayload);
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            LOGGER.warn("rawPayload is not valid JSON, storing it as a JSON string");

            try {
                return objectMapper.writeValueAsString(rawPayload.trim());
            } catch (JsonProcessingException serializationEx) {
                throw new IllegalArgumentException("rawPayload could not be serialized", serializationEx);
            }
        }
    }
}
