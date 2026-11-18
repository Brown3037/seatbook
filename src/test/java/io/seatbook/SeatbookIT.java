package io.seatbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {SeatbookApplication.class, SeatbookIT.TestClockConfiguration.class})
class SeatbookIT {
    private static final String PASSWORD = "StudentTest-2026";
    private static final String ADMIN_PASSWORD = "AdminTest-2026";
    private static final LocalDate TODAY = LocalDate.of(2030, 4, 2);
    private static final Instant BEFORE_MORNING = Instant.parse("2030-04-01T23:55:00Z");

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient db;
    @Autowired
    TestClock testClock;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    AmqpAdmin rabbitAdmin;
    @Autowired
    JsonMapper json;

    private static final String EXCHANGE = "seatbook.booking.events";
    private static final String ROUTE = "booking.changed.v1";
    private static final String QUEUE = "seatbook.booking.notifications.v1";
    private static final String DEAD_LETTER_QUEUE = "seatbook.booking.notifications.dead.v1";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        String url = System.getenv("SEATBOOK_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?")) {
            throw new IllegalStateException("Set SEATBOOK_TEST_DB_URL to a dedicated MySQL schema ending in _test");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("SEATBOOK_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("SEATBOOK_TEST_DB_PASSWORD"));
        properties.add("seatbook.jwt-secret", () -> "dGVzdC1vbmx5LWtleS1zZWF0Ym9vay1pbnRlZ3JhdGlvbi0yMDI2");
        properties.add("seatbook.admin-password", () -> ADMIN_PASSWORD);
    }

    @BeforeEach
    void resetTime() {
        testClock.now.set(BEFORE_MORNING);
    }

    @Test
    void publicUiHealthAndOpenApiAreReachable() throws Exception {
        assertThat(request("GET", "/", null, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/app.js", null, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/swagger-ui/index.html", null, null).statusCode()).isEqualTo(200);
        var spec = request("GET", "/v3/api-docs", null, null);
        assertThat(spec.statusCode()).isEqualTo(200);
        assertThat(spec.body()).contains("/api/admin/rooms/{roomId}/slots", "bearerAuth", "/api/bookings");
        var health = request("GET", "/actuator/health", null, null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat((String) value(health, "$.status")).isEqualTo("UP");
    }

    @Test
    void roleChecksAndRegistrationCannotCreateAnAdmin() throws Exception {
        assertThat(request("GET", "/api/bookings", null, null).statusCode()).isEqualTo(401);
        var rejectedToken = request("GET", "/api/bookings", "invalid-token", null);
        assertThat(rejectedToken.statusCode()).isEqualTo(401);
        assertThat(request("POST", "/api/auth/register", null, credentials("ADMIN", PASSWORD)).statusCode()).isEqualTo(409);
        assertThat(request("POST", "/api/auth/login", null, credentials("admin", PASSWORD)).statusCode()).isEqualTo(401);

        String username = username();
        var registered = request("POST", "/api/auth/register", null, credentials(username, PASSWORD));
        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat((String) value(registered, "$.role")).isEqualTo("STUDENT");
        String hash = db.sql("SELECT password_hash FROM accounts WHERE username = :username")
            .param("username", username).query(String.class).single();
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(request("POST", "/api/auth/register", null, credentials(username.toUpperCase(java.util.Locale.ROOT), PASSWORD))
            .statusCode()).isEqualTo(409);

        String regular = login(username, PASSWORD);
        long room = room();
        assertThat(request("PUT", adminPath(room), regular, slotBody(TODAY)).statusCode()).isEqualTo(403);
        String admin = login("admin", ADMIN_PASSWORD);
        assertThat(request("POST", "/api/bookings", admin, "{\"slotId\":1}").statusCode()).isEqualTo(403);
    }

    @Test
    void publishingIsIdempotentAndValidatesTheBookingWindow() throws Exception {
        String admin = login("admin", ADMIN_PASSWORD);
        long room = room();
        var first = publish(admin, room, TODAY);
        var second = publish(admin, room, TODAY);
        assertThat(first).hasSize(2).isEqualTo(second);
        assertThat(request("GET", slotsPath(room, TODAY), null, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", slotsPath(room, TODAY.plusDays(7)), null, null).statusCode()).isEqualTo(400);
        assertThat(request("PUT", adminPath(room), admin, slotBody(TODAY.minusDays(1))).statusCode()).isEqualTo(400);
        assertThat(request("PUT", adminPath(room), admin, "{}").statusCode()).isEqualTo(400);
    }

    @Test
    void onlyOneOfEightConcurrentRequestsCanBookTheSameSeat() throws Exception {
        long room = room();
        long slot = publish(login("admin", ADMIN_PASSWORD), room, TODAY).get(0);
        var tasks = new ArrayList<Callable<HttpResponse<String>>>();
        for (int i = 0; i < 8; i++) {
            String token = student();
            tasks.add(() -> book(token, slot));
        }
        var results = race(tasks);
        assertThat(results.stream().map(HttpResponse::statusCode).toList())
            .containsExactlyInAnyOrder(201, 409, 409, 409, 409, 409, 409, 409);
        assertThat(db.sql("SELECT COUNT(*) FROM bookings WHERE active_slot_id = :id")
            .param("id", slot).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void oneStudentCannotReserveDifferentSeatsInTheSamePeriod() throws Exception {
        long room = room();
        var slots = publish(login("admin", ADMIN_PASSWORD), room, TODAY);
        String token = student();
        var first = slots.get(0);
        var second = slots.get(1);
        var results = race(List.of(() -> book(token, first), () -> book(token, second)));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void cancellationIsPrivateIdempotentAndFreesTheSeat() throws Exception {
        long room = room();
        long slot = publish(login("admin", ADMIN_PASSWORD), room, TODAY).get(0);
        String owner = student();
        String other = student();
        var created = book(owner, slot);
        assertThat(created.statusCode()).isEqualTo(201);
        long booking = ((Number) value(created, "$.id")).longValue();
        String path = "/api/bookings/" + booking + "/cancellation";
        assertThat(request("PUT", path, other, null).statusCode()).isEqualTo(404);
        assertThat((List<?>) value(request("GET", "/api/bookings", other, null), "$.items")).isEmpty();
        assertThat(book(other, slot).statusCode()).isEqualTo(409);
        assertThat((Boolean) value(request("GET", slotsPath(room, TODAY), null, null), "$[0].available")).isFalse();
        var cancelled = request("PUT", path, owner, null);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat((String) value(cancelled, "$.cancelledAt")).isNotBlank();
        testClock.now.set(BEFORE_MORNING.plusSeconds(30));
        var repeated = request("PUT", path, owner, null);
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat((String) value(repeated, "$.cancelledAt")).isEqualTo((String) value(cancelled, "$.cancelledAt"));
        assertThat((String) value(repeated, "$.status")).isEqualTo("CANCELLED");
        assertThat((Boolean) value(request("GET", slotsPath(room, TODAY), null, null), "$[0].available")).isTrue();
        assertThat(book(other, slot).statusCode()).isEqualTo(201);
    }

    @Test
    void historyCursorReturnsDistinctPages() throws Exception {
        long room = room();
        String admin = login("admin", ADMIN_PASSWORD);
        String student = student();
        long firstSlot = publish(admin, room, TODAY).get(0);
        long secondSlot = publish(admin, room, TODAY.plusDays(1)).get(0);
        var first = book(student, firstSlot);
        var second = book(student, secondSlot);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        var pageOne = request("GET", "/api/bookings?size=1", student, null);
        long cursor = ((Number) value(pageOne, "$.nextCursor")).longValue();
        assertThat(cursor).isEqualTo(((Number) value(second, "$.id")).longValue());
        var pageTwo = request("GET", "/api/bookings?size=1&beforeId=" + cursor, student, null);
        assertThat((Number) value(pageTwo, "$.items[0].id")).isEqualTo((Number) value(first, "$.id"));
        assertThat((Object) value(pageTwo, "$.nextCursor")).isNull();
    }

    @Test
    void theBookingAndCancellationCutoffIsExactlyThePeriodStart() throws Exception {
        long room = room();
        var slots = publish(login("admin", ADMIN_PASSWORD), room, TODAY);
        String student = student();
        var created = book(student, slots.get(0));
        assertThat(created.statusCode()).isEqualTo(201);
        long booking = ((Number) value(created, "$.id")).longValue();

        testClock.now.set(Instant.parse("2030-04-02T00:00:00Z"));
        var closed = book(student, slots.get(1));
        assertThat(closed.statusCode()).isEqualTo(409);
        assertThat((String) value(closed, "$.code")).isEqualTo("SLOT_CLOSED");
        assertThat(request("PUT", "/api/bookings/" + booking + "/cancellation", student, null).statusCode()).isEqualTo(409);
    }

    @Test
    void redisServesTheSnapshotAndACommittedBookingInvalidatesIt() throws Exception {
        long room = room();
        var slots = publish(login("admin", ADMIN_PASSWORD), room, TODAY);
        String path = slotsPath(room, TODAY);
        String prefix = "seatbook:v1:slots:" + room + ":" + TODAY + ":MORNING";
        var first = request("GET", path, null, null);
        assertThat((String) value(first, "$[0].seatLabel")).isEqualTo("T01");
        String revision = redis.opsForValue().get(prefix + ":revision");
        assertThat(revision).isNotBlank();
        assertThat(redis.getExpire(prefix + ":snapshot:" + revision)).isBetween(1L, 15L);

        // Updating a fixture directly bypasses the app, so a read should still return the cached label.
        db.sql("UPDATE seats SET label = 'T00' WHERE room_id = :room AND label = 'T01'")
            .param("room", room).update();
        assertThat((String) value(request("GET", path, null, null), "$[0].seatLabel")).isEqualTo("T01");
        var booked = book(student(), slots.get(0));
        assertThat(booked.statusCode()).isEqualTo(201);
        assertThat(redis.opsForValue().get(prefix + ":revision")).isNotEqualTo(revision);

        var refreshed = request("GET", path, null, null);
        assertThat((String) value(refreshed, "$[0].seatLabel")).isEqualTo("T00");
        assertThat((Boolean) value(refreshed, "$[0].available")).isFalse();
        assertThat((Boolean) value(refreshed, "$[1].available")).isTrue();
        testClock.now.set(Instant.parse("2030-04-02T00:00:00Z"));
        assertThat((Boolean) value(request("GET", path, null, null), "$[1].available")).isFalse();
    }

    @Test
    void rabbitRedeliveryKeepsOneNotificationPerEventAndOnlyTheOwnerSeesIt() throws Exception {
        long room = room();
        long slot = publish(login("admin", ADMIN_PASSWORD), room, TODAY).get(0);
        String owner = student();
        String other = student();
        var created = book(owner, slot);
        assertThat(created.statusCode()).isEqualTo(201);
        long booking = ((Number) value(created, "$.id")).longValue();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(notificationTypes(owner)).containsExactly("BOOKING_CREATED");
            assertThat(published(booking)).isEqualTo(1);
        });
        var replay = db.sql("""
            SELECT event_id, user_id, event_type, occurred_at FROM booking_outbox WHERE booking_id = :booking
            """).param("booking", booking).query((rs, row) -> Map.of(
                "eventId", rs.getString("event_id"), "bookingId", booking, "userId", rs.getLong("user_id"),
                "type", rs.getString("event_type"), "occurredAt", rs.getObject("occurred_at", LocalDateTime.class)))
            .single();
        String eventId = (String) replay.get("eventId");
        sendConfirmed(json.writeValueAsString(replay), eventId);
        sendConfirmed(json.writeValueAsString(replay), eventId);

        testClock.now.set(BEFORE_MORNING.plusSeconds(1));
        String cancellation = "/api/bookings/" + booking + "/cancellation";
        assertThat(request("PUT", cancellation, owner, null).statusCode()).isEqualTo(200);
        assertThat(request("PUT", cancellation, owner, null).statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(notificationTypes(owner)).containsExactly("BOOKING_CANCELLED", "BOOKING_CREATED");
            assertThat(published(booking)).isEqualTo(2);
        });
        assertThat(notificationTypes(other)).isEmpty();
        assertThat(request("GET", "/api/notifications", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void unroutableBookingEventIsRetriedAfterRabbitBindingReturns() throws Exception {
        long room = room();
        long slot = publish(login("admin", ADMIN_PASSWORD), room, TODAY).get(0);
        String owner = student();
        var binding = new Binding(QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, ROUTE, Map.of());
        long booking;
        rabbitAdmin.removeBinding(binding);
        try {
            var response = book(owner, slot);
            assertThat(response.statusCode()).isEqualTo(201);
            booking = ((Number) value(response, "$.id")).longValue();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(db.sql("SELECT attempts FROM booking_outbox WHERE booking_id = :booking")
                    .param("booking", booking).query(Integer.class).single()).isPositive();
                assertThat(published(booking)).isZero();
            });
        } finally {
            rabbitAdmin.declareBinding(binding);
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(notificationTypes(owner)).containsExactly("BOOKING_CREATED");
            assertThat(published(booking)).isEqualTo(1);
        });
    }

    @Test
    void malformedRabbitMessageArrivesInTheDeadLetterQueue() throws Exception {
        String messageId = UUID.randomUUID().toString();
        sendConfirmed("invalid-json", messageId);
        var deadLetter = rabbit.receive(DEAD_LETTER_QUEUE, 10000);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getMessageProperties().getMessageId()).isEqualTo(messageId);
    }

    private List<String> notificationTypes(String token) throws Exception {
        var response = request("GET", "/api/notifications", token, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return value(response, "$[*].type");
    }

    private int published(long booking) {
        return db.sql("SELECT COUNT(*) FROM booking_outbox WHERE booking_id = :booking AND published_at IS NOT NULL")
            .param("booking", booking).query(Integer.class).single();
    }

    private void sendConfirmed(String body, String id) throws Exception {
        var message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
            .setMessageId(id).setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        var correlation = new CorrelationData();
        rabbit.send(EXCHANGE, ROUTE, message, correlation);
        assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).ack()).isTrue();
        assertThat(correlation.getReturned()).isNull();
    }

    private List<HttpResponse<String>> race(List<Callable<HttpResponse<String>>> tasks) throws Exception {
        var ready = new CountDownLatch(tasks.size());
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(tasks.size());
        try {
            var futures = tasks.stream().map(task -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent test did not start");
                }
                return task.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<HttpResponse<String>>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long room() {
        var key = new GeneratedKeyHolder();
        db.sql("INSERT INTO rooms (name, location) VALUES ('Integration room', 'Test')")
            .update(key, "id");
        long room = key.getKey().longValue();
        db.sql("INSERT INTO seats (room_id, label) VALUES (:id, 'T01'), (:id, 'T02')")
            .param("id", room).update();
        return room;
    }

    private List<Long> publish(String token, long room, LocalDate date) throws Exception {
        var response = request("PUT", adminPath(room), token, slotBody(date));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<Map<String, Object>> slots = JsonPath.read(response.body(), "$");
        return slots.stream().map(slot -> ((Number) slot.get("id")).longValue()).toList();
    }

    private String adminPath(long room) {
        return "/api/admin/rooms/" + room + "/slots";
    }

    private String slotsPath(long room, LocalDate date) {
        return "/api/rooms/" + room + "/slots?date=" + date + "&period=MORNING";
    }

    private String slotBody(LocalDate date) {
        return "{\"date\":\"" + date + "\",\"period\":\"MORNING\"}";
    }

    private HttpResponse<String> book(String token, long slot) throws Exception {
        return request("POST", "/api/bookings", token, "{\"slotId\":" + slot + "}");
    }

    private String student() throws Exception {
        String username = username();
        assertThat(request("POST", "/api/auth/register", null, credentials(username, PASSWORD)).statusCode()).isEqualTo(201);
        return login(username, PASSWORD);
    }

    private String username() {
        return "user_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String login(String username, String password) throws Exception {
        var response = request("POST", "/api/auth/login", null, credentials(username, password));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return value(response, "$.accessToken");
    }

    private String credentials(String name, String password) {
        return "{\"username\":\"" + name + "\",\"password\":\"" + password + "\"}";
    }

    private <T> T value(HttpResponse<String> response, String path) {
        return JsonPath.read(response.body(), path);
    }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return client.send(builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class TestClockConfiguration {
        @Bean
        @Primary
        TestClock fixedTestClock() {
            return new TestClock();
        }
    }

    static class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(BEFORE_MORNING);

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Shanghai");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
