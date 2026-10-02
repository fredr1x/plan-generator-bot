package dev.ydt.plangen.bot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets the teacher paste everything at once:
 * <pre>
 * Раздел: ...
 * Тема: ...
 * Цели: 7.2.3.1 ...
 * Пожелания: ...
 * </pre>
 * Returns the recognised fields (section, topic, objectives, notes); empty when no key was found.
 */
final class InputParser {
    private InputParser() {}

    private static final Pattern KEY = Pattern.compile(
            "^\\s*(раздел|бөлім|unit|section|тема|тақырып|topic|цели обучения|цели|цель|оқыту мақсаттары|"
                    + "мақсаттар|мақсат|objectives?|пожелания|ескертпе|notes?)\\s*[:：]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static Map<String, String> parse(String text) {
        Map<String, StringBuilder> acc = new LinkedHashMap<>();
        StringBuilder current = null;
        for (String line : text.split("\\R")) {
            Matcher m = KEY.matcher(line);
            if (m.matches()) {
                String field = field(m.group(1).toLowerCase());
                current = acc.computeIfAbsent(field, k -> new StringBuilder());
                if (!current.isEmpty()) current.append('\n');
                current.append(m.group(2).strip());
            } else if (current != null) {
                current.append('\n').append(line.strip());
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        acc.forEach((k, v) -> {
            String s = v.toString().strip();
            if (!s.isEmpty()) out.put(k, s);
        });
        return out;
    }

    private static String field(String key) {
        return switch (key) {
            case "раздел", "бөлім", "unit", "section" -> "section";
            case "тема", "тақырып", "topic" -> "topic";
            case "пожелания", "ескертпе", "note", "notes" -> "notes";
            default -> "objectives";
        };
    }
}
