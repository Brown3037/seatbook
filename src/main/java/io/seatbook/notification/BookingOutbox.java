package io.seatbook.notification;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BookingOutbox {
    private final JdbcClient db;

    BookingOutbox(JdbcClient db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreated(long bookingId, long userId, LocalDateTime occurredAt) {
        append(bookingId, userId, BookingEvent.EventType.BOOKING_CREATED, occurredAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCancelled(long bookingId, long userId, LocalDateTime occurredAt) {
        append(bookingId, userId, BookingEvent.EventType.BOOKING_CANCELLED, occurredAt);
    }

    private void append(long bookingId, long userId, BookingEvent.EventType type, LocalDateTime occurredAt) {
        db.sql("""
            INSERT INTO booking_outbox (event_id, booking_id, user_id, event_type, occurred_at, next_attempt_at)
            VALUES (:eventId, :bookingId, :userId, :type, :occurredAt, UTC_TIMESTAMP(6))
            """).param("eventId", UUID.randomUUID().toString()).param("bookingId", bookingId)
            .param("userId", userId).param("type", type.name()).param("occurredAt", occurredAt).update();
    }

    Optional<PendingEvent> nextReady() {
        return db.sql("""
            SELECT event_id, booking_id, user_id, event_type, occurred_at, attempts
            FROM booking_outbox
            WHERE published_at IS NULL AND next_attempt_at <= UTC_TIMESTAMP(6)
            ORDER BY next_attempt_at, event_id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """).query((rs, row) -> new PendingEvent(new BookingEvent(
                rs.getString("event_id"), rs.getLong("booking_id"), rs.getLong("user_id"),
                BookingEvent.EventType.valueOf(rs.getString("event_type")),
                rs.getObject("occurred_at", LocalDateTime.class)), rs.getInt("attempts")))
            .optional();
    }

    void markPublished(String eventId) {
        db.sql("UPDATE booking_outbox SET published_at = UTC_TIMESTAMP(6) WHERE event_id = :eventId")
            .param("eventId", eventId).update();
    }

    void defer(String eventId, long delaySeconds) {
        db.sql("""
            UPDATE booking_outbox
            SET attempts = attempts + 1, next_attempt_at = TIMESTAMPADD(SECOND, :delay, UTC_TIMESTAMP(6))
            WHERE event_id = :eventId
            """).param("delay", delaySeconds).param("eventId", eventId).update();
    }

    record PendingEvent(BookingEvent event, int attempts) {}
}
