package io.seatbook.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "账号")
class AuthController {
    private final AuthService service;

    AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthService.Account register(@RequestBody @Valid Credentials request) {
        return service.register(request.username(), request.password());
    }

    @PostMapping("/login")
    AuthService.Token login(@RequestBody @Valid Credentials request) {
        return service.login(request.username(), request.password());
    }

    record Credentials(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_]{3,32}") String username,
        @NotNull @Size(min = 10, max = 64) String password
    ) {}
}
