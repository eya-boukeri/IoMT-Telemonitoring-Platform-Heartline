package com.medtech.ingestion.controller;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.medtech.ingestion.model.AnomalySnapshot;
import com.medtech.ingestion.model.RawSignal;
import com.medtech.ingestion.repository.RawSignalRepository;
import com.medtech.ingestion.service.AnomalySnapshotService;
import com.medtech.ingestion.service.HealthCheckService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/ingestion")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class IngestionController {

    private final HealthCheckService healthCheckService;
    private final RawSignalRepository rawSignalRepository;
    private final AnomalySnapshotService anomalySnapshotService;

    // ============ Health Check ============

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = healthCheckService.getHealthStatus();
        String status = String.valueOf(health.getOrDefault("status", "DEGRADED"));

        if ("UP".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(health);
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(health);
    }

    // ============ Raw Signals Endpoints ============

    /**
     * Récupère les N derniers signaux bruts d'un patient.
     * Utilisé par le data-analytics-service pour extraire les 200 points
     * lors d'une détection d'anomalie.
     *
     * GET /api/ingestion/raw-signals/{patientId}/latest?limit=200
     */
    @GetMapping("/raw-signals/{patientId}/latest")
    public ResponseEntity<List<Map<String, Object>>> getLatestRawSignals(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "200") int limit) {

        if (patientId == null || patientId.isBlank()) {
            return ResponseEntity.badRequest().body(Collections.emptyList());
        }

        int safeLimit = Math.min(Math.max(limit, 1), 500);

        List<RawSignal> signals = rawSignalRepository.findLatestByPatientId(
                patientId, PageRequest.of(0, safeLimit));

        // Inverser pour avoir l'ordre chronologique (ancien → récent)
        Collections.reverse(signals);

        List<Map<String, Object>> result = signals.stream()
            .map(s -> Map.<String, Object>of(
                "id", s.getId().toString(),
                "timestamp", s.getTimestamp().toString(),
                "rawPayload", s.getRawPayload(),
                "signalType", s.getSignalType() != null ? s.getSignalType() : ""
            ))
            .collect(Collectors.toList());

        log.info("Returned {} raw signals for patient {}", result.size(), patientId);
        return ResponseEntity.ok(result);
    }

    // ============ Anomaly Snapshot Endpoints ============

    /**
     * Crée un snapshot d'anomalie avec les données brutes.
     * Appelé par le data-analytics-service après détection d'une anomalie.
     *
     * POST /api/ingestion/anomaly-snapshots
     */
    @PostMapping("/anomaly-snapshots")
    public ResponseEntity<Map<String, Object>> createAnomalySnapshot(
            @RequestBody AnomalySnapshot snapshot) {
        try {
            AnomalySnapshot saved = anomalySnapshotService.saveSnapshot(snapshot);

            Map<String, Object> response = new HashMap<>();
            response.put("snapshotId", saved.getId().toString());
            response.put("alertId", saved.getAlertId());
            response.put("patientId", saved.getPatientId());
            response.put("storageProvider", saved.getStorageProvider());
            response.put("storageBucket", saved.getStorageBucket());
            response.put("storageKey", saved.getStorageKey());
            response.put("storageEtag", saved.getStorageEtag());
            response.put("status", "created");

            log.info("Anomaly snapshot created: snapshotId={}, alertId={}, patientId={}",
                saved.getId(), saved.getAlertId(), saved.getPatientId());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid snapshot request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Invalid request"));
        } catch (Exception e) {
            log.error("Error creating anomaly snapshot: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to create snapshot"));
        }
    }

    /**
     * Récupère un snapshot d'anomalie par son UUID.
     * Appelé par le dashboard pour afficher la courbe brute.
     *
     * GET /api/ingestion/anomaly-snapshots/{snapshotId}
     */
    @GetMapping("/anomaly-snapshots/{snapshotId}")
    public ResponseEntity<?> getAnomalySnapshot(@PathVariable String snapshotId) {
        try {
            UUID uuid = UUID.fromString(snapshotId);
            return anomalySnapshotService.getSnapshotById(uuid)
                .map(snapshot -> ResponseEntity.ok((Object) snapshot))
                .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid snapshot ID format"));
        }
    }

    /**
     * Récupère un snapshot d'anomalie par l'ID de l'alerte associée.
     *
     * GET /api/ingestion/anomaly-snapshots/by-alert/{alertId}
     */
    @GetMapping("/anomaly-snapshots/by-alert/{alertId}")
    public ResponseEntity<?> getAnomalySnapshotByAlertId(@PathVariable String alertId) {
        return anomalySnapshotService.getSnapshotByAlertId(alertId)
            .map(snapshot -> ResponseEntity.ok((Object) snapshot))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Liste les snapshots d'un patient.
     *
     * GET /api/ingestion/anomaly-snapshots/patient/{patientId}
     */
    @GetMapping("/anomaly-snapshots/patient/{patientId}")
    public ResponseEntity<List<AnomalySnapshot>> getSnapshotsByPatient(
            @PathVariable String patientId) {
        List<AnomalySnapshot> snapshots = anomalySnapshotService.getSnapshotsByPatientId(patientId);
        return ResponseEntity.ok(snapshots);
    }
}