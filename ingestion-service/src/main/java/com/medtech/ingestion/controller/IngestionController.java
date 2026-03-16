package com.medtech.ingestion.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medtech.ingestion.service.HealthCheckService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final HealthCheckService healthCheckService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = healthCheckService.getHealthStatus();
        String status = String.valueOf(health.getOrDefault("status", "DEGRADED"));

        if ("UP".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(health);
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(health);
    }
}