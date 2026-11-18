package io.seatbook.notification;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "预约通知")
@SecurityRequirement(name = "bearerAuth")
class NotificationController {
    private final JdbcClient db;

    NotificationController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping("/api/notifications")
    List<Notification> recent(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return db.sql("""
            SELECT n.id, n.booking_id, n.event_type, n.occurred_at, r.name AS room_name, s.label AS seat_label
            FROM booking_notifications n
            JOIN bookings b ON b.id = n.booking_id
            JOIN seat_slots slot ON slot.id = b.slot_id
            JOIN seats s ON s.id = slot.seat_id
            JOIN rooms r ON r.id = s.room_id
            WHERE n.user_id = :userId
            ORDER BY n.occurred_at DESC, n.id DESC LIMIT 20
            """).param("userId", Long.parseLong(jwt.getSubject()))
            .query((rs, row) -> new Notification(rs.getLong("id"), rs.getLong("booking_id"),
                BookingEvent.EventType.valueOf(rs.getString("event_type")), rs.getString("room_name"),
                rs.getString("seat_label"), rs.getObject("occurred_at", LocalDateTime.class))).list();
    }

    record Notification(long id, long bookingId, BookingEvent.EventType type, String roomName, String seatLabel,
                        LocalDateTime occurredAt) {}
}
