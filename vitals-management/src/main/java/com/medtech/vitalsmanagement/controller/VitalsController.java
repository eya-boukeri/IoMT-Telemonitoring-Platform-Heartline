package com.medtech.vitalsmanagement.controller;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.medtech.vitalsmanagement.model.VitalData;
import com.medtech.vitalsmanagement.model.VitalStatsResponse;
import com.medtech.vitalsmanagement.service.InfluxDBService;
import com.medtech.vitalsmanagement.service.VitalStreamService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/vitals")
public class VitalsController {

    private final InfluxDBService influxDBService;
    private final VitalStreamService vitalStreamService;

    public VitalsController(InfluxDBService influxDBService, VitalStreamService vitalStreamService) {
        this.influxDBService = influxDBService;
        this.vitalStreamService = vitalStreamService;
    }

    @GetMapping("/history/{patientId}")
    public ResponseEntity<List<VitalData>> getHistory(
            @PathVariable String patientId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant start,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant end,
            @RequestParam(required = false) Integer limit) {
        List<VitalData> data = influxDBService.getHistory(patientId, start, end, limit);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/latest/{patientId}")
    public ResponseEntity<List<VitalData>> getLatest(
            @PathVariable String patientId,
            @RequestParam(defaultValue = "10") int limit) {
        List<VitalData> data = influxDBService.getLatest(patientId, limit);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/recent")
    public ResponseEntity<List<VitalData>> getRecent(
            @RequestParam(defaultValue = "10") long minutes,
            @RequestParam(required = false) Integer limit) {
        Instant since = Instant.now().minus(minutes, ChronoUnit.MINUTES);
        List<VitalData> data = influxDBService.getRecentAllPatients(since, limit);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/stats/{patientId}")
    public ResponseEntity<VitalStatsResponse> getStats(
            @PathVariable String patientId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant start,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant end) {
        VitalStatsResponse stats = influxDBService.getStats(patientId, start, end);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/patients")
    public ResponseEntity<List<String>> getPatients(
            @RequestParam(defaultValue = "30") int days) {
        List<String> patients = influxDBService.getPatientsWithData(days);
        return ResponseEntity.ok(patients);
    }

    // ============ SSE (Server-Sent Events) Endpoints ============

    @GetMapping(value = "/stream/{patientId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPatientVitals(@PathVariable String patientId) {
        log.info("🔌 New SSE stream connection for patient: {}", patientId);

        SseEmitter emitter = vitalStreamService.registerPatientStream(patientId);

        // Load historical data asynchronously and send it first
        CompletableFuture.runAsync(() -> {
            try {
                // Get latest 20 vitals as initial data
                List<VitalData> latest = influxDBService.getLatest(patientId, 20);
                
                for (VitalData vital : latest) {
                    try {
                        SseEmitter.SseEventBuilder event = SseEmitter.event()
                            .id(vital.getPatientId() + "-" + vital.getTimestamp())
                            .data(vital)
                            .reconnectTime(1000);
                        emitter.send(event);
                    } catch (IOException e) {
                        log.warn("Failed to send historical vital for patient: {}", patientId);
                        emitter.completeWithError(e);
                        return;
                    }
                }
                
                // Send "ready" event to indicate data is streaming live now
                try {
                    SseEmitter.SseEventBuilder readyEvent = SseEmitter.event()
                        .id("ready-" + System.currentTimeMillis())
                        .name("ready")
                        .data("Stream ready for patient: " + patientId)
                        .reconnectTime(1000);
                    emitter.send(readyEvent);
                    log.info("✅ Stream ready for patient: {}, loaded {} historical vitals", patientId, latest.size());
                } catch (IOException e) {
                    log.error("Failed to send ready event for patient: {}", patientId);
                }

            } catch (Exception e) {
                log.error("❌ Error loading historical data for patient: {}: {}", patientId, e.getMessage());
                try {
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    log.error("Failed to complete emitter with error");
                }
            }
        });

        return emitter;
    }

    @GetMapping(value = "/stream/global", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAllVitals() {
        log.info("🔌 New global SSE stream connection");

        SseEmitter emitter = vitalStreamService.registerGlobalStream();

        // Load latest vitals from all patients
        CompletableFuture.runAsync(() -> {
            try {
                List<String> patients = influxDBService.getPatientsWithData(1);
                int totalSent = 0;

                for (String patientId : patients) {
                    try {
                        List<VitalData> latest = influxDBService.getLatest(patientId, 5);
                        for (VitalData vital : latest) {
                            try {
                                SseEmitter.SseEventBuilder event = SseEmitter.event()
                                    .id(vital.getPatientId() + "-" + vital.getTimestamp())
                                    .data(vital)
                                    .reconnectTime(1000);
                                emitter.send(event);
                                totalSent++;
                            } catch (IOException e) {
                                log.warn("Failed to send vital for patient: {}", patientId);
                                return;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load vitals for patient: {}: {}", patientId, e.getMessage());
                    }
                }

                // Ready signal
                SseEmitter.SseEventBuilder readyEvent = SseEmitter.event()
                    .id("ready-" + System.currentTimeMillis())
                    .name("ready")
                    .data("Global stream ready, loaded " + totalSent + " historical vitals")
                    .reconnectTime(1000);
                emitter.send(readyEvent);
                log.info("✅ Global stream ready, loaded {} historical vitals", totalSent);

            } catch (Exception e) {
                log.error("❌ Error loading initial data for global stream: {}", e.getMessage());
                try {
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    // Ignore
                }
            }
        });

        return emitter;
    }

    @GetMapping("/stream/status")
    public ResponseEntity<StreamStatus> getStreamStatus() {
        List<String> activePatients = vitalStreamService.getActivePatients();
        int totalEmitters = activePatients.stream()
            .mapToInt(vitalStreamService::getActiveEmitterCount)
            .sum() + vitalStreamService.getGlobalEmitterCount();

        StreamStatus status = new StreamStatus(
            activePatients,
            activePatients.size(),
            totalEmitters,
            vitalStreamService.getGlobalEmitterCount()
        );

        return ResponseEntity.ok(status);
    }

    // ============ Inner Class for Stream Status ============

    public static class StreamStatus {
        public List<String> activePatients;
        public int activePatientCount;
        public int totalEmitters;
        public int globalEmitters;

        public StreamStatus(List<String> activePatients, int activePatientCount, 
                           int totalEmitters, int globalEmitters) {
            this.activePatients = activePatients;
            this.activePatientCount = activePatientCount;
            this.totalEmitters = totalEmitters;
            this.globalEmitters = globalEmitters;
        }
    }
}