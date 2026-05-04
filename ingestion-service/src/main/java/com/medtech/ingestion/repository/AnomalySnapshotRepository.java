package com.medtech.ingestion.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.medtech.ingestion.model.AnomalySnapshot;

@Repository
public interface AnomalySnapshotRepository extends JpaRepository<AnomalySnapshot, UUID> {

    Optional<AnomalySnapshot> findByAlertId(String alertId);

    List<AnomalySnapshot> findByPatientIdOrderByDetectedAtDesc(String patientId);
}
