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
import java.util.StringJoiner;

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

        try {
            URI uri = buildWriteUri();
            HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Token " + token)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(lineProtocol, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
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
        addNumericField(fields, "sample_count", metrics.getSampleCount());
        addNumericField(fields, "estimated_heart_rate", metrics.getEstimatedHeartRate());
        addNumericField(fields, "respiratory_rate", metrics.getRespiratoryRate());
        addNumericField(fields, "rr_mean_ms", metrics.getRrMeanMs());
        if (rrIntervalsMs != null && !rrIntervalsMs.isEmpty()) {
            addStringField(fields, "rr_intervals_ms", rrIntervalsMs.toString());
        }
        addNumericField(fields, "hrv_sdnn", metrics.getHrvSdnn());
        addNumericField(fields, "hrv_rmssd", metrics.getHrvRmssd());
        addNumericField(fields, "hrv_lf_hf", metrics.getHrvLfHf());
        addNumericField(fields, "vascular_index", metrics.getVascularIndex());
        addNumericField(fields, "perfusion_index", metrics.getPerfusionIndex());
        addNumericField(fields, "signal_quality_score", metrics.getSignalQualityScore());
        addStringField(fields, "signal_quality_label", metrics.getSignalQualityLabel());
        addNumericField(fields, "stress_index", metrics.getStressIndex());
        addStringField(fields, "stress_level", metrics.getStressLevel());
        addNumericField(fields, "ppg_mean", metrics.getPpgMean());
        addNumericField(fields, "ppg_min", metrics.getPpgMin());
        addNumericField(fields, "ppg_max", metrics.getPpgMax());
        addNumericField(fields, "ppg_std_dev", metrics.getPpgStdDev());
        addNumericField(fields, "ppg_variance", metrics.getPpgVariance());
        addNumericField(fields, "activity_mean", metrics.getActivityMean());
        addNumericField(fields, "activity_max", metrics.getActivityMax());
        addNumericField(fields, "activity_variance", metrics.getActivityVariance());
        addNumericField(fields, "valid_samples", metrics.getValidSamples());
        addNumericField(fields, "outlier_samples", metrics.getOutlierSamples());

        if (fields.isEmpty()) {
            return null;
        }

        StringJoiner line = new StringJoiner(",");
        line.add("ingestion_metrics")
            .add("patient_id=" + escapeTag(metrics.getPatientId() != null ? metrics.getPatientId() : "unknown"))
            .add("device_id=" + escapeTag(metrics.getDeviceId() != null ? metrics.getDeviceId() : "unknown"))
            .add(String.join(",", fields) + " " + epochNanos);

        return line.toString();
    }

    private void addNumericField(List<String> fields, String name, Number value) {
        if (value != null) {
            fields.add(name + "=" + value);
        }
    }

    private void addStringField(List<String> fields, String name, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(name + "=\"" + escapeField(value) + "\"");
        }
    }

    private String escapeTag(String value) {
        return value.replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=");
    }

    private String escapeField(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8088";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String encodeQueryParam(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}