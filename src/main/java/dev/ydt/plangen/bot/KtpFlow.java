package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ydt.plangen.domain.Book;
import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.Ktp;
import dev.ydt.plangen.domain.KtpClass;
import dev.ydt.plangen.domain.KtpLesson;
import dev.ydt.plangen.domain.KtpSlot;
import dev.ydt.plangen.domain.KtpTerm;
import dev.ydt.plangen.domain.ScheduleEntry;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.domain.Teacher;
import dev.ydt.plangen.ktp.KtpAiParser;
import dev.ydt.plangen.ktp.KtpParser;
import dev.ydt.plangen.ktp.KtpParser.Mapping;
import dev.ydt.plangen.ktp.KtpParser.Outcome;
import dev.ydt.plangen.ktp.KtpParser.Parsed;
import dev.ydt.plangen.ktp.KtpReader;
import dev.ydt.plangen.ktp.KtpReader.RawDoc;
import dev.ydt.plangen.service.KtpService;
import dev.ydt.plangen.service.SlotService;
import dev.ydt.plangen.service.TimeService;
import dev.ydt.plangen.domain.SchoolTerm;
import dev.ydt.plangen.service.KtpService.UnitInfo;
import dev.ydt.plangen.telegram.Dto.Document;
import dev.ydt.plangen.telegram.Messenger;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * /ktp: the teacher sends the KTP as a Word file, the bot reads the lessons (unit, topic, objectives with codes,
 * hours), shows a summary to check, and saves it for the classes that follow it. A plan can then be started from
 * a KTP lesson. Only the columns every KTP has are used, because layouts differ from school to school.
 */
@ApplicationScoped
public class KtpFlow {

    private static final Logger LOG = Logger.getLogger(KtpFlow.class);

    static final String KTP_FILE = "KTP_FILE";
    static final String KTP_MAP = "KTP_MAP";
    static final String KTP_GRADE = "KTP_GRADE";
    static final String KTP_REVIEW = "KTP_REVIEW";
    static final String KTP_CLASSES = "KTP_CLASSES";
    static final String KTP_EDIT = "KTP_EDIT";
    static final String KTP_START = "KTP_START";

    private static final long MAX_FILE = 10L * 1024 * 1024;
    private static final Pattern MAP_ITEM = Pattern.compile(
            "(?i)(номер|№|number|юнит|раздел|unit|тема|topic|theme|цели|цель|objectives?|часы|час|hours?|дата|date)\\D{0,6}(\\d{1,2})");
    private static final Pattern EDIT = Pattern.compile("(?is)^\\s*(\\d{1,3})\\s*(цели|цель|objectives?)?\\s*[:\\-–]\\s*(.+)$");

    /** What is being imported while the teacher checks it. */
    public static class Import {
        public String fileName;
        public Parsed parsed;
        public Integer grade;
        /** Grades 10-11: natural_math or social_humanities. */
        public String track;
        /** The teacher has said which grade (and track) the KTP is for. */
        public boolean gradeAsked;
        public List<Long> classIds = new ArrayList<>();
        /** The raw table: kept so that the columns can be asked and the plain parser can be tried instead. */
        public RawDoc raw;
        /** True when the lessons were read by the model (and checked against the file). */
        public boolean viaAi;
    }

    @Inject
    Messenger bot;

    @Inject
    KtpService ktps;

    @Inject
    ObjectMapper mapper;

    @Inject
    KtpAiParser aiParser;

    @Inject
    SlotService slots;

    @Inject
    TimeService time;

    /** KTP_USE_AI=false switches the model off (the plain parser is used). */
    @ConfigProperty(name = "app.ktp.use-ai", defaultValue = "true")
    boolean useAi;

    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    // ------------------------------------------------------------------ entry points

    /** /ktp: the saved plans, or the request for a file. */
    void start(long uid, long chat) {
        List<Ktp> mine = Ktp.forTeacher(uid);
        if (mine.isEmpty()) {
            askFile(uid, chat);
            return;
        }
        Kb.Rows rows = Kb.rows();
        StringBuilder sb = new StringBuilder("📘 Ваши КТП\n");
        for (Ktp k : mine) {
            long lessons = KtpLesson.count("ktpId = ?1", k.id);
            sb.append("\n• ").append(k.gradeLabel());
            if (k.schoolYear != null) sb.append(", ").append(k.schoolYear);
            sb.append(": ").append(lessons).append(" уроков, ").append(k.totalHours).append(" ч.");
            List<KtpTerm> terms = KtpTerm.ofKtp(k.id);
            if (!terms.isEmpty()) {
                sb.append("\n  четверти: ").append(terms.stream().map(t -> t.hours == null ? "?" : String.valueOf(t.hours))
                        .collect(Collectors.joining(" / "))).append(" ч.");
            }
            List<KtpClass> links = KtpClass.ofKtp(k.id);
            if (links.isEmpty()) sb.append("\n  классы не привязаны");
            for (KtpClass link : links) {
                SchoolClass c = SchoolClass.findById(link.classId);
                if (c != null) sb.append(classLine(k, c));
            }
            rows.add(btn("📋 Юниты и темы · " + k.gradeLabel(), "ktp:units:" + k.id));
            rows.add(btn("🔗 Классы", "ktp:lk:" + k.id), btn("📅 Пересчитать даты", "ktp:dates:" + k.id));
            rows.add(btn("🔄 Даты заново с начала года", "ktp:rd:" + k.id));
            rows.add(btn("✏️ Исправить урок", "ktp:edit:" + k.id), btn("🗑 Удалить", "ktp:del:" + k.id));
        }
        rows.add(btn("➕ Загрузить КТП", "ktp:new"));
        bot.send(chat, sb.toString(), rows.build());
    }

