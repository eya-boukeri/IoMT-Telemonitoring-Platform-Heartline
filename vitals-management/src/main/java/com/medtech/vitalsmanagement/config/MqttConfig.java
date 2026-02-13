package com.medtech.vitalsmanagement.config;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

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

    // Exemple: sensors/vitals/
    @Value("${mqtt.topic.prefix:sensors/vitals/}")
    private String topicPrefix;

    @Autowired
    private InfluxDBService influxDBService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Channel principal: DirectChannel = synchrone (OK)
     * Si tu veux éviter que le thread MQTT soit bloqué par le traitement,
     * remplace par ExecutorChannel (plus avancé).
     */
    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
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
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        // options.setKeepAliveInterval(60); // optionnel

        factory.setConnectionOptions(options);
        return factory;
    }

    @Bean
    public MessageProducer inbound() {
        String subscriptionTopic = topicPrefix.endsWith("/") ? topicPrefix + "#" : topicPrefix + "/#";

        log.info("🔌 MQTT - Broker: {}, ClientId: {}, Subscribe: {}", brokerUrl, clientId, subscriptionTopic);

        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(brokerUrl, clientId, mqttClientFactory(), subscriptionTopic);

        DefaultPahoMessageConverter converter = new DefaultPahoMessageConverter();
        // Laisse le converter produire String si possible, mais on protège quand même dans le handler
        converter.setPayloadAsBytes(false);
        adapter.setConverter(converter);

        adapter.setOutputChannel(mqttInputChannel());

        // IMPORTANT: route les erreurs vers errorChannel (sinon tu ne vois pas l’erreur réelle)
        adapter.setErrorChannelName("errorChannel");

        // QoS: mets 0 ou 1 selon ton besoin, mais cohérent avec ce que tu testes
        adapter.setQos(0);

        log.info("✅ Adaptateur MQTT prêt");
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler handler() {
        return message -> {
            String receivedTopic = String.valueOf(message.getHeaders().get("mqtt_receivedTopic"));

            // 1) payload “robuste” (String ou byte[])
            String payload = toPayloadString(message);

            // 2) log RAW (toujours utile)
            log.info("🔥 HANDLER TRIGGERED topic={} payload={}", receivedTopic, payload);

            // 3) filtrer les messages vides
            if (payload == null || payload.isBlank()) {
                log.warn("⚠️ Payload vide (topic={}) -> ignoré", receivedTopic);
                return;
            }

            try {
                // 4) parsing JSON → VitalData
                VitalData vitalData = parseVitalData(payload);

                // 5) save influx
                boolean saved = influxDBService.saveVitalData(vitalData);

                if (saved) {
                    log.info("💾 Sauvegardé: Patient {} - HR={} Temp={} SpO2={}",
                        vitalData.getPatientId(),
                        vitalData.getHeartRate(),
                        vitalData.getTemperature(),
                        vitalData.getOxygenSaturation());
                }

            } catch (Exception e) {
                // Ne pas laisser “silencieux”
                log.error("❌ Erreur traitement MQTT topic={} payload={}", receivedTopic, payload, e);

                // Fallback: on stocke le payload brut pour analyse
                influxDBService.saveRawPayload(receivedTopic, payload);

                // OPTION A (recommandé en dev): relancer pour que errorChannel/stacktrace soit clair
                // throw new RuntimeException(e);

                // OPTION B (prod tolérant): ne pas relancer, juste log
            }
        };
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
            ObjectMapper lenient = objectMapper.copy()
                    .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature())
                    .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature())
                    .enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature());

            return lenient.readValue(sanitized, VitalData.class);
        }
    }

    private String sanitizeJsonLikePayload(String payload) {
        // Best-effort: quote bare keys and simple bareword string values
        String withQuotedKeys = payload.replaceAll("([,{\\s])([A-Za-z_][A-Za-z0-9_]*)\\s*:", "$1\"$2\":");
        return withQuotedKeys.replaceAll("(:\\s*)([A-Za-z_][A-Za-z0-9_]*)(\\s*[,}])", "$1\"$2\"$3");
    }

    /**
     * Logger des erreurs Spring Integration (ce qui arrive sur errorChannel)
     */
    @Bean
    @ServiceActivator(inputChannel = "errorChannel")
    public MessageHandler mqttErrorHandler() {
        return msg -> log.error("🚨 errorChannel: {}", msg);
    }
}
