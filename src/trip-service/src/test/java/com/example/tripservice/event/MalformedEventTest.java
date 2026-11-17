package com.example.tripservice.event;

import com.example.tripservice.service.TripService;
import com.example.tripservice.repository.ProcessedEventRepository;
import com.example.tripservice.exception.ValidationException;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MalformedEventTest {

    @Test
    void jsonNullGoesStraightToValidationDlq() {
        var service = mock(TripService.class);
        var processed = mock(ProcessedEventRepository.class);
        var record = new ConsumerRecord<String, String>("payment-events", 0, 0, "trip", "null");
        assertThrows(ValidationException.class,
                () -> new PaymentEventConsumer(service, processed, new ObjectMapper()).onPaymentEvent(record));
        assertThrows(ValidationException.class,
                () -> new DriverReservedConsumer(service, processed, new ObjectMapper()).handleDriverReserved(record));
        verifyNoInteractions(service, processed);
    }

}
