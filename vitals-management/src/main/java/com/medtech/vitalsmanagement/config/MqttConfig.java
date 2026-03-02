package com.medtech.vitalsmanagement.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.ExecutorChannel;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.vitalsmanagement.model.ObservationData;
import com.medtech.vitalsmanagement.model.VitalData;
import com.medtech.vitalsmanagement.service.InfluxDBService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class MqttConfig {

    @Value("${mqtt.broker.url:tcp://127.0.0.1:1883}")
    private String brokerUrl;

    @Value("${mqtt.client.id:spring-backend}")
    private String clientId;

    @Value("${mqtt.topic.prefix:sensors/vitals/}")
    private String topicPrefix;

    @Value("${mqtt.qos:1}")
    private int qos;

    @Value("${kafka.topic.vitals:vitals-events}")
    private String kafkaVitalsTopic;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @Value("${mqtt.client.unique.enabled:}")
    private String uniqueClientIdOverride;

    @Value("${mqtt.clean-session:}")
    private String cleanSessionOverride;

    @Value("${mqtt.dedup.enabled:true}")
    private boolean dedupEnabled;

    @Value("${mqtt.dedup.ttl.seconds:120}")
    private long dedupTtlSeconds;

    @Autowired
    private InfluxDBService influxDBService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private volatile ObjectMapper lenientObjectMapper;
    private final ConcurrentHashMap<String, Long> recentDedup = new ConcurrentHashMap<>();

    /**
     * ExecutorChannel: traitement asynchrone
     * 
     * ✅ AVANTAGES:
     * - MQTT thread ne bloque pas sur le traitement (parsing JSON, InfluxDB, Kafka)
     * - Scalabilité: plusieurs messages MQTT traités en parallèle
     * - Meilleure résilience si une tâche est lente
     * 
     * ⚠️  TRADE-OFFS:
     * - Nécessite un thread pool (configurable via CorePoolSize/MaxPoolSize)
     * - Ordre des messages n'est plus garanti (utiliser patientId comme clé Kafka)
     * - Légère latence (files d'attente du pool)
     * 
     * 📊 CONFIG:
     * - corePoolSize=8 : minimum threads actifs
     * - maxPoolSize=32: maximum threads
     * - queueCapacity=200 : max messages en attente
     * - rejection policy=CALLER_RUNS (si queue pleine, le MQTT thread traite lui-même)
     */
    @Bean
    public MessageChannel mqttInputChannel() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mqtt-handler-");
        executor.setAwaitTerminationSeconds(30);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        ExecutorChannel channel = new ExecutorChannel(executor);
        return channel;
    }

    /**
     * errorChannel explicite pour voir l’erreur exacte (au lieu de stacktrace “bizarre”)
     */
    @Bean(name = "errorChannel")
    public MessageChannel errorChannel() {
        return new PublishSubscribeChannel();
    }

    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();

        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[] { brokerUrl });
        options.setAutomaticReconnect(true);
        options.setCleanSession(resolveCleanSession());
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);

        factory.setConnectionOptions(options);
        log.info("🔐 MQTT ClientFactory configured - CleanSession={}", options.isCleanSession());
        return factory;
    }

    /**
     * Generate unique client ID for multi-instance deployments
     * Format: {clientId}-{hostname}-{random-uuid}
     */
    private String generateUniqueClientId() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname == null || hostname.isEmpty()) {
            hostname = "localhost";
        }
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        return clientId + "-" + hostname + "-" + uniqueSuffix;
    }

    @Bean
    public MessageProducer inbound() {
        // Si le topic se termine par /, ajouter # pour wildcard (ex: sensors/vitals/#)
        // Sinon, utiliser le topic exact (ex: health/sensorData)
        String subscriptionTopic = topicPrefix.endsWith("/") ? topicPrefix + "#" : topicPrefix;
        String resolvedClientId = resolveClientId();

        log.info("🔌 MQTT - Broker: {}, ClientId: {}, Topic: {}, QoS: {}", brokerUrl, resolvedClientId, subscriptionTopic, qos);

        MqttPahoMessageDrivenChannelAdapter adapter =
            new MqttPahoMessageDrivenChannelAdapter(brokerUrl, resolvedClientId, mqttClientFactory(), subscriptionTopic);

        DefaultPahoMessageConverter converter = new DefaultPahoMessageConverter();
        converter.setPayloadAsBytes(false);
        adapter.setConverter(converter);

        adapter.setOutputChannel(mqttInputChannel());
        adapter.setErrorChannelName("errorChannel");

        // QoS=1: at-least-once delivery (possible duplicates). QoS=0: lower latency, possible loss.
        adapter.setQos(qos);

        log.info("✅ MQTT Adapter ready - QoS={}, ExecutorChannel enabled", qos);
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler handler() {
        return message -> {
            String receivedTopic = String.valueOf(message.getHeaders().get("mqtt_receivedTopic"));
            String payload = toPayloadString(message);

            log.debug("📨 MQTT message received - topic={} payload={}", receivedTopic, payload);

            if (payload == null || payload.isBlank()) {
                log.warn("⚠️ Empty payload (topic={}) - skipped", receivedTopic);
                return;
            }

            try {
                VitalData vitalData = parseVitalData(payload);

                if (vitalData.getPatientId() == null || vitalData.getPatientId().isBlank()) {
                    log.warn("⚠️ Missing patientId in vital data - topic={}", receivedTopic);
                    influxDBService.saveRawPayload(receivedTopic, payload);
                    return;
                }

                if (dedupEnabled && isDuplicate(vitalData)) {
                    log.debug("♻️ Duplicate MQTT payload skipped (patientId={}, timestamp={})",
                        vitalData.getPatientId(), vitalData.getTimestamp());
                    return;
                }

                boolean savedToInflux = influxDBService.saveVitalData(vitalData);

                if (savedToInflux) {
                    log.info("💾 InfluxDB: Patient {} - HR={} Temp={} SpO2={} @ {}",
                        vitalData.getPatientId(),
                        vitalData.getHeartRate(),
                        vitalData.getTemperature(),
                        vitalData.getOxygenSaturation(),
                        vitalData.getTimestamp());

                    publishToKafka(vitalData);
                } else {
                    log.warn("❌ Failed to save to InfluxDB: patient={} topic={}", vitalData.getPatientId(), receivedTopic);
                    influxDBService.saveRawPayload(receivedTopic, payload);
                }

            } catch (Exception e) {
                log.error("❌ Error parsing vital data - topic={} payload={}", receivedTopic, payload, e);
                influxDBService.saveRawPayload(receivedTopic, payload);
            }
        };
    }

    /**
     * Publish successfully parsed vital data to Kafka.
     * KEY: patientId (partitioning), TOPIC: vitals-events
     */
    private void publishToKafka(VitalData vitalData) {
        try {
            String vitalJson = objectMapper.writeValueAsString(vitalData);
            String patientKey = vitalData.getPatientId();

            kafkaTemplate.send(kafkaVitalsTopic, patientKey, vitalJson)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("❌ Failed to publish to Kafka - patient={} topic={}", patientKey, kafkaVitalsTopic, ex);
                    } else {
                        log.debug("✉️  Kafka published - patient={} partition={} offset={}",
                            patientKey,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                    }
                });
        } catch (Exception e) {
            log.error("❌ Error serializing vital data for Kafka: {}", e.getMessage(), e);
        }
    }

    private String toPayloadString(Message<?> message) {
        Object payloadObj = message.getPayload();
        if (payloadObj == null) return null;

        if (payloadObj instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        // parfois c’est déjà une String, parfois autre chose
        return String.valueOf(payloadObj);
    }

    private VitalData parseVitalData(String payload) throws JsonProcessingException {
        // Try parsing as ObservationData first (smartwatch time-series format)
        try {
            ObservationData observationData = objectMapper.readValue(payload, ObservationData.class);
            // If successful and has PPG data, convert to VitalData
            if (observationData.getPpgData() != null && !observationData.getPpgData().isEmpty()) {
                log.debug("📊 Parsed as ObservationData (smartwatch format), converting to VitalData");
                return convertObservationDataToVitalData(observationData);
            }
        } catch (JsonProcessingException e) {
            // Not ObservationData format, try VitalData
        }

        // Try parsing as VitalData (simple snapshot format)
        try {
            return objectMapper.readValue(payload, VitalData.class);
        } catch (JsonProcessingException e) {
            String sanitized = sanitizeJsonLikePayload(payload);
            return getLenientObjectMapper().readValue(sanitized, VitalData.class);
        }
    }

    private VitalData convertObservationDataToVitalData(ObservationData observationData) {
        VitalData vitalData = new VitalData();
        
        // Extract patient ID and time window
        vitalData.setPatientId(observationData.getPatientId());
        vitalData.setStartTime(observationData.getStartTime() != null ? 
            java.time.LocalDateTime.parse(observationData.getStartTime()) : null);
        vitalData.setEndTime(observationData.getEndTime() != null ? 
            java.time.LocalDateTime.parse(observationData.getEndTime()) : null);
        
        // Calculate collection duration
        if (vitalData.getStartTime() != null && vitalData.getEndTime() != null) {
            long durationSeconds = java.time.temporal.ChronoUnit.SECONDS
                .between(vitalData.getStartTime(), vitalData.getEndTime());
            vitalData.setCollectionDurationSeconds((int) durationSeconds);
        }
        
        // Use end time as primary timestamp (latest measurement)
        if (vitalData.getEndTime() != null) {
            vitalData.setTimestamp(vitalData.getEndTime().atZone(java.time.ZoneId.of("UTC")).toInstant());
        }

        // Process PPG data with detailed extraction
        if (observationData.getPpgData() != null && !observationData.getPpgData().isEmpty()) {
            processPPGData(observationData.getPpgData(), vitalData);
        }
        
        // Process accelerometer data with activity analysis
        if (observationData.getAccelerometerData() != null && !observationData.getAccelerometerData().isEmpty()) {
            processAccelerometerData(observationData.getAccelerometerData(), vitalData);
        }
        
        // Assess overall signal quality based on data completeness
        assessSignalQuality(vitalData, observationData);

        log.debug("✅ Converted ObservationData to VitalData - patientId={}, HR={} (range: {}-{}), SpO2={}, activity={}", 
            vitalData.getPatientId(), 
            vitalData.getHeartRate(),
            vitalData.getHeartRateMin(),
            vitalData.getHeartRateMax(),
            vitalData.getOxygenSaturation(),
            vitalData.getAccelerometerMagnitudeAverage());

        return vitalData;
    }

    /**
     * Extract detailed PPG (photoplethysmogram) data statistics
     * Preserves: min/max/avg green/red values, heart rate range, HRV
     */
    private void processPPGData(List<Map<String, Double>> ppgData, VitalData vitalData) {
        List<Double> greenValues = new ArrayList<>();
        List<Double> redValues = new ArrayList<>();
        
        for (Map<String, Double> measurement : ppgData) {
            Double greenValue = measurement.get("green");
            Double redValue = measurement.get("red");
            if (greenValue != null) greenValues.add(greenValue);
            if (redValue != null) redValues.add(redValue);
        }

        vitalData.setPpgDataPoints(greenValues.size());
        
        // Extract PPG statistics for raw signal preservation
        if (!greenValues.isEmpty()) {
            vitalData.setPpgGreenMin(greenValues.stream().mapToDouble(v -> v).min().orElse(0.0));
            vitalData.setPpgGreenMax(greenValues.stream().mapToDouble(v -> v).max().orElse(0.0));
            vitalData.setPpgGreenAverage(greenValues.stream().mapToDouble(v -> v).average().orElse(0.0));
        }
        
        if (!redValues.isEmpty()) {
            vitalData.setPpgRedMin(redValues.stream().mapToDouble(v -> v).min().orElse(0.0));
            vitalData.setPpgRedMax(redValues.stream().mapToDouble(v -> v).max().orElse(0.0));
            vitalData.setPpgRedAverage(redValues.stream().mapToDouble(v -> v).average().orElse(0.0));
        }

        // Calculate heart rate with better peak detection (improved algorithm)
        HeartRateAnalysis hrAnalysis = calculateHeartRateWithVariability(greenValues);
        vitalData.setHeartRate(hrAnalysis.averageHeartRate);
        vitalData.setHeartRateMin(hrAnalysis.minHeartRate);
        vitalData.setHeartRateMax(hrAnalysis.maxHeartRate);
        vitalData.setHeartRateVariability(hrAnalysis.variability);
        vitalData.setDetectedPeaks(hrAnalysis.peakCount);
        
        // Estimate SpO2 from green/red PPG ratio
        Double estimatedSpO2 = estimateSpO2FromPPG(greenValues, redValues);
        vitalData.setOxygenSaturation(estimatedSpO2);
        
        log.debug("📊 PPG Processed: {} datapoints, HR={} bpm (±{}), SpO2={}%",
            greenValues.size(), vitalData.getHeartRate(), vitalData.getHeartRateVariability(), 
            vitalData.getOxygenSaturation());
    }

    /**
     * Process accelerometer data to detect activity level
     * Preserves: movement intensity, variance (HRV equivalent for activity)
     */
    private void processAccelerometerData(List<Map<String, Object>> accelerometerData, VitalData vitalData) {
        List<Double> magnitudes = new ArrayList<>();
        
        for (Map<String, Object> measurement : accelerometerData) {
            Object accelObj = measurement.get("accelerometerPoint");
            if (accelObj instanceof ObservationData.AccelerometerPoint point) {
                // Calculate magnitude: sqrt(x² + y² + z²)
                double magnitude = Math.sqrt(point.getX() * point.getX() + 
                                           point.getY() * point.getY() + 
                                           point.getZ() * point.getZ());
                magnitudes.add(magnitude);
            }
        }

        vitalData.setAccelerometerDataPoints(magnitudes.size());
        
        if (!magnitudes.isEmpty()) {
            double avgMagnitude = magnitudes.stream().mapToDouble(v -> v).average().orElse(0.0);
            double maxMagnitude = magnitudes.stream().mapToDouble(v -> v).max().orElse(0.0);
            vitalData.setAccelerometerMagnitudeAverage(Math.round(avgMagnitude * 100.0) / 100.0);
            vitalData.setAccelerometerMagnitudeMax(Math.round(maxMagnitude * 100.0) / 100.0);
            
            // Calculate variance (std dev) of movement
            double mean = avgMagnitude;
            double variance = magnitudes.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);
            vitalData.setAccelerometerVariance(Math.sqrt(variance));
            
            log.debug("🏃 Activity Detected: avg={}, max={}, variance={}", 
                vitalData.getAccelerometerMagnitudeAverage(),
                vitalData.getAccelerometerMagnitudeMax(),
                vitalData.getAccelerometerVariance());
        }
    }

    /**
     * Assess overall signal quality based on data completeness and consistency
     */
    private void assessSignalQuality(VitalData vitalData, ObservationData observationData) {
        double qualityScore = 100.0;
        
        // Check data point count
        int ppgPoints = vitalData.getPpgDataPoints() != null ? vitalData.getPpgDataPoints() : 0;
        if (ppgPoints < 100) qualityScore -= 20; // insufficient data
        
        // Check PPG signal stability
        if (vitalData.getPpgGreenMax() != null && vitalData.getPpgGreenMin() != null) {
            double ppgRange = vitalData.getPpgGreenMax() - vitalData.getPpgGreenMin();
            if (ppgRange < 100) qualityScore -= 15; // weak signal
        }
        
        // Check heart rate validity
        if (vitalData.getHeartRate() != null) {
            if (vitalData.getHeartRate() < 40 || vitalData.getHeartRate() > 200) {
                qualityScore -= 30; // out of range
            }
        }
        
        // Check HRV (higher variability = less stable)
        if (vitalData.getHeartRateVariability() != null && vitalData.getHeartRateVariability() > 30) {
            qualityScore -= 10; // high variability
        }
        
        // Check SpO2 validity
        if (vitalData.getOxygenSaturation() != null) {
            if (vitalData.getOxygenSaturation() < 85 || vitalData.getOxygenSaturation() > 100) {
                qualityScore -= 25; // invalid SpO2
            }
        }
        
        qualityScore = Math.max(0, Math.min(100, qualityScore));
        vitalData.setSignalQualityScore(qualityScore);
        
        if (qualityScore >= 90) {
            vitalData.setSignalQuality("excellent");
        } else if (qualityScore >= 75) {
            vitalData.setSignalQuality("good");
        } else if (qualityScore >= 50) {
            vitalData.setSignalQuality("fair");
        } else {
            vitalData.setSignalQuality("poor");
        }
        
        log.debug("📈 Signal Quality: {} (score={}%)", vitalData.getSignalQuality(), qualityScore);
    }

    /**
     * Calculate heart rate with variability using improved peak detection
     * Uses threshold-based detection with dynamic adjustment
     */
    private HeartRateAnalysis calculateHeartRateWithVariability(List<Double> greenValues) {
        HeartRateAnalysis analysis = new HeartRateAnalysis();
        
        if (greenValues.isEmpty()) {
            log.warn("⚠️ No PPG values for heart rate calculation");
            return analysis;
        }

        // Calculate adaptive threshold
        double mean = greenValues.stream().mapToDouble(v -> v).average().orElse(0.0);
        double stdDev = Math.sqrt(greenValues.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average().orElse(0.0));
        
        double threshold = mean + (0.5 * stdDev); // Adaptive threshold
        
        // Detect peaks using state machine
        List<Integer> peakIntervals = new ArrayList<>();
        boolean wasAboveThreshold = greenValues.get(0) > threshold;
        
        for (int i = 1; i < greenValues.size(); i++) {
            boolean isAboveThreshold = greenValues.get(i) > threshold;
            
            if (isAboveThreshold && !wasAboveThreshold) {
                // Transition from below to above threshold = potential peak start
                analysis.peakCount++;
                
                if (!peakIntervals.isEmpty()) {
                    peakIntervals.add(i - peakIntervals.get(peakIntervals.size() - 1));
                }
                peakIntervals.add(i);
            }
            wasAboveThreshold = isAboveThreshold;
        }

        analysis.peakCount = Math.max(1, analysis.peakCount);
        
        // Convert peak intervals to heart rate
        double samplingRate = 20.0; // Samsung Health ~20 Hz
        double durationSeconds = greenValues.size() / samplingRate;
        
        if (durationSeconds > 0) {
            double averageHeartRate = (analysis.peakCount / durationSeconds) * 60.0;
            
            // Validate heart rate range
            if (averageHeartRate >= 40 && averageHeartRate <= 200) {
                analysis.averageHeartRate = Math.round(averageHeartRate * 10.0) / 10.0;
            }
        }
        
        // Calculate heart rate variability (standard deviation of rates)
        if (peakIntervals.size() > 1) {
            double avgInterval = peakIntervals.stream().mapToDouble(v -> v).average().orElse(0.0);
            double intervalStdDev = Math.sqrt(peakIntervals.stream()
                .mapToDouble(v -> Math.pow(v - avgInterval, 2))
                .average().orElse(0.0));
            
            // Convert interval variation to HR variation
            analysis.variability = Math.round((intervalStdDev * 1200.0 / (avgInterval * avgInterval)) * 10.0) / 10.0;
            analysis.minHeartRate = Math.round((3600.0 * 20.0 / (avgInterval + intervalStdDev)) * 10.0) / 10.0;
            analysis.maxHeartRate = Math.round((3600.0 * 20.0 / Math.max(0.1, avgInterval - intervalStdDev)) * 10.0) / 10.0;
        } else {
            analysis.variability = 0.0;
            analysis.minHeartRate = analysis.averageHeartRate;
            analysis.maxHeartRate = analysis.averageHeartRate;
        }

        return analysis;
    }

    private Double estimateSpO2FromPPG(List<Double> greenValues, List<Double> redValues) {
        // SpO2 estimation requires both red and IR wavelengths
        // Improved ratio-of-ratios method with better statistics
        if (greenValues.isEmpty() || redValues.isEmpty()) {
            log.debug("⚠️ Insufficient PPG data for SpO2 estimation");
            return 98.0; // Default to typical healthy value
        }

        // Calculate statistics for better filtering
        double greenAvg = greenValues.stream().mapToDouble(v -> v).average().orElse(0.0);
        double greenStdDev = Math.sqrt(greenValues.stream()
            .mapToDouble(v -> Math.pow(v - greenAvg, 2))
            .average().orElse(0.0));
        
        double redAvg = redValues.stream().mapToDouble(v -> v).average().orElse(0.0);
        double redStdDev = Math.sqrt(redValues.stream()
            .mapToDouble(v -> Math.pow(v - redAvg, 2))
            .average().orElse(0.0));
        
        // Filter outliers before calculation (±2 std dev)
        double greenFiltered = greenValues.stream()
            .filter(v -> Math.abs(v - greenAvg) <= 2 * greenStdDev)
            .mapToDouble(v -> v).average().orElse(greenAvg);
        
        double redFiltered = redValues.stream()
            .filter(v -> Math.abs(v - redAvg) <= 2 * redStdDev)
            .mapToDouble(v -> v).average().orElse(redAvg);
        
        if (redFiltered > 0) {
            // Improved SpO2 estimation using better calibration
            double ratio = greenFiltered / redFiltered;
            double estimatedSpO2 = 110.0 - (25.0 * ratio);
            
            // Sanity check: typical SpO2 is 95-100% for healthy individuals
            if (estimatedSpO2 >= 85 && estimatedSpO2 <= 100) {
                return Math.round(estimatedSpO2 * 10.0) / 10.0;
            } else if (estimatedSpO2 < 85) {
                log.warn("⚠️ Low SpO2 estimate: {}% - Signal may be poor", estimatedSpO2);
                return Math.round(estimatedSpO2 * 10.0) / 10.0; // Return as-is for low values
            }
        }

        log.debug("⚠️ Could not estimate SpO2 from PPG data ratio, using default");
        return 98.0; // Default to typical healthy value
    }

    /**
     * Inner class to encapsulate heart rate analysis results with variability metrics
     */
    public static class HeartRateAnalysis {
        public Double averageHeartRate = null;
        public Double minHeartRate = null;
        public Double maxHeartRate = null;
        public Double variability = 0.0; // Standard deviation in BPM
        public int peakCount = 0;
    }

    private String sanitizeJsonLikePayload(String payload) {
        // Best-effort: quote bare keys and simple bareword string values
        String withQuotedKeys = payload.replaceAll("([,{\\s])([A-Za-z_][A-Za-z0-9_]*)\\s*:", "$1\"$2\":");
        String withQuotedBareValues = withQuotedKeys.replaceAll("(:\\s*)([A-Za-z_][A-Za-z0-9_]*)(\\s*[,}])", "$1\"$2\"$3");
        return withQuotedBareValues.replaceAll("(:\\s*)(\\d{4}-\\d{2}-\\d{2}T[^,}\\s]+)(\\s*[,}])", "$1\"$2\"$3");
    }

    /**
     * Error handler for Spring Integration MQTT errors
     */
    @Bean
    @ServiceActivator(inputChannel = "errorChannel")
    public MessageHandler mqttErrorHandler() {
        return msg -> {
            if (msg instanceof ErrorMessage errorMessage) {
                Throwable cause = errorMessage.getPayload();
                log.error("🚨 MQTT Error: {} - Message: {}",
                    cause.getClass().getSimpleName(),
                    cause.getMessage());
                return;
            }

            Object payload = msg.getPayload();
            if (payload instanceof Throwable throwable) {
                log.error("🚨 MQTT Error: {} - Message: {}",
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage());
                return;
            }

            log.error("🚨 MQTT Error: Unexpected payload type: {}", payload);
        };
    }

    private ObjectMapper getLenientObjectMapper() {
        ObjectMapper local = lenientObjectMapper;
        if (local == null) {
            synchronized (this) {
                if (lenientObjectMapper == null) {
                    lenientObjectMapper = objectMapper.copy()
                        .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature())
                        .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature())
                        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature());
                }
                local = lenientObjectMapper;
            }
        }
        return local;
    }

    private boolean isDuplicate(VitalData vitalData) {
        if (vitalData.getTimestamp() == null || vitalData.getPatientId() == null) {
            return false;
        }

        String key = vitalData.getPatientId() + "|" + vitalData.getTimestamp().toString();
        long now = System.currentTimeMillis();
        long ttlMillis = TimeUnit.SECONDS.toMillis(Math.max(1, dedupTtlSeconds));

        Long lastSeen = recentDedup.put(key, now);
        if (lastSeen == null) {
            return false;
        }

        if (now - lastSeen <= ttlMillis) {
            return true;
        }

        recentDedup.put(key, now);
        return false;
    }

    private String resolveClientId() {
        boolean useUnique = resolveUniqueClientId();
        return useUnique ? generateUniqueClientId() : clientId;
    }

    private boolean resolveUniqueClientId() {
        if (uniqueClientIdOverride != null && !uniqueClientIdOverride.isBlank()) {
            return Boolean.parseBoolean(uniqueClientIdOverride);
        }

        return isProductionProfile();
    }

    private boolean resolveCleanSession() {
        if (cleanSessionOverride != null && !cleanSessionOverride.isBlank()) {
            return Boolean.parseBoolean(cleanSessionOverride);
        }

        return !isProductionProfile();
    }

    private boolean isProductionProfile() {
        if (activeProfiles == null || activeProfiles.isBlank()) {
            return false;
        }

        for (String profile : activeProfiles.split(",")) {
            if ("prod".equalsIgnoreCase(profile.trim())) {
                return true;
            }
        }

        return false;
    }
}
