package com.medtech.ingestion.service;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.ingestion.model.RawSignal;
import com.medtech.ingestion.repository.RawSignalRepository;

@ExtendWith(MockitoExtension.class)
class RawSignalServiceTest {

    @Mock
    private RawSignalRepository rawSignalRepository;

    private RawSignalService newService() {
        return new RawSignalService(rawSignalRepository, new ObjectMapper());
    }

    @Test
    void saveRawSignalCanonicalizesValidJsonPayload() {
        when(rawSignalRepository.save(any(RawSignal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RawSignal rawSignal = new RawSignal();
        rawSignal.setDeviceId("watch-001");
        rawSignal.setTimestamp(Instant.parse("2026-04-18T10:00:00Z"));
        rawSignal.setRawPayload("""
            {
              "patientId": "eya",
              "ppgData": [100, 102, 101]
            }
            """);

        RawSignal saved = newService().saveRawSignal(rawSignal);

        assertThat(saved.getRawPayload()).isEqualTo("{\"patientId\":\"eya\",\"ppgData\":[100,102,101]}");
    }

    @Test
    void saveRawSignalWrapsNonJsonPayloadAsJsonString() throws Exception {
        when(rawSignalRepository.save(any(RawSignal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RawSignal rawSignal = new RawSignal();
        rawSignal.setDeviceId("watch-001");
        rawSignal.setTimestamp(Instant.parse("2026-04-18T10:00:00Z"));
        rawSignal.setRawPayload("{patientId=eya, ppgData=[100, 102, 101]}");

        RawSignal saved = newService().saveRawSignal(rawSignal);

        assertThat(saved.getRawPayload()).isEqualTo("\"{patientId=eya, ppgData=[100, 102, 101]}\"");
        JsonNode node = new ObjectMapper().readTree(saved.getRawPayload());
        assertThat(node.isTextual()).isTrue();
    }
}
