package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import dev.ydt.plangen.domain.Book;
import dev.ydt.plangen.domain.KtpLesson;
import dev.ydt.plangen.domain.KtpSlot;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.domain.TeacherSettings;
import dev.ydt.plangen.service.SlotService;
import dev.ydt.plangen.service.TimeService;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Preparation of the next lesson day from the KTP dates: a suggestion with buttons (or ready plans in the "auto"
 * mode), /tomorrow, /week and "the lesson did not take place". Callbacks: ev:go:date, ev:edit:date,
 * ev:skip:date, ev:e:date:lessonId, ev:s:date:lessonId, ev:no.
 */
@ApplicationScoped
public class EveningFlow {

    private static final Logger LOG = Logger.getLogger(EveningFlow.class);

    /** One KTP lesson of the day with the classes that have it (parallel classes get one plan). */
    record Group(KtpLesson lesson, List<SchoolClass> classes, List<KtpSlot> slots) {
        boolean prepared() {
            return slots.stream().allMatch(s -> KtpSlot.PREPARED.equals(s.status));
        }

        String numbers() {
            return slots.stream().map(s -> s.lessonNo).distinct().sorted().map(String::valueOf)
                    .collect(Collectors.joining(", "));
        }
    }

    @Inject
    Messenger bot;

    @Inject
    SlotService slots;

    @Inject
    PlanFlow plan;

    @Inject
    KtpFlow ktp;

    @Inject
    BookFlow books;

    @Inject
    TimeService time;

    /** Slots handed to the generator a moment ago: a second tap on the button must not start a second plan. */
    private final Map<Long, Long> started = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ entry points

    /** /tomorrow: the suggestion for the nearest lesson day, right now. */
    void tomorrow(long uid, long chat) {
        var next = slots.nextLessonDay(uid, time.today());
        if (next.isEmpty()) {
            bot.send(chat, "Ближайших уроков по КТП в расписании нет. Загрузите КТП в /ktp и проверьте /schedule и "
                    + "/calendar.", null);
            return;
        }
        propose(uid, chat, next.get(), false);
    }

    /** The message for a day. In the auto mode (and when allowed) the plans are started at once. */
    void propose(long uid, long chat, LocalDate date, boolean autoAllowed) {
        List<Group> groups = groups(uid, date);
        if (groups.isEmpty()) {
            bot.send(chat, "На " + dayTitle(date) + " уроков по КТП нет.", null);
            return;
        }
        LOG.infof("[evening] user=%d: %s, %d lesson group(s)", uid, date, groups.size());
        if (autoAllowed && TeacherSettings.AUTO.equals(TeacherSettings.of(uid).mode)) {
            bot.send(chat, "📅 " + cap(dayTitle(date)) + ": готовлю планы сам.\n\n" + summary(groups, date), null);
            for (Group g : groups) {
                if (!g.prepared()) generate(uid, chat, g, date, false);
            }
            return;
        }
        Kb.Rows rows = Kb.rows();
        if (groups.stream().anyMatch(g -> !g.prepared())) {
            rows.add(btn("✅ Составить все", "ev:go:" + date));
        }
        rows.add(btn("✏️ Изменить урок", "ev:edit:" + date), btn("⏭ Урок не состоялся", "ev:skip:" + date));
        rows.add(btn("Не сегодня", "ev:no"));
        bot.send(chat, "📅 " + cap(dayTitle(date)) + "\n\n" + summary(groups, date), rows.build());
    }

