package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ydt.plangen.doc.FileNames;
import dev.ydt.plangen.doc.PlanDocx;
import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.KtpLesson;
import dev.ydt.plangen.domain.KtpSlot;
import dev.ydt.plangen.domain.LessonPlanRecord;
import dev.ydt.plangen.domain.ScheduleEntry;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.domain.Teacher;
import dev.ydt.plangen.llm.LessonContent;
import dev.ydt.plangen.llm.PlanService;
import dev.ydt.plangen.service.AnswerKeys;
import dev.ydt.plangen.service.BookService;
import dev.ydt.plangen.service.TimeService;
import dev.ydt.plangen.telegram.Dto.InlineButton;
import dev.ydt.plangen.telegram.Dto.InlineKeyboard;
import dev.ydt.plangen.telegram.Messenger;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * /plan: pick the day (and lesson from the timetable), collect section, topic and objectives,
 * then generate the plan in the background and send it as DOCX.
 */
@ApplicationScoped
public class PlanFlow {

    private static final Logger LOG = Logger.getLogger(PlanFlow.class);

    static final String PLAN_DAY = "PLAN_DAY";
    static final String PLAN_DATE = "PLAN_DATE";
    static final String PLAN_LESSON = "PLAN_LESSON";
    static final String PLAN_CLASSES = "PLAN_CLASSES";
    static final String PLAN_SECTION = "PLAN_SECTION";
    static final String PLAN_TOPIC = "PLAN_TOPIC";
    static final String PLAN_OBJECTIVES = "PLAN_OBJECTIVES";
    static final String PLAN_NOTES = "PLAN_NOTES";
    static final String PLAN_CONFIRM = "PLAN_CONFIRM";

    static final String PLAN_DATE_EDIT = "PLAN_DATE_EDIT";
    static final String PLAN_KTP = "PLAN_KTP";
    static final String PLAN_TEST = "PLAN_TEST";

    private static final int SUMMATIVE_MINUTES = 20;
    private static final int TERM_TEST_MINUTES = 45;
    private static final int[] LESSON_MINUTES = {20, 25, 30, 35, 40, 45, 50};

    // Notes go to the model, so they are in English (cheaper tokens).
    private static final String REDO_NOTE =
            "Offer a different version: other tasks, examples and techniques, do not repeat the previous "
                    + "lesson procedure.";
    private static final String SHORTER_NOTE =
            "Make the text of every field shorter and more compact (by about a third), keeping the concrete "
                    + "tasks, examples, assessment criteria and timings.";
    private static final String MORE_TASKS_NOTE =
            "Add more practice tasks in the middle of the lesson: at least three different tasks with ready "
                    + "text and different levels of difficulty, shortening the theory part. Do not change the "
                    + "total time.";

    /**
     * Everything the background task needs, captured while the DB transaction is still open.
     * previousJson is set when an existing plan is being edited instead of written from scratch.
     */
    private record Job(long userId, long chatId, Long placeholderId, LocalDate date, String subject, String lang,
                       int minutes, String school, String teacher, String classes, int students,
                       String section, String topic, String objectives, String notes, String previousJson,
                       String bookRefs, String bookContext, List<BookService.Answer> bookAnswers,
                       String classCounts, Long ktpLessonId, List<Long> slotIds, boolean summative,
                       boolean termTest, String testText) {}

    @Inject
    Messenger bot;

    @Inject
    BookFlow bookFlow;

    @Inject
    KtpFlow ktpFlow;

    @Inject
    PlanService plans;

    @Inject
    TimeService time;

    @Inject
    ObjectMapper mapper;

    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    // ---------- entry points ----------

    public void start(long uid, long chat) {
        Teacher t = Teacher.findById(uid);
        if (t == null || !t.isComplete()) {
            bot.send(chat, "Сначала заполните профиль: /start", null);
            return;
        }
        if (SchoolClass.forTeacher(uid).isEmpty()) {
            bot.send(chat, "Сначала добавьте классы: /classes", null);
            return;
        }
        save(uid, PLAN_DAY, new Draft());
        askDay(chat, "На какой день составляем план?", "pl:d:");
    }

    /** Day chooser. The prefix decides what happens next: pl:d: starts the flow, pl:dd: only changes the date. */
    private void askDay(long chat, String question, String prefix) {
        LocalDate today = time.today();
        bot.send(chat, question, Kb.rows()
                .add(btn("Сегодня · " + dayLabel(today), prefix + "0"))
                .add(btn("Завтра · " + dayLabel(today.plusDays(1)), prefix + "1"))
                .add(btn("Другая дата", prefix + "x"))
                .build());
    }

    public void history(long uid, long chat) {
        List<LessonPlanRecord> list = LessonPlanRecord.latest(uid, 5);
        if (list.isEmpty()) {
            bot.send(chat, "Пока нет составленных планов. Начните с /plan", null);
            return;
        }
        List<String> full = list.stream()
                .map(r -> r.planDate.format(Dates.SHORT) + " · " + r.classes + " · " + r.topic).toList();
        List<String> lb = Kb.labels(full);
        Kb.Rows rows = Kb.rows();
        for (int i = 0; i < list.size(); i++) rows.add(btn(lb.get(i), "pl:send:" + list.get(i).id));
        bot.send(chat, "Последние планы. Нажмите, чтобы получить файл ещё раз:" + Kb.legend(full), rows.build());
    }

