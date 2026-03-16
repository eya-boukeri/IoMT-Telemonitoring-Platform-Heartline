package com.medtech.ingestion.model;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AggregatedData {
    private String patientId;
    private String deviceId;
    private Instant windowStart;
    private Instant windowEnd;
    private int sampleCount;
    
    // PPG statistics
    private double ppgMean;
    private double ppgMin;
    private double ppgMax;
    private double ppgStdDev;
    private double ppgVariance;
    
    // Heart rate estimation
    private Double estimatedHeartRate;
    private Double heartRateConfidence;
    
    // Activity metrics
    private double activityMean;
    private double activityMax;
    private double activityVariance;
    
    // Quality metrics
    private double signalQuality;
    private int validSamples;
    private int outlierSamples;
}