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
@Table(name = "raw_signals")
@Data
@NoArgsConstructor
public class RawSignal {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false)
    private Instant timestamp;
    
    @Column(name = "device_id", nullable = false)
    private String deviceId;
    
    @Column(name = "patient_id")
    private String patientId;
    
    @Column(name = "raw_payload", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String rawPayload;
    
    @Column(name = "signal_type")
    private String signalType;
    
    @Column(name = "processed")
    private boolean processed = false;
    
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}