    public void onText(long uid, long chat, ConvState st, String text) {
        Draft d = read(st);
        switch (st.state) {
            case PLAN_DATE -> {
                var date = Dates.parse(text, time.today());
                if (date.isEmpty()) {
                    bot.send(chat, "Не понял дату. Пример: 15.10 или 15.10.2026", null);
                    return;
                }
                d.date = date.get().toString();
                afterDate(uid, chat, d);
            }
            case PLAN_DATE_EDIT -> {
                var date = Dates.parse(text, time.today());
                if (date.isEmpty()) {
                    bot.send(chat, "Не понял дату. Пример: 15.10 или 15.10.2026", null);
                    return;
                }
                d.date = date.get().toString();
                askNext(uid, chat, d);
            }
            case PLAN_SECTION, PLAN_TOPIC, PLAN_OBJECTIVES, PLAN_NOTES -> {
                Map<String, String> kv = InputParser.parse(text);
                if (kv.isEmpty()) {
                    String v = text.strip();
                    switch (st.state) {
                        case PLAN_SECTION -> d.section = v;
                        case PLAN_TOPIC -> d.topic = v;
                        case PLAN_OBJECTIVES -> d.objectives = v;
                        default -> d.notes = v;
                    }
                } else {
                    apply(d, kv);
                }
                askNext(uid, chat, d);
            }
            case PLAN_TEST -> {
                d.testText = text.strip();
                confirm(uid, chat, d);
            }
            case BookFlow.PLAN_PAGES -> {
                if (bookFlow.onPages(uid, chat, d, classes(uid, d), LocalDate.parse(d.date), text)) {
                    askNext(uid, chat, d);
                }
            }
            case BookFlow.PLAN_BOOK ->
                    bot.send(chat, "Выберите книгу кнопкой выше или нажмите «Без учебника».", null);
            default -> bot.send(chat, "Нажмите одну из кнопок выше или /cancel, чтобы начать заново.", null);
        }
    }

