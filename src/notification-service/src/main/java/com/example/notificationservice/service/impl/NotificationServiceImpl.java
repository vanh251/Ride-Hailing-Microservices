package com.example.notificationservice.service.impl;

import com.example.notificationservice.dto.NotificationDto;
import com.example.notificationservice.dto.RecipientType;
import com.example.notificationservice.dto.SendNotificationRequest;
import com.example.notificationservice.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private static final int MAX_BUFFER_SIZE = 5000;
    private final ConcurrentLinkedDeque<NotificationDto> notificationBuffer = new ConcurrentLinkedDeque<>();

    @Override
    public void sendPassengerNotification(String passengerId, String tripId, String title,
                                          String message, String eventType, String correlationId) {
        dispatch(RecipientType.PASSENGER, passengerId, tripId, title, message, eventType, correlationId);
    }

    @Override
    public void sendDriverNotification(String driverId, String tripId, String title,
                                       String message, String eventType, String correlationId) {
        dispatch(RecipientType.DRIVER, driverId, tripId, title, message, eventType, correlationId);
    }

    @Override
    public List<NotificationDto> getRecentNotifications() {
        return new ArrayList<>(notificationBuffer);
    }

    @Override
    public List<NotificationDto> getNotificationsForPassenger(String passengerId) {
        return notificationBuffer.stream()
                .filter(n -> n.getRecipientType() == RecipientType.PASSENGER && passengerId.equals(n.getRecipientId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<NotificationDto> getNotificationsForDriver(String driverId) {
        return notificationBuffer.stream()
                .filter(n -> n.getRecipientType() == RecipientType.DRIVER && driverId.equals(n.getRecipientId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<NotificationDto> getNotificationsForTrip(String tripId) {
        return notificationBuffer.stream()
                .filter(n -> tripId != null && tripId.equals(n.getTripId()))
                .collect(Collectors.toList());
    }

    @Override
    public NotificationDto sendManualNotification(SendNotificationRequest request, String correlationId) {
        return dispatch(request.getRecipientType(), request.getRecipientId(), request.getTripId(),
                request.getTitle(), request.getMessage(), "ManualNotification", correlationId);
    }

    private NotificationDto dispatch(RecipientType recipientType, String recipientId, String tripId,
                                     String title, String message, String eventType, String correlationId) {
        String id = "notif-" + UUID.randomUUID().toString().substring(0, 8);
        String resolvedCorrelationId = (correlationId != null && !correlationId.isBlank()) ? correlationId : UUID.randomUUID().toString();
        Instant now = Instant.now();

        NotificationDto notification = NotificationDto.builder()
                .id(id)
                .recipientType(recipientType)
                .recipientId(recipientId)
                .tripId(tripId)
                .title(title)
                .message(message)
                .eventType(eventType)
                .correlationId(resolvedCorrelationId)
                .timestamp(now)
                .build();

        notificationBuffer.addFirst(notification);
        while (notificationBuffer.size() > MAX_BUFFER_SIZE) {
            notificationBuffer.pollLast();
        }

        log.info("[NOTIFICATION] to={}:{}, tripId={}, title=\"{}\", message=\"{}\", eventType={}, correlationId={}",
                recipientType, recipientId, tripId, title, message, eventType, resolvedCorrelationId);

        return notification;
    }
}
