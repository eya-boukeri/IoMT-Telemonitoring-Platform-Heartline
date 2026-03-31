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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(recipient.getEmail());
            helper.setSubject(String.format("[%s] Alerte: %s", alert.getSeverity(), alert.getAlertType()));
            helper.setText(buildEmailContent(alert), true);
            mailSender.send(message);
            log.info("📧 Email sent to {}", recipient.getEmail());
        } catch (Exception e) {
            log.error("❌ Email failed: {}", e.getMessage());
        }
    }

    private String buildEmailContent(Alert alert) {
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