    public void onCallback(long uid, long chat, long msgId, String cbId, String[] p) {
        switch (p[1]) {
            case "redo" -> {
                bot.answer(cbId, null);
                redo(uid, chat, Long.parseLong(p[2]));
                return;
            }
            case "send" -> {
                bot.answer(cbId, null);
                resend(uid, chat, Long.parseLong(p[2]));
                return;
            }
            case "adj" -> {
                bot.answer(cbId, null);
                adjust(uid, chat, Long.parseLong(p[2]), p[3]);
                return;
            }
            case "again" -> {
                bot.answer(cbId, null);
                again(uid, chat, Long.parseLong(p[2]));
                return;
            }
            case "cancel" -> {
                bot.answer(cbId, "Отменено");
                ConvState.clear(uid);
                bot.edit(chat, msgId, "Отменено.", null);
                return;
            }
            default -> { }
        }

        ConvState st = ConvState.get(uid);
        if (st == null || !st.state.startsWith("PLAN_")) {
            bot.answer(cbId, "Сессия устарела, начните заново: /plan");
            return;
        }
        Draft d = read(st);
        switch (p[1]) {
            case "d" -> {
                bot.answer(cbId, null);
                if ("x".equals(p[2])) {
                    ConvState.set(uid, PLAN_DATE, write(d));
                    bot.send(chat, "Введите дату урока, например 15.10 или 15.10.2026:", null);
                } else {
                    d.date = time.today().plusDays(Integer.parseInt(p[2])).toString();
                    afterDate(uid, chat, d);
                }
            }
            case "l" -> {
                bot.answer(cbId, null);
                int lessonNo = Integer.parseInt(p[2]);
                if (lessonNo == 0) {
                    askClasses(uid, chat, null, d);
                } else {
                    int wd = Dates.weekday(LocalDate.parse(d.date));
                    d.classIds = ScheduleEntry.forDay(uid, wd, LocalDate.parse(d.date)).stream()
                            .filter(e -> e.lessonNo == lessonNo)
                            .map(e -> e.classId)
                            .distinct()
                            .collect(Collectors.toCollection(ArrayList::new));
                    askNext(uid, chat, d);
                }
            }
            case "c" -> {
                bot.answer(cbId, null);
                Long id = Long.parseLong(p[2]);
                if (!d.classIds.remove(id)) d.classIds.add(id);
                askClasses(uid, chat, msgId, d);
            }
            case "cdone" -> {
                if (d.classIds.isEmpty()) {
                    bot.answer(cbId, "Выберите хотя бы один класс");
                    return;
                }
                bot.answer(cbId, null);
                askNext(uid, chat, d);
            }
            case "skipnotes" -> {
                bot.answer(cbId, null);
                d.notes = "";
                askNext(uid, chat, d);
            }
            case "dd" -> {
                bot.answer(cbId, null);
                if ("x".equals(p[2])) {
                    ask(uid, chat, d, PLAN_DATE_EDIT, "Введите новую дату урока, например 15.10 или 15.10.2026:");
                } else {
                    d.date = time.today().plusDays(Integer.parseInt(p[2])).toString();
                    askNext(uid, chat, d);
                }
            }
            case "copy" -> {
                bot.answer(cbId, null);
                copyPlan(uid, chat, d);
            }
            case "mn" -> {
                bot.answer(cbId, null);
                if (d.summative) {
                    bot.send(chat, "БЖБ идёт 20 минут. Чтобы выбрать другое время, выключите БЖБ.", null);
                    return;
                }
                Kb.Rows rows = Kb.rows();
                List<dev.ydt.plangen.telegram.Dto.InlineButton> line = new ArrayList<>();
                for (int m : LESSON_MINUTES) line.add(btn(m + " мин", "pl:min:" + m));
                rows.add(line);
                bot.send(chat, "Сколько минут длится урок?", rows.build());
            }
            case "min" -> {
                bot.answer(cbId, null);
                d.minutes = Integer.parseInt(p[2]);
                confirm(uid, chat, d);
            }
            case "bzb" -> {
                bot.answer(cbId, null);
                d.summative = !d.summative;
                if (d.summative) d.termTest = false;
                confirm(uid, chat, d);
            }
            case "pgs" -> {
                if (bookFlow.shiftPages(d, p[2])) {
                    bot.answer(cbId, null);
                    confirm(uid, chat, d);
                } else {
                    bot.answer(cbId, "Такой страницы нет в загруженной книге");
                }
            }
            case "tzb" -> {
                bot.answer(cbId, null);
                d.termTest = !d.termTest;
                if (d.termTest) {
                    d.summative = false;
                    askTest(uid, chat, d);
                } else {
                    confirm(uid, chat, d);
                }
            }
            case "tt" -> {
                bot.answer(cbId, null);
                askTest(uid, chat, d);
            }
            case "ku" -> {
                bot.answer(cbId, null);
                ktpFlow.onPickUnit(chat, msgId, Long.parseLong(p[2]), Integer.parseInt(p[3]));
            }
            case "kb" -> {
                bot.answer(cbId, null);
                ktpFlow.onBackToUnits(chat, msgId, Long.parseLong(p[2]));
            }
            case "kl" -> {
                bot.answer(cbId, null);
                if (ktpFlow.applyLesson(d, Long.parseLong(p[2]))) {
                    askNext(uid, chat, d);
                } else {
                    bot.send(chat, "Урок КТП не найден, введите данные вручную.", null);
                    d.ktpAsked = true;
                    askNext(uid, chat, d);
                }
            }
            case "kn" -> {
                bot.answer(cbId, null);
                d.ktpAsked = true;
                askNext(uid, chat, d);
            }
            case "frombook" -> {
                bot.answer(cbId, null);
                BookService.Heading h = bookFlow.proposal(d);
                if (h != null) {
                    if (!h.section().isBlank()) d.section = h.section();
                    if (!h.topic().isBlank()) d.topic = h.topic();
                }
                askNext(uid, chat, d);
            }
            case "bk" -> {
                bot.answer(cbId, null);
                if (bookFlow.onChoose(uid, chat, d, classes(uid, d), LocalDate.parse(d.date), p[2])) {
                    askNext(uid, chat, d);
                }
            }
            case "find" -> {
                bot.answer(cbId, null);
                bookFlow.onFind(uid, chat, d);
            }
            case "pg" -> {
                bot.answer(cbId, null);
                if (bookFlow.onPages(uid, chat, d, classes(uid, d), LocalDate.parse(d.date), p[2])) {
                    askNext(uid, chat, d);
                }
            }
            case "edit" -> {
                bot.answer(cbId, null);
                switch (p[2]) {
                    case "ktp" -> {
                        d.section = null;
                        d.topic = null;
                        d.objectives = null;
                        d.ktpLessonId = null;
                        d.ktpAsked = false;
                        askNext(uid, chat, d);
                    }
                    case "book" -> {
                        bookFlow.reset(d);
                        askNext(uid, chat, d);
                    }
                    case "date" -> {
                        save(uid, PLAN_DATE_EDIT, d);
                        askDay(chat, "Новая дата урока:", "pl:dd:");
                    }
                    case "classes" -> askClasses(uid, chat, null, d);
                    case "section" -> ask(uid, chat, d, PLAN_SECTION, "Раздел:");
                    case "topic" -> ask(uid, chat, d, PLAN_TOPIC, "Тема урока:");
                    case "objectives" -> ask(uid, chat, d, PLAN_OBJECTIVES, "Цели обучения с кодами:");
                    default -> ask(uid, chat, d, PLAN_NOTES, "Пожелания к уроку:");
                }
            }
            case "go" -> {
                bot.answer(cbId, null);
                generate(uid, chat, d);
            }
            default -> bot.answer(cbId, null);
        }
    }

    // ---------- steps ----------

