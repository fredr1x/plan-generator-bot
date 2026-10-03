package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.ScheduleEntry;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.service.SlotService;
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

/**
 * Weekly timetable: which lesson number of which weekday is taught to which class(es).
 * Callbacks: sch:day:wd (day screen), sch:rm:entryId, sch:mv:wd:no (move a lesson), sch:md:wd:no:newWd,
 * sch:mn:wd:no:newWd:newNo, sch:add:wd, sch:rep:wd, sch:back.
 */
@ApplicationScoped
public class ScheduleFlow {

    /** The text that follows replaces the whole day. */
    static final String SCHEDULE_ADD = "SCHEDULE_ADD";
    /** The text that follows is added to the day. */
    static final String SCHEDULE_PLUS = "SCHEDULE_PLUS";

    private static final Pattern LESSON_LINE = Pattern.compile("^\\s*(\\d{1,2})[.)]?\\s+(.+)$");
    private static final Pattern CLASS_TOKEN = Pattern.compile("(\\d{1,2})\\s*[-–]?\\s*(\\p{L})");

    @Inject
    Messenger bot;

    @Inject
    SlotService slots;

    @Inject
    KtpFlow ktpFlow;

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
        bot.send(chat, sb.toString().stripTrailing() + "\n\nВыберите день, чтобы изменить:",
                Kb.rows().add(dayButtons("sch:day:")).build());
    }

    private static List<InlineButton> dayButtons(String prefix) {
        List<InlineButton> days = new ArrayList<>();
        for (int d = 1; d <= 6; d++) {
            days.add(btn(Dates.dayShort(d), prefix + d));
        }
        return days;
    }

    /** One day: every lesson can be removed or moved, lessons can be added, or the whole day replaced. */
    private void showDay(long uid, long chat, int wd) {
        List<ScheduleEntry> entries = ScheduleEntry.forDay(uid, wd);
        Map<Long, SchoolClass> classes = classMap(uid);
        StringBuilder sb = new StringBuilder("📅 ").append(Dates.dayName(wd)).append('\n');
        Kb.Rows rows = Kb.rows();
        if (entries.isEmpty()) {
            sb.append("\nУроков нет.");
        } else {
            sb.append("\nНажмите 🗑, чтобы убрать урок у класса, или ↔️, чтобы перенести урок на другой день или номер.");
        }
        Set<Integer> numbers = new LinkedHashSet<>();
        for (ScheduleEntry e : entries) {
            SchoolClass c = classes.get(e.classId);
            if (c == null) continue;
            rows.add(btn("🗑 " + e.lessonNo + " урок · " + c.label(), "sch:rm:" + e.id));
            numbers.add(e.lessonNo);
        }
        for (int no : numbers) {
            rows.add(btn("↔️ Перенести " + no + " урок", "sch:mv:" + wd + ":" + no));
        }
        rows.add(btn("➕ Добавить урок", "sch:add:" + wd), btn("✏️ Заменить день", "sch:rep:" + wd));
        rows.add(btn("← Расписание", "sch:back"));
        bot.send(chat, sb.toString(), rows.build());
    }

    public void onCallback(long uid, long chat, String cbId, String[] p) {
        bot.answer(cbId, null);
        if (SchoolClass.forTeacher(uid).isEmpty()) {
            bot.send(chat, "Сначала добавьте классы: /classes", null);
            return;
        }
        switch (p[1]) {
            case "back" -> show(uid, chat);
            case "day" -> showDay(uid, chat, Integer.parseInt(p[2]));
            case "rep" -> {
                int wd = Integer.parseInt(p[2]);
                ConvState.set(uid, SCHEDULE_ADD, String.valueOf(wd));
                bot.send(chat, Dates.dayName(wd) + ". Пришлите уроки: номер урока и класс, каждый с новой строки:\n\n"
                        + "1 7А\n2 7Б\n3 8А 8Б\n\n"
                        + "(несколько классов в одной строке = урок у обоих)\n"
                        + "Это заменит расписание этого дня. Чтобы очистить день, пришлите «-». Отмена: /cancel", null);
            }
            case "add" -> {
                int wd = Integer.parseInt(p[2]);
                ConvState.set(uid, SCHEDULE_PLUS, String.valueOf(wd));
                bot.send(chat, Dates.dayName(wd) + ". Пришлите урок, который нужно добавить: номер и класс, например "
                        + "«3 7А» или «4 8А 8Б». Остальные уроки дня останутся. Отмена: /cancel", null);
            }
            case "rm" -> {
                ScheduleEntry e = ScheduleEntry.findById(Long.parseLong(p[2]));
                if (e == null || e.teacherId != uid) return;
                int wd = e.weekday;
                e.delete();
                changed(uid, chat, wd);
            }
            case "mv" -> {
                int wd = Integer.parseInt(p[2]);
                int no = Integer.parseInt(p[3]);
                bot.send(chat, "Перенести " + no + " урок (" + Dates.dayName(wd) + ") на какой день?",
                        Kb.rows().add(dayButtons("sch:md:" + wd + ":" + no + ":")).build());
            }
            case "md" -> {
                String base = "sch:mn:" + p[2] + ":" + p[3] + ":" + p[4] + ":";
                List<InlineButton> nums = new ArrayList<>();
                for (int n = 1; n <= 9; n++) nums.add(btn(String.valueOf(n), base + n));
                bot.send(chat, "Каким по счёту уроком в " + Dates.dayName(Integer.parseInt(p[4])).toLowerCase() + "?",
                        Kb.rows().add(nums.subList(0, 5)).add(nums.subList(5, 9)).build());
            }
            case "mn" -> move(uid, chat, Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                    Integer.parseInt(p[5]));
            default -> { }
        }
    }

    /** All classes of one lesson number move together; a class that already has a lesson there is not doubled. */
    private void move(long uid, long chat, int wd, int no, int newWd, int newNo) {
        for (ScheduleEntry e : ScheduleEntry.<ScheduleEntry>list("teacherId = ?1 and weekday = ?2 and lessonNo = ?3",
                uid, wd, no)) {
            boolean exists = ScheduleEntry.count("teacherId = ?1 and weekday = ?2 and lessonNo = ?3 and classId = ?4",
                    uid, newWd, newNo, e.classId) > 0;
            if (exists && !(wd == newWd && no == newNo)) {
                e.delete();
            } else {
                e.weekday = newWd;
                e.lessonNo = newNo;
            }
        }
        ScheduleEntry.getEntityManager().flush();
        changed(uid, chat, newWd);
    }

    public void onText(long uid, long chat, ConvState st, String text) {
        boolean add = SCHEDULE_PLUS.equals(st.state);
        int wd = Integer.parseInt(st.payload);
        Map<String, SchoolClass> byLabel = new LinkedHashMap<>();
        for (SchoolClass c : SchoolClass.forTeacher(uid)) {
            byLabel.put(c.grade + c.letter, c);
        }
        Set<String> entries = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        List<ScheduleEntry> toSave = new ArrayList<>();
        boolean clear = !add && (text.strip().equals("-") || text.strip().equalsIgnoreCase("пусто"));

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
                    boolean already = add && ScheduleEntry.count(
                            "teacherId = ?1 and weekday = ?2 and lessonNo = ?3 and classId = ?4",
                            uid, wd, lessonNo, c.id) > 0;
                    if (!already && entries.add(lessonNo + ":" + c.id)) {
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
                        + (add && problems.isEmpty() ? "Такой урок уже есть." : "")
                        + "\n\nПопробуйте ещё раз или /cancel.", null);
                return;
            }
        }

        if (!add) {
            ScheduleEntry.delete("teacherId = ?1 and weekday = ?2", uid, wd);
            ScheduleEntry.getEntityManager().flush();
        }
        toSave.forEach(e -> e.persist());
        ConvState.clear(uid);
        if (!problems.isEmpty()) {
            bot.send(chat, "Есть замечания:\n" + String.join("\n", problems), null);
        }
        if (add) {
            changed(uid, chat, wd);
        } else {
            show(uid, chat);
            recalculate(uid, chat);
        }
    }

    /** After a quick edit: the day is shown again and the dates of the KTP lessons follow the timetable. */
    private void changed(long uid, long chat, int wd) {
        showDay(uid, chat, wd);
        recalculate(uid, chat);
    }

    private void recalculate(long uid, long chat) {
        var built = slots.rebuildAll(uid); // lessons of the KTP move together with the timetable
        if (!built.isEmpty()) {
            bot.send(chat, "Расписание изменилось, пересчитал даты уроков по КТП.\n\n"
                    + SlotService.describeAll(built), null);
        }
        ktpFlow.offerDates(uid, chat);
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
