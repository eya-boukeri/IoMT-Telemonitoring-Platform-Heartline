package com.medtech.vitalsmanagement.config;

import java.nio.charset.StandardCharsets;
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
        String subscriptionTopic = topicPrefix.endsWith("/") ? topicPrefix + "#" : topicPrefix + "/#";
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
        try {
            return objectMapper.readValue(payload, VitalData.class);
        } catch (JsonProcessingException e) {
            String sanitized = sanitizeJsonLikePayload(payload);
            return getLenientObjectMapper().readValue(sanitized, VitalData.class);
        }
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
