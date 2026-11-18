package io.seatbook.booking;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "管理员")
@SecurityRequirement(name = "bearerAuth")
class AdminController {
    private final RoomService rooms;

    AdminController(RoomService rooms) {
        this.rooms = rooms;
    }

    @PutMapping("/api/admin/rooms/{roomId}/slots")
    List<RoomService.Slot> publish(@PathVariable @Positive long roomId, @RequestBody @Valid PublishSlots request) {
        return rooms.publish(roomId, request.date(), request.period());
    }

    record PublishSlots(@NotNull LocalDate date, @NotNull Period period) {}
}
