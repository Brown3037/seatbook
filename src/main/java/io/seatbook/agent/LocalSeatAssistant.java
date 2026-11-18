package io.seatbook.agent;

import io.seatbook.booking.AgentCatalog;
import io.seatbook.booking.Period;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class LocalSeatAssistant {
    private static final Pattern ISO_DATE = Pattern.compile("(?<![0-9])([0-9]{4}-[0-9]{2}-[0-9]{2})(?![0-9])");
    private static final Pattern ROOM_LIST = Pattern.compile("有哪些自习室|自习室列表|哪些自习室|列出自习室");
    private static final Pattern RULES = Pattern.compile("规则|多久|最早|最晚|最多|开放时间|怎么取消|取消.*(要求|截止)");
    private static final List<PeriodWords> PERIOD_WORDS = List.of(
        new PeriodWords(Period.MORNING, Pattern.compile("上午|早上|早晨")),
        new PeriodWords(Period.AFTERNOON, Pattern.compile("下午")),
        new PeriodWords(Period.EVENING, Pattern.compile("晚上|傍晚|晚间"))
    );

    private final AgentCatalog catalog;

    LocalSeatAssistant(AgentCatalog catalog) {
        this.catalog = catalog;
    }

    AgentReply ask(String message) {
        var tools = new AgentToolbox(catalog);
        var explicitDates = ISO_DATE.matcher(message).results().map(match -> match.group(1)).distinct().toList();
        List<Period> periods = PERIOD_WORDS.stream().filter(words -> words.words().matcher(message).find())
            .map(PeriodWords::period).toList();
        boolean hasDate = !explicitDates.isEmpty() || List.of("今天", "明天", "后天").stream().anyMatch(message::contains);
        if (!hasDate && periods.isEmpty()) {
            if (ROOM_LIST.matcher(message).find()) {
                tools.rooms();
                return tools.render(AgentReply.Mode.LOCAL);
            }
            if (RULES.matcher(message).find()) {
                tools.rules();
                return tools.render(AgentReply.Mode.LOCAL);
            }
        }
        if (!hasDate || periods.size() != 1) {
            return clarify(tools, "请提供一个日期和一个时段，比如「明天下午哪里有空位？」。也可以问「预约规则是什么？」。");
        }

        var rules = tools.rules();
        List<LocalDate> dates = new ArrayList<>();
        try {
            for (String date : explicitDates) {
                dates.add(LocalDate.parse(date));
            }
        } catch (DateTimeException ex) {
            return clarify(tools, "日期无效，请使用 YYYY-MM-DD，或输入今天、明天、后天。");
        }
        if (message.contains("今天")) {
            dates.add(rules.today());
        }
        if (message.contains("明天")) {
            dates.add(rules.today().plusDays(1));
        }
        if (message.contains("后天")) {
            dates.add(rules.today().plusDays(2));
        }

        List<LocalDate> uniqueDates = dates.stream().distinct().toList();
        if (uniqueDates.size() != 1) {
            return clarify(tools, "请在一次查询中选择一个日期。");
        }
        LocalDate date = uniqueDates.get(0);
        if (date.isBefore(rules.today()) || date.isAfter(rules.lastBookableDay())) {
            return clarify(tools, "可预约日期为 " + rules.today() + " 至 " + rules.lastBookableDay() + "，请选择范围内的日期。");
        }

        var matched = tools.rooms().stream().filter(room -> message.contains(room.name())).toList();
        Long roomId = matched.size() == 1 ? matched.get(0).id() : null;
        tools.availability(new AgentToolbox.AvailabilityQuery(date.toString(), periods.get(0), roomId));
        return tools.render(AgentReply.Mode.LOCAL);
    }

    private AgentReply clarify(AgentToolbox tools, String text) {
        return tools.reply(AgentReply.Mode.LOCAL, text, List.of());
    }

    private record PeriodWords(Period period, Pattern words) {}
}
