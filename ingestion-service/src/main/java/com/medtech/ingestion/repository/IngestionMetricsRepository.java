package com.medtech.ingestion.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.medtech.ingestion.model.IngestionMetrics;

public interface IngestionMetricsRepository extends JpaRepository<IngestionMetrics, UUID> {

    List<IngestionMetrics> findByPatientIdOrderByWindowEndDesc(String patientId);

    List<IngestionMetrics> findByPatientIdAndWindowStartBetweenOrderByWindowStartAsc(
        String patientId,
        Instant start,
        Instant end
    );
}
