package com.medtech.notification.model;

import java.time.Instant;

import com.medtech.notification.model.enums.Priority;

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
@Table(name = "notification_recipients")
@Data
@NoArgsConstructor
public class NotificationRecipient {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "patient_id", nullable = false)
    private String patientId;
    
    @Column(name = "recipient_type")
    private String recipientType;
    
    @Column(name = "recipient_name")
    private String recipientName;
    
    @Column(name = "email")
    private String email;
    
    @Column(name = "phone")
    private String phone;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "priority_level")
    private Priority priorityLevel = Priority.LOW;
    
    @Column(name = "active")
    private boolean active = true;
    
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}