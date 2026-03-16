package com.medtech.ingestion.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.medtech.ingestion.model.RawSignal;

@Repository
public interface RawSignalRepository extends JpaRepository<RawSignal, UUID> {
    
    List<RawSignal> findByDeviceIdAndTimestampBetween(
        String deviceId, Instant start, Instant end);
    
    List<RawSignal> findByPatientIdAndProcessedFalse(String patientId);
    
    @Modifying
    @Transactional
    @Query("UPDATE RawSignal r SET r.processed = true WHERE r.id = :id")
    void markAsProcessed(@Param("id") UUID id);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM RawSignal r WHERE r.createdAt < :cutoffDate")
    void deleteOlderThan(@Param("cutoffDate") Instant cutoffDate);
}