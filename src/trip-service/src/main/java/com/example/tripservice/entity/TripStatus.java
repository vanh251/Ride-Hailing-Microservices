package com.example.tripservice.entity;

public enum TripStatus {
    REQUESTED,
    SEARCHING,
    ASSIGNING,
    ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    PAID,
    PAYMENT_FAILED,
    CANCELLED;

    public boolean canTransitionTo(TripStatus nextStatus) {
        if (nextStatus == null) {
            return false;
        }
        switch (this) {
            case REQUESTED:
                return nextStatus == SEARCHING || nextStatus == CANCELLED;
            case SEARCHING:
                return nextStatus == ASSIGNING || nextStatus == CANCELLED;
            case ASSIGNING:
                return nextStatus == ASSIGNED || nextStatus == SEARCHING || nextStatus == CANCELLED;
            case ASSIGNED:
                return nextStatus == ASSIGNED || nextStatus == IN_PROGRESS || nextStatus == CANCELLED;
            case IN_PROGRESS:
                return nextStatus == COMPLETED;
            case COMPLETED:
                return nextStatus == PAID || nextStatus == PAYMENT_FAILED;
            case PAID:
            case PAYMENT_FAILED:
            case CANCELLED:
            default:
                return false;
        }
    }
}
