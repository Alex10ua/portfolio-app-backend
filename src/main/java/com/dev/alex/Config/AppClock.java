package com.dev.alex.Config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * "Today" in the user's timezone ({@code app.timezone}, env APP_TIMEZONE), not the JVM's.
 *
 * <p>The container runs on UTC, so between local midnight and 01:00/02:00 in Central Europe
 * the JVM still reads yesterday: a trade dated today counted as a future trade and stayed
 * out of every position, and a custom-asset price dated today was rejected as future-dated.
 * Only the cutoff moves; how LocalDate fields are stored is untouched (that still follows
 * the JVM default zone, which must stay as it is for existing documents).
 *
 * <p>Static so plain unit tests that build services by hand keep working unchanged.
 * Unset or unknown zone = the JVM default, which is the old behaviour.
 */
@Slf4j
@Component
public class AppClock {
    private static volatile ZoneId zone = ZoneId.systemDefault();

    @Value("${app.timezone:}")
    void setTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) return;
        try {
            zone = ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            log.warn("Unknown app.timezone '{}', using {}", timezone, zone);
        }
    }

    public static ZoneId zone() {
        return zone;
    }

    public static LocalDate today() {
        return LocalDate.now(zone);
    }
}
