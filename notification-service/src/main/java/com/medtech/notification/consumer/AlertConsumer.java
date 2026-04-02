package com.medtech.notification.consumer;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medtech.notification.model.Alert;
import com.medtech.notification.model.NotificationRecipient;
import com.medtech.notification.model.enums.NotificationStatus;
import com.medtech.notification.model.enums.Severity;
import com.medtech.notification.repository.NotificationLogRepository;
import com.medtech.notification.service.EmailService;
import com.medtech.notification.service.RecipientService;
import com.medtech.notification.service.SmsService;
import com.medtech.notification.service.SseService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AlertConsumer {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SseService sseService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private RecipientService recipientService;

    @Autowired
    private SmsService smsService;

    @Autowired
    private NotificationLogRepository logRepository;

    @KafkaListener(topics = "${kafka.topic.alerts}", groupId = "notification-group")
    public void consumeAlert(String message) {
        try {
            Alert alert = objectMapper.readValue(message, Alert.class);
            log.info("📥 Alert received: {} - {} - {}", 
                alert.getPatientId(), alert.getAlertType(), alert.getSeverity());

            // Send via SSE (always)
            sseService.sendToPatient(alert.getPatientId(), alert);
            log.info("📡 Alert sent via SSE to patient {}", alert.getPatientId());

            // WARNING and CRITICAL are escalated to additional channels.
            List<NotificationRecipient> recipients = recipientService.getRecipientsForAlert(alert);

            Severity severity = alert.getSeverity() != null ? alert.getSeverity() : Severity.INFO;

            if (severity == Severity.WARNING || severity == Severity.CRITICAL) {
                for (NotificationRecipient recipient : recipients) {
                    if (recipient.getEmail() != null && !recipient.getEmail().isEmpty()) {
                        emailService.sendAlertEmail(alert, recipient);
                        log.info("📧 Alert sent via email to {}", recipient.getEmail());
                    }
                }
            }

            if (severity == Severity.CRITICAL) {
                boolean smsSent = false;
                for (NotificationRecipient recipient : recipients) {
                    if (recipient.getPhone() != null && !recipient.getPhone().isEmpty()) {
                        smsService.sendAlertSms(alert, recipient.getPhone());
                        smsSent = true;
                    }
                }

                if (!smsSent) {
                    String fallbackPhone = recipientService.getDefaultEmergencyPhone();
                    smsService.sendAlertSms(alert, fallbackPhone);
                    log.info("📱 Critical alert sent to fallback emergency phone");
                }
            }

            // Log in database
            var notificationLog = new com.medtech.notification.model.NotificationLog();
            notificationLog.setAlertId(alert.getAlertId());
            notificationLog.setPatientId(alert.getPatientId());
            notificationLog.setStatus(NotificationStatus.SENT);
            notificationLog.setSentAt(Instant.now());
            logRepository.save(notificationLog);

        } catch (Exception e) {
            log.error("❌ Error processing alert: {}", e.getMessage(), e);
        }
    }
}