package com.medtech.vitalsmanagement.model;

import java.time.Instant;

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
    private Instant timestamp;//Moment exact de la mesure
    private Double heartRate; // bpm
    private Double bloodPressureSystolic; // mmHg
    private Double bloodPressureDiastolic; // mmHg
    private Double temperature; // Celsius
    private Double oxygenSaturation; // %
}