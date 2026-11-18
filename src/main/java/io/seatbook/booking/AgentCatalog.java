package io.seatbook.booking;

import io.seatbook.common.ApiException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The assistant can read this catalog; reservation commands are outside its interface. */
@Service
public class AgentCatalog {
    private final RoomService rooms;
    private final ScheduleRules schedule;

    AgentCatalog(RoomService rooms, ScheduleRules schedule) {
        this.rooms = rooms;
        this.schedule = schedule;
    }

    public Rules rules() {
        var current = schedule.schedule();
        var periods = current.periods().stream()
            .map(period -> new StudyPeriod(period.code(), period.start(), period.end()))
            .toList();
        return new Rules(current.today(), current.lastBookableDay(), current.timeZone(), periods,
            "同一学生同一天的同一时段最多预约一个座位。时段开始前才可预约和取消。");
    }

    public List<Room> listRooms() {
        return rooms.listRooms().stream().limit(8)
            .map(room -> new Room(room.id(), room.name(), room.location(), room.seatCount())).toList();
    }

    public List<Availability> findAvailability(LocalDate date, Period period, Long roomId) {
        if (date == null || period == null || (roomId != null && roomId <= 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_QUERY", "请提供有效的日期、自习室和时段");
        }
        schedule.requireInWindow(date, schedule.now().toLocalDate());
        List<Room> candidates = listRooms().stream()
            .filter(room -> roomId == null || room.id() == roomId).toList();
        if (roomId != null && candidates.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "未找到该自习室");
        }
        return candidates.stream().map(room -> {
            var open = rooms.listSlots(room.id(), date, period).stream().filter(RoomService.Slot::available).toList();
            var examples = open.stream().map(RoomService.Slot::seatLabel).limit(3).toList();
            return new Availability(room.id(), room.name(), date, period, open.size(), examples);
        }).toList();
    }

    public record Rules(LocalDate today, LocalDate lastBookableDay, String timeZone, List<StudyPeriod> periods,
                        String bookingPolicy) {}
    public record StudyPeriod(Period code, LocalTime start, LocalTime end) {}
    public record Room(long id, String name, String location, int seatCount) {}
    public record Availability(long roomId, String roomName, LocalDate date, Period period, int availableCount,
                               List<String> sampleSeats) {}
}
