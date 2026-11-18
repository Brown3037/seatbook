package io.seatbook.booking;

import io.seatbook.common.ApiException;
import io.seatbook.notification.BookingOutbox;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BookingService {
    private static final String BOOKING_VIEW = """
        SELECT b.id, b.slot_id, b.study_day, b.period, b.created_at, b.cancelled_at,
               r.id AS room_id, r.name AS room_name, s.label AS seat_label
        FROM bookings b
        JOIN seat_slots slot ON slot.id = b.slot_id
        JOIN seats s ON s.id = slot.seat_id
        JOIN rooms r ON r.id = s.room_id
        """;

    private final JdbcClient db;
    private final ScheduleRules rules;
    private final BookingOutbox outbox;
    private final ApplicationEventPublisher events;

    BookingService(JdbcClient db, ScheduleRules rules, BookingOutbox outbox, ApplicationEventPublisher events) {
        this.db = db;
        this.rules = rules;
        this.outbox = outbox;
        this.events = events;
    }

    @Transactional
    public Booking create(long userId, long slotId) {
        SlotTime slot = db.sql("SELECT study_day, period FROM seat_slots WHERE id = :id")
            .param("id", slotId).query((rs, row) -> slotTime(rs)).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SLOT_NOT_FOUND", "座位时段不存在"));
        LocalDateTime now = rules.now();
        rules.requireInWindow(slot.date(), now.toLocalDate());
        requireNotStarted(slot, now);

        var key = new GeneratedKeyHolder();
        try {
            db.sql("""
                INSERT INTO bookings (slot_id, user_id, study_day, period, created_at)
                VALUES (:slotId, :userId, :day, :period, :createdAt)
                """).param("slotId", slotId).param("userId", userId).param("day", slot.date())
                .param("period", slot.period().name()).param("createdAt", now).update(key, "id");
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "BOOKING_CONFLICT",
                "该座位已被预约，或你在同一时段已有预约");
        }
        Booking booking = find(userId, key.getKey().longValue(), now);
        outbox.recordCreated(booking.id(), userId, now);
        reportAvailabilityChange(booking);
        return booking;
    }

    BookingPage history(long userId, Long beforeId, int size) {
        LocalDateTime now = rules.now();
        String cursorFilter = beforeId == null ? "" : " AND b.id < :beforeId";
        var query = db.sql(BOOKING_VIEW + " WHERE b.user_id = :userId" + cursorFilter
                + " ORDER BY b.id DESC LIMIT :limit")
            .param("userId", userId).param("limit", size + 1);
        if (beforeId != null) {
            query.param("beforeId", beforeId);
        }
        List<Booking> results = query.query((rs, row) -> view(rs, now)).list();
        if (results.size() <= size) {
            return new BookingPage(results, null);
        }
        return new BookingPage(results.subList(0, size), results.get(size - 1).id());
    }

    @Transactional
    public Booking cancel(long userId, long bookingId) {
        Cancellation current = db.sql("""
            SELECT study_day, period, cancelled_at
            FROM bookings
            WHERE id = :id AND user_id = :userId FOR UPDATE
            """).param("id", bookingId).param("userId", userId)
            .query((rs, row) -> new Cancellation(slotTime(rs), rs.getObject("cancelled_at", LocalDateTime.class)))
            .optional().orElseThrow(this::bookingNotFound);
        LocalDateTime now = rules.now();
        if (current.cancelledAt() == null) {
            requireNotStarted(current.slot(), now);
            db.sql("UPDATE bookings SET cancelled_at = :time WHERE id = :id")
                .param("time", now).param("id", bookingId).update();
        }
        Booking booking = find(userId, bookingId, now);
        if (current.cancelledAt() == null) {
            outbox.recordCancelled(booking.id(), userId, now);
            reportAvailabilityChange(booking);
        }
        return booking;
    }

    private void reportAvailabilityChange(Booking booking) {
        events.publishEvent(new SlotCache.ScheduleChanged(booking.roomId(), booking.date(), booking.period()));
    }

    private Booking find(long userId, long id, LocalDateTime now) {
        return db.sql(BOOKING_VIEW + " WHERE b.user_id = :userId AND b.id = :id")
            .param("userId", userId).param("id", id)
            .query((rs, row) -> view(rs, now)).optional().orElseThrow(this::bookingNotFound);
    }

    private Booking view(ResultSet rs, LocalDateTime now) throws SQLException {
        SlotTime slot = slotTime(rs);
        var cancelledAt = rs.getObject("cancelled_at", LocalDateTime.class);
        boolean canCancel = cancelledAt == null && !rules.hasStarted(slot.date(), slot.period(), now);
        return new Booking(rs.getLong("id"), rs.getLong("slot_id"), rs.getLong("room_id"), rs.getString("room_name"),
            rs.getString("seat_label"), slot.date(), slot.period(), cancelledAt == null ? "BOOKED" : "CANCELLED",
            rs.getObject("created_at", LocalDateTime.class), cancelledAt, canCancel);
    }

    private static SlotTime slotTime(ResultSet rs) throws SQLException {
        return new SlotTime(rs.getObject("study_day", LocalDate.class), Period.valueOf(rs.getString("period")));
    }

    private void requireNotStarted(SlotTime slot, LocalDateTime now) {
        if (rules.hasStarted(slot.date(), slot.period(), now)) {
            throw new ApiException(HttpStatus.CONFLICT, "SLOT_CLOSED", "该时段已开始，无法预约或取消");
        }
    }

    private ApiException bookingNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "预约不存在");
    }

    record Booking(long id, long slotId, long roomId, String roomName, String seatLabel, LocalDate date, Period period,
                   String status, LocalDateTime createdAt, LocalDateTime cancelledAt, boolean canCancel) {}
    record BookingPage(List<Booking> items, Long nextCursor) {}
    private record SlotTime(LocalDate date, Period period) {}
    private record Cancellation(SlotTime slot, LocalDateTime cancelledAt) {}
}
