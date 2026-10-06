package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.ScheduleEntry;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.domain.SchoolTerm;
import dev.ydt.plangen.domain.TeacherSettings;
import dev.ydt.plangen.service.SlotService;
import dev.ydt.plangen.service.TimeService;
import dev.ydt.plangen.telegram.Dto.InlineButton;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
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
 * Weekly timetable: which lesson number of which weekday is taught to which class(es). The timetable has versions:
 * a change is written to the version that starts on the chosen date, so the lessons before that date keep the old
 * timetable. Callbacks: sch:day:wd (day screen), sch:rm:wd:no:classId, sch:mv:wd:no, sch:md:wd:no:newWd,
 * sch:mn:wd:no:newWd:newNo, sch:add:wd, sch:rep:wd, sch:back, sch:ed (date of the change), sch:es:t|n|x.
 */
@ApplicationScoped
public class ScheduleFlow {

    /** The text that follows replaces the whole day. */
    static final String SCHEDULE_ADD = "SCHEDULE_ADD";
    /** The text that follows is added to the day. */
    static final String SCHEDULE_PLUS = "SCHEDULE_PLUS";
    /** The text that follows is the date from which changes take effect. */
    static final String SCHEDULE_DATE = "SCHEDULE_DATE";

    private static final Pattern LESSON_LINE = Pattern.compile("^\\s*(\\d{1,2})[.)]?\\s+(.+)$");
    private static final Pattern CLASS_TOKEN = Pattern.compile("(\\d{1,2})\\s*[-–]?\\s*(\\p{L})");

    @Inject
    Messenger bot;

    @Inject
    SlotService slots;

    @Inject
    KtpFlow ktpFlow;

    @Inject
    TimeService time;

    // ------------------------------------------------------------------ the date of a change

    /** First day of the next term, or null when there is none. */
    private LocalDate nextTermStart(LocalDate today) {
        return SchoolTerm.all().stream().map(t -> t.startsOn).filter(d -> d.isAfter(today)).findFirst().orElse(null);
    }

    /** During a vacation a change is meant for the next term, during a term it starts today. */
    private LocalDate defaultFrom(LocalDate today) {
        boolean inTerm = SchoolTerm.all().stream().anyMatch(t -> t.contains(today));
        LocalDate next = nextTermStart(today);
        return !inTerm && next != null ? next : today;
    }

    /** The date from which the changes of the teacher take effect: the chosen one, otherwise the default. */
    LocalDate editFrom(long uid) {
        LocalDate today = time.today();
        TeacherSettings s = TeacherSettings.of(uid);
        return s.scheduleFrom != null && !s.scheduleFrom.isBefore(today) ? s.scheduleFrom : defaultFrom(today);
    }

    private String fromLabel(LocalDate from) {
        LocalDate today = time.today();
        String label = from.format(Dates.FULL);
        if (from.equals(today)) return label + " (сегодня)";
        for (SchoolTerm t : SchoolTerm.all()) {
            if (t.startsOn.equals(from)) return label + " (начало " + t.termNo + " четверти)";
        }
        return label;
    }

    /**
     * The version that receives the changes. A new version starts as a copy of the timetable that is in force on
     * that day, so everything that is not changed stays. The very first timetable covers the whole past.
     */
    private LocalDate ensureVersion(long uid, LocalDate from) {
        List<LocalDate> versions = ScheduleEntry.versionDates(uid);
        if (versions.isEmpty()) return ScheduleEntry.ALWAYS;
        if (versions.contains(from)) return from;
        for (ScheduleEntry e : ScheduleEntry.at(uid, from)) {
            ScheduleEntry copy = new ScheduleEntry();
            copy.teacherId = uid;
            copy.weekday = e.weekday;
            copy.lessonNo = e.lessonNo;
            copy.classId = e.classId;
            copy.validFrom = from;
            copy.persist();
        }
        ScheduleEntry.getEntityManager().flush();
        return from;
    }

    // ------------------------------------------------------------------ screens

