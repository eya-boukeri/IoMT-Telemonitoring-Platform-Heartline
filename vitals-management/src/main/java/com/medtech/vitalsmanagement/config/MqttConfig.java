package com.medtech.vitalsmanagement.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
        
        log.warn("─────────────────────────────────────────────────────────────");
        log.warn("🔧 MQTT CLIENT FACTORY Configuration");
        log.warn("   Broker URL: {}", brokerUrl);
        log.warn("   AutoReconnect: {}", options.isAutomaticReconnect());
        log.warn("   CleanSession: {}", options.isCleanSession());
        log.warn("   ConnectionTimeout: {} sec", options.getConnectionTimeout());
        log.warn("   KeepAliveInterval: {} sec", options.getKeepAliveInterval());
        log.warn("─────────────────────────────────────────────────────────────");
        
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
        // Configuration du topic d'écoute
        // Si le topic se termine par /, ajouter # pour wildcard (ex: sensors/vitals/#)
        // Sinon, utiliser le topic exact (ex: health/sensorData)
        String subscriptionTopic = topicPrefix.endsWith("/") ? topicPrefix + "#" : topicPrefix;
        String resolvedClientId = resolveClientId();

        // 🔌 CLIENT MQTT #1: Backend en tant que SUBSCRIBER (réception des données)
        log.warn("═══════════════════════════════════════════════════════════════");
        log.warn("🔌 MQTT CLIENT #1: BACKEND - MODE SUBSCRIBER (Réception)");
        log.warn("   Broker URL: {}", brokerUrl);
        log.warn("   Client ID: {}", resolvedClientId);
        log.warn("   Subscribe Topic: {}", subscriptionTopic);
        log.warn("   QoS Level: {}", qos);
        log.warn("═══════════════════════════════════════════════════════════════");

        MqttPahoMessageDrivenChannelAdapter adapter =
            new MqttPahoMessageDrivenChannelAdapter(brokerUrl, resolvedClientId, mqttClientFactory(), subscriptionTopic);

        DefaultPahoMessageConverter converter = new DefaultPahoMessageConverter();
        converter.setPayloadAsBytes(false);
        adapter.setConverter(converter);

        adapter.setOutputChannel(mqttInputChannel());
        adapter.setErrorChannelName("errorChannel");

        // QoS=1: at-least-once delivery (possible duplicates). QoS=0: lower latency, possible loss.
        adapter.setQos(qos);

        log.warn("✅ MQTT Adapter configured - awaiting messages on topic: {}", subscriptionTopic);
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler handler() {
        return message -> {
            String receivedTopic = String.valueOf(message.getHeaders().get("mqtt_receivedTopic"));
            String payload = toPayloadString(message);

            // 📨 Log visible pour chaque message reçu
            log.warn("📨 ✅ MESSAGE MQTT REÇU!");
            log.warn("   Topic: {}", receivedTopic);
            log.warn("   Payload Size: {} bytes", payload != null ? payload.length() : 0);
            log.warn("   Headers: {}", message.getHeaders());

            if (payload == null || payload.isBlank()) {
                log.warn("⚠️  Empty payload (topic={}) - skipped", receivedTopic);
                return;
            }

            try {
                VitalData vitalData = parseVitalData(payload);

                if (vitalData.getPatientId() == null || vitalData.getPatientId().isBlank()) {
                    log.warn("⚠️  Missing patientId in vital data - topic={}", receivedTopic);
                    influxDBService.saveRawPayload(receivedTopic, payload);
                    return;
                }

                if (dedupEnabled && isDuplicate(vitalData)) {
                    log.debug("♻️  Duplicate MQTT payload skipped (patientId={}, timestamp={})",
                        vitalData.getPatientId(), vitalData.getTimestamp());
                    return;
                }

                boolean savedToInflux = influxDBService.saveVitalData(vitalData);

                if (savedToInflux) {
                    log.warn("💾 ✅ SUCCESS: Données sauvegardées dans InfluxDB");
                    log.warn("   Patient: {}", vitalData.getPatientId());
                    log.warn("   HR: {} bpm", vitalData.getHeartRate());

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
            // If successful and looks like smartwatch payload, convert to VitalData
            if (isObservationPayload(observationData)) {
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
        vitalData.setStartTime(parseLocalDateTimeSafely(observationData.getStartTime()));
        vitalData.setEndTime(parseLocalDateTimeSafely(observationData.getEndTime()));
        
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
        assessSignalQuality(vitalData);

        log.debug("✅ Converted ObservationData to VitalData - patientId={}, HR={} (range: {}-{}), activity={}", 
            vitalData.getPatientId(), 
            vitalData.getHeartRate(),
            vitalData.getHeartRateMin(),
            vitalData.getHeartRateMax(),
            vitalData.getAccelerometerMagnitudeAverage());

        return vitalData;
    }

    private boolean isObservationPayload(ObservationData observationData) {
        if (observationData == null) {
            return false;
        }

        return (observationData.getPpgData() != null && !observationData.getPpgData().isEmpty())
            || (observationData.getAccelerometerData() != null && !observationData.getAccelerometerData().isEmpty())
            || observationData.getStartTime() != null
            || observationData.getEndTime() != null;
    }

    /**
     * Extract detailed PPG (photoplethysmogram) data statistics
     * Preserves: min/max/avg green/red values, heart rate range, HRV
     */
    private void processPPGData(List<Map<String, Object>> ppgData, VitalData vitalData) {
        List<Double> greenValues = new ArrayList<>();
        List<Double> redValues = new ArrayList<>();
        
        for (Map<String, Object> measurement : ppgData) {
            if (measurement == null || measurement.isEmpty()) {
                continue;
            }

            // Format 1 (legacy): {"green":1234.5, "red":987.3}
            Double greenValue = extractDouble(measurement.get("green"));
            Double redValue = extractDouble(measurement.get("red"));
            if (greenValue != null) {
                greenValues.add(greenValue);
            }
            if (redValue != null) {
                redValues.add(redValue);
            }

            // Format 2 (SensorApp): {"2026-...":1234.5}
            if (greenValue == null && redValue == null) {
                for (Map.Entry<String, Object> entry : measurement.entrySet()) {
                    if (entry == null) {
                        continue;
                    }
                    if ("timestamp".equalsIgnoreCase(entry.getKey())) {
                        continue;
                    }
                    Double timestampValue = extractDouble(entry.getValue());
                    if (timestampValue != null) {
                        greenValues.add(timestampValue);
                    }
                }
            }
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
        
        log.debug("📊 PPG Processed: {} datapoints, HR={} bpm (±{})",
            greenValues.size(), vitalData.getHeartRate(), vitalData.getHeartRateVariability());
    }

    /**
     * Process accelerometer data to detect activity level
     * Preserves: movement intensity, variance (HRV equivalent for activity)
     */
    private void processAccelerometerData(List<Map<String, Object>> accelerometerData, VitalData vitalData) {
        List<Double> magnitudes = new ArrayList<>();
        
        for (Map<String, Object> measurement : accelerometerData) {
            if (measurement == null || measurement.isEmpty()) {
                continue;
            }

            // Format 1 (legacy): {"accelerometerPoint": {...}}
            Object accelObj = measurement.get("accelerometerPoint");
            ObservationData.AccelerometerPoint point = toAccelerometerPoint(accelObj);
            if (point != null) {
                // Calculate magnitude: sqrt(x² + y² + z²)
                double magnitude = Math.sqrt(point.getX() * point.getX() + 
                                           point.getY() * point.getY() + 
                                           point.getZ() * point.getZ());
                magnitudes.add(magnitude);
                continue;
            }

            // Format 2 (SensorApp): {"2026-...": {"x":..., "y":..., "z":...}}
            for (Map.Entry<String, Object> entry : measurement.entrySet()) {
                if (entry == null) {
                    continue;
                }
                ObservationData.AccelerometerPoint mappedPoint = toAccelerometerPoint(entry.getValue());
                if (mappedPoint != null) {
                    double magnitude = Math.sqrt(mappedPoint.getX() * mappedPoint.getX() +
                                               mappedPoint.getY() * mappedPoint.getY() +
                                               mappedPoint.getZ() * mappedPoint.getZ());
                    magnitudes.add(magnitude);
                }
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
    private void assessSignalQuality(VitalData vitalData) {
        double qualityScore = 100.0;
        
        // Check data point count
        Integer ppgPointsValue = vitalData.getPpgDataPoints();
        int ppgPoints = ppgPointsValue != null ? ppgPointsValue.intValue() : 0;
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

    private ObservationData.AccelerometerPoint toAccelerometerPoint(Object accelObj) {
        if (accelObj == null) {
            return null;
        }

        if (accelObj instanceof ObservationData.AccelerometerPoint point) {
            return point;
        }

        if (accelObj instanceof Map<?, ?> rawMap) {
            Double x = extractDouble(rawMap.get("x"));
            Double y = extractDouble(rawMap.get("y"));
            Double z = extractDouble(rawMap.get("z"));
            if (x != null && y != null && z != null) {
                return new ObservationData.AccelerometerPoint(x, y, z);
            }
        }

        return null;
    }

    private Double extractDouble(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDateTime parseLocalDateTimeSafely(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
        }

        try {
            return Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ignored) {
        }

        log.warn("⚠️ Unable to parse datetime value: {}", value);
        return null;
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
