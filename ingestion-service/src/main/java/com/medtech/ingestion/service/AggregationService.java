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
import java.util.stream.IntStream;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.medtech.ingestion.model.AggregatedData;
import com.medtech.ingestion.model.FilteredSignal;
import com.medtech.ingestion.model.IngestionMetrics;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AggregationService {

    private static final long WINDOW_SECONDS = 30;

    private final KafkaProducerService kafkaProducerService;
    private final IngestionMetricsService ingestionMetricsService;

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

        persistIngestionMetrics(window, aggregatedData, ppg, hr);

        return aggregatedData;
    }

    private void persistIngestionMetrics(
        BufferWindow window,
        AggregatedData aggregatedData,
        List<Double> ppg,
        HeartRateEstimation hr
    ) {
        long durationSeconds = Math.max(1, Duration.between(window.windowStart(), window.windowEnd()).getSeconds());
        double sampleRate = ppg.isEmpty() ? 0.0 : (double) ppg.size() / durationSeconds;

        List<Integer> peakIndices = detectPeakIndices(ppg);
        List<Double> rrIntervalsMs = computeRrIntervalsMs(peakIndices, sampleRate);
        HrvMetrics hrvMetrics = computeHrv(rrIntervalsMs);

        Double rrBasedHeartRate = hrvMetrics.rrMeanMs() != null && hrvMetrics.rrMeanMs() > 0
            ? 60000.0 / hrvMetrics.rrMeanMs()
            : null;
        Double heartRate = hr.heartRate() != null ? hr.heartRate() : rrBasedHeartRate;

        double respiratoryRate = estimateRespiratoryRate(ppg, durationSeconds, sampleRate);
        double perfusionIndex = computePerfusionIndex(ppg);
        double vascularIndex = computeVascularIndex(ppg, sampleRate);

        double stressIndex = computeStressIndex(
            heartRate,
            hrvMetrics.rmssd(),
            aggregatedData.getActivityMean(),
            aggregatedData.getSignalQuality()
        );

        IngestionMetrics metrics = new IngestionMetrics();
        metrics.setPatientId(window.patientId() != null ? window.patientId() : "unknown-patient");
        metrics.setDeviceId(window.deviceId());
        metrics.setWindowStart(window.windowStart());
        metrics.setWindowEnd(window.windowEnd());
        metrics.setSampleCount(aggregatedData.getSampleCount());
        metrics.setEstimatedHeartRate(heartRate);
        metrics.setRespiratoryRate(respiratoryRate);
        metrics.setRrMeanMs(hrvMetrics.rrMeanMs());
        metrics.setHrvSdnn(hrvMetrics.sdnn());
        metrics.setHrvRmssd(hrvMetrics.rmssd());
        metrics.setHrvLfHf(hrvMetrics.lfHf());
        metrics.setVascularIndex(vascularIndex);
        metrics.setPerfusionIndex(perfusionIndex);
        metrics.setSignalQualityScore(aggregatedData.getSignalQuality());
        metrics.setSignalQualityLabel(computeSignalQualityLabel(aggregatedData.getSignalQuality()));
        metrics.setStressIndex(stressIndex);
        metrics.setStressLevel(computeStressLevel(stressIndex));
        metrics.setPpgMean(aggregatedData.getPpgMean());
        metrics.setPpgMin(aggregatedData.getPpgMin());
        metrics.setPpgMax(aggregatedData.getPpgMax());
        metrics.setPpgStdDev(aggregatedData.getPpgStdDev());
        metrics.setPpgVariance(aggregatedData.getPpgVariance());
        metrics.setActivityMean(aggregatedData.getActivityMean());
        metrics.setActivityMax(aggregatedData.getActivityMax());
        metrics.setActivityVariance(aggregatedData.getActivityVariance());
        metrics.setValidSamples(aggregatedData.getValidSamples());
        metrics.setOutlierSamples(aggregatedData.getOutlierSamples());

        ingestionMetricsService.save(metrics, rrIntervalsMs);
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

    private List<Integer> detectPeakIndices(List<Double> values) {
        if (values == null || values.size() < 3) {
            return Collections.emptyList();
        }

        double threshold = mean(values);
        List<Integer> peaks = new ArrayList<>();
        int refractorySamples = 2;
        int lastPeak = -refractorySamples;

        for (int i = 1; i < values.size() - 1; i++) {
            double previous = values.get(i - 1);
            double current = values.get(i);
            double next = values.get(i + 1);
            if (current > previous && current >= next && current >= threshold && i - lastPeak >= refractorySamples) {
                peaks.add(i);
                lastPeak = i;
            }
        }

        return peaks;
    }

    private List<Double> computeRrIntervalsMs(List<Integer> peakIndices, double sampleRate) {
        if (peakIndices == null || peakIndices.size() < 2 || sampleRate <= 0) {
            return Collections.emptyList();
        }

        List<Double> rr = new ArrayList<>();
        for (int i = 1; i < peakIndices.size(); i++) {
            int deltaSamples = peakIndices.get(i) - peakIndices.get(i - 1);
            if (deltaSamples <= 0) {
                continue;
            }
            rr.add((deltaSamples / sampleRate) * 1000.0);
        }

        return rr;
    }

    private HrvMetrics computeHrv(List<Double> rrIntervalsMs) {
        if (rrIntervalsMs == null || rrIntervalsMs.isEmpty()) {
            return new HrvMetrics(null, null, null, null);
        }

        double rrMean = mean(rrIntervalsMs);
        double sdnn = Math.sqrt(variance(rrIntervalsMs, rrMean));

        Double rmssd = null;
        if (rrIntervalsMs.size() >= 2) {
            List<Double> successiveDiffSquared = IntStream.range(1, rrIntervalsMs.size())
                .mapToObj(i -> Math.pow(rrIntervalsMs.get(i) - rrIntervalsMs.get(i - 1), 2))
                .toList();
            rmssd = Math.sqrt(mean(successiveDiffSquared));
        }

        Double lfHf = null;
        if (rmssd != null) {
            lfHf = Math.max(0.1, (sdnn + 1e-6) / (rmssd + 1e-6));
        }

        return new HrvMetrics(rrMean, sdnn, rmssd, lfHf);
    }

    private double estimateRespiratoryRate(List<Double> ppg, long durationSeconds, double sampleRate) {
        if (ppg == null || ppg.size() < 6 || durationSeconds <= 0 || sampleRate <= 0) {
            return 0.0;
        }

        int movingWindow = Math.max(2, (int) Math.round(sampleRate * 2.0));
        List<Double> smoothed = movingAverage(ppg, movingWindow);
        List<Integer> breathPeaks = detectPeakIndices(smoothed);

        if (breathPeaks.isEmpty()) {
            return 0.0;
        }

        double breathsPerMinute = (breathPeaks.size() * 60.0) / durationSeconds;
        return clampDouble(breathsPerMinute, 6.0, 40.0);
    }

    private List<Double> movingAverage(List<Double> values, int window) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }

        List<Double> smoothed = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            int start = Math.max(0, i - window + 1);
            int end = i + 1;
            smoothed.add(mean(values.subList(start, end)));
        }
        return smoothed;
    }

    private double computePerfusionIndex(List<Double> ppg) {
        if (ppg == null || ppg.isEmpty()) {
            return 0.0;
        }

        double max = max(ppg);
        double min = min(ppg);
        double meanAbs = ppg.stream().mapToDouble(v -> Math.abs(v)).average().orElse(0.0);
        if (meanAbs <= 0) {
            return 0.0;
        }

        return ((max - min) / meanAbs) * 100.0;
    }

    private double computeVascularIndex(List<Double> ppg, double sampleRate) {
        if (ppg == null || ppg.isEmpty() || sampleRate <= 0) {
            return 0.0;
        }

        double localStd = Math.sqrt(variance(ppg, mean(ppg)));
        return clampDouble((localStd / (Math.abs(mean(ppg)) + 1e-6)) * 10.0, 0.0, 100.0);
    }

    private double computeStressIndex(Double heartRate, Double rmssd, double activityMean, double signalQuality) {
        double hrScore = heartRate == null ? 0.0 : clampDouble((heartRate - 55.0) / 70.0, 0.0, 1.0);
        double hrvPenalty = rmssd == null ? 0.5 : 1.0 - clampDouble(rmssd / 90.0, 0.0, 1.0);
        double motionScore = clampDouble(activityMean / 3.0, 0.0, 1.0);
        double qualityPenalty = 1.0 - clampDouble(signalQuality / 100.0, 0.0, 1.0);

        return clampDouble((hrScore * 0.40 + hrvPenalty * 0.35 + motionScore * 0.15 + qualityPenalty * 0.10) * 100.0, 0.0, 100.0);
    }

    private String computeSignalQualityLabel(double score) {
        if (score >= 85) {
            return "excellent";
        }
        if (score >= 70) {
            return "good";
        }
        if (score >= 50) {
            return "fair";
        }
        return "poor";
    }

    private String computeStressLevel(double stressIndex) {
        if (stressIndex >= 75) {
            return "high";
        }
        if (stressIndex >= 50) {
            return "moderate";
        }
        if (stressIndex >= 25) {
            return "mild";
        }
        return "low";
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private record HrvMetrics(Double rrMeanMs, Double sdnn, Double rmssd, Double lfHf) {}

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