    /** The classes that can follow this KTP (same grade, same track for 10-11) with a tick for the linked ones. */
    private void showLinks(long uid, long chat, Long editMsg, Ktp k) {
        List<SchoolClass> fitting = SchoolClass.forTeacher(uid).stream()
                .filter(c -> k.grade != null && c.grade == k.grade
                        && (k.track == null || c.track == null || c.track.equals(k.track)))
                .toList();
        if (fitting.isEmpty()) {
            bot.send(chat, "Нет классов, которые подходят к КТП (" + k.gradeLabel() + "). Добавьте класс в /classes "
                    + (k.track == null ? "" : "и укажите направление ") + "и вернитесь сюда.", null);
            return;
        }
        java.util.Set<Long> mine = KtpClass.ofKtp(k.id).stream().map(l -> l.classId)
                .collect(Collectors.toSet());
        Kb.Rows rows = Kb.rows();
        for (SchoolClass c : fitting) {
            boolean other = !mine.contains(c.id) && KtpClass.count("classId = ?1 and ktpId <> ?2", c.id, k.id) > 0;
            rows.add(btn((mine.contains(c.id) ? "✅ " : "▫️ ") + c.label() + (other ? " (сейчас по другому КТП)" : ""),
                    "ktp:lt:" + k.id + ":" + c.id));
        }
        rows.add(btn("Готово →", "ktp:ld:" + k.id));
        String text = "🔗 Классы КТП (" + k.gradeLabel() + "). Отметьте классы, которые идут по этому плану: один КТП "
                + "могут вести несколько классов (параллели), а класс идёт только по одному КТП.";
        if (editMsg != null) bot.edit(chat, editMsg, text, rows.build());
        else bot.send(chat, text, rows.build());
    }

    /** Links the class to the KTP, or unlinks it. A class that followed another KTP moves to this one. */
    private void toggleLink(Ktp k, SchoolClass c) {
        KtpClass link = KtpClass.find("ktpId = ?1 and classId = ?2", k.id, c.id).firstResult();
        if (link != null) {
            link.delete();
            KtpSlot.delete("ktpId = ?1 and classId = ?2", k.id, c.id);
            LOG.infof("[ktp] class %s unlinked from KTP #%d", c.label(), k.id);
            return;
        }
        KtpClass.delete("classId = ?1 and ktpId <> ?2", c.id, k.id);
        KtpSlot.delete("classId = ?1 and ktpId <> ?2", c.id, k.id);
        KtpClass.getEntityManager().flush();
        KtpClass add = new KtpClass();
        add.ktpId = k.id;
        add.classId = c.id;
        add.persist();
        LOG.infof("[ktp] class %s linked to KTP #%d", c.label(), k.id);
    }

    /** One line per class of a KTP: the timetable load and where the dates stand. */
    private String classLine(Ktp k, SchoolClass c) {
        long perWeek = ScheduleEntry.at(c.teacherId, time.today()).stream().filter(e -> e.classId == c.id).count();
        StringBuilder sb = new StringBuilder("\n  ▸ ").append(c.label()).append(": ");
        sb.append(perWeek == 0 ? "нет уроков в расписании" : perWeek + " ур. в неделю в расписании");
        List<KtpSlot> live = KtpSlot.list("ktpId = ?1 and classId = ?2 and status in ('planned', 'prepared') "
                + "order by slotDate, lessonNo", k.id, c.id);
        if (live.isEmpty()) {
            sb.append(perWeek == 0 ? "" : ", даты не расставлены (кнопка «Даты уроков»)");
            return sb.toString();
        }
        KtpSlot next = live.stream().filter(x -> !x.slotDate.isBefore(time.today())).findFirst().orElse(null);
        long prepared = live.stream().filter(x -> KtpSlot.PREPARED.equals(x.status)).count();
        long left = live.stream().filter(x -> !x.slotDate.isBefore(time.today())).count();
        sb.append("\n    даты: ").append(live.get(0).slotDate.format(Dates.SHORT)).append("–")
                .append(live.get(live.size() - 1).slotDate.format(Dates.SHORT)).append(", впереди уроков: ")
                .append(left).append(" из ").append(live.size()).append(", планов готово: ").append(prepared);
        if (next != null) {
            KtpLesson l = KtpLesson.findById(next.lessonId);
            sb.append("\n    ближайший: ").append(next.slotDate.format(Dates.SHORT)).append(" · ")
                    .append(l == null ? "?" : "№" + l.seq + " " + l.title());
        }
        // lessons that did not fit into their term have no slot
        long dated = KtpSlot.count("ktpId = ?1 and classId = ?2 and status <> 'skipped'", k.id, c.id);
        long without = KtpLesson.count("ktpId", k.id) - dated;
        if (without > 0) {
            sb.append("\n    ⚠️ без даты (не поместились в четверть): ").append(without);
        }
        return sb.toString();
    }

    private void askFile(long uid, long chat) {
        ConvState.set(uid, KTP_FILE, null);
        bot.send(chat, "Пришлите файл КТП (Word: .docx или .doc, либо PDF с текстом). Я возьму из него юниты, темы, цели обучения с "
                + "кодами и часы. Таблицы у КТП бывают разные, нужны хотя бы номер, тема, цели и часы.\n"
                + "Отмена: /cancel", null);
    }

