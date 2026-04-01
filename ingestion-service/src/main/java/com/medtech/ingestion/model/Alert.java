package com.medtech.ingestion.model;

import java.time.Instant;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Alert {
    private String alertId;
    private String patientId;
    private String patientName;
    private String alertType;
    private String severity;
    private String priority;
    private Instant timestamp;
    private String message;
    private Double detectionScore;
    private Double heartRate;
    private Double activity;
}