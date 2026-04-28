package com.medtech.ingestion.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
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

    /**
     * Récupère les derniers signaux bruts d'un patient, triés par timestamp décroissant.
     * Utilisé pour extraire les 200 points bruts lors d'une détection d'anomalie.
     */
    @Query("SELECT r FROM RawSignal r WHERE r.patientId = :patientId ORDER BY r.timestamp DESC")
    List<RawSignal> findLatestByPatientId(@Param("patientId") String patientId, Pageable pageable);

    /**
     * Récupère les signaux bruts d'un patient dans une fenêtre temporelle.
     */
    @Query("SELECT r FROM RawSignal r WHERE r.patientId = :patientId " +
           "AND r.timestamp BETWEEN :start AND :end ORDER BY r.timestamp ASC")
    List<RawSignal> findByPatientIdAndTimestampBetween(
        @Param("patientId") String patientId,
        @Param("start") Instant start,
        @Param("end") Instant end);
    
    @Modifying
    @Transactional
    @Query("UPDATE RawSignal r SET r.processed = true WHERE r.id = :id")
    void markAsProcessed(@Param("id") UUID id);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM RawSignal r WHERE r.createdAt < :cutoffDate")
    void deleteOlderThan(@Param("cutoffDate") Instant cutoffDate);
}