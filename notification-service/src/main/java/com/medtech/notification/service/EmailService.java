package com.medtech.notification.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.medtech.notification.model.Alert;
import com.medtech.notification.model.NotificationRecipient;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Async
    public void sendAlertEmail(Alert alert, NotificationRecipient recipient) {
        if (!hasText(fromEmail)) {
            log.warn("Email channel disabled: spring.mail.username is empty");
            return;
        }

        if (alert == null) {
            log.warn("Email not sent: alert payload is null");
            return;
        }

        if (recipient == null || !hasText(recipient.getEmail())) {
            log.warn("Email not sent: recipient email is missing or invalid");
            return;
        }

        String safeTo      = recipient.getEmail().trim();
        String safeFrom    = hasText(fromEmail) ? fromEmail.trim() : "noreply@medtech.tn";
        String safeSubject = String.format("[%s] Alerte: %s", 
                alert.getSeverity() != null ? alert.getSeverity() : "INCONNUE",
                alert.getAlertType() != null ? alert.getAlertType() : "Médicale");
        String safeBody    = buildEmailContent(alert);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(safeFrom);
            helper.setTo(safeTo);
            helper.setSubject(safeSubject);
            helper.setText(safeBody, true);
            mailSender.send(message);
            log.info("📧 Email sent to {}", safeTo);
        } catch (Exception e) {
            log.error("❌ Email failed sending to {}", safeTo, e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String buildEmailContent(Alert alert) {
        // Les champs peuvent être null, String.format les convertit en "null" – acceptable
        return String.format("""
            <h2>🚨 Alerte Médicale</h2>
            <p><strong>Patient:</strong> %s</p>
            <p><strong>Type:</strong> %s</p>
            <p><strong>Sévérité:</strong> %s</p>
            <p><strong>Message:</strong> %s</p>
            <p><strong>Fréquence cardiaque:</strong> %.1f bpm</p>
            <p><strong>Date:</strong> %s</p>
            """,
            alert.getPatientId(),
            alert.getAlertType(),
            alert.getSeverity(),
            alert.getMessage(),
            alert.getHeartRate() != null ? alert.getHeartRate() : 0,
            alert.getTimestamp()
        );
    }
}