package dev.ydt.plangen.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class TimeService {

    @ConfigProperty(name = "app.timezone")
    String timezone;

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    public LocalDate today() {
        return LocalDate.now(zone());
    }

    public Instant startOf(LocalDate day) {
        return day.atStartOfDay(zone()).toInstant();
    }
}
