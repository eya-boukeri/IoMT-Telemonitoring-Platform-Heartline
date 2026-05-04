package com.medtech.ingestion.storage;

public record StoredSnapshotReference(
        boolean storedInR2,
        String provider,
        String bucket,
        String objectKey,
        String eTag) {
}