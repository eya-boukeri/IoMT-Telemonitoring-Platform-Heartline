package com.medtech.notification.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.medtech.notification.model.Alert;
import com.medtech.notification.model.NotificationRecipient;
import com.medtech.notification.model.enums.Priority;
import com.medtech.notification.repository.NotificationRecipientRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RecipientService {

    @Autowired
    private NotificationRecipientRepository recipientRepository;

    @Value("${notification.default.doctor.email}")
    private String defaultDoctorEmail;

    @Value("${notification.default.emergency.phone}")
    private String defaultEmergencyPhone;

    public List<NotificationRecipient> getRecipientsForAlert(Alert alert) {
        List<NotificationRecipient> recipients = recipientRepository
            .findByPatientIdAndActiveTrueAndPriorityLevelLessThanEqual(
                alert.getPatientId(), alert.getPriority());
        
        if (recipients.isEmpty()) {
            recipients = getDefaultRecipients(alert.getPatientId());
        }
        return recipients;
    }

    private List<NotificationRecipient> getDefaultRecipients(String patientId) {
        List<NotificationRecipient> defaults = new ArrayList<>();
        
        NotificationRecipient doctor = new NotificationRecipient();
        doctor.setPatientId(patientId);
        doctor.setRecipientType("DOCTOR");
        doctor.setEmail(defaultDoctorEmail);
        doctor.setPriorityLevel(Priority.MEDIUM);
        doctor.setActive(true);
        defaults.add(doctor);
        
        return defaults;
    }
}