package com.medtech.ingestion.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ingestion_metrics")
@Data
@NoArgsConstructor
public class IngestionMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private String patientId;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    @Column(name = "estimated_heart_rate")
    private Double estimatedHeartRate;

    @Column(name = "respiratory_rate")
    private Double respiratoryRate;

    @Column(name = "rr_mean_ms")
    private Double rrMeanMs;

    @Column(name = "rr_intervals_ms", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String rrIntervalsMs;

    @Column(name = "hrv_sdnn")
    private Double hrvSdnn;

    @Column(name = "hrv_rmssd")
    private Double hrvRmssd;

    @Column(name = "hrv_lf_hf")
    private Double hrvLfHf;

    @Column(name = "vascular_index")
    private Double vascularIndex;

    @Column(name = "perfusion_index")
    private Double perfusionIndex;

    @Column(name = "signal_quality_score")
    private Double signalQualityScore;

    @Column(name = "signal_quality_label")
    private String signalQualityLabel;

    @Column(name = "stress_index")
    private Double stressIndex;

    @Column(name = "stress_level")
    private String stressLevel;

    @Column(name = "ppg_mean")
    private Double ppgMean;

    @Column(name = "ppg_min")
    private Double ppgMin;

    @Column(name = "ppg_max")
    private Double ppgMax;

    @Column(name = "ppg_std_dev")
    private Double ppgStdDev;

    @Column(name = "ppg_variance")
    private Double ppgVariance;

    @Column(name = "activity_mean")
    private Double activityMean;

    @Column(name = "activity_max")
    private Double activityMax;

    @Column(name = "activity_variance")
    private Double activityVariance;

    @Column(name = "valid_samples")
    private Integer validSamples;

    @Column(name = "outlier_samples")
    private Integer outlierSamples;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
