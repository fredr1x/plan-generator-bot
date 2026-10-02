package dev.ydt.plangen.bot;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

final class Dates {
    private Dates() {}

    static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM");
    static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final String[] NAMES = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота",
            "Воскресенье"};
    private static final String[] SHORT_NAMES = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};

    /** 1 = Monday .. 7 = Sunday. */
    static String dayName(int weekday) {
        return NAMES[weekday - 1];
    }

    static String dayShort(int weekday) {
        return SHORT_NAMES[weekday - 1];
    }

    static int weekday(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w.getValue();
    }

    /** Accepts 15.10, 15.10.2026, 15.10.26 and 2026-10-15. */
    static Optional<LocalDate> parse(String text, LocalDate today) {
        String s = text.trim();
        try {
            if (s.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                return Optional.of(LocalDate.parse(s, DateTimeFormatter.ofPattern("uuuu-M-d")));
            }
            if (s.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{4}")) {
                return Optional.of(LocalDate.parse(s, DateTimeFormatter.ofPattern("d.M.uuuu")));
            }
            if (s.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{2}")) {
                return Optional.of(LocalDate.parse(s, DateTimeFormatter.ofPattern("d.M.uu")));
            }
            if (s.matches("\\d{1,2}\\.\\d{1,2}\\.?")) {
                String[] p = s.replace(".", " ").trim().split("\\s+");
                return Optional.of(LocalDate.of(today.getYear(), Integer.parseInt(p[1]), Integer.parseInt(p[0])));
            }
        } catch (java.time.DateTimeException e) { // also covers DateTimeParseException
            return Optional.empty();
        }
        return Optional.empty();
    }
}
