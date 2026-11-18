package io.seatbook.agent;

import io.seatbook.booking.AgentCatalog;
import java.util.List;

record AgentReply(Mode mode, String answer, List<AgentCatalog.Availability> options, List<String> toolsUsed) {
    enum Mode {
        LOCAL, MODEL
    }
}
