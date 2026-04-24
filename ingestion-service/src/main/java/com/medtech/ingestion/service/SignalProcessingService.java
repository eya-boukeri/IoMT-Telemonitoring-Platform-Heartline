package com.medtech.ingestion.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.ingestion.model.Alert;
import com.medtech.ingestion.model.FilteredSignal;
import com.medtech.ingestion.model.RawSignal;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SignalProcessingService {

    private static final double OUTLIER_Z_SCORE_THRESHOLD = 3.0;

    private final ObjectMapper objectMapper;
    private final KafkaProducerService kafkaProducerService;

    @Value("${processing.filter.lowpass-cutoff:5}")
    private double lowpassCutoff;

    @Value("${processing.filter.highpass-cutoff:0.5}")
    private double highpassCutoff;

    @Value("${processing.sampling-rate:20}")
    private double samplingRate;

    @Value("${processing.motion.threshold-factor:2.5}")
    private double motionThresholdFactor;

    @Value("${processing.motion.absolute-threshold:1.5}")
    private double absoluteMotionThreshold;

    @Value("${processing.motion.critical-ratio:0.35}")
    private double criticalMotionRatio;

    @Value("${processing.motion.warning-ratio:0.20}")
    private double warningMotionRatio;

    @Value("${processing.motion.lms-learning-rate:0.01}")
    private double lmsLearningRate;

    public FilteredSignal filter(RawSignal rawSignal) {
        PayloadVectors vectors = extractPayloadVectors(rawSignal.getRawPayload());

        MotionCleaningResult motionCleaningResult = cleanPpgWithMotionReference(vectors.ppgData(), vectors.accelerometerX(), vectors.accelerometerY(), vectors.accelerometerZ());
        List<Double> cleanedPpg = motionCleaningResult.cleanedPpg();

        FilterResult ppgFiltered = removeOutliers(cleanedPpg);
        FilterResult xFiltered = removeOutliers(vectors.accelerometerX());
        FilterResult yFiltered = removeOutliers(vectors.accelerometerY());
        FilterResult zFiltered = removeOutliers(vectors.accelerometerZ());

        int totalOutliers = ppgFiltered.outlierCount()
            + xFiltered.outlierCount()
            + yFiltered.outlierCount()
            + zFiltered.outlierCount();

        FilteredSignal.ProcessingMetadata metadata = buildMetadata(vectors.ppgData(), ppgFiltered.filtered(), motionCleaningResult);

        if (motionCleaningResult.motionRatio() >= warningMotionRatio) {
            publishMotionAlert(rawSignal, motionCleaningResult);
        }

        return new FilteredSignal(
            rawSignal.getPatientId(),
            rawSignal.getDeviceId(),
            rawSignal.getTimestamp(),
            ppgFiltered.filtered(),
            xFiltered.filtered(),
            yFiltered.filtered(),
            zFiltered.filtered(),
            computeSignalQuality(vectors.ppgData().size(), ppgFiltered.filtered().size(), totalOutliers, motionCleaningResult.motionRatio()),
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

    private FilteredSignal.ProcessingMetadata buildMetadata(
        List<Double> original,
        List<Double> filtered,
        MotionCleaningResult motionCleaningResult
    ) {
        if (filtered.isEmpty()) {
            return new FilteredSignal.ProcessingMetadata(
                0.0,
                0.0,
                0.0,
                0.0,
                original.size(),
                motionCleaningResult.motionMean(),
                motionCleaningResult.motionMax(),
                motionCleaningResult.motionThreshold(),
                motionCleaningResult.motionRatio(),
                motionCleaningResult.removedSamples(),
                motionCleaningResult.corruptedSegments(),
                motionCleaningResult.cleanedSamples()
            );
        }

        double mean = mean(filtered);
        double stdDev = stdDev(filtered, mean);
        double min = filtered.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = filtered.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        return new FilteredSignal.ProcessingMetadata(
            mean,
            stdDev,
            min,
            max,
            original.size(),
            motionCleaningResult.motionMean(),
            motionCleaningResult.motionMax(),
            motionCleaningResult.motionThreshold(),
            motionCleaningResult.motionRatio(),
            motionCleaningResult.removedSamples(),
            motionCleaningResult.corruptedSegments(),
            motionCleaningResult.cleanedSamples()
        );
    }

    private double computeSignalQuality(int originalCount, int keptCount, int outlierCount, double motionRatio) {
        if (originalCount == 0) {
            return 0.0;
        }

        double keptRatio = (double) keptCount / originalCount;
        double penalty = Math.min(40.0, outlierCount * 0.25);
        double motionPenalty = Math.min(45.0, motionRatio * 100.0);
        double score = (keptRatio * 100.0) - penalty - motionPenalty;
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

    private MotionCleaningResult cleanPpgWithMotionReference(
        List<Double> ppg,
        List<Double> accX,
        List<Double> accY,
        List<Double> accZ
    ) {
        if (ppg == null || ppg.isEmpty()) {
            return new MotionCleaningResult(Collections.emptyList(), 0.0, 0.0, 0.0, 0.0, 0, 0, 0);
        }

        int size = ppg.size();
        List<Double> alignedX = align(accX, size);
        List<Double> alignedY = align(accY, size);
        List<Double> alignedZ = align(accZ, size);

        // Remove gravity/static component so motion threshold targets dynamic movement only.
        double meanX = mean(alignedX);
        double meanY = mean(alignedY);
        double meanZ = mean(alignedZ);

        List<Double> motionMagnitude = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double x = alignedX.get(i) - meanX;
            double y = alignedY.get(i) - meanY;
            double z = alignedZ.get(i) - meanZ;
            motionMagnitude.add(Math.sqrt((x * x) + (y * y) + (z * z)));
        }

        double motionMean = motionMagnitude.isEmpty() ? 0.0 : mean(motionMagnitude);
        double motionStdDev = motionMagnitude.isEmpty() ? 0.0 : stdDev(motionMagnitude, motionMean);
        double motionMax = motionMagnitude.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        long excessiveSamples = motionMagnitude.stream().filter(value -> value > absoluteMotionThreshold).count();
        double excessiveRatio = size == 0 ? 0.0 : (double) excessiveSamples / size;

        List<Double> quietReference = motionMagnitude.stream()
            .sorted()
            .limit(Math.max(1, motionMagnitude.size() / 4))
            .toList();

        double quietMean = quietReference.isEmpty() ? motionMean : mean(quietReference);
        double quietStdDev = quietReference.isEmpty() ? motionStdDev : stdDev(quietReference, quietMean);
        double adaptiveThreshold = quietStdDev == 0.0
            ? quietMean + motionThresholdFactor
            : quietMean + (motionThresholdFactor * quietStdDev);
        double motionThreshold = excessiveRatio >= criticalMotionRatio
            ? absoluteMotionThreshold
            : Math.max(adaptiveThreshold, absoluteMotionThreshold);
        double criticalThreshold = Math.max(motionThreshold * 1.35, absoluteMotionThreshold * 1.35);

        List<Double> bandPassedPpg = applyBandPassFilter(ppg);
        List<Double> cleaned = new ArrayList<>(size);

        int removedSamples = 0;
        int corruptedSegments = 0;
        int cleanSampleCount = 0;
        boolean inCorruptedSegment = false;
        double[] weights = new double[] {0.0, 0.0, 0.0, 0.0};

        for (int i = 0; i < size; i++) {
            double baseSample = bandPassedPpg.get(i);
            double motionSample = motionMagnitude.get(i);

            if (motionSample > criticalThreshold) {
                removedSamples++;
                if (!inCorruptedSegment) {
                    corruptedSegments++;
                    inCorruptedSegment = true;
                }
                continue;
            }

            if (inCorruptedSegment && motionSample <= motionThreshold) {
                inCorruptedSegment = false;
            }

            double cleanedSample = baseSample;
            if (motionSample > motionThreshold) {
                cleanedSample = applyLmsFilter(baseSample, motionSample, weights);
            }

            cleaned.add(cleanedSample);
            cleanSampleCount++;
        }

        double motionRatio = excessiveRatio;

        return new MotionCleaningResult(
            cleaned,
            motionMean,
            motionMax,
            motionThreshold,
            motionRatio,
            removedSamples,
            corruptedSegments,
            cleanSampleCount
        );
    }

    private List<Double> applyBandPassFilter(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }

        // MQTT packets may carry 1 sample. Keep it as-is instead of forcing a 0-valued band-pass output.
        if (values.size() < 3) {
            return new ArrayList<>(values);
        }

        int lowWindow = Math.max(3, (int) Math.round(samplingRate / Math.max(0.1, lowpassCutoff)));
        int highWindow = Math.max(3, (int) Math.round(samplingRate / Math.max(0.1, highpassCutoff)));

        if (lowWindow % 2 == 0) {
            lowWindow++;
        }
        if (highWindow % 2 == 0) {
            highWindow++;
        }

        List<Double> highPassed = new ArrayList<>(values.size());
        List<Double> lowPassBaseline = movingAverage(values, lowWindow);
        for (int i = 0; i < values.size(); i++) {
            highPassed.add(values.get(i) - lowPassBaseline.get(i));
        }

        return movingAverage(highPassed, highWindow);
    }

    private double applyLmsFilter(double desiredSample, double motionReference, double[] weights) {
        double predictedNoise = 0.0;
        double[] referenceVector = new double[] {motionReference, motionReference * 0.8, motionReference * 0.6, motionReference * 0.4};

        for (int i = 0; i < weights.length; i++) {
            predictedNoise += weights[i] * referenceVector[i];
        }

        double error = desiredSample - predictedNoise;

        for (int i = 0; i < weights.length; i++) {
            weights[i] += lmsLearningRate * error * referenceVector[i];
        }

        return error;
    }

    private List<Double> movingAverage(List<Double> values, int window) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }

        int safeWindow = Math.max(1, window);
        List<Double> smoothed = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            int start = Math.max(0, i - safeWindow + 1);
            smoothed.add(mean(values.subList(start, i + 1)));
        }
        return smoothed;
    }

    private List<Double> align(List<Double> values, int targetSize) {
        if (targetSize <= 0) {
            return Collections.emptyList();
        }

        if (values == null || values.isEmpty()) {
            return Collections.nCopies(targetSize, 0.0);
        }

        if (values.size() >= targetSize) {
            return new ArrayList<>(values.subList(0, targetSize));
        }

        List<Double> aligned = new ArrayList<>(targetSize);
        aligned.addAll(values);
        Double lastValue = values.get(values.size() - 1);
        while (aligned.size() < targetSize) {
            aligned.add(lastValue);
        }
        return aligned;
    }

    private void publishMotionAlert(RawSignal rawSignal, MotionCleaningResult motionCleaningResult) {
        Alert alert = new Alert();
        alert.setAlertId(rawSignal.getId() != null ? rawSignal.getId().toString() : java.util.UUID.randomUUID().toString());
        alert.setPatientId(rawSignal.getPatientId());
        alert.setAlertType("motion-artifact");
        alert.setSeverity(motionCleaningResult.motionRatio() >= criticalMotionRatio ? "CRITICAL" : "WARNING");
        alert.setPriority(motionCleaningResult.motionRatio() >= criticalMotionRatio ? "URGENT" : "HIGH");
        alert.setTimestamp(rawSignal.getTimestamp() != null ? rawSignal.getTimestamp() : java.time.Instant.now());
        alert.setMessage(String.format(
            "Too much motion detected while cleaning PPG: ratio=%.2f threshold=%.2f removed=%d",
            motionCleaningResult.motionRatio(),
            motionCleaningResult.motionThreshold(),
            motionCleaningResult.removedSamples()
        ));
        alert.setDetectionScore(Math.max(0.0, 100.0 - (motionCleaningResult.motionRatio() * 100.0)));
        alert.setActivity(motionCleaningResult.motionMean());
        kafkaProducerService.publishAlert(alert);
    }

    private record MotionCleaningResult(
        List<Double> cleanedPpg,
        double motionMean,
        double motionMax,
        double motionThreshold,
        double motionRatio,
        int removedSamples,
        int corruptedSegments,
        int cleanedSamples
    ) {}
}
