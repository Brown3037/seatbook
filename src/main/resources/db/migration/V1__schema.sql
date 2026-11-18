CREATE TABLE accounts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    role VARCHAR(12) NOT NULL,
    CONSTRAINT uq_accounts_username UNIQUE (username),
    CONSTRAINT ck_accounts_role CHECK (role IN ('STUDENT', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rooms (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    location VARCHAR(120) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE seats (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    label VARCHAR(16) NOT NULL,
    CONSTRAINT uq_seats_room_label UNIQUE (room_id, label),
    CONSTRAINT fk_seats_room FOREIGN KEY (room_id) REFERENCES rooms (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE seat_slots (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    seat_id BIGINT NOT NULL,
    study_day DATE NOT NULL,
    period VARCHAR(12) NOT NULL,
    CONSTRAINT uq_slots_seat_period UNIQUE (seat_id, study_day, period),
    CONSTRAINT uq_slots_reference UNIQUE (id, study_day, period),
    CONSTRAINT fk_slots_seat FOREIGN KEY (seat_id) REFERENCES seats (id),
    CONSTRAINT ck_slots_period CHECK (period IN ('MORNING', 'AFTERNOON', 'EVENING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bookings (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    slot_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    study_day DATE NOT NULL,
    period VARCHAR(12) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    cancelled_at DATETIME(6),
    active_slot_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN cancelled_at IS NULL THEN slot_id ELSE NULL END
    ) STORED,
    active_user_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN cancelled_at IS NULL THEN user_id ELSE NULL END
    ) STORED,
    CONSTRAINT fk_bookings_slot FOREIGN KEY (slot_id, study_day, period)
        REFERENCES seat_slots (id, study_day, period),
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES accounts (id),
    CONSTRAINT uq_bookings_active_slot UNIQUE (active_slot_id),
    CONSTRAINT uq_bookings_active_user_period UNIQUE (active_user_id, study_day, period),
    INDEX ix_bookings_user_history (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