    /** A Word file arrived: read it as a KTP. */
    void onDocument(long uid, long chat, Document doc) {
        String name = doc.fileName() == null ? "" : doc.fileName();
        String low = name.toLowerCase(Locale.ROOT);
        if (!doc.fileId().startsWith("local:") && !low.endsWith(".docx") && !low.endsWith(".doc")
                && !low.endsWith(".pdf")) {
            bot.send(chat, "Для КТП пришлите файл Word (.docx, .doc) или PDF с текстом.", null);
            return;
        }
        byte[] data;
        try {
            data = bot.download(doc.fileId(), MAX_FILE);
        } catch (IOException e) {
            LOG.warnf("KTP download failed: %s", e.getMessage());
            bot.send(chat, "Не получилось скачать файл: " + e.getMessage(), null);
            return;
        }
        importBytes(uid, chat, name, data);
    }

    void importBytes(long uid, long chat, String fileName, byte[] data) {
        RawDoc raw;
        try {
            raw = KtpReader.read(data);
        } catch (IOException | RuntimeException e) {
            LOG.warnf("KTP read failed: %s", e.getMessage());
            bot.send(chat, "Не смог прочитать файл: " + e.getMessage()
                    + "\nПроверьте, что это Word-файл с таблицей КТП.", null);
            return;
        }
        // the plain code parse: the cross-check for the model, and the fallback when the model is not used
        if (raw.flat() && !useAi) {
            bot.send(chat, "PDF читает только ИИ, а он сейчас выключен. Пришлите КТП в Word (.docx или .doc).", null);
            return;
        }
        // a PDF has no table, so only the model can read it (no code parse to compare with)
        Outcome o = raw.flat() ? new Outcome(null, true, List.of()) : KtpParser.parse(raw, null);
        LOG.infof("[ktp] user=%d file=\"%s\": %d %s, columns %s", uid, fileName, raw.rows().size(),
                raw.flat() ? "PDF text lines" : "table rows", raw.flat() ? "read by the model" :
                o.needMapping() ? "NOT recognised" : "recognised");
        Import d = new Import();
        d.fileName = fileName;
        d.raw = raw;
        if (useAi) {
            Long placeholder = bot.send(chat, "⏳ Читаю КТП с помощью ИИ, это займёт около минуты…", null);
            Parsed det = o.needMapping() ? null : o.parsed();
            exec.submit(() -> runAi(uid, chat, placeholder, d, det));
            return;
        }
        if (o.needMapping()) {
            save(uid, KTP_MAP, d);
            bot.send(chat, mappingQuestion(o.preview()), null);
            return;
        }
        d.parsed = o.parsed();
        review(uid, chat, d);
    }

    /** Runs on its own thread: the model call can take a minute, no DB transaction is open during it. */
    private void runAi(long uid, long chat, Long placeholder, Import d, Parsed det) {
        var ctx = Arc.container().requestContext();
        ctx.activate();
        try {
            Parsed result = null;
            try {
                result = aiParser.parse(d.raw, det).parsed();
                d.viaAi = true;
            } catch (RuntimeException e) {
                LOG.errorf(e, "[ktp-ai] user=%d the model could not read the KTP", uid);
            }
            final Parsed shown = result;
            QuarkusTransaction.requiringNew().run(() -> {
                if (placeholder != null) bot.delete(chat, placeholder);
                if (shown != null) {
                    d.parsed = shown;
                    review(uid, chat, d);
                } else if (det != null) {
                    det.warnings.add(0, "ИИ сейчас недоступен, разобрал файл без него.");
                    d.parsed = det;
                    d.viaAi = false;
                    review(uid, chat, d);
                } else {
                    if (d.raw.flat()) {
                        ConvState.clear(uid);
                        bot.send(chat, "ИИ сейчас недоступен, а PDF без него не прочитать. Попробуйте ещё раз "
                                + "через минуту или пришлите КТП в Word.", null);
                        return;
                    }
                    save(uid, KTP_MAP, d);
                    bot.send(chat, "ИИ сейчас недоступен.\n" + mappingQuestion(KtpParser.parse(d.raw, null).preview()),
                            null);
                }
            });
        } finally {
            ctx.terminate();
        }
    }

    // ------------------------------------------------------------------ text

    void onText(long uid, long chat, ConvState st, String text) {
        switch (st.state) {
            case KTP_MAP -> onMapping(uid, chat, st, text);
            case KTP_GRADE -> {
                Import d = read(st);
                String t = text.strip();
                if (d == null || !t.matches("\\d{1,2}") || Integer.parseInt(t) < 1 || Integer.parseInt(t) > 11) {
                    bot.send(chat, "Напишите номер класса числом от 1 до 11.", null);
                    return;
                }
                d.grade = Integer.valueOf(t);
                d.gradeAsked = true;
                d.track = null;
                review(uid, chat, d);
            }
            case KTP_EDIT -> onEdit(uid, chat, st, text);
            case KTP_START -> {
                Ktp k = st.payload == null ? null : ownedKtp(uid, Long.parseLong(st.payload));
                String t = text.strip();
                if (k == null || !t.matches("\\d{1,3}")) {
                    bot.send(chat, "Напишите номер урока числом, например 12.", null);
                    return;
                }
                int n = Integer.parseInt(t);
                if (n < 1 || n > KtpLesson.count("ktpId", k.id)) {
                    bot.send(chat, "В этом КТП уроки с 1 по " + KtpLesson.count("ktpId", k.id) + ".", null);
                    return;
                }
                ConvState.clear(uid);
                buildDates(uid, chat, k, n);
            }
            default -> bot.send(chat, "Нажмите одну из кнопок выше или /cancel.", null);
        }
    }

