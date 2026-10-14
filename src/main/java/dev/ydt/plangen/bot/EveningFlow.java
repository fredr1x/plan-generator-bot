package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import dev.ydt.plangen.domain.Book;
import dev.ydt.plangen.domain.KtpClass;
import dev.ydt.plangen.domain.NonTeachingDay;
import dev.ydt.plangen.domain.ScheduleEntry;
import dev.ydt.plangen.domain.SchoolTerm;
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
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Comparator;
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
            rows.add(btn("✅ Составить все", "ev:go:" + date), btn("🎯 Выбрать", "ev:pk:d:" + date));
        }
        rows.add(btn("✏️ Изменить урок", "ev:edit:" + date), btn("⏭ Урок не состоялся", "ev:skip:" + date));
        rows.add(btn("Не сегодня", "ev:no"));
        bot.send(chat, "📅 " + cap(dayTitle(date)) + "\n\n" + summary(groups, date), rows.build());
    }

    /**
     * /week (offset 0) and /next_week (offset 1): the lessons of that calendar week, Monday to Sunday, with topics
     * and the state of the plans.
     */
    void week(long uid, long chat, int offset) {
        LocalDate today = time.today();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1).plusWeeks(offset);
        LocalDate sunday = monday.plusDays(6);
        String title = (offset == 0 ? "этой неделе" : "следующей неделе") + " (" + monday.format(Dates.SHORT) + "–"
                + sunday.format(Dates.SHORT) + ")";
        List<KtpSlot> rows = KtpSlot.live(uid, monday, sunday);
        Kb.Rows nav = Kb.rows();
        nav.add(offset == 0 ? btn("➡️ Следующая неделя", "ev:wk:1") : btn("⬅️ Эта неделя", "ev:wk:0"));

        Map<Long, SchoolClass> classes = ScheduleFlow.classMap(uid);
        Map<Long, KtpLesson> lessons = new HashMap<>();
        Map<LocalDate, Map<String, KtpSlot>> slotAt = new HashMap<>();
        for (KtpSlot s : rows) {
            lessons.computeIfAbsent(s.lessonId, id -> KtpLesson.findById(id));
            slotAt.computeIfAbsent(s.slotDate, d -> new LinkedHashMap<>()).put(s.classId + "#" + s.lessonNo, s);
        }
        List<SchoolTerm> terms = SchoolTerm.all();
        record Item(int no, String cls, String text) {}
        StringBuilder sb = new StringBuilder("🗓 Уроки на ").append(title);
        int ready = 0;
        int total = 0;
        int shownLessons = 0;
        for (LocalDate d = monday; !d.isAfter(sunday); d = d.plusDays(1)) {
            final LocalDate day = d;
            boolean inTerm = terms.stream().anyMatch(t -> t.contains(day));
            Map<String, KtpSlot> slots = slotAt.getOrDefault(day, Map.of());
            List<Item> items = new ArrayList<>();
            Set<String> used = new HashSet<>();
            // every lesson of the timetable, with the lesson of the KTP when there is one
            for (ScheduleEntry e : ScheduleEntry.forDay(uid, day.getDayOfWeek().getValue(), day)) {
                SchoolClass c = classes.get(e.classId);
                if (c == null) continue;
                String key = e.classId + "#" + e.lessonNo;
                KtpSlot slot = slots.get(key);
                if (slot != null) {
                    used.add(key);
                    items.add(new Item(slot.lessonNo, c.label(), slotLine(slot, lessons.get(slot.lessonId))));
                    total++;
                    if (KtpSlot.PREPARED.equals(slot.status)) ready++;
                    continue;
                }
                if (!inTerm) continue; // vacation: no lesson is expected
                NonTeachingDay off = NonTeachingDay.visible(uid, day).stream()
                        .filter(n -> n.covers(day) && n.appliesTo(c.grade)).findFirst().orElse(null);
                items.add(new Item(e.lessonNo, c.label(), off != null
                        ? "выходной" + (off.title == null ? "" : ": " + Kb.trunc(off.title, 30))
                        : "нет урока по КТП" + noKtpReason(c, day)));
            }
            for (var en : slots.entrySet()) { // a lesson of the KTP that is not in the timetable of that day
                if (used.contains(en.getKey())) continue;
                KtpSlot slot = en.getValue();
                items.add(new Item(slot.lessonNo, classes.get(slot.classId) == null ? "?" : classes.get(slot.classId).label(),
                        slotLine(slot, lessons.get(slot.lessonId))));
                total++;
                if (KtpSlot.PREPARED.equals(slot.status)) ready++;
            }
            if (items.isEmpty()) continue;
            items.sort(Comparator.comparingInt(Item::no).thenComparing(Item::cls));
            sb.append("\n\n").append(Dates.dayShort(Dates.weekday(day))).append(' ').append(day.format(Dates.SHORT));
            for (Item it : items) {
                sb.append("\n").append(it.no()).append(" ур. · ").append(it.cls()).append(" — ").append(it.text());
                shownLessons++;
            }
        }
        if (shownLessons == 0) {
            boolean any = !KtpSlot.live(uid, today, today.plusDays(400)).isEmpty();
            bot.send(chat, "На " + title + " уроков нет."
                    + (any ? " Это каникулы, выходные или в расписании на эти дни нет уроков."
                    : " Заполните /schedule, загрузите КТП (/ktp) и расставьте даты."), nav.build());
            return;
        }
        sb.append("\n\nПланов готово: ").append(ready).append(" из ").append(total).append(". ✅ значит план "
                + "составлен. «Нет урока по КТП» значит, что в расписании урок есть, а в КТП на эту дату нет.");
        long todo = rows.stream().filter(s -> !s.slotDate.isBefore(today) && !KtpSlot.PREPARED.equals(s.status))
                .map(s -> s.slotDate + "#" + s.lessonId).distinct().count();
        if (todo > 0) {
            nav.add(btn("✅ Составить планы недели (" + todo + ")", "ev:wgo:" + offset),
                    btn("🎯 Выбрать уроки", "ev:pk:w" + offset));
        }
        if (!lessons.isEmpty()) nav.add(btn("✏️ Изменить урок (темы, цели, страницы)", "ev:wed:" + offset));
        nav.add(btn("📅 Предложение на ближайший день", "ev:next"));
        bot.send(chat, Kb.trunc(sb.toString(), 3900), nav.build());
    }

    private static String slotLine(KtpSlot s, KtpLesson l) {
        return (l == null ? "?" : Kb.trunc(l.title(), 45)) + (KtpSlot.PREPARED.equals(s.status) ? " ✅" : "");
    }

    /** Why a timetable lesson has no KTP lesson that day. */
    private String noKtpReason(SchoolClass c, LocalDate day) {
        if (KtpClass.count("classId", c.id) == 0) return " (к классу не привязан КТП)";
        LocalDate first = KtpSlot.getEntityManager()
                .createQuery("select min(s.slotDate) from KtpSlot s where s.classId = ?1", LocalDate.class)
                .setParameter(1, c.id).getSingleResult();
        if (first != null && day.isBefore(first)) return " (КТП начинается с " + first.format(Dates.SHORT) + ")";
        return "";
    }

    // ------------------------------------------------------------------ callbacks

    void onCallback(long uid, long chat, long msgId, String cbId, String[] p) {
        bot.answer(cbId, null);
        switch (p[1]) {
            case "no" -> bot.edit(chat, msgId, "Хорошо, напомню завтра вечером. Планы можно составить в /plan.", null);
            case "next" -> tomorrow(uid, chat);
            case "wk" -> week(uid, chat, "1".equals(p[2]) ? 1 : 0);
            case "pk" -> pick(uid, chat, msgId, p[2], p.length > 3 ? p[3] : null, null);
            case "one" -> {
                LocalDate date = LocalDate.parse(p[3]);
                long lessonId = Long.parseLong(p[4]);
                for (Group g : groups(uid, date)) {
                    if (g.lesson().id == lessonId && !g.prepared()) generate(uid, chat, g, date, true);
                }
                pick(uid, chat, msgId, p[2], "d".equals(p[2]) ? p[3] : null, null);
            }
            case "wed" -> {
                LocalDate today = time.today();
                LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1).plusWeeks("1".equals(p[2]) ? 1 : 0);
                List<String> full = new ArrayList<>();
                List<String> data = new ArrayList<>();
                for (LocalDate d = monday; !d.isAfter(monday.plusDays(6)); d = d.plusDays(1)) {
                    if (d.isBefore(today)) continue;
                    for (Group g : groups(uid, d)) {
                        full.add(Dates.dayShort(Dates.weekday(d)) + " " + d.format(Dates.SHORT) + " · " + labels(g)
                                + " · " + g.lesson().title());
                        data.add("ev:e:" + d + ":" + g.lesson().id);
                    }
                }
                Kb.Rows rows = Kb.rows();
                List<String> lb = Kb.labels(full);
                for (int i = 0; i < data.size(); i++) rows.add(btn(lb.get(i), data.get(i)));
                bot.send(chat, "Какой урок изменить? Откроется экран с темой, целями и страницами книги."
                        + Kb.legend(full), rows.build());
            }
            case "wgo" -> {
                LocalDate today = time.today();
                LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1).plusWeeks("1".equals(p[2]) ? 1 : 0);
                int n = 0;
                for (LocalDate d = monday; !d.isAfter(monday.plusDays(6)); d = d.plusDays(1)) {
                    if (d.isBefore(today)) continue;
                    for (Group g : groups(uid, d)) {
                        if (!g.prepared() && generate(uid, chat, g, d, true)) n++;
                    }
                }
                bot.send(chat, "Составляю планов на неделю: " + n + ". Файлы придут отдельными сообщениями.", null);
            }
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
                List<String> full = groups.stream()
                        .map(g -> g.numbers() + " ур. · " + labels(g) + " · " + g.lesson().title()).toList();
                List<String> lb = Kb.labels(full);
                Kb.Rows rows = Kb.rows();
                for (int i = 0; i < groups.size(); i++) {
                    rows.add(btn(lb.get(i), "ev:" + (edit ? "e:" : "s:") + date + ":" + groups.get(i).lesson().id));
                }
                bot.send(chat, (edit ? "Какой урок изменить?" : "Какой урок не состоялся?") + Kb.legend(full),
                        rows.build());
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

    /** Selective list: each button starts the plan of one lesson; the list is redrawn without it. */
    private void pick(long uid, long chat, long msgId, String scope, String dateArg, String unused) {
        LocalDate today = time.today();
        List<LocalDate> days = new ArrayList<>();
        if ("d".equals(scope)) {
            days.add(LocalDate.parse(dateArg));
        } else {
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1)
                    .plusWeeks("w1".equals(scope) ? 1 : 0);
            for (int i = 0; i < 7; i++) {
                if (!monday.plusDays(i).isBefore(today)) days.add(monday.plusDays(i));
            }
        }
        long now = System.currentTimeMillis();
        List<String> full = new ArrayList<>();
        List<String> data = new ArrayList<>();
        for (LocalDate d : days) {
            for (Group g : groups(uid, d)) {
                if (g.prepared() || g.slots().stream().anyMatch(s -> now - started.getOrDefault(s.id, 0L) < 180_000)) {
                    continue;
                }
                full.add(Dates.dayShort(Dates.weekday(d)) + " " + d.format(Dates.SHORT) + " · " + labels(g) + " · "
                        + g.lesson().title());
                data.add("ev:one:" + scope + ":" + d + ":" + g.lesson().id);
            }
        }
        Kb.Rows rows = Kb.rows();
        List<String> lb = Kb.labels(full);
        for (int i = 0; i < data.size(); i++) rows.add(btn(lb.get(i), data.get(i)));
        int n = data.size();
        String text = n == 0 ? "Все планы составлены или уже составляются. Файлы придут отдельными сообщениями."
                : "Нажмите на урок, чтобы составить его план (осталось: " + n + ")." + Kb.legend(full);
        if (msgId > 0) bot.edit(chat, msgId, text, rows.build());
        else bot.send(chat, text, rows.build());
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
            sb.append(Kb.trunc(g.lesson().title(), 70));
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