    private void afterDate(long uid, long chat, Draft d) {
        LocalDate date = LocalDate.parse(d.date);
        int wd = Dates.weekday(date);
        Map<Long, SchoolClass> classes = ScheduleFlow.classMap(uid);
        Map<Integer, List<String>> lessons = ScheduleFlow
                .grouped(ScheduleEntry.forDay(uid, wd, date), classes)
                .getOrDefault(wd, Map.of());
        if (lessons.isEmpty()) {
            askClasses(uid, chat, null, d);
            return;
        }
        Kb.Rows rows = Kb.rows();
        lessons.forEach((no, labels) -> rows.add(btn(no + " урок · " + String.join(", ", labels), "pl:l:" + no)));
        rows.add(btn("Другие классы", "pl:l:0"));
        save(uid, PLAN_LESSON, d);
        bot.send(chat, Dates.dayName(wd) + ", " + date.format(Dates.SHORT) + ". Выберите урок из расписания:",
                rows.build());
    }

    private void askClasses(long uid, long chat, Long editMsg, Draft d) {
        List<SchoolClass> all = SchoolClass.forTeacher(uid);
        List<InlineButton> buttons = new ArrayList<>();
        for (SchoolClass c : all) {
            buttons.add(btn((d.classIds.contains(c.id) ? "✅ " : "▫️ ") + c.label(), "pl:c:" + c.id));
        }
        Kb.Rows rows = Kb.rows();
        for (int i = 0; i < buttons.size(); i += 3) {
            rows.add(buttons.subList(i, Math.min(i + 3, buttons.size())));
        }
        rows.add(btn("Готово →", "pl:cdone"));
        save(uid, PLAN_CLASSES, d);
        String text = "Выберите класс или несколько классов:";
        if (editMsg != null) {
            bot.edit(chat, editMsg, text, rows.build());
        } else {
            bot.send(chat, text, rows.build());
        }
    }

    /** Asks for the first missing field, or shows the summary when everything is there. */
    private void askNext(long uid, long chat, Draft d) {
        if (d.classIds.isEmpty()) {
            askClasses(uid, chat, null, d);
        } else if (!d.ktpAsked && ktpFlow.offerPick(uid, chat, d, classes(uid, d))) {
            save(uid, PLAN_KTP, d); // units of the KTP were offered, the flow waits for a tap
        } else if (blank(d.section)) {
            ask(uid, chat, d, PLAN_SECTION, "Раздел? (например: «Unit 4. Животные»)\n\n"
                    + "Можно прислать всё сразу одним сообщением:\nРаздел: ...\nТема: ...\nЦели: коды и формулировки\n"
                    + "Пожелания: ... (необязательно)");
        } else if (blank(d.topic)) {
            ask(uid, chat, d, PLAN_TOPIC, "Тема урока?");
        } else if (blank(d.objectives)) {
            ask(uid, chat, d, PLAN_OBJECTIVES,
                    "Цели обучения с кодами (скопируйте из КТП или программы, например: 7.2.3.1 …):");
        } else if (!d.bookAsked && bookFlow.offer(uid, chat, d, classes(uid, d), LocalDate.parse(d.date))) {
            // a question about the textbook was sent, the flow waits for the answer
        } else if (d.notes == null) {
            save(uid, PLAN_NOTES, d);
            bot.send(chat, "Есть пожелания к уроку? Например: «начать с игры», «работа в парах», «больше устной "
                    + "речи». Или нажмите «Пропустить».", Kb.row(btn("Пропустить", "pl:skipnotes")));
        } else {
            confirm(uid, chat, d);
        }
    }

    private void ask(long uid, long chat, Draft d, String state, String question) {
        save(uid, state, d);
        bot.send(chat, question, null);
    }

    /** Edits of a lesson that comes from the KTP are kept in the KTP lesson, not only in this draft. */
    private void saveToKtp(Draft d) {
        KtpLesson l = d.ktpLessonId == null ? null : KtpLesson.findById(d.ktpLessonId);
        if (l == null) return;
        String unit = l.unitTitle == null || l.unitTitle.isBlank() ? l.topic : l.unitTitle;
        if (!blank(d.section) && !d.section.equals(unit)) l.unitTitle = d.section;
        if (!blank(d.topic)) l.topic = d.topic;
        if (!blank(d.objectives)) l.objectives = d.objectives;
        if (!d.books.isEmpty()) l.bookRefs = bookFlow.toJson(d.books);
    }

