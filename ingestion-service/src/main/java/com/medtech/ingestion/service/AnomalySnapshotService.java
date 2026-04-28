package com.medtech.ingestion.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.medtech.ingestion.model.AnomalySnapshot;
import com.medtech.ingestion.repository.AnomalySnapshotRepository;
import com.medtech.ingestion.storage.AnomalySnapshotObjectStorageService;
import com.medtech.ingestion.storage.StoredSnapshotReference;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AnomalySnapshotService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnomalySnapshotService.class);

    private final AnomalySnapshotRepository snapshotRepository;
    private final AnomalySnapshotObjectStorageService objectStorageService;

    /**
     * Sauvegarde un snapshot d'anomalie contenant les points bruts.
     */
    public AnomalySnapshot saveSnapshot(AnomalySnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is required");
        }
        if (snapshot.getAlertId() == null || snapshot.getAlertId().isBlank()) {
            throw new IllegalArgumentException("alertId is required");
        }
        if (snapshot.getPatientId() == null || snapshot.getPatientId().isBlank()) {
            throw new IllegalArgumentException("patientId is required");
        }
        if (snapshot.getRawData() == null || snapshot.getRawData().isBlank()) {
            throw new IllegalArgumentException("rawData is required");
        }

        if (snapshot.getDetectedAt() == null) {
            snapshot.setDetectedAt(Instant.now());
        }
        if (snapshot.getCreatedAt() == null) {
            snapshot.setCreatedAt(Instant.now());
        }

        AnomalySnapshot saved = snapshotRepository.save(snapshot);

        StoredSnapshotReference storageReference = objectStorageService.storeSnapshot(saved);
        saved.setStorageProvider(storageReference.provider());
        saved.setStorageBucket(storageReference.bucket());
        saved.setStorageKey(storageReference.objectKey());
        saved.setStorageEtag(storageReference.eTag());

        if (storageReference.storedInR2()) {
            saved.setRawData(null);
        }

        saved = snapshotRepository.save(saved);
        LOGGER.info("Anomaly snapshot saved: id={}, alertId={}, patientId={}, points={}",
            saved.getId(), saved.getAlertId(), saved.getPatientId(),
            saved.getRawData() != null ? saved.getRawData().length() : 0);
        return hydrateSnapshot(saved);
    }

    /**
     * Récupère un snapshot par son UUID.
     */
    public Optional<AnomalySnapshot> getSnapshotById(UUID id) {
        return snapshotRepository.findById(id).map(this::hydrateSnapshot);
    }

    /**
     * Récupère un snapshot par l'ID de l'alerte associée.
     */
    public Optional<AnomalySnapshot> getSnapshotByAlertId(String alertId) {
        return snapshotRepository.findByAlertId(alertId).map(this::hydrateSnapshot);
    }

    /**
     * Liste tous les snapshots d'un patient, du plus récent au plus ancien.
     */
    public List<AnomalySnapshot> getSnapshotsByPatientId(String patientId) {
        return snapshotRepository.findByPatientIdOrderByDetectedAtDesc(patientId)
            .stream()
            .map(this::hydrateSnapshot)
            .toList();
    }

    private AnomalySnapshot hydrateSnapshot(AnomalySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }

        if ((snapshot.getRawData() == null || snapshot.getRawData().isBlank())
                && snapshot.getStorageKey() != null) {
            objectStorageService.readSnapshot(snapshot)
                .ifPresent(snapshot::setRawData);
        }

        return snapshot;
    }
}