    public void show(long uid, long chat) {
        LocalDate from = editFrom(uid);
        List<ScheduleEntry> entries = ScheduleEntry.at(uid, from);
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
        List<LocalDate> versions = ScheduleEntry.versionDates(uid);
        if (versions.size() > 1) {
            sb.append("\nВерсии расписания: ").append(versions.stream()
                    .map(v -> v.equals(ScheduleEntry.ALWAYS) ? "с начала года" : "с " + v.format(Dates.SHORT))
                    .collect(Collectors.joining(" · "))).append('.');
        }
        sb.append("\n\n📆 Изменения вступят в силу: ").append(fromLabel(from))
                .append(". Более ранние даты останутся по прежнему расписанию.\n\nВыберите день, чтобы изменить:");
        bot.send(chat, sb.toString(), Kb.rows().add(dayButtons("sch:day:"))
                .add(btn("📆 С какой даты менять", "sch:ed")).build());
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
        LocalDate from = editFrom(uid);
        List<ScheduleEntry> entries = ScheduleEntry.forDay(uid, wd, from);
        Map<Long, SchoolClass> classes = classMap(uid);
        StringBuilder sb = new StringBuilder("📅 ").append(Dates.dayName(wd)).append('\n');
        Kb.Rows rows = Kb.rows();
        if (entries.isEmpty()) {
            sb.append("\nУроков нет.");
        } else {
            sb.append("\nНажмите 🗑, чтобы убрать урок у класса, или ↔️, чтобы перенести урок на другой день или номер.");
        }
        sb.append("\n📆 Изменения вступят в силу: ").append(fromLabel(from));
        Set<Integer> numbers = new LinkedHashSet<>();
        for (ScheduleEntry e : entries) {
            SchoolClass c = classes.get(e.classId);
            if (c == null) continue;
            rows.add(btn("🗑 " + e.lessonNo + " урок · " + c.label(), "sch:rm:" + wd + ":" + e.lessonNo + ":" + c.id));
            numbers.add(e.lessonNo);
        }
        for (int no : numbers) {
            rows.add(btn("↔️ Перенести " + no + " урок", "sch:mv:" + wd + ":" + no));
        }
        rows.add(btn("➕ Добавить урок", "sch:add:" + wd), btn("✏️ Заменить день", "sch:rep:" + wd));
        rows.add(btn("← Расписание", "sch:back"));
        bot.send(chat, sb.toString(), rows.build());
    }

    // ------------------------------------------------------------------ callbacks