    private void confirm(long uid, long chat, Draft d) {
        List<SchoolClass> classes = classes(uid, d);
        int students = classes.stream().mapToInt(c -> c.studentCount).sum();
        LocalDate date = LocalDate.parse(d.date);
        int minutes = minutesOf(d, Teacher.findById(uid));
        String text = "📝 Проверьте данные:\n"
                + "Дата: " + date.format(Dates.FULL) + " (" + Dates.dayShort(Dates.weekday(date)) + ")\n"
                + "Время урока: " + minutes + " мин" + (d.summative ? " (БЖБ)" : d.termTest ? " (ТЖБ)" : "") + "\n"
                + "Классы: " + labels(classes) + " (" + students + " уч.)\n"
                + "Раздел: " + d.section + "\n"
                + "Тема: " + d.topic + "\n"
                + "Цели обучения:\n" + d.objectives
                + (d.termTest ? "" : bookFlow.summaryLine(d))
                + (d.notes.isBlank() ? "" : "\nПожелания: " + d.notes)
                + (!d.termTest ? "" : blank(d.testText) ? "\nЗадания ТЖБ: ещё не присланы"
                : "\nЗадания ТЖБ: получены (" + d.testText.length() + " симв.)");
        saveToKtp(d);
        save(uid, PLAN_CONFIRM, d);
        Kb.Rows rows = Kb.rows().add(btn("✅ Составить план", "pl:go"));
        if (d.sourcePlanId != null) {
            rows.add(btn("♻️ Тот же план без ИИ (бесплатно)", "pl:copy"));
        }
        BookService.Heading fromBook = d.termTest ? null : bookFlow.proposal(d);
        if (fromBook != null) {
            rows.add(btn(Kb.trunc("📖 Из книги: " + (fromBook.section().isBlank() ? "" : fromBook.section() + " · ")
                    + fromBook.topic(), 60), "pl:frombook"));
        }
        if (!d.termTest && !d.books.isEmpty()) {
            rows.add(btn("◀ стр. −1", "pl:pgs:-1"), btn("стр. +1 ▶", "pl:pgs:1"), btn("➕ ещё страница", "pl:pgs:add"));
        }
        rows.add(btn("⏱ Время: " + minutes + " мин", "pl:mn"),
                btn(d.summative ? "📝 БЖБ: да ✅" : "📝 БЖБ: нет", "pl:bzb"),
                btn(d.termTest ? "🧪 ТЖБ: да ✅" : "🧪 ТЖБ: нет", "pl:tzb"));
        if (d.termTest) {
            rows.add(btn(blank(d.testText) ? "📎 Прислать задания ТЖБ" : "📎 Заменить задания ТЖБ", "pl:tt"));
        }
        rows.add(btn("✏️ Дата", "pl:edit:date"), btn("✏️ Классы", "pl:edit:classes"))
                .add(btn("✏️ Раздел", "pl:edit:section"), btn("✏️ Тема", "pl:edit:topic"),
                        btn("✏️ Цели", "pl:edit:objectives"))
                .add(d.termTest ? List.of(btn("✏️ Пожелания", "pl:edit:notes"))
                        : List.of(btn("✏️ Пожелания", "pl:edit:notes"), btn("📚 Учебник", "pl:edit:book")));
        if (ktpFlow.hasKtp(classes)) {
            rows.add(btn("📘 Другой урок из КТП", "pl:edit:ktp"));
        }
        rows.add(btn("✖ Отмена", "pl:cancel"));
        bot.send(chat, text, rows.build());
    }

    // ---------- generation ----------

    private void generate(long uid, long chat, Draft d) {
        Teacher t = Teacher.findById(uid);
        List<SchoolClass> classes = classes(uid, d);
        if (t == null || classes.isEmpty() || blank(d.topic) || blank(d.objectives)) {
            bot.send(chat, "Не хватает данных. Начните заново: /plan", null);
            return;
        }
        if (d.termTest && blank(d.testText)) {
            bot.send(chat, "Сначала пришлите задания ТЖБ.", null);
            askTest(uid, chat, d);
            return;
        }
        ConvState.clear(uid);
        submit(uid, chat, d, slotsOf(d, classes));
    }

    /** The planned slots that this draft covers: same KTP lesson, same classes, same day. */
    private List<Long> slotsOf(Draft d, List<SchoolClass> classes) {
        if (d.ktpLessonId == null || d.date == null) return List.of();
        return KtpSlot.<KtpSlot>list("lessonId = ?1 and slotDate = ?2 and classId in ?3 and status = 'planned'",
                d.ktpLessonId, LocalDate.parse(d.date), classes.stream().map(c -> c.id).toList())
                .stream().map(s -> s.id).toList();
    }

    /**
     * Starts the background generation for a complete draft without touching the dialogue state: the evening
     * preparation uses it too. The slots are marked as prepared when the plan is saved.
     */
    void submit(long uid, long chat, Draft d, List<Long> slotIds) {
        Teacher t = Teacher.findById(uid);
        List<SchoolClass> classes = classes(uid, d);
        LOG.infof("[plan] user=%d confirmed: date=%s classes=\"%s\" topic=\"%s\"", uid, d.date, labels(classes),
                d.topic);
        Long placeholder = bot.send(chat, "⏳ Составляю план «" + d.topic
                + "». Обычно это занимает 20–60 секунд…", null);
        String notes = LessonKinds.withKindNote(d.notes, d.ktpLessonId, d.summative);
        Job job = new Job(uid, chat, placeholder, LocalDate.parse(d.date), t.subject, t.lang, minutesOf(d, t),
                t.school, t.fullName, labels(classes), classes.stream().mapToInt(c -> c.studentCount).sum(),
                d.section, d.topic, d.objectives, notes, null, bookFlow.toJson(booksOf(d)),
                bookFlow.contextFor(booksOf(d)), bookFlow.answersFor(booksOf(d)), countsOf(classes), d.ktpLessonId,
                slotIds, d.summative, d.termTest, d.testText);
        exec.submit(() -> run(job));
    }

