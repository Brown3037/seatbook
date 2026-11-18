package io.seatbook.booking;

import java.time.LocalTime;

public enum Period {
    MORNING(LocalTime.of(8, 0), LocalTime.of(12, 0)),
    AFTERNOON(LocalTime.of(13, 0), LocalTime.of(17, 0)),
    EVENING(LocalTime.of(18, 0), LocalTime.of(22, 0));

    private final LocalTime start;
    private final LocalTime end;

    Period(LocalTime start, LocalTime end) {
        this.start = start;
        this.end = end;
    }

    public LocalTime start() {
        return start;
    }

    public LocalTime end() {
        return end;
    }
}
