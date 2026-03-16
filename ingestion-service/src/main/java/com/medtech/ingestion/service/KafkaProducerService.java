package com.medtech.ingestion.service;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.medtech.ingestion.model.AggregatedData;
import com.medtech.ingestion.model.FilteredSignal;
import com.medtech.ingestion.model.RawSignal;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaProducerService.class);
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 100L;
    private static final long SEND_TIMEOUT_SECONDS = 5L;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topic.raw:signals.raw}")
    private String rawTopic;

    @Value("${kafka.topic.filtered:signals.filtered}")
    private String filteredTopic;

    @Value("${kafka.topic.aggregated:signals.aggregated}")
    private String aggregatedTopic;

    private final AtomicLong publishedCount = new AtomicLong(0);
    private volatile Instant lastPublishedAt;

    public boolean publishRawSignal(RawSignal rawSignal) {
        return publish(rawTopic, rawSignal.getPatientId(), rawSignal);
    }

    public boolean publishFilteredSignal(FilteredSignal filteredSignal) {
        return publish(filteredTopic, filteredSignal.getPatientId(), filteredSignal);
    }

    public boolean publishAggregatedData(AggregatedData aggregatedData) {
        return publish(aggregatedTopic, aggregatedData.getPatientId(), aggregatedData);
    }

    public long getPublishedCount() {
        return publishedCount.get();
    }

    public Instant getLastPublishedAt() {
        return lastPublishedAt;
    }

    private boolean publish(String topic, String key, Object payload) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var result = kafkaTemplate.send(topic, key, payload)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                publishedCount.incrementAndGet();
                lastPublishedAt = Instant.now();

                LOGGER.debug("Kafka send successful - topic: {}, partition: {}, offset: {}",
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

                return true;
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                LOGGER.error("Kafka publish interrupted on topic {}", topic, interruptedException);
                return false;
            } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                if (attempt < MAX_RETRIES) {
                    LOGGER.warn("Kafka send failed on attempt {} for topic {}", attempt, topic, ex);
                    try {
                        TimeUnit.MILLISECONDS.sleep(BASE_BACKOFF_MS * attempt);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        LOGGER.error("Kafka retry backoff interrupted on topic {}", topic, interruptedException);
                        return false;
                    }
                } else {
                    LOGGER.error("Kafka publish failed on topic {} after {} retries", topic, MAX_RETRIES, ex);
                }
            }
        }

        return false;
    }
}
