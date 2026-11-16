package com.example.driverservice.service;

import com.example.driverservice.entity.DriverStatus;
import com.example.driverservice.entity.ProcessedEvent;
import com.example.driverservice.repository.DriverRepository;
import com.example.driverservice.repository.ProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverTripEventServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("e0000000-0000-4000-8000-000000000003");
    private static final UUID TRIP_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID DRIVER_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");

    @Mock
    private DriverRepository driverRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private DriverTripEventService service;

    private ProcessedEvent savedEvent() {
        ArgumentCaptor<ProcessedEvent> saved = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("onDriverAssigned moves the driver to BUSY and records the event")
    void onDriverAssigned_newEvent_marksBusyAndRecordsEvent() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(driverRepository.markBusy(eq(DRIVER_ID), eq(TRIP_ID), any())).thenReturn(1);

        service.onDriverAssigned(EVENT_ID, TRIP_ID, DRIVER_ID);

        verify(driverRepository).markBusy(eq(DRIVER_ID), eq(TRIP_ID), any());
        ProcessedEvent recorded = savedEvent();
        assertEquals(EVENT_ID, recorded.getEventId());
        assertEquals("DriverAssigned", recorded.getEventType());
        assertNotNull(recorded.getProcessedAt());
    }

    @Test
    @DisplayName("onDriverAssigned with no matching reservation still records the event, so it is not processed again")
    void onDriverAssigned_noMatchingReservation_recordsEvent() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(driverRepository.markBusy(eq(DRIVER_ID), eq(TRIP_ID), any())).thenReturn(0);

        service.onDriverAssigned(EVENT_ID, TRIP_ID, DRIVER_ID);

        assertEquals("DriverAssigned", savedEvent().getEventType());
    }

    @Test
    @DisplayName("onTripCompleted releases only a BUSY driver of the trip and records the event")
    void onTripCompleted_newEvent_releasesBusyDriverAndRecordsEvent() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(driverRepository.releaseForTrip(eq(TRIP_ID), any(), any())).thenReturn(1);

        service.onTripCompleted(EVENT_ID, TRIP_ID);

        verify(driverRepository).releaseForTrip(eq(TRIP_ID), eq(List.of(DriverStatus.BUSY)), any());
        assertEquals("TripCompleted", savedEvent().getEventType());
    }

    @Test
    @DisplayName("onTripCancelled releases a RESERVED or BUSY driver of the trip and records the event")
    void onTripCancelled_newEvent_releasesReservedOrBusyDriverAndRecordsEvent() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(driverRepository.releaseForTrip(eq(TRIP_ID), any(), any())).thenReturn(0);

        service.onTripCancelled(EVENT_ID, TRIP_ID);

        verify(driverRepository).releaseForTrip(eq(TRIP_ID), eq(List.of(DriverStatus.RESERVED, DriverStatus.BUSY)), any());
        assertEquals("TripCancelled", savedEvent().getEventType());
    }

    @Test
    @DisplayName("An event that was already processed changes nothing")
    void onEvent_duplicateEventId_skipsWithoutTouchingDrivers() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        service.onDriverAssigned(EVENT_ID, TRIP_ID, DRIVER_ID);
        service.onTripCompleted(EVENT_ID, TRIP_ID);
        service.onTripCancelled(EVENT_ID, TRIP_ID);

        verifyNoInteractions(driverRepository);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A failed update is not recorded as processed, so the retry runs it again")
    void onTripCompleted_updateFails_doesNotRecordEvent() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(driverRepository.releaseForTrip(eq(TRIP_ID), any(), any())).thenThrow(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, () -> service.onTripCompleted(EVENT_ID, TRIP_ID));

        verify(processedEventRepository, never()).save(any());
    }
}
