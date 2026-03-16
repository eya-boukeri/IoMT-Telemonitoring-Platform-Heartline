package com.medtech.ingestion.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.ingestion.model.FilteredSignal;
import com.medtech.ingestion.model.RawSignal;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SignalProcessingService {

    private static final double OUTLIER_Z_SCORE_THRESHOLD = 3.0;

    private final ObjectMapper objectMapper;

    public FilteredSignal filter(RawSignal rawSignal) {
        PayloadVectors vectors = extractPayloadVectors(rawSignal.getRawPayload());

        FilterResult ppgFiltered = removeOutliers(vectors.ppgData());
        FilterResult xFiltered = removeOutliers(vectors.accelerometerX());
        FilterResult yFiltered = removeOutliers(vectors.accelerometerY());
        FilterResult zFiltered = removeOutliers(vectors.accelerometerZ());

        int totalOutliers = ppgFiltered.outlierCount()
            + xFiltered.outlierCount()
            + yFiltered.outlierCount()
            + zFiltered.outlierCount();

        FilteredSignal.ProcessingMetadata metadata = buildMetadata(vectors.ppgData(), ppgFiltered.filtered());

        return new FilteredSignal(
            rawSignal.getPatientId(),
            rawSignal.getDeviceId(),
            rawSignal.getTimestamp(),
            ppgFiltered.filtered(),
            xFiltered.filtered(),
            yFiltered.filtered(),
            zFiltered.filtered(),
            computeSignalQuality(vectors.ppgData().size(), ppgFiltered.filtered().size(), totalOutliers),
            totalOutliers,
            metadata
        );
    }

    private PayloadVectors extractPayloadVectors(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return new PayloadVectors(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(rawPayload, new TypeReference<Map<String, Object>>() {});

            List<Double> ppgData = extractPpgData(payload);
            List<Double> accelerometerX = extractAxis(payload, "accelerometerX", "x");
            List<Double> accelerometerY = extractAxis(payload, "accelerometerY", "y");
            List<Double> accelerometerZ = extractAxis(payload, "accelerometerZ", "z");

            return new PayloadVectors(ppgData, accelerometerX, accelerometerY, accelerometerZ);
        } catch (JsonProcessingException e) {
            return new PayloadVectors(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
    }

    private List<Double> extractPpgData(Map<String, Object> payload) {
        Object ppgDataObject = payload.get("ppgData");
        if (!(ppgDataObject instanceof List<?> ppgDataList)) {
            return Collections.emptyList();
        }

        List<Double> extracted = new ArrayList<>();

        for (Object entry : ppgDataList) {
            if (entry instanceof Number number) {
                extracted.add(number.doubleValue());
                continue;
            }

            if (entry instanceof Map<?, ?> sampleMap) {
                if (sampleMap.containsKey("green") && sampleMap.containsKey("red")) {
                    Double green = toDouble(sampleMap.get("green"));
                    Double red = toDouble(sampleMap.get("red"));
                    if (green != null && red != null) {
                        extracted.add((green + red) / 2.0);
                    }
                    continue;
                }

                for (Object value : sampleMap.values()) {
                    if (value instanceof Number numberValue) {
                        extracted.add(numberValue.doubleValue());
                    }
                }
            }
        }

        return extracted;
    }

    private List<Double> extractAxis(Map<String, Object> payload, String directKey, String axisKey) {
        Object directValues = payload.get(directKey);
        if (directValues instanceof List<?> directList) {
            List<Double> axis = new ArrayList<>();
            for (Object value : directList) {
                if (value instanceof Number number) {
                    axis.add(number.doubleValue());
                }
            }
            if (!axis.isEmpty()) {
                return axis;
            }
        }

        Object accelerometerDataObject = payload.get("accelerometerData");
        if (!(accelerometerDataObject instanceof List<?> accelerometerDataList)) {
            return Collections.emptyList();
        }

        List<Double> extracted = new ArrayList<>();
        for (Object item : accelerometerDataList) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                continue;
            }

            if (itemMap.containsKey(axisKey)) {
                Double axisValue = toDouble(itemMap.get(axisKey));
                if (axisValue != null) {
                    extracted.add(axisValue);
                }
            }

            if (itemMap.containsKey("accelerometerPoint") && itemMap.get("accelerometerPoint") instanceof Map<?, ?> pointMap) {
                Double axisValue = toDouble(pointMap.get(axisKey));
                if (axisValue != null) {
                    extracted.add(axisValue);
                }
            }

            for (Object nestedValue : itemMap.values()) {
                if (nestedValue instanceof Map<?, ?> nestedMap) {
                    Double axisValue = toDouble(nestedMap.get(axisKey));
                    if (axisValue != null) {
                        extracted.add(axisValue);
                    }
                }
            }
        }

        return extracted;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private FilterResult removeOutliers(List<Double> values) {
        if (values.isEmpty()) {
            return new FilterResult(Collections.emptyList(), 0);
        }

        double mean = mean(values);
        double stdDev = stdDev(values, mean);

        if (stdDev == 0.0) {
            return new FilterResult(values, 0);
        }

        List<Double> filtered = new ArrayList<>();
        int outliers = 0;

        for (double value : values) {
            double zScore = Math.abs((value - mean) / stdDev);
            if (zScore <= OUTLIER_Z_SCORE_THRESHOLD) {
                filtered.add(value);
            } else {
                outliers++;
            }
        }

        return new FilterResult(filtered, outliers);
    }

    private FilteredSignal.ProcessingMetadata buildMetadata(List<Double> original, List<Double> filtered) {
        if (filtered.isEmpty()) {
            return new FilteredSignal.ProcessingMetadata(0.0, 0.0, 0.0, 0.0, original.size());
        }

        double mean = mean(filtered);
        double stdDev = stdDev(filtered, mean);
        double min = filtered.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = filtered.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        return new FilteredSignal.ProcessingMetadata(mean, stdDev, min, max, original.size());
    }

    private double computeSignalQuality(int originalCount, int keptCount, int outlierCount) {
        if (originalCount == 0) {
            return 0.0;
        }

        double keptRatio = (double) keptCount / originalCount;
        double penalty = Math.min(40.0, outlierCount * 0.25);
        double score = (keptRatio * 100.0) - penalty;
        return Math.max(0.0, Math.min(100.0, score));
    }

    private double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double stdDev(List<Double> values, double mean) {
        if (values.isEmpty()) {
            return 0.0;
        }

        double variance = values.stream()
            .mapToDouble(value -> Math.pow(value - mean, 2))
            .average()
            .orElse(0.0);

        return Math.sqrt(variance);
    }

    private record PayloadVectors(
        List<Double> ppgData,
        List<Double> accelerometerX,
        List<Double> accelerometerY,
        List<Double> accelerometerZ
    ) {}

    private record FilterResult(List<Double> filtered, int outlierCount) {}
}