    public void onCallback(long uid, long chat, String cbId, String[] p) {
        bot.answer(cbId, null);
        if (SchoolClass.forTeacher(uid).isEmpty()) {
            bot.send(chat, "Сначала добавьте классы: /classes", null);
            return;
        }
        switch (p[1]) {
            case "back" -> show(uid, chat);
            case "day" -> showDay(uid, chat, Integer.parseInt(p[2]));
            case "ed" -> askFrom(uid, chat);
            case "es" -> setFrom(uid, chat, p[2]);
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
                int wd = Integer.parseInt(p[2]);
                LocalDate v = ensureVersion(uid, editFrom(uid));
                ScheduleEntry.delete("teacherId = ?1 and validFrom = ?2 and weekday = ?3 and lessonNo = ?4 "
                        + "and classId = ?5", uid, v, wd, Integer.parseInt(p[3]), Long.parseLong(p[4]));
                ScheduleEntry.getEntityManager().flush();
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

    private void askFrom(long uid, long chat) {
        LocalDate today = time.today();
        LocalDate next = nextTermStart(today);
        Kb.Rows rows = Kb.rows().add(btn("С сегодняшнего дня (" + today.format(Dates.SHORT) + ")", "sch:es:t"));
        if (next != null) {
            rows.add(btn("С начала следующей четверти (" + next.format(Dates.SHORT) + ")", "sch:es:n"));
        }
        rows.add(btn("Другая дата", "sch:es:x"));
        bot.send(chat, "С какого дня должны действовать изменения расписания? Уроки до этой даты останутся по прежнему "
                + "расписанию, а даты уроков КТП после неё пересчитаются. Сейчас: " + fromLabel(editFrom(uid)) + ".",
                rows.build());
    }

    private void setFrom(long uid, long chat, String choice) {
        LocalDate today = time.today();
        if ("x".equals(choice)) {
            ConvState.set(uid, SCHEDULE_DATE, null);
            bot.send(chat, "Пришлите дату, с которой действуют изменения, например 02.11 или 02.11.2026. Не раньше "
                    + "сегодняшнего дня. Отмена: /cancel", null);
            return;
        }
        LocalDate date = "n".equals(choice) ? nextTermStart(today) : today;
        if (date == null) date = today;
        saveFrom(uid, date);
        show(uid, chat);
    }

    private void saveFrom(long uid, LocalDate date) {
        TeacherSettings s = TeacherSettings.of(uid);
        s.scheduleFrom = date;
        TeacherSettings.getEntityManager().merge(s);
    }

    /** All classes of one lesson number move together; a class that already has a lesson there is not doubled. */
    private void move(long uid, long chat, int wd, int no, int newWd, int newNo) {
        LocalDate v = ensureVersion(uid, editFrom(uid));
        for (ScheduleEntry e : ScheduleEntry.<ScheduleEntry>list(
                "teacherId = ?1 and validFrom = ?2 and weekday = ?3 and lessonNo = ?4", uid, v, wd, no)) {
            boolean exists = ScheduleEntry.count(
                    "teacherId = ?1 and validFrom = ?2 and weekday = ?3 and lessonNo = ?4 and classId = ?5",
                    uid, v, newWd, newNo, e.classId) > 0;
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

    // ------------------------------------------------------------------ text input

    public void onText(long uid, long chat, ConvState st, String text) {
        if (SCHEDULE_DATE.equals(st.state)) {
            onDate(uid, chat, text);
            return;
        }
        boolean add = SCHEDULE_PLUS.equals(st.state);
        int wd = Integer.parseInt(st.payload);
        Map<String, SchoolClass> byLabel = new LinkedHashMap<>();
        for (SchoolClass c : SchoolClass.forTeacher(uid)) {
            byLabel.put(c.grade + c.letter, c);
        }
        Set<String> entries = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        List<int[]> lessons = new ArrayList<>(); // lesson number, class index in classIds
        List<Long> classIds = new ArrayList<>();
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
                    if (entries.add(lessonNo + ":" + c.id)) {
                        classIds.add(c.id);
                        lessons.add(new int[] {lessonNo, classIds.size() - 1});
                    }
                }
                if (!any) problems.add("В строке нет класса: " + line.strip());
            }
            if (lessons.isEmpty()) {
                bot.send(chat, "Ничего не сохранил.\n" + String.join("\n", problems)
                        + "\n\nПопробуйте ещё раз или /cancel.", null);
                return;
            }
        }

        LocalDate v = ensureVersion(uid, editFrom(uid));
        if (!add) {
            ScheduleEntry.delete("teacherId = ?1 and validFrom = ?2 and weekday = ?3", uid, v, wd);
            ScheduleEntry.getEntityManager().flush();
        }
        int added = 0;
        for (int[] l : lessons) {
            long classId = classIds.get(l[1]);
            boolean already = ScheduleEntry.count(
                    "teacherId = ?1 and validFrom = ?2 and weekday = ?3 and lessonNo = ?4 and classId = ?5",
                    uid, v, wd, l[0], classId) > 0;
            if (already) continue;
            ScheduleEntry e = new ScheduleEntry();
            e.teacherId = uid;
            e.weekday = wd;
            e.lessonNo = l[0];
            e.classId = classId;
            e.validFrom = v;
            e.persist();
            added++;
        }
        ConvState.clear(uid);
        if (add && added == 0) {
            bot.send(chat, "Такой урок уже есть.", null);
        }
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

    private void onDate(long uid, long chat, String text) {
        LocalDate today = time.today();
        var date = SlotService.date(text, today);
        if (date.isEmpty()) {
            bot.send(chat, "Не понял дату. Пример: 02.11 или 02.11.2026.", null);
            return;
        }
        if (date.get().isBefore(today)) {
            bot.send(chat, "Дата не может быть раньше сегодняшнего дня. Пришлите другую или /cancel.", null);
            return;
        }
        ConvState.clear(uid);
        saveFrom(uid, date.get());
        show(uid, chat);
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
