package com.medtech.notification.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.medtech.notification.model.Alert;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SseService {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> patientEmitters = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> globalEmitters = new CopyOnWriteArrayList<>();

    public SseEmitter createEmitter(String patientId) {
        SseEmitter emitter = new SseEmitter(600_000L);
        
        emitter.onCompletion(() -> removeEmitter(patientId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            removeEmitter(patientId, emitter);
        });
        
        patientEmitters.computeIfAbsent(patientId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        
        try {
            emitter.send(SseEmitter.event().name("connected").data("Stream ready"));
        } catch (IOException e) {
            log.error("Error sending connection event: {}", e.getMessage());
        }
        
        log.info("📡 SSE connection for patient {}", patientId);
        return emitter;
    }

    public SseEmitter createGlobalEmitter() {
        SseEmitter emitter = new SseEmitter(600_000L);
        globalEmitters.add(emitter);
        return emitter;
    }

    public void sendToPatient(String patientId, Alert alert) {
        CopyOnWriteArrayList<SseEmitter> emitters = patientEmitters.get(patientId);
        if (emitters != null) {
            emitters.forEach(emitter -> {
                try {
                    // Send as named 'alert' event for addEventListener('alert', ...)
                    emitter.send(SseEmitter.event().name("alert").data(alert));
                    // Also send as default message event for onmessage handler compatibility
                    emitter.send(SseEmitter.event().data(alert));
                    log.debug("📡 Alert sent to patient {}", patientId);
                } catch (IOException e) {
                    log.error("Error sending to patient {}: {}", patientId, e.getMessage());
                    removeEmitter(patientId, emitter);
                }
            });
        }
    }

    public void sendToAll(Alert alert) {
        globalEmitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("alert").data(alert));
            } catch (IOException e) {
                log.error("Error sending global alert: {}", e.getMessage());
                globalEmitters.remove(emitter);
            }
        });
    }

    private void removeEmitter(String patientId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = patientEmitters.get(patientId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                patientEmitters.remove(patientId);
            }
        }
    }

    // ✅ AJOUTER CETTE MÉTHODE
    public int getActiveConnections() {
        int patientConnections = patientEmitters.values().stream()
            .mapToInt(list -> list.size())
            .sum();
        return patientConnections + globalEmitters.size();
    }
}