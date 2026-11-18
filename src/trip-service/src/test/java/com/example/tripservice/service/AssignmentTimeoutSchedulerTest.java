package com.example.tripservice.service;

import com.example.tripservice.entity.Trip;
import com.example.tripservice.entity.TripStatus;
import com.example.tripservice.repository.TripRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;

class AssignmentTimeoutSchedulerTest {
    @Test
    void checksEveryPendingTripForExpiry() {
        var repository = mock(TripRepository.class);
        var service = mock(TripService.class);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(repository.findByStatus(TripStatus.ASSIGNING)).thenReturn(List.of(
            Trip.builder().tripId(first).build(), Trip.builder().tripId(second).build()));
        new AssignmentTimeoutScheduler(repository, service).expirePendingAssignments();
        verify(service).expirePendingAssignment(first);
        verify(service).expirePendingAssignment(second);
    }
}