    /** /week: lessons of this week (or the next one on a weekend) with topics and the state of the plans. */
    void week(long uid, long chat) {
        LocalDate today = time.today();
        LocalDate monday = today.getDayOfWeek().getValue() >= 6 ? today.plusDays(8 - today.getDayOfWeek().getValue())
                : today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate sunday = monday.plusDays(6);
        List<KtpSlot> rows = KtpSlot.live(uid, monday, sunday);
        if (rows.isEmpty()) { // nothing left this week (for example on Friday): show the next one
            monday = monday.plusDays(7);
            sunday = sunday.plusDays(7);
            rows = KtpSlot.live(uid, monday, sunday);
        }
        if (rows.isEmpty()) {
            bot.send(chat, "На неделе " + monday.format(Dates.SHORT) + "–" + sunday.format(Dates.SHORT)
                    + " уроков по КТП нет. Загрузите КТП (/ktp) и заполните /schedule.", null);
            return;
        }
        Map<Long, KtpLesson> lessons = new LinkedHashMap<>();
        Map<Long, SchoolClass> classes = new LinkedHashMap<>();
        TreeMap<LocalDate, List<KtpSlot>> byDay = new TreeMap<>();
        for (KtpSlot s : rows) {
            lessons.computeIfAbsent(s.lessonId, id -> KtpLesson.findById(id));
            classes.computeIfAbsent(s.classId, id -> SchoolClass.findById(id));
            byDay.computeIfAbsent(s.slotDate, d -> new ArrayList<>()).add(s);
        }
        StringBuilder sb = new StringBuilder("🗓 Неделя ").append(monday.format(Dates.SHORT)).append("–")
                .append(sunday.format(Dates.SHORT));
        int ready = 0;
        int total = 0;
        for (var day : byDay.entrySet()) {
            sb.append("\n\n").append(Dates.dayShort(Dates.weekday(day.getKey()))).append(' ')
                    .append(day.getKey().format(Dates.SHORT));
            for (KtpSlot s : day.getValue()) {
                KtpLesson l = lessons.get(s.lessonId);
                SchoolClass c = classes.get(s.classId);
                boolean ok = KtpSlot.PREPARED.equals(s.status);
                total++;
                if (ok) ready++;
                sb.append("\n").append(s.lessonNo).append(" ур. · ").append(c == null ? "?" : c.label()).append(" — ")
                        .append(l == null ? "?" : Kb.trunc(l.topic, 45)).append(ok ? " ✅" : "");
            }
        }
        sb.append("\n\nПланов готово: ").append(ready).append(" из ").append(total).append(". ✅ значит план "
                + "составлен.");
        bot.send(chat, Kb.trunc(sb.toString(), 3900), Kb.row(btn("📅 Предложение на ближайший день", "ev:next")));
    }

    // ------------------------------------------------------------------ callbacks

    void onCallback(long uid, long chat, long msgId, String cbId, String[] p) {
        bot.answer(cbId, null);
        switch (p[1]) {
            case "no" -> bot.edit(chat, msgId, "Хорошо, напомню завтра вечером. Планы можно составить в /plan.", null);
            case "next" -> tomorrow(uid, chat);
            case "go" -> {
                LocalDate date = LocalDate.parse(p[2]);
                int n = 0;
                for (Group g : groups(uid, date)) {
                    if (!g.prepared() && generate(uid, chat, g, date, true)) n++;
                }
                bot.edit(chat, msgId, "Составляю планов: " + n + ". Файлы придут отдельными сообщениями.", null);
            }
            case "edit", "skip" -> {
                LocalDate date = LocalDate.parse(p[2]);
                List<Group> groups = groups(uid, date);
                if (groups.isEmpty()) return;
                boolean edit = "edit".equals(p[1]);
                if (groups.size() == 1) {
                    act(uid, chat, groups.get(0), date, edit);
                    return;
                }
                Kb.Rows rows = Kb.rows();
                for (Group g : groups) {
                    rows.add(btn(Kb.trunc(g.numbers() + " ур. · " + labels(g) + " · " + g.lesson().topic, 58),
                            "ev:" + (edit ? "e:" : "s:") + date + ":" + g.lesson().id));
                }
                bot.send(chat, edit ? "Какой урок изменить?" : "Какой урок не состоялся?", rows.build());
            }
            case "e", "s" -> {
                LocalDate date = LocalDate.parse(p[2]);
                long lessonId = Long.parseLong(p[3]);
                for (Group g : groups(uid, date)) {
                    if (g.lesson().id == lessonId) {
                        act(uid, chat, g, date, "e".equals(p[1]));
                        return;
                    }
                }
            }
            default -> { }
        }
    }

