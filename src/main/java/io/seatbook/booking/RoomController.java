package io.seatbook.booking;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "自习室查询")
class RoomController {
    private final RoomService rooms;
    private final ScheduleRules schedule;

    RoomController(RoomService rooms, ScheduleRules schedule) {
        this.rooms = rooms;
        this.schedule = schedule;
    }

    @GetMapping("/api/schedule")
    ScheduleRules.Schedule schedule() {
        return schedule.schedule();
    }

    @GetMapping("/api/rooms")
    List<RoomService.Room> rooms() {
        return rooms.listRooms();
    }

    @GetMapping("/api/rooms/{roomId}/slots")
    List<RoomService.Slot> slots(@PathVariable @Positive long roomId,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                 @RequestParam Period period) {
        return rooms.listSlots(roomId, date, period);
    }
}
