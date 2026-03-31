package com.medtech.notification.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.medtech.notification.model.NotificationRecipient;
import com.medtech.notification.model.enums.Priority;

@Repository
public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, Long> {
    List<NotificationRecipient> findByPatientIdAndActiveTrue(String patientId);
    List<NotificationRecipient> findByPatientIdAndActiveTrueAndPriorityLevelLessThanEqual(
        String patientId, Priority priority);
}