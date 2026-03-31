package com.medtech.notification.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.medtech.notification.model.NotificationLog;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findByPatientIdOrderByCreatedAtDesc(String patientId);
    long countByStatus(com.medtech.notification.model.enums.NotificationStatus status);
}