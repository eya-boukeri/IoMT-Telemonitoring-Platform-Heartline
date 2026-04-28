package com.medtech.ingestion.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.medtech.ingestion.model.AnomalySnapshot;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class AnomalySnapshotObjectStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnomalySnapshotObjectStorageService.class);

    private final CloudflareR2Properties properties;
    private volatile S3Client s3Client;

    public AnomalySnapshotObjectStorageService(CloudflareR2Properties properties) {
        this.properties = properties;
    }

    public StoredSnapshotReference storeSnapshot(AnomalySnapshot snapshot) {
        if (snapshot == null || isBlank(snapshot.getRawData())) {
            return new StoredSnapshotReference(false, "postgres", null, null, null);
        }

        if (!isR2Configured()) {
            LOGGER.info("Cloudflare R2 disabled or incomplete configuration, keeping snapshot {} in Postgres", snapshot.getId());
            return new StoredSnapshotReference(false, "postgres", null, null, null);
        }

        String objectKey = buildObjectKey(snapshot);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.getBucketName())
                .key(objectKey)
                .contentType("application/json")
                .build();

            String eTag = client().putObject(request,
                RequestBody.fromString(snapshot.getRawData(), StandardCharsets.UTF_8)).eTag();

            LOGGER.info("Snapshot uploaded to Cloudflare R2: snapshotId={}, bucket={}, key={}",
                snapshot.getId(), properties.getBucketName(), objectKey);

            return new StoredSnapshotReference(true, "cloudflare-r2", properties.getBucketName(), objectKey, eTag);
        } catch (RuntimeException ex) {
            LOGGER.warn("Failed to upload snapshot {} to Cloudflare R2, falling back to Postgres: {}",
                snapshot.getId(), ex.getMessage());
            return new StoredSnapshotReference(false, "postgres", null, null, null);
        }
    }

    public Optional<String> readSnapshot(AnomalySnapshot snapshot) {
        if (snapshot == null) {
            return Optional.empty();
        }

        if (!isBlank(snapshot.getRawData())) {
            return Optional.of(snapshot.getRawData());
        }

        if (!isR2Configured() || isBlank(snapshot.getStorageKey())) {
            return Optional.empty();
        }

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(resolveBucket(snapshot))
                .key(snapshot.getStorageKey())
                .build();

            ResponseBytes<GetObjectResponse> response = client().getObject(request, ResponseTransformer.toBytes());
            return Optional.of(new String(response.asByteArray(), StandardCharsets.UTF_8));
        } catch (S3Exception ex) {
            LOGGER.warn("Failed to read snapshot {} from Cloudflare R2: {}", snapshot.getId(), ex.getMessage());
            return Optional.empty();
        } catch (RuntimeException ex) {
            LOGGER.warn("Unexpected error reading snapshot {} from Cloudflare R2: {}", snapshot.getId(), ex.getMessage());
            return Optional.empty();
        }
    }

    public boolean isR2Configured() {
        return properties.isEnabled()
            && !isBlank(properties.getAccessKeyId())
            && !isBlank(properties.getSecretAccessKey())
            && !isBlank(properties.getBucketName())
            && !isBlank(resolveEndpoint());
    }

    private String resolveBucket(AnomalySnapshot snapshot) {
        return !isBlank(snapshot.getStorageBucket()) ? snapshot.getStorageBucket() : properties.getBucketName();
    }

    private String buildObjectKey(AnomalySnapshot snapshot) {
        String prefix = !isBlank(properties.getObjectPrefix()) ? properties.getObjectPrefix() : "snapshots";
        Instant detectedAt = snapshot.getDetectedAt() != null ? snapshot.getDetectedAt() : Instant.now();
        String patientId = sanitize(snapshot.getPatientId());
        String alertId = sanitize(snapshot.getAlertId());
        return String.format("%s/%s/%s/%s-%s.json",
            prefix,
            patientId,
            detectedAt.toEpochMilli(),
            alertId,
            UUID.randomUUID());
    }

    private String sanitize(String value) {
        if (isBlank(value)) {
            return "unknown";
        }

        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String resolveEndpoint() {
        if (!isBlank(properties.getEndpoint())) {
            return properties.getEndpoint();
        }

        if (!isBlank(properties.getAccountId())) {
            return "https://" + properties.getAccountId() + ".r2.cloudflarestorage.com";
        }

        return null;
    }

    private S3Client client() {
        if (s3Client == null) {
            synchronized (this) {
                if (s3Client == null) {
                    s3Client = S3Client.builder()
                        .endpointOverride(URI.create(resolveEndpoint()))
                        .region(Region.of(!isBlank(properties.getRegion()) ? properties.getRegion() : "auto"))
                        .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey())))
                        .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build())
                        .build();
                }
            }
        }

        return s3Client;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @PreDestroy
    public void shutdown() {
        if (s3Client != null) {
            s3Client.close();
        }
    }
}