    /** The term test uses no coursebook. */
    private static List<Draft.BookRef> booksOf(Draft d) {
        return d.termTest ? List.of() : d.books;
    }

    private void askTest(long uid, long chat, Draft d) {
        ask(uid, chat, d, PLAN_TEST, "Пришлите одним сообщением текст заданий ТЖБ (СОЧ): listening, speaking, reading, "
                + "writing. Они попадут в план как есть. Урок: 3 минуты начало, 40 минут работа, 2 минуты конец.");
    }

    /** Lesson length: 20 minutes for the summative assessment, else the one chosen for this plan or the profile's. */
    private static int minutesOf(Draft d, Teacher t) {
        if (d.termTest) return TERM_TEST_MINUTES;
        if (d.summative) return SUMMATIVE_MINUTES;
        return d.minutes != null ? d.minutes : t.lessonMinutes;
    }

    /** The length of a stored plan is the sum of its stages. */
    private int minutesOf(LessonPlanRecord r, Teacher t) {
        try {
            int sum = mapper.readValue(r.contentJson, LessonContent.class).stages().stream()
                    .mapToInt(LessonContent.Stage::minutes).sum();
            if (sum > 0) return sum;
        } catch (IOException | RuntimeException e) {
            LOG.warnf("plan #%d: cannot read the length from the content: %s", r.id, e.getMessage());
        }
        return r.summative ? SUMMATIVE_MINUTES : t.lessonMinutes;
    }

    /** Shows the check screen for a draft that was filled in by the program (evening suggestions). */
    void review(long uid, long chat, Draft d) {
        confirm(uid, chat, d);
    }

    /** Fresh variant of an existing plan: same input, different activities. */
    private void redo(long uid, long chat, long recordId) {
        startRevision(uid, chat, recordId, REDO_NOTE, false, "⏳ Составляю другой вариант…");
    }

    /** Edits the stored plan: kind is "short" or "more". */
    private void adjust(long uid, long chat, long recordId, String kind) {
        boolean shorter = "short".equals(kind);
        startRevision(uid, chat, recordId, shorter ? SHORTER_NOTE : MORE_TASKS_NOTE, true,
                shorter ? "⏳ Сокращаю план…" : "⏳ Добавляю задания…");
    }

    private void startRevision(long uid, long chat, long recordId, String note, boolean editPrevious,
                               String progress) {
        LessonPlanRecord r = LessonPlanRecord.findById(recordId);
        Teacher t = Teacher.findById(uid);
        if (r == null || r.teacherId != uid || t == null) {
            bot.send(chat, "План не найден.", null);
            return;
        }
        if (r.termTest) {
            bot.send(chat, "План ТЖБ составлен из ваших заданий без ИИ, другой вариант и сокращение к нему не "
                    + "подходят. Чтобы изменить задания, составьте план заново: /plan", null);
            return;
        }
        LOG.infof("[revision] user=%d plan #%d: %s", uid, recordId,
                !editPrevious ? "another variant" : note == SHORTER_NOTE ? "shorter" : "more tasks");
        Long placeholder = bot.send(chat, progress, null);
        Job job = new Job(uid, chat, placeholder, r.planDate, t.subject, t.lang, minutesOf(r, t), t.school,
                t.fullName, r.classes, r.students, r.section, r.topic, r.objectives, note,
                editPrevious ? r.contentJson : null, r.bookRefs, bookFlow.contextFor(bookFlow.fromJson(r.bookRefs)),
                bookFlow.answersFor(bookFlow.fromJson(r.bookRefs)), countsFor(r), null, List.of(), r.summative, false, null);
        exec.submit(() -> run(job));
    }

    /** Reuse a finished lesson for another class or date: only the day and classes are asked again. */
    private void again(long uid, long chat, long recordId) {
        LessonPlanRecord r = LessonPlanRecord.findById(recordId);
        if (r == null || r.teacherId != uid) {
            bot.send(chat, "План не найден.", null);
            return;
        }
        Draft d = new Draft();
        d.section = r.section;
        d.topic = r.topic;
        d.objectives = r.objectives;
        d.notes = "";
        d.sourcePlanId = r.id;
        d.books = bookFlow.fromJson(r.bookRefs);
        d.bookAsked = true; // the same lesson keeps the same book pages
        save(uid, PLAN_DAY, d);
        askDay(chat, "Тот же урок «" + r.topic + "». На какой день?", "pl:d:");
    }

