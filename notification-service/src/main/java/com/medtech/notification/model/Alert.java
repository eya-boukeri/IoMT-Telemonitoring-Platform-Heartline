package com.medtech.notification.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.medtech.notification.model.enums.Priority;
import com.medtech.notification.model.enums.Severity;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Alert {
    private String alertId;
    private String snapshotId;
    private String patientId;
    private String patientName;
    private String alertType;
    private Severity severity;
    private Priority priority;
    private Instant timestamp;
    private String message;
    private Double detectionScore;
    private Double heartRate;
    private Double activity;
}