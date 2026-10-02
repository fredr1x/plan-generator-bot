package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.ScheduleEntry;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.telegram.Dto.InlineButton;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Weekly timetable: which lesson number of which weekday is taught to which class(es). */
@ApplicationScoped
public class ScheduleFlow {

    static final String SCHEDULE_ADD = "SCHEDULE_ADD";

    private static final Pattern LESSON_LINE = Pattern.compile("^\\s*(\\d{1,2})[.)]?\\s+(.+)$");
    private static final Pattern CLASS_TOKEN = Pattern.compile("(\\d{1,2})\\s*[-–]?\\s*(\\p{L})");

    @Inject
    Messenger bot;

    public void show(long uid, long chat) {
        List<ScheduleEntry> entries = ScheduleEntry.forTeacher(uid);
        Map<Long, SchoolClass> classes = classMap(uid);
        StringBuilder sb = new StringBuilder("📅 Расписание\n");
        if (entries.isEmpty()) {
            sb.append("\nПока пусто. Выберите день, чтобы заполнить.");
        } else {
            grouped(entries, classes).forEach((wd, lessons) -> {
                sb.append('\n').append(Dates.dayName(wd)).append('\n');
                lessons.forEach((no, labels) ->
                        sb.append("  ").append(no).append(" урок — ").append(String.join(", ", labels)).append('\n'));
            });
        }
        List<InlineButton> days = new ArrayList<>();
        for (int d = 1; d <= 6; d++) {
            days.add(btn(Dates.dayShort(d), "sch:day:" + d));
        }
        bot.send(chat, sb.toString().stripTrailing() + "\n\nИзменить день:", Kb.rows().add(days).build());
    }

    public void onCallback(long uid, long chat, String cbId, String[] p) {
        bot.answer(cbId, null);
        if (!"day".equals(p[1])) return;
        int wd = Integer.parseInt(p[2]);
        if (SchoolClass.forTeacher(uid).isEmpty()) {
            bot.send(chat, "Сначала добавьте классы: /classes", null);
            return;
        }
        ConvState.set(uid, SCHEDULE_ADD, String.valueOf(wd));
        bot.send(chat, Dates.dayName(wd) + ". Пришлите уроки: номер урока и класс, каждый с новой строки:\n\n"
                + "1 7А\n2 7Б\n3 8А 8Б\n\n"
                + "(несколько классов в одной строке = урок у обоих)\n"
                + "Это заменит расписание этого дня. Чтобы очистить день, пришлите «-». Отмена: /cancel", null);
    }

    public void onText(long uid, long chat, ConvState st, String text) {
        int wd = Integer.parseInt(st.payload);
        Map<String, SchoolClass> byLabel = new LinkedHashMap<>();
        for (SchoolClass c : SchoolClass.forTeacher(uid)) {
            byLabel.put(c.grade + c.letter, c);
        }
        Set<String> entries = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        List<ScheduleEntry> toSave = new ArrayList<>();
        boolean clear = text.strip().equals("-") || text.strip().equalsIgnoreCase("пусто");

        if (!clear) {
            for (String line : text.split("\\R")) {
                if (line.isBlank()) continue;
                Matcher m = LESSON_LINE.matcher(line);
                if (!m.matches()) {
                    problems.add("Не понял строку: " + line.strip());
                    continue;
                }
                int lessonNo = Integer.parseInt(m.group(1));
                Matcher cm = CLASS_TOKEN.matcher(m.group(2));
                boolean any = false;
                while (cm.find()) {
                    any = true;
                    String key = Integer.parseInt(cm.group(1)) + ClassesFlow.normLetter(cm.group(2));
                    SchoolClass c = byLabel.get(key);
                    if (c == null) {
                        problems.add("Класс " + cm.group(1) + " " + ClassesFlow.normLetter(cm.group(2))
                                + " не найден, добавьте его в /classes");
                        continue;
                    }
                    if (lessonNo < 1 || lessonNo > 12) {
                        problems.add("Номер урока должен быть от 1 до 12: " + line.strip());
                        continue;
                    }
                    if (entries.add(lessonNo + ":" + c.id)) {
                        ScheduleEntry e = new ScheduleEntry();
                        e.teacherId = uid;
                        e.weekday = wd;
                        e.lessonNo = lessonNo;
                        e.classId = c.id;
                        toSave.add(e);
                    }
                }
                if (!any) problems.add("В строке нет класса: " + line.strip());
            }
            if (toSave.isEmpty()) {
                bot.send(chat, "Ничего не сохранил.\n" + String.join("\n", problems)
                        + "\n\nПопробуйте ещё раз или /cancel.", null);
                return;
            }
        }

        ScheduleEntry.delete("teacherId = ?1 and weekday = ?2", uid, wd);
        ScheduleEntry.getEntityManager().flush();
        toSave.forEach(e -> e.persist());
        ConvState.clear(uid);
        if (!problems.isEmpty()) {
            bot.send(chat, "Есть замечания:\n" + String.join("\n", problems), null);
        }
        show(uid, chat);
    }

    // ---------- helpers shared with the plan flow ----------

    static Map<Long, SchoolClass> classMap(long uid) {
        return SchoolClass.forTeacher(uid).stream()
                .collect(Collectors.toMap(c -> c.id, c -> c, (a, b) -> a, LinkedHashMap::new));
    }

    /** weekday -> lesson number -> class labels. */
    static Map<Integer, Map<Integer, List<String>>> grouped(List<ScheduleEntry> entries,
                                                                    Map<Long, SchoolClass> classes) {
        Map<Integer, Map<Integer, List<String>>> out = new LinkedHashMap<>();
        for (ScheduleEntry e : entries) {
            SchoolClass c = classes.get(e.classId);
            if (c == null) continue;
            out.computeIfAbsent(e.weekday, k -> new LinkedHashMap<>())
                    .computeIfAbsent(e.lessonNo, k -> new ArrayList<>())
                    .add(c.label());
        }
        return out;
    }

}
