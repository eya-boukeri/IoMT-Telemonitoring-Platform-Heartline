package com.medtech.ingestion.service;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.ingestion.model.FilteredSignal;
import com.medtech.ingestion.model.RawSignal;

@ExtendWith(MockitoExtension.class)
class SignalProcessingServiceTest {

    @Mock
    private KafkaProducerService kafkaProducerService;
    private SignalProcessingService newService() {
        SignalProcessingService signalProcessingService = new SignalProcessingService(new ObjectMapper(), kafkaProducerService);
        ReflectionTestUtils.setField(signalProcessingService, "lowpassCutoff", 10.0);
        ReflectionTestUtils.setField(signalProcessingService, "highpassCutoff", 0.5);
        ReflectionTestUtils.setField(signalProcessingService, "samplingRate", 20.0);
        ReflectionTestUtils.setField(signalProcessingService, "motionThresholdFactor", 2.5);
        ReflectionTestUtils.setField(signalProcessingService, "absoluteMotionThreshold", 1.5);
        ReflectionTestUtils.setField(signalProcessingService, "criticalMotionRatio", 0.35);
        ReflectionTestUtils.setField(signalProcessingService, "warningMotionRatio", 0.20);
        ReflectionTestUtils.setField(signalProcessingService, "lmsLearningRate", 0.01);
        return signalProcessingService;
    }

    @Test
    void filterRemovesHighlyMotionCorruptedSegmentsAndPublishesAlert() {
        RawSignal rawSignal = new RawSignal();
        rawSignal.setDeviceId("device-1");
        rawSignal.setPatientId("patient-1");
        rawSignal.setTimestamp(Instant.parse("2026-04-01T12:00:00Z"));
        rawSignal.setRawPayload("""
            {
              "ppgData": [10, 11, 12, 11, 10, 12, 11, 10],
              "accelerometerX": [0, 0, 8, 8, 8, 8, 8, 8],
              "accelerometerY": [0, 0, 8, 8, 8, 8, 8, 8],
              "accelerometerZ": [0, 0, 8, 8, 8, 8, 8, 8]
            }
            """);

        FilteredSignal result = newService().filter(rawSignal);

        assertThat(result.getPpgData()).isNotEmpty();
        assertThat(result.getPpgData().size()).isLessThan(8);
        assertThat(result.getSignalQuality()).isBetween(0.0, 100.0);
        assertThat(result.getMetadata().getMotionRatio()).isGreaterThanOrEqualTo(0.20);
        assertThat(result.getMetadata().getRemovedSamples()).isGreaterThanOrEqualTo(1);

        verify(kafkaProducerService).publishAlert(any());
    }

    @Test
    void filterKeepsMostlyStableSegmentsWithoutAlert() {
        RawSignal rawSignal = new RawSignal();
        rawSignal.setDeviceId("device-2");
        rawSignal.setPatientId("patient-2");
        rawSignal.setTimestamp(Instant.parse("2026-04-01T12:00:00Z"));
        rawSignal.setRawPayload("""
            {
              "ppgData": [10, 11, 12, 11, 10, 11, 12, 11],
              "accelerometerX": [0, 0, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1],
              "accelerometerY": [0, 0, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1],
              "accelerometerZ": [0, 0, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1]
            }
            """);

        FilteredSignal result = newService().filter(rawSignal);

        assertThat(result.getPpgData()).isNotEmpty();
        assertThat(result.getMetadata().getMotionRatio()).isLessThan(0.20);
        verify(kafkaProducerService, never()).publishAlert(any());
    }
}
