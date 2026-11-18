package io.seatbook.booking;

import io.seatbook.common.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class ScheduleRules {
    private static final int LAST_BOOKABLE_DAY_OFFSET = 6;
    private final Clock clock;

    ScheduleRules(Clock clock) {
        this.clock = clock;
    }

    LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    Schedule schedule() {
        LocalDate today = LocalDate.now(clock);
        var periods = Arrays.stream(Period.values())
            .map(period -> new TimeBlock(period, period.start(), period.end()))
            .toList();
        return new Schedule(today, today.plusDays(LAST_BOOKABLE_DAY_OFFSET), clock.getZone().getId(), periods);
    }

    void requireInWindow(LocalDate day, LocalDate today) {
        if (day.isBefore(today) || day.isAfter(today.plusDays(LAST_BOOKABLE_DAY_OFFSET))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATE_OUT_OF_RANGE", "请选择今天起 7 天内的日期");
        }
    }

    boolean hasStarted(LocalDate day, Period period, LocalDateTime time) {
        return !time.isBefore(day.atTime(period.start()));
    }

    record Schedule(LocalDate today, LocalDate lastBookableDay, String timeZone, List<TimeBlock> periods) {}
    record TimeBlock(Period code, LocalTime start, LocalTime end) {}
}
