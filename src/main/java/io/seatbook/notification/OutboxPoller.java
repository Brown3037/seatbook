package io.seatbook.notification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class OutboxPoller {
    private final OutboxDispatcher dispatcher;

    OutboxPoller(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${seatbook.outbox-poll-delay}")
    void dispatchBatch() {
        for (int i = 0; i < 10; i++) {
            if (!dispatcher.dispatchOne()) {
                return;
            }
        }
    }
}
