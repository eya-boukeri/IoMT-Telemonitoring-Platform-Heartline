package com.medtech.vitalsmanagement.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Modèle pour les données reçues depuis SensorApp (smartwatch Samsung)
 * Format: Séries temporelles avec PPG, accéléromètre
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObservationData {
    private String patientId;
    private String startTime;  // Format: ISO LocalDateTime
    private String endTime;    // Format: ISO LocalDateTime
    
    // PPG data: List of maps with timestamp -> value
    private List<Map<String, Double>> ppgData;
    
    // Accelerometer data: List of maps with timestamp -> {x, y, z}
    private List<Map<String, AccelerometerPoint>> accelerometerData;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccelerometerPoint {
        private double x;
        private double y;
        private double z;
    }
}
