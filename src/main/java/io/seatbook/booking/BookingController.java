package io.seatbook.booking;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
@Tag(name = "座位预约")
@SecurityRequirement(name = "bearerAuth")
class BookingController {
    private final BookingService bookings;

    BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BookingService.Booking create(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                  @RequestBody @Valid CreateBooking request) {
        return bookings.create(userId(jwt), request.slotId());
    }

    @GetMapping
    BookingService.BookingPage history(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(required = false) @Positive Long beforeId,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return bookings.history(userId(jwt), beforeId, size);
    }

    @PutMapping("/{bookingId}/cancellation")
    BookingService.Booking cancel(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                  @PathVariable @Positive long bookingId) {
        return bookings.cancel(userId(jwt), bookingId);
    }

    private long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    record CreateBooking(@NotNull @Positive Long slotId) {}
}
