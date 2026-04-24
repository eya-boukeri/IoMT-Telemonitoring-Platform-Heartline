package com.medtech.vitalsmanagement.model;

import java.time.Instant;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VitalData {
    private String patientId;
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING,
        pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSS]'Z'", timezone = "UTC")
    private Instant timestamp; // Moment exact de la mesure (snapshot)
    
    // === VITAL SIGNS (Primary Metrics) ===
    private Double heartRate; // bpm (primary value)
    private Double bloodPressureSystolic; // mmHg
    private Double bloodPressureDiastolic; // mmHg
    
    // === HEART RATE VARIABILITY (HRV) - preserved from time-series ===
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double heartRateMin; // minimum HR detected during period
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double heartRateMax; // maximum HR detected during period
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double heartRateVariability; // standard deviation (SDNN)
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer detectedPeaks; // number of heart beats detected
    
    // === PPG RAW DATA DETAILS ===
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double ppgGreenMin; // minimum green PPG value
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double ppgGreenMax; // maximum green PPG value
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double ppgGreenAverage; // average green PPG value

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double ppgFilteredSignal; // filtered PPG representative value (ingestion band-pass)
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double ppgRedMin; // minimum red PPG value
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double ppgRedMax; // maximum red PPG value
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double ppgRedAverage; // average red PPG value
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer ppgDataPoints; // number of PPG measurements
    
    // === ACCELEROMETER DATA (Activity Level) ===
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double accelerometerMagnitudeAverage; // average movement intensity
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double accelerometerMagnitudeMax; // peak movement intensity
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double accelerometerVariance; // activity variability (std dev)
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer accelerometerDataPoints; // number of acceleration measurements
    
    // === TIME WINDOW (Signal Duration) ===
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LocalDateTime startTime; // collection start
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LocalDateTime endTime; // collection end
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer collectionDurationSeconds; // total duration
    
    // === DATA QUALITY ===
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String signalQuality; // "excellent", "good", "fair", "poor"
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double signalQualityScore; // 0-100 percentage
}