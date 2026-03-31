package com.medtech.notification.model;

import java.time.Instant;

import com.medtech.notification.model.enums.Channel;
import com.medtech.notification.model.enums.NotificationStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notification_logs")
@Data
@NoArgsConstructor
public class NotificationLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "alert_id", nullable = false)
    private String alertId;
    
    @Column(name = "patient_id")
    private String patientId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "channel")
    private Channel channel;
    
    @Column(name = "recipient_id")
    private Long recipientId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private NotificationStatus status;
    
    @Column(name = "sent_at")
    private Instant sentAt;
    
    @Column(name = "error_message")
    private String errorMessage;
    
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}