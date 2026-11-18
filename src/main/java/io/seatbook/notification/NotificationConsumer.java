package io.seatbook.notification;

import java.util.Objects;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class NotificationConsumer {
    private final JdbcClient db;
    private final JsonMapper json;

    NotificationConsumer(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    @RabbitListener(queues = MessagingConfig.QUEUE)
    @Transactional
    public void receive(Message message) {
        BookingEvent event;
        try {
            event = json.readValue(message.getBody(), BookingEvent.class);
        } catch (JacksonException ex) {
            throw new AmqpRejectAndDontRequeueException("Malformed booking event", ex);
        }
        if (event == null || event.eventId() == null || event.eventId().length() != 36
            || !Objects.equals(event.eventId(), message.getMessageProperties().getMessageId())
            || event.bookingId() <= 0 || event.userId() <= 0 || event.type() == null || event.occurredAt() == null) {
            throw new AmqpRejectAndDontRequeueException("Incomplete or mismatched booking event");
        }

        // Copy canonical event data from the outbox in this app's database.
        boolean exists = db.sql("SELECT event_id FROM booking_outbox WHERE event_id = :id")
            .param("id", event.eventId()).query(String.class).optional().isPresent();
        if (!exists) {
            throw new AmqpRejectAndDontRequeueException("Unknown booking event ID");
        }
        db.sql("""
            INSERT INTO booking_notifications (event_id, booking_id, user_id, event_type, occurred_at)
            SELECT o.event_id, o.booking_id, o.user_id, o.event_type, o.occurred_at
            FROM booking_outbox o WHERE o.event_id = :id
            ON DUPLICATE KEY UPDATE id = booking_notifications.id
            """).param("id", event.eventId()).update();
    }
}