    private void act(long uid, long chat, Group g, LocalDate date, boolean edit) {
        if (edit) {
            plan.review(uid, chat, draftFor(g, date));
            return;
        }
        var built = slots.skip(uid, g.slots().stream().map(s -> s.id).toList());
        LOG.infof("[evening] user=%d skipped lesson #%d (%s) on %s", uid, g.lesson().seq, labels(g), date);
        bot.send(chat, "⏭ Урок «" + Kb.trunc(g.lesson().topic, 60) + "» не состоялся: он и следующие сдвинуты на "
                + "ближайшие свободные уроки.\n\n" + SlotService.describeAll(built), null);
    }

    // ------------------------------------------------------------------ helpers

    /** Starts the plan of a group. Returns false when it was started a moment ago or lacks objectives. */
    private boolean generate(long uid, long chat, Group g, LocalDate date, boolean verbose) {
        long now = System.currentTimeMillis();
        boolean fresh = g.slots().stream().noneMatch(s -> now - started.getOrDefault(s.id, 0L) < 180_000);
        if (!fresh) return false;
        Draft d = draftFor(g, date);
        if (d.objectives == null || d.objectives.isBlank()) {
            bot.send(chat, "В КТП у урока «" + Kb.trunc(g.lesson().topic, 50) + "» нет целей обучения. Допишите их:",
                    null);
            plan.review(uid, chat, d);
            return false;
        }
        g.slots().forEach(s -> started.put(s.id, now));
        plan.submit(uid, chat, d, g.slots().stream().map(s -> s.id).toList());
        return true;
    }

    private Draft draftFor(Group g, LocalDate date) {
        Draft d = new Draft();
        ktp.applyLesson(d, g.lesson().id);
        d.date = date.toString();
        d.classIds = g.classes().stream().map(c -> c.id).collect(Collectors.toCollection(ArrayList::new));
        d.notes = "";
        d.ktpAsked = true;
        d.bookAsked = true;
        d.books = new ArrayList<>(books.autoPick(g.lesson(), g.classes(), date));
        return d;
    }

    List<Group> groups(long uid, LocalDate date) {
        Map<Long, List<KtpSlot>> byLesson = new LinkedHashMap<>();
        for (KtpSlot s : KtpSlot.live(uid, date, date)) {
            byLesson.computeIfAbsent(s.lessonId, id -> new ArrayList<>()).add(s);
        }
        List<Group> out = new ArrayList<>();
        for (var e : byLesson.entrySet()) {
            KtpLesson l = KtpLesson.findById(e.getKey());
            if (l == null) continue;
            List<SchoolClass> classes = e.getValue().stream().map(s -> SchoolClass.<SchoolClass>findById(s.classId))
                    .filter(c -> c != null).toList();
            out.add(new Group(l, classes, e.getValue()));
        }
        return out;
    }

    private String summary(List<Group> groups, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        for (Group g : groups) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(g.numbers()).append(" урок · ").append(labels(g)).append(" — ");
            if (g.lesson().unitTitle != null && !g.lesson().unitTitle.isBlank()) {
                sb.append(Kb.trunc(g.lesson().unitTitle, 40)).append(" · ");
            }
            sb.append(Kb.trunc(g.lesson().topic, 70));
            if (!"lesson".equals(g.lesson().kind)) sb.append(" [").append(g.lesson().kind).append(']');
            if (g.prepared()) sb.append("\n    ✅ план уже готов");
            List<Draft.BookRef> refs = books.autoPick(g.lesson(), g.classes(), date);
            for (Draft.BookRef r : refs) {
                Book b = r.bookId == null ? null : Book.findById(r.bookId);
                if (b != null) sb.append("\n    📚 ").append(Kb.trunc(b.title, 40)).append(", стр. ").append(r.pages);
            }
            if (refs.isEmpty()) sb.append("\n    📚 книга: страницы не найдены, план без учебника");
        }
        return sb.toString();
    }

    private static String labels(Group g) {
        return g.classes().stream().map(SchoolClass::label).collect(Collectors.joining(", "));
    }

    private String dayTitle(LocalDate d) {
        LocalDate today = time.today();
        String name = Dates.dayName(Dates.weekday(d)).toLowerCase() + " " + d.format(Dates.SHORT);
        if (d.equals(today.plusDays(1))) return "завтра, " + name;
        if (d.equals(today)) return "сегодня, " + name;
        return name;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
