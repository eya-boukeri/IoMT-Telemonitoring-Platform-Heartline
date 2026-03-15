package com.medtech.vitalsmanagement.model;

import java.time.Instant;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VitalStatsResponse {
    private String patientId;
    private Instant start;
    private Instant end;
    private Map<String, VitalStat> stats;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VitalStat {
        private Double min;
        private Double max;
        private Double avg;
        private Double last;
    }
}
