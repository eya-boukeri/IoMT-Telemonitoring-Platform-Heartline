package com.medtech.notification.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.medtech.notification.repository.NotificationLogRepository;
import com.medtech.notification.service.SseService;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private SseService sseService;

    @Autowired
    private NotificationLogRepository logRepository;

    @GetMapping(value = "/stream/{patientId}", produces = "text/event-stream")
    public SseEmitter streamNotifications(@PathVariable String patientId) {
        return sseService.createEmitter(patientId);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("service", "notification-service");
        status.put("activeConnections", sseService.getActiveConnections());
        status.put("totalNotifications", logRepository.count());
        return status;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", logRepository.count());
        stats.put("activeConnections", sseService.getActiveConnections());
        return stats;
    }
}