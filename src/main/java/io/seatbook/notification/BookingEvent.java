package io.seatbook.notification;

import java.time.LocalDateTime;

record BookingEvent(String eventId, long bookingId, long userId, EventType type, LocalDateTime occurredAt) {
    enum EventType {
        BOOKING_CREATED,
        BOOKING_CANCELLED
    }
}
