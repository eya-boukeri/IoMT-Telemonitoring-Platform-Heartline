package com.medtech.vitalsmanagement.model;

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
    
    // PPG data supports both formats:
    // 1) {"green":1234.5, "red":987.3}
    // 2) {"2026-03-04T10:12:13.123":1234.5}
    private List<Map<String, Object>> ppgData;
    
    // Accelerometer data supports both formats:
    // 1) {"accelerometerPoint":{"x":0.1,"y":-0.9,"z":0.2}}
    // 2) {"2026-03-04T10:12:13.123":{"x":0.1,"y":-0.9,"z":0.2}}
    private List<Map<String, Object>> accelerometerData;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccelerometerPoint {
        private double x;
        private double y;
        private double z;
    }
}
