package io.seatbook.booking;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
class SlotCache {
    private static final Logger log = LoggerFactory.getLogger(SlotCache.class);
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(15);
    private static final Duration REVISION_TTL = Duration.ofMinutes(2);
    private static final TypeReference<List<RoomService.Slot>> SLOT_LIST = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    SlotCache(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    List<RoomService.Slot> get(ScheduleChanged schedule, Supplier<List<RoomService.Slot>> load) {
        String key;
        try {
            String revision = redis.opsForValue().get(schedule.prefix() + ":revision");
            key = schedule.prefix() + ":snapshot:" + Optional.ofNullable(revision).orElse("initial");
            String snapshot = redis.opsForValue().get(key);
            if (snapshot != null) {
                return json.readValue(snapshot, SLOT_LIST);
            }
        } catch (DataAccessException | JacksonException ex) {
            log.debug("Slot cache read failed for {}", schedule.prefix(), ex);
            return load.get();
        }

        List<RoomService.Slot> slots = load.get();
        try {
            redis.opsForValue().set(key, json.writeValueAsString(slots), SNAPSHOT_TTL);
        } catch (DataAccessException | JacksonException ex) {
            log.debug("Slot cache fill failed for {}", schedule.prefix(), ex);
        }
        return slots;
    }

    @TransactionalEventListener
    public void invalidate(ScheduleChanged schedule) {
        try {
            // A refill started before this commit can only write to the previous revision.
            redis.opsForValue().set(schedule.prefix() + ":revision", UUID.randomUUID().toString(), REVISION_TTL);
        } catch (DataAccessException ex) {
            log.debug("Slot cache invalidation failed for {}; the snapshot still expires", schedule.prefix(), ex);
        }
    }

    record ScheduleChanged(long roomId, LocalDate date, Period period) {
        String prefix() {
            return "seatbook:v1:slots:" + roomId + ":" + date + ":" + period;
        }
    }
}
