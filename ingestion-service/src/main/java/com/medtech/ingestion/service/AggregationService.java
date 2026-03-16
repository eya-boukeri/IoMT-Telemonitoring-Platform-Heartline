package com.medtech.ingestion.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.medtech.ingestion.model.AggregatedData;
import com.medtech.ingestion.model.FilteredSignal;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AggregationService {

    private static final long WINDOW_SECONDS = 30;

    private final KafkaProducerService kafkaProducerService;

    private final Map<String, BufferWindow> bufferByWindow = new ConcurrentHashMap<>();
    private final AtomicLong emittedWindowCount = new AtomicLong(0);
    private volatile Instant lastFlushAt;

    public void bufferSignal(FilteredSignal signal) {
        Instant timestamp = signal.getTimestamp() != null ? signal.getTimestamp() : Instant.now();
        Instant windowStart = Instant.ofEpochSecond((timestamp.getEpochSecond() / WINDOW_SECONDS) * WINDOW_SECONDS);
        Instant windowEnd = windowStart.plusSeconds(WINDOW_SECONDS);

        String key = buildWindowKey(signal.getPatientId(), signal.getDeviceId(), windowStart);
        BufferWindow window = bufferByWindow.computeIfAbsent(
            key,
            ignored -> new BufferWindow(signal.getPatientId(), signal.getDeviceId(), windowStart, windowEnd)
        );

        window.add(signal);
    }

    @Scheduled(fixedDelayString = "${processing.aggregation.flush-ms:1000}")
    public void flushExpiredWindows() {
        List<AggregatedData> readyAggregations = drainReadyWindows(Instant.now());
        for (AggregatedData aggregatedData : readyAggregations) {
            kafkaProducerService.publishAggregatedData(aggregatedData);
        }

        if (!readyAggregations.isEmpty()) {
            emittedWindowCount.addAndGet(readyAggregations.size());
            lastFlushAt = Instant.now();
        }
    }

    public List<AggregatedData> drainReadyWindows(Instant now) {
        List<AggregatedData> result = new ArrayList<>();

        Iterator<Map.Entry<String, BufferWindow>> iterator = bufferByWindow.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, BufferWindow> entry = iterator.next();
            BufferWindow window = entry.getValue();

            if (!window.windowEnd().isAfter(now)) {
                iterator.remove();
                result.add(aggregateWindow(window));
            }
        }

        return result;
    }

    public int getBufferedWindowCount() {
        return bufferByWindow.size();
    }

    public int getBufferedSignalCount() {
        return bufferByWindow.values().stream()
            .mapToInt(BufferWindow::size)
            .sum();
    }

    public long getEmittedWindowCount() {
        return emittedWindowCount.get();
    }

    public Instant getLastFlushAt() {
        return lastFlushAt;
    }

    private AggregatedData aggregateWindow(BufferWindow window) {
        List<Double> ppg = new ArrayList<>();
        List<Double> x = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        List<Double> z = new ArrayList<>();

        int outlierSamples = 0;
        double weightedSignalQuality = 0.0;
        int weight = 0;

        for (FilteredSignal signal : window.snapshot()) {
            List<Double> signalPpg = safeList(signal.getPpgData());
            List<Double> signalX = safeList(signal.getAccelerometerX());
            List<Double> signalY = safeList(signal.getAccelerometerY());
            List<Double> signalZ = safeList(signal.getAccelerometerZ());

            ppg.addAll(signalPpg);
            x.addAll(signalX);
            y.addAll(signalY);
            z.addAll(signalZ);

            outlierSamples += Math.max(0, signal.getOutlierCount());

            int localWeight = Math.max(1, signalPpg.size());
            weightedSignalQuality += signal.getSignalQuality() * localWeight;
            weight += localWeight;
        }

        List<Double> magnitudes = computeMagnitude(x, y, z);

        double ppgMean = mean(ppg);
        double ppgVariance = variance(ppg, ppgMean);
        double activityMean = mean(magnitudes);

        HeartRateEstimation hr = estimateHeartRate(ppg, window.windowStart(), window.windowEnd());

        AggregatedData aggregatedData = new AggregatedData();
        aggregatedData.setPatientId(window.patientId());
        aggregatedData.setDeviceId(window.deviceId());
        aggregatedData.setWindowStart(window.windowStart());
        aggregatedData.setWindowEnd(window.windowEnd());
        aggregatedData.setSampleCount(ppg.size());

        aggregatedData.setPpgMean(ppgMean);
        aggregatedData.setPpgMin(min(ppg));
        aggregatedData.setPpgMax(max(ppg));
        aggregatedData.setPpgVariance(ppgVariance);
        aggregatedData.setPpgStdDev(Math.sqrt(ppgVariance));

        aggregatedData.setEstimatedHeartRate(hr.heartRate());
        aggregatedData.setHeartRateConfidence(hr.confidence());

        aggregatedData.setActivityMean(activityMean);
        aggregatedData.setActivityMax(max(magnitudes));
        aggregatedData.setActivityVariance(variance(magnitudes, activityMean));

        aggregatedData.setSignalQuality(weight == 0 ? 0.0 : weightedSignalQuality / weight);
        aggregatedData.setValidSamples(ppg.size());
        aggregatedData.setOutlierSamples(outlierSamples);

        return aggregatedData;
    }

    private HeartRateEstimation estimateHeartRate(List<Double> ppg, Instant windowStart, Instant windowEnd) {
        if (ppg.size() < 3) {
            return new HeartRateEstimation(null, 0.0);
        }

        long durationSeconds = Math.max(1, Duration.between(windowStart, windowEnd).getSeconds());
        double threshold = mean(ppg);
        int peaks = 0;

        for (int i = 1; i < ppg.size() - 1; i++) {
            double previous = ppg.get(i - 1);
            double current = ppg.get(i);
            double next = ppg.get(i + 1);

            if (current > previous && current > next && current >= threshold) {
                peaks++;
            }
        }

        if (peaks == 0) {
            return new HeartRateEstimation(null, 0.0);
        }

        double heartRate = (peaks * 60.0) / durationSeconds;
        double confidence = Math.min(1.0, ppg.size() / 200.0);

        return new HeartRateEstimation(heartRate, confidence);
    }

    private String buildWindowKey(String patientId, String deviceId, Instant windowStart) {
        return String.join("|",
            patientId != null ? patientId : "unknown-patient",
            deviceId != null ? deviceId : "unknown-device",
            String.valueOf(windowStart.getEpochSecond())
        );
    }

    private List<Double> safeList(List<Double> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private List<Double> computeMagnitude(List<Double> x, List<Double> y, List<Double> z) {
        int size = Math.min(x.size(), Math.min(y.size(), z.size()));
        if (size == 0) {
            return Collections.emptyList();
        }

        List<Double> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double magnitude = Math.sqrt((x.get(i) * x.get(i)) + (y.get(i) * y.get(i)) + (z.get(i) * z.get(i)));
            result.add(magnitude);
        }
        return result;
    }

    private double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double min(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
    }

    private double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    private double variance(List<Double> values, double mean) {
        if (values.isEmpty()) {
            return 0.0;
        }

        return values.stream()
            .mapToDouble(value -> Math.pow(value - mean, 2))
            .average()
            .orElse(0.0);
    }

    private record HeartRateEstimation(Double heartRate, Double confidence) {}

    private static final class BufferWindow {
        private final String patientId;
        private final String deviceId;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final List<FilteredSignal> signals = Collections.synchronizedList(new ArrayList<>());

        private BufferWindow(String patientId, String deviceId, Instant windowStart, Instant windowEnd) {
            this.patientId = patientId;
            this.deviceId = deviceId;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
        }

        private void add(FilteredSignal signal) {
            signals.add(signal);
        }

        private List<FilteredSignal> snapshot() {
            synchronized (signals) {
                return new ArrayList<>(signals);
            }
        }

        private int size() {
            return signals.size();
        }

        private String patientId() {
            return patientId;
        }

        private String deviceId() {
            return deviceId;
        }

        private Instant windowStart() {
            return windowStart;
        }

        private Instant windowEnd() {
            return windowEnd;
        }
    }
}