    /** Same content for other classes or another date, no model call. */
    private void copyPlan(long uid, long chat, Draft d) {
        LessonPlanRecord src = d.sourcePlanId == null ? null : LessonPlanRecord.findById(d.sourcePlanId);
        Teacher t = Teacher.findById(uid);
        List<SchoolClass> classes = classes(uid, d);
        if (src == null || src.teacherId != uid || t == null || classes.isEmpty()) {
            bot.send(chat, "Не получилось взять исходный план. Начните заново: /plan", null);
            return;
        }
        LessonPlanRecord r = new LessonPlanRecord();
        r.teacherId = uid;
        r.planDate = LocalDate.parse(d.date);
        r.classes = labels(classes);
        r.students = classes.stream().mapToInt(c -> c.studentCount).sum();
        r.section = d.section;
        r.topic = d.topic;
        r.objectives = d.objectives;
        r.contentJson = src.contentJson;
        r.bookRefs = src.bookRefs;
        r.classCounts = countsOf(classes);
        r.summative = src.summative;
        r.termTest = src.termTest;
        r.model = "copy";
        r.persist();
        LOG.infof("[copy] user=%d plan #%d copied to #%d for \"%s\" on %s (no model call)", uid, src.id, r.id,
                r.classes, r.planDate);
        ConvState.clear(uid);
        try {
            LessonContent content = mapper.readValue(r.contentJson, LessonContent.class);
            boolean sent = sendDocx(chat, new PlanDocx.Data(t.lang, t.school, t.fullName, r.planDate, r.classes,
                    r.students, r.section, r.topic, r.objectives, content, r.classCounts, r.termTest), r.id, r.topic);
            if (sent) {
                bot.send(chat, "Готово ✅ План скопирован для " + r.classes + " на "
                        + r.planDate.format(Dates.FULL) + ".", actions(r.id));
            }
        } catch (IOException e) {
            LOG.error("copy failed", e);
            bot.send(chat, "Не удалось собрать файл.", null);
        }
    }

    /** Buttons under every finished plan. */
    private static InlineKeyboard actions(long planId) {
        return Kb.rows()
                .add(btn("✂️ Короче", "pl:adj:" + planId + ":short"), btn("➕ Больше заданий", "pl:adj:" + planId + ":more"))
                .add(btn("🔁 Другой вариант", "pl:redo:" + planId))
                .add(btn("📋 Тот же урок: другой класс или дата", "pl:again:" + planId))
                .build();
    }

    private void resend(long uid, long chat, long recordId) {
        LessonPlanRecord r = LessonPlanRecord.findById(recordId);
        Teacher t = Teacher.findById(uid);
        if (r == null || r.teacherId != uid || t == null) {
            bot.send(chat, "План не найден.", null);
            return;
        }
        try {
            LessonContent content = mapper.readValue(r.contentJson, LessonContent.class);
            if (sendDocx(chat, new PlanDocx.Data(t.lang, t.school, t.fullName, r.planDate, r.classes, r.students,
                    r.section, r.topic, r.objectives, content, countsFor(r), r.termTest), r.id, r.topic)) {
                bot.send(chat, "Что сделать с этим планом?", actions(r.id));
            }
        } catch (IOException e) {
            LOG.error("resend failed", e);
            bot.send(chat, "Не удалось собрать файл.", null);
        }
    }

    /** Runs on a virtual thread: LLM call, save, DOCX, send. No DB transaction is held during the LLM call. */
    private void run(Job job) {
        var ctx = Arc.container().requestContext();
        ctx.activate();
        long started = System.nanoTime();
        try {
            LOG.infof("[job] user=%d start: classes=\"%s\" topic=\"%s\" minutes=%d revision=%s", job.userId(),
                    job.classes(), Kb.trunc(job.topic(), 60), job.minutes(), job.previousJson() != null);
            PlanService.Generated g = job.termTest()
                    ? new PlanService.Generated(TermTest.content(job.lang(), job.testText()), 0, 0, 0, 0)
                    : plans.generate(new PlanService.Request(job.subject(), job.lang(),
                    job.minutes(), job.classes(), job.students(), job.section(), job.topic(), job.objectives(),
                    job.notes(), job.previousJson(), job.bookContext()));
            // the model picks and cites the exercises, the exact answer keys come from the stored page data
            LessonContent content = AnswerKeys.inject(g.content(), job.bookAnswers());
            String json = mapper.writeValueAsString(content);
            Instant dayStart = time.startOf(time.today());
            record Saved(Long id, long[] today) {}
            Saved saved = QuarkusTransaction.requiringNew().call(() -> {
                LessonPlanRecord r = new LessonPlanRecord();
                r.teacherId = job.userId();
                r.planDate = job.date();
                r.classes = job.classes();
                r.students = job.students();
                r.section = job.section();
                r.topic = job.topic();
                r.objectives = job.objectives();
                r.contentJson = json;
                r.model = job.termTest() ? "local" : plans.model();
                r.bookRefs = job.bookRefs();
                r.classCounts = job.classCounts();
                r.summative = job.summative();
                r.termTest = job.termTest();
                r.tokensIn = g.tokensIn();
                r.tokensOut = g.tokensOut();
                r.attempts = g.attempts();
                r.millis = (int) g.millis();
                r.persist();
                if (!job.slotIds().isEmpty()) {
                    KtpSlot.update("status = 'prepared', planId = ?1 where id in ?2", r.id, job.slotIds());
                }
                if (job.ktpLessonId() != null && job.bookRefs() != null) {
                    // the pages are chosen once per KTP lesson: the next time they are taken from here
                    KtpLesson.update("bookRefs = ?1 where id = ?2", job.bookRefs(), job.ktpLessonId());
                }
                return new Saved(r.id, LessonPlanRecord.usageSince(job.userId(), dayStart));
            });
            Long id = saved.id();
            long[] day = saved.today();
            LOG.infof("[job] plan #%d saved: tokens in=%d out=%d (est. $%.4f), attempts=%d, model time %d ms",
                    id, g.tokensIn(), g.tokensOut(), plans.estimateCost(g.tokensIn(), g.tokensOut()),
                    g.attempts(), g.millis());
            LOG.infof("[usage] today for user=%d: plans=%d, tokens in=%d out=%d, est. $%.4f",
                    job.userId(), day[0], day[1], day[2], plans.estimateCost(day[1], day[2]));
            if (job.placeholderId() != null) bot.delete(job.chatId(), job.placeholderId());
            boolean sent = sendDocx(job.chatId(), new PlanDocx.Data(job.lang(), job.school(), job.teacher(),
                    job.date(), job.classes(), job.students(), job.section(), job.topic(), job.objectives(),
                    content, job.classCounts(), job.termTest()), id, job.topic());
            LOG.infof("[job] user=%d finished in %d ms, document sent=%s", job.userId(),
                    (System.nanoTime() - started) / 1_000_000, sent);
            if (sent) {
                bot.send(job.chatId(), "Готово ✅ Прочитайте план перед уроком: ИИ может ошибаться.\n"
                        + "Можно сократить, добавить заданий, получить другой вариант или взять этот урок "
                        + "для другого класса.", actions(id));
            }
        } catch (Exception e) {
            LOG.errorf(e, "[job] user=%d plan generation FAILED after %d ms", job.userId(),
                    (System.nanoTime() - started) / 1_000_000);
            if (job.placeholderId() != null) {
                bot.edit(job.chatId(), job.placeholderId(),
                        "Не получилось составить план. Попробуйте ещё раз через минуту: /plan", null);
            } else {
                bot.send(job.chatId(), "Не получилось составить план. Попробуйте ещё раз: /plan", null);
            }
        } finally {
            ctx.terminate();
        }
    }

