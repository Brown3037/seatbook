package io.seatbook;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SeatbookApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeatbookApplication.class, args);
    }

    @Bean
    Clock campusClock(@Value("${seatbook.zone}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
