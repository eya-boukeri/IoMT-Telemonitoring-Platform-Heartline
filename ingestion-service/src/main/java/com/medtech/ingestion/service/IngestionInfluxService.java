package com.medtech.ingestion.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.medtech.ingestion.model.IngestionMetrics;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class IngestionInfluxService {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${influxdb.url:http://localhost:8088}")
    private String influxUrl;

    @Value("${influxdb.token:my-secret-token}")
    private String token;

    @Value("${influxdb.org:myorg}")
    private String influxOrg;

    @Value("${influxdb.bucket:medical_data}")
    private String bucket;

    public boolean save(IngestionMetrics metrics, List<Double> rrIntervalsMs) {
        if (metrics == null) {
            return false;
        }

        String lineProtocol = buildLineProtocol(metrics, rrIntervalsMs);
        if (lineProtocol == null || lineProtocol.isBlank()) {
            return false;
        }

        log.debug("InfluxDB line protocol: [{}]", lineProtocol);

        try {
            URI uri = buildWriteUri();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Token " + token)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(lineProtocol, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("✅ InfluxDB write successful for patient {}", metrics.getPatientId());
                return true;
            }

            log.warn("InfluxDB write failed status={} body={}", response.statusCode(), response.body());
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("InfluxDB write interrupted", ex);
            return false;
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("InfluxDB write failed", ex);
            return false;
        }
    }

    private URI buildWriteUri() {
        String baseUrl = stripTrailingSlash(influxUrl);
        String uri = baseUrl + "/api/v2/write?org=" + encodeQueryParam(influxOrg)
                + "&bucket=" + encodeQueryParam(bucket)
                + "&precision=ns";
        return URI.create(uri);
    }

    private String buildLineProtocol(IngestionMetrics metrics, List<Double> rrIntervalsMs) {
        Instant timestamp = metrics.getWindowEnd() != null ? metrics.getWindowEnd() : Instant.now();
        long epochNanos = timestamp.getEpochSecond() * 1_000_000_000L + timestamp.getNano();

        List<String> fields = new ArrayList<>();
        addLongField(fields,   "sample_count",          metrics.getSampleCount());
        addDoubleField(fields, "estimated_heart_rate",   metrics.getEstimatedHeartRate());
        addDoubleField(fields, "respiratory_rate",       metrics.getRespiratoryRate());
        addDoubleField(fields, "rr_mean_ms",             metrics.getRrMeanMs());
        if (rrIntervalsMs != null && !rrIntervalsMs.isEmpty()) {
            addStringField(fields, "rr_intervals_ms", rrIntervalsMs.toString());
        }
        addDoubleField(fields, "hrv_sdnn",               metrics.getHrvSdnn());
        addDoubleField(fields, "hrv_rmssd",              metrics.getHrvRmssd());
        addDoubleField(fields, "hrv_lf_hf",              metrics.getHrvLfHf());
        addDoubleField(fields, "vascular_index",         metrics.getVascularIndex());
        addDoubleField(fields, "perfusion_index",        metrics.getPerfusionIndex());
        addDoubleField(fields, "signal_quality_score",   metrics.getSignalQualityScore());
        addStringField(fields, "signal_quality_label",   metrics.getSignalQualityLabel());
        addDoubleField(fields, "stress_index",           metrics.getStressIndex());
        addStringField(fields, "stress_level",           metrics.getStressLevel());
        addDoubleField(fields, "ppg_mean",               metrics.getPpgMean());
        addDoubleField(fields, "ppg_min",                metrics.getPpgMin());
        addDoubleField(fields, "ppg_max",                metrics.getPpgMax());
        addDoubleField(fields, "ppg_std_dev",            metrics.getPpgStdDev());
        addDoubleField(fields, "ppg_variance",           metrics.getPpgVariance());
        addDoubleField(fields, "activity_mean",          metrics.getActivityMean());
        addDoubleField(fields, "activity_max",           metrics.getActivityMax());
        addDoubleField(fields, "activity_variance",      metrics.getActivityVariance());
        addLongField(fields,   "valid_samples",          metrics.getValidSamples());
        addLongField(fields,   "outlier_samples",        metrics.getOutlierSamples());

        if (fields.isEmpty()) {
            log.warn("No valid fields to write for patient {}", metrics.getPatientId());
            return null;
        }

        String patientId = sanitizeTag(metrics.getPatientId(), "unknown");
        String deviceId  = sanitizeTag(metrics.getDeviceId(),  "unknown");

        // Format strict InfluxDB Line Protocol :
        // measurement,tag1=val1,tag2=val2 field1=v1,field2=v2 timestamp
        // AUCUN espace dans la section tags, UN SEUL espace avant les fields
        return "vitals"
            + ",patientId=" + patientId
            + ",deviceId="  + deviceId
                + " "
                + String.join(",", fields)
                + " " + epochNanos;
    }

    // --- helpers fields ---

    /** Ajoute un champ entier avec suffixe 'i' (obligatoire pour InfluxDB integer) */
    private void addLongField(List<String> fields, String name, Number value) {
        if (value == null) return;
        long v = value.longValue();
        fields.add(name + "=" + v + "i");
    }

    /** Ajoute un champ flottant, ignore NaN et Infinite */
    private void addDoubleField(List<String> fields, String name, Number value) {
        if (value == null) return;
        double v = value.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) return;
        fields.add(name + "=" + v);
    }

    private void addStringField(List<String> fields, String name, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(name + "=\"" + escapeFieldString(value) + "\"");
        }
    }

    // --- helpers tags ---

    /**
     * Nettoie une valeur de tag pour InfluxDB :
     * - trim des espaces en bord
     * - remplace espaces internes, virgules et '=' par '_'
     * (ces caractères doivent être échappés en Line Protocol ; on préfère les supprimer)
     */
    private String sanitizeTag(String value, String fallback) {
        if (value == null) return fallback;
        String cleaned = value.trim().replaceAll("[\\s,=]", "_");
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private String escapeFieldString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // --- misc ---

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:8088";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String encodeQueryParam(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}