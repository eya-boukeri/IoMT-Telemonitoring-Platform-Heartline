package com.medtech.ingestion.model;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilteredSignal {
    private String patientId;
    private String deviceId;
    private Instant timestamp;
    private List<Double> ppgData;
    private List<Double> accelerometerX;
    private List<Double> accelerometerY;
    private List<Double> accelerometerZ;
    private double signalQuality;
    private int outlierCount;
    private ProcessingMetadata metadata;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingMetadata {
        private double mean;
        private double stdDev;
        private double min;
        private double max;
        private int originalLength;
        private double motionMean;
        private double motionMax;
        private double motionThreshold;
        private double motionRatio;
        private int removedSamples;
        private int corruptedSegments;
        private int cleanedSamples;
    }
}