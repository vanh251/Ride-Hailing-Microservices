package com.example.driverservice.exception;

/**
 * A Kafka event is malformed or misses a required field (the ValidationException of docs/events.md §8).
 * Retrying cannot help, so the consumer error handler sends the record straight to the DLQ.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
