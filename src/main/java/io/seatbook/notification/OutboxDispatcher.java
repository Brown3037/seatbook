package io.seatbook.notification;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final BookingOutbox outbox;
    private final RabbitTemplate rabbit;
    private final JsonMapper json;

    OutboxDispatcher(BookingOutbox outbox, RabbitTemplate rabbit, JsonMapper json) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean dispatchOne() {
        var pending = outbox.nextReady();
        if (pending.isEmpty()) {
            return false;
        }
        var event = pending.get().event();
        if (publish(event)) {
            outbox.markPublished(event.eventId());
            return true;
        }

        // Retry indefinitely with capped backoff. Reusing eventId makes redelivery safe.
        long delay = Math.min(60, 1L << Math.min(pending.get().attempts(), 6));
        outbox.defer(event.eventId(), delay);
        return false;
    }

    private boolean publish(BookingEvent event) {
        var message = MessageBuilder.withBody(json.writeValueAsBytes(event))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setMessageId(event.eventId())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build();
        var correlation = new CorrelationData();
        try {
            rabbit.send(MessagingConfig.EXCHANGE, MessagingConfig.ROUTING_KEY, message, correlation);
            var confirm = correlation.getFuture().get(2, TimeUnit.SECONDS);
            if (confirm.ack() && correlation.getReturned() == null) {
                return true;
            }
            log.warn("Broker did not route or accept booking event {}", event.eventId());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (AmqpException | ExecutionException | TimeoutException ex) {
            log.warn("Booking event {} remains in the outbox: {}", event.eventId(), ex.getClass().getSimpleName());
        }
        return false;
    }
}
