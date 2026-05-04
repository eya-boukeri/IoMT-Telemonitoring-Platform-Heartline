package com.medtech.vitalsmanagement.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.medtech.vitalsmanagement.model.VitalData;
import com.medtech.vitalsmanagement.model.VitalStatsResponse;
import com.medtech.vitalsmanagement.model.VitalStatsResponse.VitalStat;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class InfluxDBService {

    @Autowired
    private InfluxDBClient influxDBClient;

    @Value("${influxdb.org:medtech}")
    private String orgName;

    @Value("${influxdb.bucket:vitals}")
    private String bucket;

    public boolean saveVitalData(VitalData vitalData) {
        if (vitalData == null) {
            log.warn("InfluxDB: vitalData is null, skipping write");
            return false;
        }

        WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();

        Instant timestamp = vitalData.getTimestamp() != null
                ? vitalData.getTimestamp()
                : Instant.now();

        Point point = Point.measurement("vitals").time(timestamp, WritePrecision.NS);

        if (vitalData.getPatientId() != null && !vitalData.getPatientId().isBlank()) {
            point.addTag("patientId", vitalData.getPatientId());
        }

        boolean hasField = false;
        if (vitalData.getHeartRate() != null) {
            point.addField("heartRate", vitalData.getHeartRate());
            hasField = true;
        }
        if (vitalData.getBloodPressureSystolic() != null) {
            point.addField("bloodPressureSystolic", vitalData.getBloodPressureSystolic());
            hasField = true;
        }
        if (vitalData.getBloodPressureDiastolic() != null) {
            point.addField("bloodPressureDiastolic", vitalData.getBloodPressureDiastolic());
            hasField = true;
        }
        if (vitalData.getHeartRateMin() != null) {
            point.addField("heartRateMin", vitalData.getHeartRateMin());
            hasField = true;
        }
        if (vitalData.getHeartRateMax() != null) {
            point.addField("heartRateMax", vitalData.getHeartRateMax());
            hasField = true;
        }
        if (vitalData.getHeartRateVariability() != null) {
            point.addField("heartRateVariability", vitalData.getHeartRateVariability());
            hasField = true;
        }
        if (vitalData.getDetectedPeaks() != null) {
            point.addField("detectedPeaks", vitalData.getDetectedPeaks());
            hasField = true;
        }
        if (vitalData.getPpgGreenMin() != null) {
            point.addField("ppgGreenMin", vitalData.getPpgGreenMin());
            hasField = true;
        }
        if (vitalData.getPpgGreenMax() != null) {
            point.addField("ppgGreenMax", vitalData.getPpgGreenMax());
            hasField = true;
        }
        if (vitalData.getPpgGreenAverage() != null) {
            point.addField("ppgGreenAverage", vitalData.getPpgGreenAverage());
            hasField = true;
        }
        if (vitalData.getPpgFilteredSignal() != null) {
            point.addField("ppgFilteredSignal", vitalData.getPpgFilteredSignal());
            hasField = true;
        }
        if (vitalData.getPpgRedMin() != null) {
            point.addField("ppgRedMin", vitalData.getPpgRedMin());
            hasField = true;
        }
        if (vitalData.getPpgRedMax() != null) {
            point.addField("ppgRedMax", vitalData.getPpgRedMax());
            hasField = true;
        }
        if (vitalData.getPpgRedAverage() != null) {
            point.addField("ppgRedAverage", vitalData.getPpgRedAverage());
            hasField = true;
        }
        if (vitalData.getPpgDataPoints() != null) {
            point.addField("ppgDataPoints", vitalData.getPpgDataPoints());
            hasField = true;
        }
        if (vitalData.getAccelerometerMagnitudeAverage() != null) {
            point.addField("accelerometerMagnitudeAverage", vitalData.getAccelerometerMagnitudeAverage());
            hasField = true;
        }
        if (vitalData.getAccelerometerMagnitudeMax() != null) {
            point.addField("accelerometerMagnitudeMax", vitalData.getAccelerometerMagnitudeMax());
            hasField = true;
        }
        if (vitalData.getAccelerometerVariance() != null) {
            point.addField("accelerometerVariance", vitalData.getAccelerometerVariance());
            hasField = true;
        }
        if (vitalData.getAccelerometerDataPoints() != null) {
            point.addField("accelerometerDataPoints", vitalData.getAccelerometerDataPoints());
            hasField = true;
        }
        if (vitalData.getCollectionDurationSeconds() != null) {
            point.addField("collectionDurationSeconds", vitalData.getCollectionDurationSeconds());
            hasField = true;
        }
        if (vitalData.getSignalQualityScore() != null) {
            point.addField("signalQualityScore", vitalData.getSignalQualityScore());
            hasField = true;
        }
        if (vitalData.getSignalQuality() != null && !vitalData.getSignalQuality().isBlank()) {
            point.addField("signalQuality", vitalData.getSignalQuality());
            hasField = true;
        }

        if (!hasField) {
            log.warn("InfluxDB: no fields to write for payload: {}", vitalData);
            return false;
        }

        try {
            writeApi.writePoint(point);
            log.debug("InfluxDB: write success for patientId={} timestamp={}",
                    vitalData.getPatientId(), timestamp);
            return true;
        } catch (RuntimeException e) {
            log.error("InfluxDB: write failed for payload: {}", vitalData, e);
            return false;
        }
    }

    public void saveRawPayload(String topic, String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("InfluxDB: raw payload is empty, skipping write");
            return;
        }

        WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
        Instant timestamp = Instant.now();

        Point point = Point.measurement("vitals_raw")
                .time(timestamp, WritePrecision.NS)
                .addField("payload", payload);

        if (topic != null && !topic.isBlank()) {
            point.addTag("topic", topic);
        }

        try {
            writeApi.writePoint(point);
            log.debug("InfluxDB: raw payload write success topic={} timestamp={}", topic, timestamp);
        } catch (RuntimeException e) {
            log.error("InfluxDB: raw payload write failed topic={} payload={}", topic, payload, e);
        }
    }

    public List<VitalData> getHistory(String patientId, Instant start, Instant end, Integer limit) {
        if (patientId == null || patientId.isBlank()) {
            return Collections.emptyList();
        }

        Instant safeStart = start != null ? start : Instant.now().minus(Duration.ofHours(24));
        Instant safeEnd = end != null ? end : Instant.now();

        String flux = """
                from(bucket: \"%s\")
                  |> range(start: time(v: \"%s\"), stop: time(v: \"%s\"))
                  |> filter(fn: (r) => r._measurement == \"vitals\")
                  |> filter(fn: (r) => r.patientId == \"%s\")
                  |> pivot(rowKey:[\"_time\"], columnKey:[\"_field\"], valueColumn:\"_value\")
                  |> sort(columns:[\"_time\"])
                """.formatted(bucket, safeStart, safeEnd, escapeTagValue(patientId));

        if (limit != null && limit > 0) {
            flux += "\n  |> limit(n: " + limit + ")";
        }

        return queryToVitals(flux);
    }

    public List<VitalData> getLatest(String patientId, int limit) {
        if (patientId == null || patientId.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }

        String flux = """
                from(bucket: \"%s\")
                  |> range(start: -30d)
                  |> filter(fn: (r) => r._measurement == \"vitals\")
                  |> filter(fn: (r) => r.patientId == \"%s\")
                  |> pivot(rowKey:[\"_time\"], columnKey:[\"_field\"], valueColumn:\"_value\")
                  |> sort(columns:[\"_time\"], desc: true)
                  |> limit(n: %d)
                """.formatted(bucket, escapeTagValue(patientId), limit);

        List<VitalData> results = queryToVitals(flux);
        results.sort(Comparator.comparing(VitalData::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())));
        return results;
    }

    public List<VitalData> getRecentAllPatients(Instant since, Integer limit) {
        Instant safeSince = since != null ? since : Instant.now().minus(Duration.ofMinutes(10));

        String flux = """
                from(bucket: \"%s\")
                  |> range(start: time(v: \"%s\"))
                  |> filter(fn: (r) => r._measurement == \"vitals\")
                  |> pivot(rowKey:[\"_time\"], columnKey:[\"_field\"], valueColumn:\"_value\")
                  |> sort(columns:[\"_time\"], desc: true)
                """.formatted(bucket, safeSince);

        if (limit != null && limit > 0) {
            flux += "\n  |> limit(n: " + limit + ")";
        }

        return queryToVitals(flux);
    }

    public VitalStatsResponse getStats(String patientId, Instant start, Instant end) {
        Instant safeStart = start != null ? start : Instant.now().minus(Duration.ofHours(24));
        Instant safeEnd = end != null ? end : Instant.now();

        List<VitalData> history = getHistory(patientId, safeStart, safeEnd, null);
        if (history.isEmpty()) {
            return new VitalStatsResponse(patientId, safeStart, safeEnd, Collections.emptyMap());
        }

        Map<String, VitalStat> stats = new HashMap<>();
        stats.put("heartRate", calculateStats(history.stream().map(VitalData::getHeartRate).toList()));
        stats.put("ppgGreenAverage", calculateStats(history.stream().map(VitalData::getPpgGreenAverage).toList()));
        stats.put("ppgFilteredSignal", calculateStats(history.stream().map(VitalData::getPpgFilteredSignal).toList()));
        stats.put("ppgRedAverage", calculateStats(history.stream().map(VitalData::getPpgRedAverage).toList()));
        stats.put("accelerometerMagnitudeAverage", calculateStats(history.stream().map(VitalData::getAccelerometerMagnitudeAverage).toList()));

        return new VitalStatsResponse(patientId, safeStart, safeEnd, stats);
    }

    public List<String> getPatientsWithData(int days) {
        int safeDays = days > 0 ? days : 30;
        String flux = """
                from(bucket: \"%s\")
                  |> range(start: -%dd)
                  |> filter(fn: (r) => r._measurement == \"vitals\")
                                    |> filter(fn: (r) => exists r.patientId)
                  |> keep(columns: [\"patientId\"])
                  |> distinct(column: \"patientId\")
                  |> sort(columns: [\"patientId\"])
                """.formatted(bucket, safeDays);

        QueryApi queryApi = influxDBClient.getQueryApi();
        List<FluxTable> tables = queryApi.query(flux, orgName);
        List<String> patients = new ArrayList<>();
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Object value = record.getValueByKey("patientId");
                if (value == null) {
                    value = record.getValue();
                }
                if (value != null) {
                    patients.add(String.valueOf(value));
                }
            }
        }

        return patients.stream().filter(s -> !s.isBlank()).distinct().sorted().toList();
    }

    private List<VitalData> queryToVitals(String flux) {
        QueryApi queryApi = influxDBClient.getQueryApi();
        List<FluxTable> tables = queryApi.query(flux, orgName);
        List<VitalData> results = new ArrayList<>();

        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                VitalData data = toVitalData(record);
                if (data != null) {
                    results.add(data);
                }
            }
        }

        return results;
    }

    private VitalData toVitalData(FluxRecord record) {
        if (record == null) {
            return null;
        }

        VitalData data = new VitalData();
        data.setTimestamp(record.getTime());

        Object patientTag = record.getValueByKey("patientId");
        if (patientTag != null) {
            data.setPatientId(String.valueOf(patientTag));
        }

        data.setHeartRate(firstNonNull(
            toDouble(record.getValueByKey("heartRate")),
            toDouble(record.getValueByKey("estimated_heart_rate"))
        ));
        data.setBloodPressureSystolic(toDouble(record.getValueByKey("bloodPressureSystolic")));
        data.setBloodPressureDiastolic(toDouble(record.getValueByKey("bloodPressureDiastolic")));
        data.setPpgGreenMin(firstNonNull(
            toDouble(record.getValueByKey("ppgGreenMin")),
            toDouble(record.getValueByKey("ppg_min")),
            toDouble(record.getValueByKey("ppg_filtered_min"))
        ));
        data.setPpgGreenMax(firstNonNull(
            toDouble(record.getValueByKey("ppgGreenMax")),
            toDouble(record.getValueByKey("ppg_max")),
            toDouble(record.getValueByKey("ppg_filtered_max"))
        ));
        data.setPpgGreenAverage(firstNonNull(
            toDouble(record.getValueByKey("ppgGreenAverage")),
            toDouble(record.getValueByKey("ppg_mean")),
            toDouble(record.getValueByKey("ppg_filtered_mean"))
        ));
        data.setPpgFilteredSignal(firstNonNull(
            toDouble(record.getValueByKey("ppgFilteredSignal")),
            toDouble(record.getValueByKey("ppg_filtered_mean")),
            toDouble(record.getValueByKey("ppgGreenAverage")),
            toDouble(record.getValueByKey("ppg_mean"))
        ));
        data.setPpgDataPoints(firstNonNull(
            toInteger(record.getValueByKey("ppgDataPoints")),
            toInteger(record.getValueByKey("sample_count")),
            toInteger(record.getValueByKey("valid_samples"))
        ));
        data.setSignalQualityScore(firstNonNull(
            toDouble(record.getValueByKey("signalQualityScore")),
            toDouble(record.getValueByKey("signal_quality_score"))
        ));
        data.setAccelerometerMagnitudeAverage(firstNonNull(
            toDouble(record.getValueByKey("accelerometerMagnitudeAverage")),
            toDouble(record.getValueByKey("activity_mean"))
        ));
        data.setAccelerometerMagnitudeMax(firstNonNull(
            toDouble(record.getValueByKey("accelerometerMagnitudeMax")),
            toDouble(record.getValueByKey("activity_max"))
        ));
        data.setAccelerometerVariance(firstNonNull(
            toDouble(record.getValueByKey("accelerometerVariance")),
            toDouble(record.getValueByKey("activity_variance"))
        ));
        return data;
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private VitalStat calculateStats(List<Double> values) {
        List<Double> filtered = values.stream().filter(Objects::nonNull).collect(Collectors.toList());
        if (filtered.isEmpty()) {
            return new VitalStat(null, null, null, null);
        }

        double min = filtered.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
        double max = filtered.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        double avg = filtered.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        Double last = filtered.get(filtered.size() - 1);

        return new VitalStat(min, max, avg, last);
    }

    private String escapeTagValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}