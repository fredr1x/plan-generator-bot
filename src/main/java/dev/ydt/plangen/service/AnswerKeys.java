package dev.ydt.plangen.service;

import dev.ydt.plangen.llm.LessonContent;
import dev.ydt.plangen.llm.LessonContent.Stage;
import dev.ydt.plangen.service.BookService.Answer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts the answer keys of the textbook exercises into the plan. The model decides which exercises to use and
 * cites them ("Ex. 2, p. 22"); the keys themselves are copied from the stored page data by code, so they are
 * never lost, shortened or invented by the model.
 */
public final class AnswerKeys {
    private AnswerKeys() {}

    /** "Ex. 2, p. 22", "Ex.3 on p.22", "Exercise 4 (page 23)", "Ex. 5". */
    private static final Pattern CITE = Pattern.compile(
            "(?i)\\bEx(?:ercises?|s)?\\.?\\s*(\\d{1,2}[a-z]?)\\b(?:[\\s,(]*(?:on\\s+)?(?:pp?|page)\\.?\\s*(\\d{1,3}))?");

    public static LessonContent inject(LessonContent content, List<Answer> answers) {
        if (content == null || content.stages() == null || answers == null || answers.isEmpty()) {
            return content;
        }
        List<Stage> out = new ArrayList<>();
        for (Stage s : content.stages()) {
            out.add(withKeys(s, answers));
        }
        return new LessonContent(content.lessonObjectives(), out, content.programValue());
    }

    private static Stage withKeys(Stage s, List<Answer> answers) {
        String teacher = nz(s.teacherActions());
        String students = nz(s.studentActions());
        Set<String> added = new LinkedHashSet<>();
        StringBuilder extra = new StringBuilder();
        Matcher m = CITE.matcher(teacher + "\n" + students);
        while (m.find()) {
            String number = m.group(1);
            Integer page = m.group(2) == null ? null : Integer.valueOf(m.group(2));
            List<Answer> hits = answers.stream()
                    .filter(a -> a.number().equalsIgnoreCase(number) && (page == null || a.page() == page))
                    .toList();
            if (page == null && hits.size() > 1) {
                continue; // the same number on several pages and no page cited: do not guess
            }
            for (Answer a : hits) {
                if (!added.add(a.page() + ":" + a.number()) || alreadyThere(students, a.key())) {
                    continue;
                }
                extra.append("\nAnswer key, Ex. ").append(a.number()).append(" (p. ").append(a.page())
                        .append("sample".equals(a.type()) ? ", sample answers" : "").append("): ")
                        .append(a.key().strip());
            }
        }
        if (extra.length() == 0) {
            return s;
        }
        return new Stage(s.name(), s.minutes(), s.teacherActions(), students.stripTrailing() + extra,
                s.assessment(), s.resources());
    }

    /** The key is already in the text if its first characters appear there (ignoring case and punctuation). */
    private static boolean alreadyThere(String text, String key) {
        String k = flat(key);
        if (k.length() < 6) {
            return false;
        }
        return flat(text).contains(k.substring(0, Math.min(30, k.length())));
    }

    private static String flat(String s) {
        return s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
