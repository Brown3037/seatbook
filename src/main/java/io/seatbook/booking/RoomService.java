package io.seatbook.booking;

import io.seatbook.common.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RoomService {
    private final JdbcClient db;
    private final ScheduleRules rules;
    private final SlotCache cache;
    private final ApplicationEventPublisher events;

    RoomService(JdbcClient db, ScheduleRules rules, SlotCache cache, ApplicationEventPublisher events) {
        this.db = db;
        this.rules = rules;
        this.cache = cache;
        this.events = events;
    }

    List<Room> listRooms() {
        return db.sql("""
            SELECT r.id, r.name, r.location, COUNT(s.id) AS seat_count
            FROM rooms r LEFT JOIN seats s ON s.room_id = r.id
            GROUP BY r.id, r.name, r.location
            ORDER BY r.id
            """).query((rs, row) -> new Room(
                rs.getLong("id"), rs.getString("name"), rs.getString("location"), rs.getInt("seat_count")))
            .list();
    }

    List<Slot> listSlots(long roomId, LocalDate date, Period period) {
        LocalDateTime now = rules.now();
        rules.requireInWindow(date, now.toLocalDate());
        if (rules.hasStarted(date, period, now)) {
            requireRoom(roomId);
            return readSlots(roomId, date, period, now);
        }
        List<Slot> slots = cache.get(new SlotCache.ScheduleChanged(roomId, date, period), () -> {
            requireRoom(roomId);
            return readSlots(roomId, date, period, now);
        });
        if (rules.hasStarted(date, period, rules.now())) {
            return slots.stream().map(slot -> new Slot(slot.id(), slot.seatLabel(), date, period, false)).toList();
        }
        return slots;
    }

    @Transactional
    public List<Slot> publish(long roomId, LocalDate date, Period period) {
        LocalDateTime now = rules.now();
        rules.requireInWindow(date, now.toLocalDate());
        if (rules.hasStarted(date, period, now)) {
            throw new ApiException(HttpStatus.CONFLICT, "SLOT_CLOSED", "该时段已开始");
        }
        requireRoom(roomId);
        db.sql("""
            INSERT INTO seat_slots (seat_id, study_day, period)
            SELECT s.id, :day, :period FROM seats s WHERE s.room_id = :roomId ORDER BY s.id
            ON DUPLICATE KEY UPDATE id = seat_slots.id
            """).param("day", date).param("period", period.name()).param("roomId", roomId).update();
        events.publishEvent(new SlotCache.ScheduleChanged(roomId, date, period));
        return readSlots(roomId, date, period, now);
    }

    private void requireRoom(long roomId) {
        boolean exists = db.sql("SELECT id FROM rooms WHERE id = :id")
            .param("id", roomId).query(Long.class).optional().isPresent();
        if (!exists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "自习室不存在");
        }
    }

    private List<Slot> readSlots(long roomId, LocalDate date, Period period, LocalDateTime now) {
        boolean started = rules.hasStarted(date, period, now);
        return db.sql("""
            SELECT slot.id, s.label, b.id IS NOT NULL AS booked
            FROM seats s
            JOIN seat_slots slot ON slot.seat_id = s.id
            LEFT JOIN bookings b ON b.active_slot_id = slot.id
            WHERE s.room_id = :roomId AND slot.study_day = :day AND slot.period = :period
            ORDER BY s.label
            """).param("roomId", roomId).param("day", date).param("period", period.name())
            .query((rs, row) -> new Slot(rs.getLong("id"), rs.getString("label"), date, period,
                !started && !rs.getBoolean("booked")))
            .list();
    }

    record Room(long id, String name, String location, int seatCount) {}
    record Slot(long id, String seatLabel, LocalDate date, Period period, boolean available) {}
}
