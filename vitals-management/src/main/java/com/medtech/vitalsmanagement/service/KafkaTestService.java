package com.medtech.vitalsmanagement.service;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaTestService {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaTestService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendTestMessage() {
        String message = "Test message from Spring Boot";
        kafkaTemplate.send("medical-alerts", message);
        System.out.println("Message sent to topic 'medical-alerts': " + message);
    }
}
