package io.seatbook.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.sun.net.httpserver.HttpServer;
import io.seatbook.booking.AgentCatalog;
import io.seatbook.booking.Period;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SeatAgentTest {
    private static final LocalDate TODAY = LocalDate.of(2030, 4, 2);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AgentCatalog catalog = mock(AgentCatalog.class);

    @Test
    void springAiSendsTheToolResultBackToTheModelAndOptionsUseOnlyServerData() throws IOException {
        seedCatalog();
        try (var stub = new ModelStub(200, call -> call == 0
            ? tool("available-1", AgentToolbox.AVAILABILITY, Map.of("date", "2030-04-03", "period", "AFTERNOON"))
            : answer("999 empty seats and a bonus room"))) {
            var result = agent(stub).ask("明天下午哪里有空位？");
            assertThat(result.mode()).isEqualTo(AgentReply.Mode.MODEL);
            assertThat(result.toolsUsed()).containsExactly(AgentToolbox.AVAILABILITY);
            assertThat(result.options()).containsExactly(availability());
            assertThat(result.answer()).doesNotContain("999");
            assertThat(stub.requests).hasSize(2);

            var first = stub.requests.get(0);
            assertThat(first.path()).isEqualTo("/v1/chat/completions");
            assertThat(first.json().path("tools").size()).isEqualTo(3);
            assertThat(first.json().path("tools").toString())
                .contains(AgentToolbox.RULES, AgentToolbox.ROOMS, AgentToolbox.AVAILABILITY)
                .doesNotContain("create_booking", "cancel_booking");
            assertThat(stub.requests.get(1).json().path("messages").toString())
                .contains("available-1", "tool", "A01");
            verify(catalog).findAvailability(TOMORROW, Period.AFTERNOON, null);
        }
    }

    @Test
    void rejectsUnknownToolAndFallsBackToReadOnlyLocalReply() throws IOException {
        seedCatalog();
        try (var stub = new ModelStub(200, call -> tool("unexpected-1", "create_booking", Map.of("slotId", 555)))) {
            var result = agent(stub).ask("帮我直接代办预约");
            assertThat(result.mode()).isEqualTo(AgentReply.Mode.LOCAL);
            assertThat(result.toolsUsed()).isEmpty();
            assertThat(result.options()).isEmpty();
            assertThat(stub.requests).hasSize(1);
            verify(catalog, never()).findAvailability(any(), any(), any());
        }
    }

    @Test
    void repeatedToolRequestsAreStoppedAtThreeModelCalls() throws IOException {
        seedCatalog();
        try (var stub = new ModelStub(200, call -> tool("rules-" + call, AgentToolbox.RULES, Map.of()))) {
            var result = agent(stub).ask("预约规则是什么？");
            assertThat(result.mode()).isEqualTo(AgentReply.Mode.MODEL);
            assertThat(result.toolsUsed()).containsExactly(AgentToolbox.RULES);
            assertThat(result.answer()).contains("2030-04-02");
            assertThat(stub.requests).hasSize(3);
        }
    }

    @Test
    void providerFailureUsesLocalRulesAndStillReturnsActualAvailability() throws IOException {
        seedCatalog();
        try (var stub = new ModelStub(503, call -> "{\"error\":{\"message\":\"test provider offline\"}}")) {
            var result = agent(stub).ask("明天下午哪个自习室还有空位？");
            assertThat(result.mode()).isEqualTo(AgentReply.Mode.LOCAL);
            assertThat(result.options()).containsExactly(availability());
            assertThat(stub.requests).hasSize(1);
        }
    }

    private SeatAgent agent(ModelStub stub) {
        ChatModel model = new AgentModelConfig().seatbookChatModel(stub.baseUrl(), "local-test-key", "model-stub");
        var beans = new StaticListableBeanFactory(Map.of("chatModel", model));
        return new SeatAgent(catalog, new LocalSeatAssistant(catalog), beans.getBeanProvider(ChatModel.class));
    }

    private void seedCatalog() {
        var periods = List.of(new AgentCatalog.StudyPeriod(Period.AFTERNOON, LocalTime.of(13, 0), LocalTime.of(17, 0)));
        when(catalog.rules()).thenReturn(new AgentCatalog.Rules(TODAY, TODAY.plusDays(6), "Asia/Shanghai", periods,
            "每人同一时段可预约一个座位。"));
        when(catalog.listRooms()).thenReturn(List.of(new AgentCatalog.Room(1, "静阅区", "二层", 6)));
        when(catalog.findAvailability(TOMORROW, Period.AFTERNOON, null)).thenReturn(List.of(availability()));
    }

    private static AgentCatalog.Availability availability() {
        return new AgentCatalog.Availability(1, "静阅区", TOMORROW, Period.AFTERNOON, 2, List.of("A01", "A03"));
    }

    private static String tool(String id, String name, Map<String, ?> arguments) {
        return completion(Map.of(
            "role", "assistant",
            "tool_calls", List.of(Map.of("id", id, "type", "function",
                "function", Map.of("name", name, "arguments", JSON.writeValueAsString(arguments))))
        ), "tool_calls");
    }

    private static String answer(String text) {
        return completion(Map.of("role", "assistant", "content", text), "stop");
    }

    private static String completion(Map<String, ?> message, String finish) {
        return JSON.writeValueAsString(Map.of("id", "response-stub", "object", "chat.completion",
            "created", 1_890_000_000, "model", "model-stub",
            "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish))));
    }

    private record ModelRequest(String path, JsonNode json) {}

    private static final class ModelStub implements AutoCloseable {
        private final HttpServer server;
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

        ModelStub(int status, IntFunction<String> responses) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    requests.add(new ModelRequest(exchange.getRequestURI().getPath(), body));
                    byte[] response = responses.apply(requests.size() - 1).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
