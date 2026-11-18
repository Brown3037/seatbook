package io.seatbook.agent;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "智能预约助手")
@SecurityRequirement(name = "bearerAuth")
class AgentController {
    private final SeatAgent assistant;

    AgentController(SeatAgent assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/api/agent/ask")
    AgentReply ask(@RequestBody @Valid Question request) {
        return assistant.ask(request.message().strip());
    }

    record Question(@NotBlank @Size(max = 300) String message) {}
}
