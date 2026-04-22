package com.medtech.ingestion.service;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private String normalizeJsonPayload(String payload) {
    if (payload == null || payload.isEmpty()) {
        throw new IllegalArgumentException("Payload is empty");
    }
    String cleanPayload = payload.trim().replaceFirst("^\uFEFF", "");
    
    // Tenter de parser directement
    try {
        JsonNode node = objectMapper.readTree(cleanPayload);
        return objectMapper.writeValueAsString(node);
    } catch (IOException e) {
        // Si échec, tenter de réparer les clés sans guillemets
        String repaired = repairJsonWithoutQuotedKeys(cleanPayload);
        try {
            JsonNode node = objectMapper.readTree(repaired);
            return objectMapper.writeValueAsString(node);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to parse JSON payload even after repair: " + cleanPayload, ex);
        }
    }
}

private String repairJsonWithoutQuotedKeys(String input) {
    // Ajouter des guillemets autour des clés si elles n'en ont pas
    // Exemple: {patientId:value} -> {"patientId":value}
    // On utilise une regex simple pour trouver les clés non quotées avant les ':'
    String regex = "(\\{|,)\\s*([a-zA-Z0-9_]+)\\s*:";
    String replacement = "$1\"$2\":";
    return input.replaceAll(regex, replacement);
}
}
