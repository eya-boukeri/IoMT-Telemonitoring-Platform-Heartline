package com.medtech.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.medtech.notification.model.Alert;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SmsService {

    @Value("${twilio.account.sid:}")
    private String accountSid;

    @Value("${twilio.auth.token:}")
    private String authToken;

    @Value("${twilio.phone.number:}")
    private String fromPhoneNumber;

    private boolean twilioEnabled;

    @PostConstruct
    public void init() {
        twilioEnabled = hasText(accountSid) && hasText(authToken) && hasText(fromPhoneNumber);

        if (twilioEnabled) {
            Twilio.init(accountSid, authToken);
            log.info("SMS channel initialized (Twilio)");
        } else {
            log.warn("SMS channel disabled: Twilio config is incomplete");
        }
    }

    public void sendAlertSms(Alert alert, String toPhoneNumber) {
        if (!hasText(toPhoneNumber)) {
            return;
        }

        String body = String.format(
            "[MEDTECH][%s] %s - patient=%s message=%s",
            alert.getSeverity(),
            alert.getAlertType(),
            alert.getPatientId(),
            alert.getMessage()
        );

        if (!twilioEnabled) {
            log.info("SMS fallback (no Twilio): to={} body={}", toPhoneNumber, body);
            return;
        }

        try {
            Message.creator(new PhoneNumber(toPhoneNumber), new PhoneNumber(fromPhoneNumber), body).create();
            log.info("SMS sent to {}", toPhoneNumber);
        } catch (Exception e) {
            log.error("SMS failed to {}: {}", toPhoneNumber, e.getMessage());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}