    private boolean sendDocx(long chat, PlanDocx.Data data, long recordId, String topic) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("plan");
            Path file = dir.resolve(FileNames.docx(data.date(), data.classes(), data.topic()));
            PlanDocx.write(data, file);
            long size = Files.size(file);
            boolean sent = bot.sendDocument(chat, file.toFile(), "План урока: " + Kb.trunc(topic, 150));
            LOG.infof("[docx] %s, %d bytes, sent to telegram=%s", file.getFileName(), size, sent);
            return sent;
        } catch (IOException e) {
            LOG.error("docx failed", e);
            bot.send(chat, "Не удалось собрать файл.", null);
            return false;
        } finally {
            deleteQuietly(dir);
        }
    }

    // ---------- helpers ----------

    private static void apply(Draft d, Map<String, String> kv) {
        if (kv.containsKey("section")) d.section = kv.get("section");
        if (kv.containsKey("topic")) d.topic = kv.get("topic");
        if (kv.containsKey("objectives")) d.objectives = kv.get("objectives");
        if (kv.containsKey("notes")) d.notes = kv.get("notes");
    }

    private List<SchoolClass> classes(long uid, Draft d) {
        Map<Long, SchoolClass> all = ScheduleFlow.classMap(uid);
        return d.classIds.stream().map(all::get).filter(c -> c != null)
                .sorted(Comparator.comparingInt((SchoolClass c) -> c.grade).thenComparing(c -> c.letter))
                .toList();
    }

    /** "7 А=25;7 Б=24": the class list in the form the document builder reads. */
    private static String countsOf(List<SchoolClass> classes) {
        return classes.stream().map(c -> c.label() + "=" + c.studentCount).collect(Collectors.joining(";"));
    }

    /** Stored counts of a plan; for plans made before they were stored, the teacher's current classes are used. */
    private static String countsFor(LessonPlanRecord r) {
        if (r.classCounts != null && !r.classCounts.isBlank()) {
            return r.classCounts;
        }
        List<SchoolClass> mine = SchoolClass.forTeacher(r.teacherId);
        List<SchoolClass> found = new ArrayList<>();
        for (String label : r.classes.split(",\\s*")) {
            mine.stream().filter(c -> c.label().equals(label.strip())).findFirst().ifPresent(found::add);
        }
        return found.isEmpty() ? null : countsOf(found);
    }

    private static String labels(List<SchoolClass> classes) {
        return classes.stream().map(SchoolClass::label).collect(Collectors.joining(", "));
    }

    private static String dayLabel(LocalDate d) {
        return Dates.dayShort(Dates.weekday(d)) + " " + d.format(Dates.SHORT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private Draft read(ConvState st) {
        try {
            return st.payload == null ? new Draft() : mapper.readValue(st.payload, Draft.class);
        } catch (JsonProcessingException e) {
            return new Draft();
        }
    }

    private String write(Draft d) {
        try {
            return mapper.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void save(long uid, String state, Draft d) {
        ConvState.set(uid, state, write(d));
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var files = Files.list(dir)) {
            files.forEach(f -> f.toFile().delete());
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // temp files, the OS cleans them up eventually
        }
    }
}