    private void onMapping(long uid, long chat, ConvState st, String text) {
        Import d = read(st);
        if (d == null || d.raw == null) {
            bot.send(chat, "Сессия устарела, начните заново: /ktp", null);
            return;
        }
        int[] col = {-1, -1, -1, -1, -1, -1}; // number unit topic objectives hours date
        Matcher m = MAP_ITEM.matcher(text);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(2)) - 1;
            String w = m.group(1).toLowerCase(Locale.ROOT);
            if (w.startsWith("ном") || w.equals("№") || w.startsWith("num")) col[0] = idx;
            else if (w.startsWith("юн") || w.startsWith("раз") || w.startsWith("uni")) col[1] = idx;
            else if (w.startsWith("тем") || w.startsWith("top") || w.startsWith("the")) col[2] = idx;
            else if (w.startsWith("цел") || w.startsWith("obj")) col[3] = idx;
            else if (w.startsWith("час") || w.startsWith("hou")) col[4] = idx;
            else col[5] = idx;
        }
        if (col[2] < 0) {
            bot.send(chat, "Укажите хотя бы колонку темы, например: «тема 3, цели 4, часы 5».", null);
            return;
        }
        Outcome o = KtpParser.parse(d.raw, new Mapping(col[0], col[1], col[2], col[3], col[4], col[5]));
        if (o.needMapping() || o.parsed() == null) {
            bot.send(chat, "Не получилось разобрать таблицу с такими колонками. Попробуйте ещё раз.", null);
            return;
        }
        d.parsed = o.parsed();
        d.raw = null;
        review(uid, chat, d);
    }

    private void onEdit(long uid, long chat, ConvState st, String text) {
        long ktpId = Long.parseLong(st.payload);
        Ktp k = ownedKtp(uid, ktpId);
        Matcher m = EDIT.matcher(text);
        if (k == null || !m.matches()) {
            bot.send(chat, "Формат: «5: новая тема» или «5 цели: 4.1.1.1 текст цели». Отмена: /cancel", null);
            return;
        }
        KtpLesson l = KtpLesson.find("ktpId = ?1 and seq = ?2", ktpId, Integer.parseInt(m.group(1))).firstResult();
        if (l == null) {
            bot.send(chat, "Урока с таким номером нет (в КТП " + KtpLesson.count("ktpId = ?1", ktpId) + " уроков).",
                    null);
            return;
        }
        // the hours of one topic are one thing for the teacher: the change goes to all of them
        List<KtpLesson> group = l.parts > 1
                ? KtpLesson.list("ktpId = ?1 and seq >= ?2 and seq <= ?3", ktpId, l.seq - (l.part - 1),
                        l.seq + (l.parts - l.part))
                : List.of(l);
        if (m.group(2) != null) {
            String[] norm = KtpParser.normalizeObjectives(m.group(3));
            for (KtpLesson x : group) {
                x.objectives = norm[0];
                x.codes = norm[1];
            }
        } else {
            String topic = m.group(3).strip();
            for (KtpLesson x : group) x.topic = topic;
        }
        ConvState.clear(uid);
        bot.send(chat, "✅ Урок " + l.seq + " обновлён:\n" + l.topic + (l.objectives == null || l.objectives.isBlank()
                ? "" : "\n" + l.objectives), Kb.row(btn("Мои КТП", "ktp:list")));
    }

    // ------------------------------------------------------------------ review and save

    /** Which grade is the KTP for: the grades of the teacher's classes, the one found in the file is marked. */
    private void askGrade(long uid, long chat, Import d) {
        Integer guess = d.parsed.grade != null ? d.parsed.grade : KtpParser.gradeFromCodes(d.parsed);
        // one button per grade (and track for grades 10-11) of the teacher's classes: a tap gives both answers
        java.util.TreeSet<String> options = new java.util.TreeSet<>(java.util.Comparator
                .comparingInt((String o) -> Integer.parseInt(o.split(":")[0])).thenComparing(o -> o));
        SchoolClass.<SchoolClass>forTeacher(uid).forEach(c -> options.add(c.grade + (c.grade >= 10 && c.track != null
                ? ":" + (Book.NATURAL_MATH.equals(c.track) ? "nm" : "sh") : "")));
        if (guess != null && options.stream().noneMatch(o -> o.startsWith(guess + ":") || o.equals(String.valueOf(guess)))) {
            options.add(String.valueOf(guess));
        }
        Kb.Rows rows = Kb.rows();
        for (String o : options) {
            String[] a = o.split(":");
            int g = Integer.parseInt(a[0]);
            String label = g + " класс" + (a.length > 1 ? ", " + Book.trackLabel("nm".equals(a[1])
                    ? Book.NATURAL_MATH : Book.SOCIAL_HUMANITIES) : "") + (guess != null && g == guess ? " (так в файле)" : "");
            rows.add(btn(label, "ktp:g:" + o));
        }
        rows.add(btn("Другой класс", "ktp:g:x"));
        save(uid, KTP_GRADE, d);
        bot.send(chat, "Для какого класса этот КТП?", rows.build());
    }

    private void review(long uid, long chat, Import d) {
        Parsed p = d.parsed;
        if (p.lessons.isEmpty()) {
            ConvState.clear(uid);
            bot.send(chat, "В таблице не нашлось уроков. " + String.join(" ", p.warnings)
                    + "\nПришлите другой файл: /ktp", null);
            return;
        }
        if (!d.gradeAsked) {
            askGrade(uid, chat, d);
            return;
        }
        if (d.grade >= 10 && d.track == null) {
            save(uid, KTP_GRADE, d);
            bot.send(chat, "Какое направление у " + d.grade + " класса?", Kb.rows()
                    .add(btn("Естественно-математическое", "ktp:t:nm"))
                    .add(btn("Общественно-гуманитарное", "ktp:t:sh")).build());
            return;
        }
        List<UnitInfo> units = KtpService.units(p.lessons.stream().map(l -> l.unit).toList());
        StringBuilder sb = new StringBuilder("📘 КТП");
        if (p.title != null) sb.append(": ").append(p.title);
        sb.append("\nКласс: ").append(d.grade).append(d.track == null ? "" : ", " + Book.trackLabel(d.track));
        if (p.schoolYear != null) sb.append(" · учебный год ").append(p.schoolYear);
        int perHour = p.lessons.stream().mapToInt(l -> Math.max(1, l.hours)).sum();
        sb.append("\nУроков в файле: ").append(p.lessons.size()).append(" (часов ").append(p.totalHours()).append(")")
                .append(" · юнитов: ").append(units.size());
        if (perHour != p.lessons.size()) {
            sb.append("\nУроки на несколько часов будут разбиты на отдельные уроки по 1 часу: после сохранения ")
                    .append(perHour).append(" уроков.");
        }
        if (!p.terms.isEmpty()) {
            sb.append("\nЧетверти: ").append(p.terms.stream()
                    .map(t -> t.no + " — " + (t.hours == null ? "?" : t.hours + " ч"))
                    .collect(Collectors.joining(", ")));
        }
        long checks = p.lessons.stream().filter(l -> l.kind.equals("checkpoint") || l.kind.equals("interim")).count();
        long projects = p.lessons.stream().filter(l -> l.kind.equals("project")).count();
        sb.append("\nКонтрольных и срезовых: ").append(checks).append(", проектов: ").append(projects);
        if (!p.warnings.isEmpty()) {
            sb.append("\n\n⚠️ Замечания:");
            p.warnings.stream().limit(8).forEach(w -> sb.append("\n• ").append(w));
        }
        long existing = d.track == null ? Ktp.count("teacherId = ?1 and grade = ?2 and track is null", uid, d.grade)
                : Ktp.count("teacherId = ?1 and grade = ?2 and track = ?3", uid, d.grade, d.track);
        if (existing > 0) {
            sb.append("\n\nЭто заменит ваш сохранённый КТП ").append(d.grade).append(" класса")
                    .append(d.track == null ? "" : " (" + Book.trackLabel(d.track) + ")").append(".");
        }
        sb.append("\n\nРазобрано: ").append(d.viaAi ? "с помощью ИИ, тексты сверены с файлом" : "без ИИ (кодом)");
        save(uid, KTP_REVIEW, d);
        Kb.Rows buttons = Kb.rows()
                .add(btn("✅ Верно, сохранить", "ktp:ok"))
                .add(btn("📋 Показать юниты", "ktp:preview"), btn("✖ Отмена", "ktp:cancel"));
        if (d.viaAi && d.raw != null && !d.raw.flat()) {
            buttons.add(btn("🔁 Разобрать без ИИ", "ktp:code"));
        }
        bot.send(chat, sb.toString(), buttons.build());
    }

    private void askClasses(long uid, long chat, Long editMsg, Import d) {
        // classes of this grade; for grades 10-11 only the same track (a class without a track is shown too)
        List<SchoolClass> fitting = SchoolClass.forTeacher(uid).stream()
                .filter(c -> c.grade == d.grade && (d.track == null || c.track == null || c.track.equals(d.track)))
                .toList();
        if (fitting.isEmpty()) {
            if (!SchoolClass.forTeacher(uid).isEmpty()) {
                bot.send(chat, "Среди ваших классов нет подходящих (" + d.grade + " класс"
                        + (d.track == null ? "" : ", " + Book.trackLabel(d.track)) + "): КТП сохраню без привязки. "
                        + "Добавьте класс в /classes и привяжите КТП кнопкой «Классы» в /ktp.", null);
            }
            saveKtp(uid, chat, d);
            return;
        }
        Kb.Rows rows = Kb.rows();
        for (SchoolClass c : fitting) {
            rows.add(btn((d.classIds.contains(c.id) ? "✅ " : "▫️ ") + c.label(), "ktp:c:" + c.id));
        }
        rows.add(btn("Готово →", "ktp:save"));
        save(uid, KTP_CLASSES, d);
        String text = "Какие классы идут по этому КТП? (параллели используют один план)";
        if (editMsg != null) bot.edit(chat, editMsg, text, rows.build());
        else bot.send(chat, text, rows.build());
    }

    private void saveKtp(long uid, long chat, Import d) {
        Teacher t = Teacher.findById(uid);
        Ktp k = ktps.save(uid, t == null ? null : t.subject, d.parsed, d.grade, d.track, d.fileName, d.classIds);
        ConvState.clear(uid);
        LOG.infof("[ktp] user=%d saved KTP #%d: grade %d, %d lessons, %d classes", uid, k.id, d.grade,
                d.parsed.lessons.size(), d.classIds.size());
        bot.send(chat, "✅ КТП сохранён: " + KtpLesson.count("ktpId", k.id) + " уроков по 1 часу, "
                + d.parsed.totalHours() + " часов, классов: " + d.classIds.size() + ".\n"
                + "Теперь в /plan можно взять урок прямо из КТП: выберите класс, и бот предложит юнит и тему.",
                Kb.row(btn("📋 Юниты и темы", "ktp:units:" + k.id)));
        if (!d.classIds.isEmpty()) {
            askStart(uid, chat, k);
        } else {
            start(uid, chat);
        }
    }

    /** Dates need a starting point: from the first lesson in a new year, or from the lesson the class is at now. */
    private void askStart(long uid, long chat, Ktp k) {
        buildDates(uid, chat, k, 1); // always from the first day of the school year and the first lesson
    }

    /** Places the lessons of the KTP on dates for every class that follows it, starting from startSeq. */
    private void buildDates(long uid, long chat, Ktp k, int startSeq) {
        StringBuilder sb = new StringBuilder();
        for (KtpClass link : KtpClass.ofKtp(k.id)) {
            SchoolClass c = SchoolClass.findById(link.classId);
            // classes that already have dates keep them (the button "Даты уроков" recalculates those)
            if (c == null || KtpSlot.count("ktpId = ?1 and classId = ?2", k.id, c.id) > 0) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(SlotService.describe(new SlotService.Built(k, c, slots.build(k, c, startSeq))));
        }
        if (sb.length() == 0) sb.append("К КТП не привязан ни один класс: добавьте их в /classes и загрузите КТП "
                + "снова.");
        bot.send(chat, sb + "\n\nПосмотреть: /week, предложение на завтра: /tomorrow.", null);
        start(uid, chat); // the summary of all KTPs: hours, classes, dates
    }

    /**
     * After the timetable was saved: the KTP classes that still have no dates get an offer to place them
     * (they could not be placed before because there were no lessons in the timetable).
     */
    void offerDates(long uid, long chat) {
        for (Ktp k : Ktp.forTeacher(uid)) {
            boolean missing = false;
            for (KtpClass link : KtpClass.ofKtp(k.id)) {
                if (KtpSlot.count("ktpId = ?1 and classId = ?2", k.id, link.classId) == 0
                        && ScheduleEntry.count("classId", link.classId) > 0) {
                    missing = true;
                }
            }
            if (missing) {
                bot.send(chat, "У КТП (" + k.gradeLabel() + ") ещё нет дат уроков, а расписание теперь есть. "
                        + "Расставить даты?", Kb.row(btn("📅 Расставить даты", "ktp:dates:" + k.id)));
            }
        }
    }

    // ------------------------------------------------------------------ callbacks

    void onCallback(long uid, long chat, long msgId, String cbId, String[] p) {
        switch (p[1]) {
            case "new" -> {
                bot.answer(cbId, null);
                askFile(uid, chat);
            }
            case "list" -> {
                bot.answer(cbId, null);
                start(uid, chat);
            }
            case "g" -> {
                bot.answer(cbId, null);
                Import d = read(ConvState.get(uid));
                if (d == null || d.parsed == null) {
                    bot.send(chat, "Сессия устарела, начните заново: /ktp", null);
                    return;
                }
                if ("x".equals(p[2])) {
                    save(uid, KTP_GRADE, d);
                    bot.send(chat, "Для какого класса этот КТП? Напишите число (1–11).", null);
                    return;
                }
                d.grade = Integer.valueOf(p[2]);
                d.gradeAsked = true;
                d.track = p.length > 3 ? ("nm".equals(p[3]) ? Book.NATURAL_MATH : Book.SOCIAL_HUMANITIES) : null;
                review(uid, chat, d);
            }
            case "t" -> {
                bot.answer(cbId, null);
                Import d = read(ConvState.get(uid));
                if (d == null || d.parsed == null || d.grade == null) {
                    bot.send(chat, "Сессия устарела, начните заново: /ktp", null);
                    return;
                }
                d.track = "nm".equals(p[2]) ? Book.NATURAL_MATH : Book.SOCIAL_HUMANITIES;
                review(uid, chat, d);
            }
            case "lk" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k != null) showLinks(uid, chat, msgId, k);
            }
            case "lt" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                SchoolClass c = SchoolClass.findById(Long.parseLong(p[3]));
                if (k == null || c == null || c.teacherId != uid) return;
                toggleLink(k, c);
                showLinks(uid, chat, msgId, k);
            }
            case "ld" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k == null) return;
                boolean needDates = KtpClass.ofKtp(k.id).stream().anyMatch(l ->
                        KtpSlot.count("ktpId = ?1 and classId = ?2", k.id, l.classId) == 0
                                && ScheduleEntry.count("classId", l.classId) > 0);
                if (needDates) askStart(uid, chat, k);
                else start(uid, chat);
            }
            case "rd" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k == null) return;
                bot.send(chat, "Расставить даты КТП (" + k.gradeLabel() + ") заново, с первого дня учебного года и с "
                        + "первого урока? Прежние даты и отметки «план готов» у уроков сбросятся. Сами составленные "
                        + "планы останутся в /history, КТП и привязанные классы тоже.",
                        Kb.row(btn("Да, заново", "ktp:rdok:" + k.id), btn("Нет", "ktp:list")));
            }
            case "rdok" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k == null) return;
                long n = KtpSlot.delete("ktpId", k.id);
                KtpSlot.getEntityManager().flush();
                LOG.infof("[ktp] user=%d reset the dates of KTP #%d (%d slots removed)", uid, k.id, n);
                buildDates(uid, chat, k, 1);
            }
            case "dates" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k == null) return;
                var built = slots.rebuildAll(uid);
                if (!built.isEmpty()) {
                    bot.send(chat, "Пересчитал даты.\n\n" + SlotService.describeAll(built), null);
                }
                boolean missing = KtpClass.ofKtp(k.id).stream().anyMatch(l ->
                        KtpSlot.count("ktpId = ?1 and classId = ?2", k.id, l.classId) == 0);
                if (missing || built.isEmpty()) {
                    askStart(uid, chat, k); // places the classes that have no dates yet
                }
            }
            case "start" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k == null) return;
                ConvState.clear(uid);
                buildDates(uid, chat, k, Integer.parseInt(p[3]));
            }
            case "cancel" -> {
                bot.answer(cbId, "Отменено");
                ConvState.clear(uid);
                bot.edit(chat, msgId, "Отменено.", null);
            }
            case "preview" -> {
                bot.answer(cbId, null);
                Import d = read(ConvState.get(uid));
                if (d == null || d.parsed == null) {
                    bot.send(chat, "Сессия устарела, начните заново: /ktp", null);
                    return;
                }
                bot.send(chat, unitsText(KtpService.units(d.parsed.lessons.stream().map(l -> l.unit).toList()),
                        d.parsed.lessons.size()), null);
            }
            case "code" -> {
                bot.answer(cbId, null);
                Import d = read(ConvState.get(uid));
                if (d == null || d.raw == null) {
                    bot.send(chat, "Сессия устарела, начните заново: /ktp", null);
                    return;
                }
                Outcome o = KtpParser.parse(d.raw, null);
                if (o.needMapping() || o.parsed() == null) {
                    bot.send(chat, "Кодом колонки распознать не удалось, оставляю разбор ИИ.", null);
                    return;
                }
                d.parsed = o.parsed();
                d.viaAi = false;
                review(uid, chat, d);
            }
            case "ok" -> {
                bot.answer(cbId, null);
                Import d = read(ConvState.get(uid));
                if (d == null || d.parsed == null || d.grade == null) {
                    bot.send(chat, "Сессия устарела, начните заново: /ktp", null);
                    return;
                }
                askClasses(uid, chat, null, d);
            }
            case "c" -> {
                bot.answer(cbId, null);
                Import d = read(ConvState.get(uid));
                if (d == null) {
                    bot.send(chat, "Сессия устарела, начните заново: /ktp", null);
                    return;
                }
                Long id = Long.valueOf(p[2]);
                if (!d.classIds.remove(id)) d.classIds.add(id);
                askClasses(uid, chat, msgId, d);
            }
            case "save" -> {
                bot.answer(cbId, null);
                Import d = read(ConvState.get(uid));
                if (d == null || d.parsed == null || d.grade == null) {
                    bot.send(chat, "Сессия устарела, начните заново: /ktp", null);
                    return;
                }
                saveKtp(uid, chat, d);
            }
            case "units" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k == null) return;
                List<KtpLesson> lessons = KtpLesson.ofKtp(k.id);
                List<UnitInfo> units = KtpService.units(lessons.stream().map(l -> l.unitTitle).toList());
                List<String> full = units.stream().map(u -> u.title() + " (" + u.count() + ")").toList();
                List<String> lb = Kb.labels(full);
                Kb.Rows rows = Kb.rows();
                for (int i = 0; i < units.size(); i++) rows.add(btn(lb.get(i), "ktp:u:" + k.id + ":" + i));
                bot.send(chat, unitsText(units, lessons.size()) + Kb.legend(full), rows.build());
            }
            case "u" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k == null) return;
                List<KtpLesson> lessons = KtpLesson.ofKtp(k.id);
                List<UnitInfo> units = KtpService.units(lessons.stream().map(l -> l.unitTitle).toList());
                int idx = Integer.parseInt(p[3]);
                if (idx < 0 || idx >= units.size()) return;
                UnitInfo u = units.get(idx);
                StringBuilder sb = new StringBuilder("📋 ").append(u.title()).append('\n');
                for (KtpLesson l : lessons) {
                    if (l.seq < u.firstSeq() || l.seq > u.lastSeq()) continue;
                    sb.append('\n').append(l.seq).append(". ").append(l.title());
                    if (!"lesson".equals(l.kind)) sb.append(" [").append(l.kind).append(']');
                    if (l.codes != null && !l.codes.isBlank()) sb.append("\n    ").append(l.codes);
                }
                bot.send(chat, Kb.trunc(sb.toString(), 3900), null);
            }
            case "edit" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k == null) return;
                ConvState.set(uid, KTP_EDIT, String.valueOf(k.id));
                bot.send(chat, "Что исправить? Пришлите:\n«5: новая тема» чтобы поменять тему урока 5\n"
                        + "«5 цели: 4.1.1.1 текст цели; 4.3.1.1 ещё цель» чтобы поменять цели\nОтмена: /cancel", null);
            }
            case "del" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k == null) return;
                bot.send(chat, "Удалить КТП (" + k.gradeLabel() + ") вместе со всеми уроками?",
                        Kb.row(btn("Да, удалить", "ktp:delok:" + k.id), btn("Нет", "ktp:list")));
            }
            case "delok" -> {
                bot.answer(cbId, null);
                Ktp k = ownedKtp(uid, Long.parseLong(p[2]));
                if (k != null) {
                    k.delete();
                    LOG.infof("[ktp] user=%d deleted KTP #%d", uid, k.id);
                }
                start(uid, chat);
            }
            default -> bot.answer(cbId, null);
        }
    }

    // ------------------------------------------------------------------ picking a lesson inside /plan

    /**
     * Offers the units of the KTP that the chosen classes follow. Returns true when a question was sent and the
     * plan flow waits; false when there is nothing to offer (no KTP, or section and topic are already known).
     */
    boolean offerPick(long uid, long chat, Draft d, List<SchoolClass> classes) {
        Ktp k = ktpOf(classes);
        if (k == null || (d.section != null && !d.section.isBlank()) || (d.topic != null && !d.topic.isBlank())) {
            d.ktpAsked = true;
            return false;
        }
        sendUnits(chat, k, null);
        return true;
    }

    boolean hasKtp(List<SchoolClass> classes) {
        return ktpOf(classes) != null;
    }

    /** The first KTP that any of the classes follows, or null. */
    private Ktp ktpOf(List<SchoolClass> classes) {
        for (SchoolClass c : classes) {
            KtpClass link = KtpClass.find("classId", c.id).firstResult();
            if (link != null) {
                Ktp k = Ktp.findById(link.ktpId);
                if (k != null) return k;
            }
        }
        return null;
    }

    private void sendUnits(long chat, Ktp k, Long editMsg) {
        List<KtpLesson> lessons = KtpLesson.ofKtp(k.id);
        List<UnitInfo> units = KtpService.units(lessons.stream().map(l -> l.unitTitle).toList());
        List<String> full = units.stream().map(u -> u.title() + " (" + u.count() + ")").toList();
        List<String> lb = Kb.labels(full);
        Kb.Rows rows = Kb.rows();
        for (int i = 0; i < units.size(); i++) rows.add(btn(lb.get(i), "pl:ku:" + k.id + ":" + i));
        rows.add(btn("Ввести вручную", "pl:kn"));
        String text = "📘 Взять урок из КТП (" + k.gradeLabel() + ")? Выберите юнит:" + Kb.legend(full);
        if (editMsg != null) bot.edit(chat, editMsg, text, rows.build());
        else bot.send(chat, text, rows.build());
    }

    /** pl:ku:ktpId:unitIdx shows the lessons of a unit; pl:kb:ktpId goes back to the units. */
    void onPickUnit(long chat, long msgId, long ktpId, int unitIdx) {
        Ktp k = Ktp.findById(ktpId);
        if (k == null) return;
        List<KtpLesson> lessons = KtpLesson.ofKtp(k.id);
        List<UnitInfo> units = KtpService.units(lessons.stream().map(l -> l.unitTitle).toList());
        if (unitIdx < 0 || unitIdx >= units.size()) return;
        UnitInfo u = units.get(unitIdx);
        List<KtpLesson> inUnit = lessons.stream().filter(l -> l.seq >= u.firstSeq() && l.seq <= u.lastSeq()).toList();
        List<String> full = inUnit.stream()
                .map(l -> l.seq + ". " + l.title() + ("lesson".equals(l.kind) ? "" : " [" + l.kind + "]")).toList();
        List<String> lb = Kb.labels(full);
        Kb.Rows rows = Kb.rows();
        for (int i = 0; i < inUnit.size(); i++) rows.add(btn(lb.get(i), "pl:kl:" + inUnit.get(i).id));
        rows.add(btn("← Юниты", "pl:kb:" + k.id), btn("Ввести вручную", "pl:kn"));
        bot.edit(chat, msgId, "📘 " + u.title() + "\nВыберите урок:" + Kb.legend(full), rows.build());
    }

    void onBackToUnits(long chat, long msgId, long ktpId) {
        Ktp k = Ktp.findById(ktpId);
        if (k != null) sendUnits(chat, k, msgId);
    }

    /** A lesson was chosen: its unit, topic and objectives become the plan data. False when it is gone. */
    boolean applyLesson(Draft d, long lessonId) {
        KtpLesson l = KtpLesson.findById(lessonId);
        if (l == null) return false;
        d.section = l.unitTitle == null || l.unitTitle.isBlank() ? l.topic : l.unitTitle;
        d.topic = l.topic;
        d.objectives = l.objectives == null || l.objectives.isBlank() ? null : l.objectives;
        d.ktpLessonId = l.id;
        d.ktpAsked = true;
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private String unitsText(List<UnitInfo> units, int lessons) {
        StringBuilder sb = new StringBuilder("📋 Юниты (").append(units.size()).append("), уроков ").append(lessons)
                .append("\n");
        for (UnitInfo u : units) {
            sb.append("\n• ").append(u.title()).append(" — уроки ").append(u.firstSeq()).append("–")
                    .append(u.lastSeq()).append(" (").append(u.count()).append(")");
        }
        return Kb.trunc(sb.toString(), 3900);
    }

    private String mappingQuestion(List<List<String>> preview) {
        StringBuilder sb = new StringBuilder("Не смог сам понять колонки таблицы. Вот первые строки:\n");
        int i = 1;
        for (List<String> row : preview) {
            sb.append("\nстрока ").append(i++).append(": ");
            for (int c = 0; c < row.size(); c++) {
                sb.append('[').append(c + 1).append("] ").append(Kb.trunc(row.get(c), 25)).append("  ");
            }
        }
        sb.append("\n\nНапишите, какая колонка что, например: «тема 3, цели 4, часы 5». Можно ещё «номер 1», "
                + "«юнит 2», «дата 6». Обязательна только тема.");
        return Kb.trunc(sb.toString(), 3900);
    }

    private Ktp ownedKtp(long uid, long id) {
        Ktp k = Ktp.findById(id);
        return k != null && k.teacherId == uid ? k : null;
    }

    /** Terms stored with a KTP, for the status line later (kept here to avoid an unused-entity warning). */
    List<KtpTerm> terms(long ktpId) {
        return KtpTerm.ofKtp(ktpId);
    }

    private void save(long uid, String state, Import d) {
        try {
            ConvState.set(uid, state, mapper.writeValueAsString(d));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Import read(ConvState st) {
        if (st == null || st.payload == null || !st.state.startsWith("KTP_")) return null;
        try {
            return mapper.readValue(st.payload, Import.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
