package com.medtech.ingestion.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.ExecutorChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.ingestion.model.FilteredSignal;
import com.medtech.ingestion.model.RawSignal;
import com.medtech.ingestion.service.AggregationService;
import com.medtech.ingestion.service.KafkaProducerService;
import com.medtech.ingestion.service.RawSignalService;
import com.medtech.ingestion.service.SignalProcessingService;

@Configuration
public class MqttConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(MqttConfig.class);
    private static final String UNKNOWN_DEVICE_ID = "unknown-device";

    @Value("${mqtt.broker.url:tcp://127.0.0.1:1884}")
    private String brokerUrl;

    @Value("${mqtt.client.id:ingestion-service-client}")
    private String clientId;

    @Value("${mqtt.topic.input:health/sensorData}")
    private String inputTopic;

    @Value("${mqtt.qos:1}")
    private int qos;

    @Value("${mqtt.clean-session:true}")
    private boolean cleanSession;

    private final ObjectMapper objectMapper;
    private final RawSignalService rawSignalService;
    private final SignalProcessingService signalProcessingService;
    private final KafkaProducerService kafkaProducerService;
    private final AggregationService aggregationService;

    public MqttConfig(
        ObjectMapper objectMapper,
        RawSignalService rawSignalService,
        SignalProcessingService signalProcessingService,
        KafkaProducerService kafkaProducerService,
        AggregationService aggregationService
    ) {
        this.objectMapper = objectMapper;
        this.rawSignalService = rawSignalService;
        this.signalProcessingService = signalProcessingService;
        this.kafkaProducerService = kafkaProducerService;
        this.aggregationService = aggregationService;
    }

    @Bean
    public ThreadPoolTaskExecutor mqttInboundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mqtt-ingestion-");
        executor.initialize();
        return executor;
    }

    @Bean
    public MessageChannel mqttInputChannel(ThreadPoolTaskExecutor mqttInboundExecutor) {
        return new ExecutorChannel(mqttInboundExecutor);
    }

    @Bean
    public MessageChannel mqttErrorChannel() {
        return new DirectChannel();
    }

    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();

        org.eclipse.paho.client.mqttv3.MqttConnectOptions options = new org.eclipse.paho.client.mqttv3.MqttConnectOptions();
        options.setServerURIs(new String[] { brokerUrl });
        options.setAutomaticReconnect(true);
        options.setCleanSession(cleanSession);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(20);
        factory.setConnectionOptions(options);

        return factory;
    }

    @Bean
    public DefaultPahoMessageConverter mqttMessageConverter() {
        DefaultPahoMessageConverter converter = new DefaultPahoMessageConverter();
        converter.setPayloadAsBytes(false);
        return converter;
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter mqttInbound(
        MessageChannel mqttInputChannel,
        MessageChannel mqttErrorChannel
    ) {
        MqttPahoMessageDrivenChannelAdapter adapter =
            new MqttPahoMessageDrivenChannelAdapter(brokerUrl, clientId, mqttClientFactory(), inputTopic);

        adapter.setCompletionTimeout(5_000);
        adapter.setConverter(mqttMessageConverter());
        adapter.setQos(qos);
        adapter.setOutputChannel(mqttInputChannel);
        adapter.setErrorChannel(mqttErrorChannel);

        LOGGER.info("MQTT inbound configured - broker={} topic={} clientId={} qos={}",
            brokerUrl, inputTopic, clientId, qos);

        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler mqttMessageHandler() {
        return message -> {
            String topic = toStringHeader(message, MqttHeaders.RECEIVED_TOPIC, inputTopic);
            String payload = toPayloadString(message.getPayload());

            if (payload == null || payload.isBlank()) {
                LOGGER.warn("MQTT payload empty, skipped - topic={}", topic);
                return;
            }

            try {
                JsonNode root = tryParse(payload);
                String deviceId = readText(root, "deviceId", extractDeviceIdFromTopic(topic));
                String patientId = readNullableText(root, "patientId");
                String signalType = readText(root, "signalType", extractSignalType(topic));
                Instant timestamp = extractTimestamp(root);

                RawSignal savedRaw = rawSignalService.saveRawSignal(
                    deviceId,
                    patientId,
                    payload,
                    signalType,
                    timestamp
                );

                kafkaProducerService.publishRawSignal(savedRaw);

                FilteredSignal filteredSignal = signalProcessingService.filter(savedRaw);
                kafkaProducerService.publishFilteredSignal(filteredSignal);
                aggregationService.bufferSignal(filteredSignal);

                LOGGER.debug("MQTT message processed - topic={} patientId={} deviceId={} rawSignalId={}",
                    topic, savedRaw.getPatientId(), savedRaw.getDeviceId(), savedRaw.getId());
            } catch (RuntimeException ex) {
                LOGGER.error("MQTT message processing failed - topic={} payload={}", topic, payload, ex);
            }
        };
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttErrorChannel")
    public MessageHandler mqttErrorHandler() {
        return message -> LOGGER.error("MQTT integration error: {}", message.getPayload());
    }

    private JsonNode tryParse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (IOException ex) {
            LOGGER.debug("Payload is not valid JSON, metadata extraction fallback enabled");
            return null;
        }
    }

    private Instant extractTimestamp(JsonNode root) {
        if (root == null) {
            return Instant.now();
        }

        JsonNode timestampNode = root.get("timestamp");
        if (timestampNode == null || timestampNode.isNull()) {
            timestampNode = root.get("startTime");
        }

        if (timestampNode == null || timestampNode.isNull()) {
            return Instant.now();
        }

        if (timestampNode.isNumber()) {
            long value = timestampNode.asLong();
            return value > 10_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        }

        String value = timestampNode.asText(null);
        if (value == null || value.isBlank()) {
            return Instant.now();
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            LOGGER.debug("Invalid timestamp format received: {}", value);
            return Instant.now();
        }
    }

    private String readText(JsonNode root, String fieldName, String fallback) {
        if (root == null) {
            return fallback;
        }

        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return fallback;
        }

        String value = node.asText(null);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private String readNullableText(JsonNode root, String fieldName) {
        if (root == null) {
            return null;
        }

        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }

        String value = node.asText(null);
        return (value == null || value.isBlank()) ? null : value;
    }

    private String toPayloadString(Object payload) {
        if (payload == null) {
            return null;
        }

        if (payload instanceof byte[] bytes) {
            return normalizeJsonString(new String(bytes, StandardCharsets.UTF_8));
        }

        if (payload instanceof String text) {
            return normalizeJsonString(text);
        }

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            LOGGER.warn("Unsupported MQTT payload type={}, fallback to toString()", payload.getClass().getName());
            return String.valueOf(payload);
        }
    }

    private String normalizeJsonString(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }

        try {
            JsonNode node = objectMapper.readTree(payload);
            return objectMapper.writeValueAsString(node);
        } catch (IOException ex) {
            return payload.trim();
        }
    }

    private String toStringHeader(Message<?> message, String headerName, String fallback) {
        Object value = message.getHeaders().get(headerName);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private String extractDeviceIdFromTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return UNKNOWN_DEVICE_ID;
        }

        String[] segments = topic.split("/");
        if (segments.length == 0) {
            return UNKNOWN_DEVICE_ID;
        }

        String lastSegment = segments[segments.length - 1];
        return (lastSegment == null || lastSegment.isBlank()) ? UNKNOWN_DEVICE_ID : lastSegment;
    }

    private String extractSignalType(String topic) {
        if (topic == null || topic.isBlank()) {
            return "mqtt";
        }

        String[] segments = topic.split("/");
        if (segments.length < 2) {
            return "mqtt";
        }

        String penultimate = segments[segments.length - 2];
        return (penultimate == null || penultimate.isBlank()) ? "mqtt" : penultimate;
    }
}