package io.seatbook.agent;

import io.seatbook.booking.AgentCatalog;
import io.seatbook.booking.Period;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/** An invocation records which information the server actually read. */
final class AgentToolbox {
    static final String RULES = "get_booking_rules";
    static final String ROOMS = "list_study_rooms";
    static final String AVAILABILITY = "find_available_slots";

    private final AgentCatalog catalog;
    private final Set<String> calls = new LinkedHashSet<>();
    private final Map<AvailabilityKey, AgentCatalog.Availability> availability = new LinkedHashMap<>();
    private AgentCatalog.Rules rules;
    private List<AgentCatalog.Room> rooms;

    AgentToolbox(AgentCatalog catalog) {
        this.catalog = catalog;
    }

    List<ToolCallback> callbacks() {
        return List.of(
            FunctionToolCallback.builder(RULES, this::rules)
                .description("Read campus date, valid booking dates, time blocks and booking rules.").build(),
            FunctionToolCallback.builder(ROOMS, this::rooms)
                .description("List study rooms with IDs, names and locations.").build(),
            FunctionToolCallback.builder(AVAILABILITY, this::availability)
                .description("Read available seats for a campus date and period. Omit roomId to compare rooms.")
                .inputType(AvailabilityQuery.class)
                .inputSchema("""
                    {
                      "type": "object",
                      "properties": {
                        "date": {"type": "string", "description": "Campus date in YYYY-MM-DD"},
                        "period": {"type": "string", "enum": ["MORNING", "AFTERNOON", "EVENING"]},
                        "roomId": {"type": "integer", "minimum": 1, "description": "Optional room ID"}
                      },
                      "required": ["date", "period"],
                      "additionalProperties": false
                    }
                    """).build()
        );
    }

    AgentCatalog.Rules rules() {
        calls.add(RULES);
        rules = catalog.rules();
        return rules;
    }

    List<AgentCatalog.Room> rooms() {
        calls.add(ROOMS);
        rooms = catalog.listRooms();
        return rooms;
    }

    List<AgentCatalog.Availability> availability(AvailabilityQuery input) {
        if (input == null || input.date() == null) {
            throw new IllegalArgumentException("A campus date is required");
        }
        var date = LocalDate.parse(input.date());
        var result = catalog.findAvailability(date, input.period(), input.roomId());
        for (var room : result) {
            availability.put(new AvailabilityKey(room.roomId(), room.date(), room.period()), room);
        }
        calls.add(AVAILABILITY);
        return result;
    }

    boolean hasResults() {
        return !calls.isEmpty();
    }

    AgentReply render(AgentReply.Mode mode) {
        if (calls.contains(AVAILABILITY)) {
            var available = availability.values().stream().filter(option -> option.availableCount() > 0)
                .sorted(Comparator.comparingInt(AgentCatalog.Availability::availableCount).reversed())
                .limit(6).toList();
            if (available.isEmpty()) {
                return reply(mode, "查询的时段暂无可预约座位。可以换一个日期或时段。", List.of());
            }
            return reply(mode, "已按可用座位数排序。点「查看座位」后，可以在座位列表中确认并预约。", available);
        }

        StringBuilder answer = new StringBuilder();
        if (rooms != null) {
            String locationList = rooms.stream().map(room -> room.name() + "（" + room.location() + "）")
                .collect(Collectors.joining("、"));
            answer.append(rooms.isEmpty() ? "暂无自习室。" : "自习室有 " + locationList + "。");
        }
        if (rules != null) {
            if (!answer.isEmpty()) {
                answer.append('\n');
            }
            String periods = rules.periods().stream().map(period ->
                periodName(period.code()) + " " + period.start() + "–" + period.end()).collect(Collectors.joining("、"));
            answer.append("可预约日期 ").append(rules.today()).append(" 至 ").append(rules.lastBookableDay())
                .append("（北京时间）。").append(periods).append("。").append(rules.bookingPolicy());
        }
        return reply(mode, answer.toString(), List.of());
    }

    AgentReply reply(AgentReply.Mode mode, String answer, List<AgentCatalog.Availability> options) {
        return new AgentReply(mode, answer, options, List.copyOf(calls));
    }

    private static String periodName(Period period) {
        return switch (period) {
            case MORNING -> "上午";
            case AFTERNOON -> "下午";
            case EVENING -> "晚上";
        };
    }

    record AvailabilityQuery(String date, Period period, Long roomId) {}
    private record AvailabilityKey(long roomId, LocalDate date, Period period) {}
}
