package io.seatbook.notification;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class MessagingConfig {
    static final String EXCHANGE = "seatbook.booking.events";
    static final String ROUTING_KEY = "booking.changed.v1";
    static final String QUEUE = "seatbook.booking.notifications.v1";
    static final String DEAD_LETTER_KEY = "booking.dead.v1";
    static final String DEAD_LETTER_QUEUE = "seatbook.booking.notifications.dead.v1";

    @Bean
    Declarables bookingTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange(EXCHANGE)
            .deadLetterRoutingKey(DEAD_LETTER_KEY).build();
        var dead = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        return new Declarables(exchange, queue, dead,
            BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY),
            BindingBuilder.bind(dead).to(exchange).with(DEAD_LETTER_KEY));
    }
}
