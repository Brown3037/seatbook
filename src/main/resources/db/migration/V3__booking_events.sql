CREATE TABLE booking_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    published_at DATETIME(6),
    attempts INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_outbox_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT uq_outbox_booking_type UNIQUE (booking_id, event_type),
    INDEX ix_outbox_pending (published_at, next_attempt_at, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE booking_notifications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    booking_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_notification_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT uq_notification_event UNIQUE (event_id),
    INDEX ix_notifications_user_time (user_id, occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
