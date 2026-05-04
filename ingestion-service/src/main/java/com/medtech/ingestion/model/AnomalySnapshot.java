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
@Table(name = "anomaly_snapshots")
@Data
@NoArgsConstructor
public class AnomalySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "alert_id", nullable = false)
    private String alertId;

    @Column(name = "patient_id", nullable = false)
    private String patientId;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "severity")
    private String severity;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "message")
    private String message;

    @Column(name = "storage_provider")
    private String storageProvider;

    @Column(name = "storage_bucket")
    private String storageBucket;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "storage_etag")
    private String storageEtag;

    @Column(name = "raw_data", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String rawData;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
