package com.medtech.vitalsmanagement.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.medtech.vitalsmanagement.model.VitalData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class VitalStreamService {

    // {patientId} -> [SseEmitter, SseEmitter, ...]
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emittersByPatient = 
        new ConcurrentHashMap<>();

    // Global stream for all patients
    private final CopyOnWriteArrayList<SseEmitter> globalEmitters = new CopyOnWriteArrayList<>();

    private static final long SSE_TIMEOUT = 300000L; // 5 minutes

    /**
     * Register a new SSE emitter for a specific patient
     */
    public SseEmitter registerPatientStream(String patientId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByPatient.computeIfAbsent(
            patientId, 
            k -> new CopyOnWriteArrayList<>()
        );
        
        emitters.add(emitter);
        
        // Setup cleanup
        setupEmitterCallbacks(emitter, patientId, emitters);
        
        log.info("📡 SSE stream registered for patient: {}, total: {}", patientId, emitters.size());
        
        return emitter;
    }

    /**
     * Register a global SSE emitter (receives all patients' data)
     */
    public SseEmitter registerGlobalStream() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        globalEmitters.add(emitter);
        
        // Setup cleanup
        setupGlobalEmitterCallbacks(emitter);
        
        log.info("📡 Global SSE stream registered, total: {}", globalEmitters.size());
        
        return emitter;
    }

    /**
     * Send vital data to a specific patient's stream + global stream
     */
    public void sendVitalToPatient(String patientId, VitalData vital) {
        if (vital == null || patientId == null) {
            return;
        }

        // Send to patient-specific stream
        CopyOnWriteArrayList<SseEmitter> patientEmitters = emittersByPatient.get(patientId);
        if (patientEmitters != null && !patientEmitters.isEmpty()) {
            sendToEmitters(patientEmitters, vital, "vital-" + patientId);
        }

        // Send to global stream
        if (!globalEmitters.isEmpty()) {
            sendToEmitters(globalEmitters, vital, "vital-all");
        }
    }

    /**
     * Send vital stats to a patient's stream
     */
    public void sendStatsToPatient(String patientId, Object stats) {
        if (stats == null || patientId == null) {
            return;
        }

        CopyOnWriteArrayList<SseEmitter> patientEmitters = emittersByPatient.get(patientId);
        if (patientEmitters != null && !patientEmitters.isEmpty()) {
            sendToEmitters(patientEmitters, stats, "stats-" + patientId);
        }
    }

    /**
     * Broadcast vital to all patient streams
     */
    public void broadcastVital(VitalData vital) {
        if (vital == null) {
            return;
        }

        sendVitalToPatient(vital.getPatientId(), vital);
    }

    /**
     * Get active emitter count for a patient
     */
    public int getActiveEmitterCount(String patientId) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByPatient.get(patientId);
        return emitters != null ? emitters.size() : 0;
    }

    /**
     * Get global active emitter count
     */
    public int getGlobalEmitterCount() {
        return globalEmitters.size();
    }

    /**
     * Get all active patient streams
     */
    public List<String> getActivePatients() {
        return new ArrayList<>(emittersByPatient.keySet());
    }

    // ============ Private Helper Methods ============

    private void sendToEmitters(CopyOnWriteArrayList<SseEmitter> emitters, Object data, String eventName) {
        for (SseEmitter emitter : emitters) {
            try {
                SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .id(System.currentTimeMillis() + "-" + eventName)
                    .name(eventName)
                    .data(data)
                    .reconnectTime(1000);

                emitter.send(event);
                log.debug("✉️  Event sent: {}", eventName);
            } catch (IOException e) {
                log.warn("❌ Failed to send event: {}, removing emitter", eventName);
                emitters.remove(emitter);
            } catch (IllegalStateException e) {
                log.debug("⚠️  Emitter already completed/timeout: {}", e.getMessage());
                emitters.remove(emitter);
            }
        }
    }

    private void setupEmitterCallbacks(SseEmitter emitter, String patientId, 
                                       CopyOnWriteArrayList<SseEmitter> emitters) {
        emitter.onCompletion(() -> {
            log.info("✅ SSE stream completed for patient: {}", patientId);
            emitters.remove(emitter);
            cleanupIfEmpty(patientId);
        });

        emitter.onTimeout(() -> {
            log.warn("⏱️  SSE stream timeout for patient: {}", patientId);
            emitters.remove(emitter);
            cleanupIfEmpty(patientId);
        });

        emitter.onError((throwable) -> {
            log.error("❌ SSE stream error for patient: {}: {}", patientId, throwable.getMessage());
            emitters.remove(emitter);
            cleanupIfEmpty(patientId);
        });
    }

    private void setupGlobalEmitterCallbacks(SseEmitter emitter) {
        emitter.onCompletion(() -> {
            log.info("✅ Global SSE stream completed");
            globalEmitters.remove(emitter);
        });

        emitter.onTimeout(() -> {
            log.warn("⏱️  Global SSE stream timeout");
            globalEmitters.remove(emitter);
        });

        emitter.onError((throwable) -> {
            log.error("❌ Global SSE stream error: {}", throwable.getMessage());
            globalEmitters.remove(emitter);
        });
    }

    private void cleanupIfEmpty(String patientId) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByPatient.get(patientId);
        if (emitters != null && emitters.isEmpty()) {
            emittersByPatient.remove(patientId);
            log.info("🧹 Removed empty emitter list for patient: {}", patientId);
        }
    }
}
