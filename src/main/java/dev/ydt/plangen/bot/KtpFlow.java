package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.Ktp;
import dev.ydt.plangen.domain.KtpClass;
import dev.ydt.plangen.domain.KtpLesson;
import dev.ydt.plangen.domain.KtpTerm;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.domain.Teacher;
import dev.ydt.plangen.ktp.KtpParser;
import dev.ydt.plangen.ktp.KtpParser.Mapping;
import dev.ydt.plangen.ktp.KtpParser.Outcome;
import dev.ydt.plangen.ktp.KtpParser.Parsed;
import dev.ydt.plangen.ktp.KtpReader;
import dev.ydt.plangen.ktp.KtpReader.RawDoc;
import dev.ydt.plangen.service.KtpService;
import dev.ydt.plangen.service.KtpService.UnitInfo;
import dev.ydt.plangen.telegram.Dto.Document;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
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

    private static final long MAX_FILE = 10L * 1024 * 1024;
    private static final Pattern MAP_ITEM = Pattern.compile(
            "(?i)(номер|№|number|юнит|раздел|unit|тема|topic|theme|цели|цель|objectives?|часы|час|hours?|дата|date)\\D{0,6}(\\d{1,2})");
    private static final Pattern EDIT = Pattern.compile("(?is)^\\s*(\\d{1,3})\\s*(цели|цель|objectives?)?\\s*[:\\-–]\\s*(.+)$");

    /** What is being imported while the teacher checks it. */
    public static class Import {
        public String fileName;
        public Parsed parsed;
        public Integer grade;
        public List<Long> classIds = new ArrayList<>();
        /** Only while the columns are asked: the raw table. */
        public RawDoc raw;
    }

    @Inject
    Messenger bot;

    @Inject
    KtpService ktps;

    @Inject
    ObjectMapper mapper;

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
            sb.append("\n• ").append(k.grade == null ? "?" : k.grade).append(" класс");
            if (k.schoolYear != null) sb.append(", ").append(k.schoolYear);
            sb.append(": ").append(lessons).append(" уроков, ").append(k.totalHours).append(" ч.");
            String classes = KtpClass.ofKtp(k.id).stream()
                    .map(c -> SchoolClass.<SchoolClass>findById(c.classId))
                    .filter(c -> c != null).map(SchoolClass::label).collect(Collectors.joining(", "));
            sb.append(classes.isEmpty() ? "\n  классы не привязаны" : "\n  классы: " + classes);
            rows.add(btn("📋 Юниты и темы · " + k.grade + " класс", "ktp:units:" + k.id));
            rows.add(btn("✏️ Исправить урок", "ktp:edit:" + k.id), btn("🗑 Удалить", "ktp:del:" + k.id));
        }
        rows.add(btn("➕ Загрузить КТП", "ktp:new"));
        bot.send(chat, sb.toString(), rows.build());
    }

    private void askFile(long uid, long chat) {
        ConvState.set(uid, KTP_FILE, null);
        bot.send(chat, "Пришлите файл КТП (Word: .docx или .doc). Я возьму из него юниты, темы, цели обучения с "
                + "кодами и часы. Таблицы у КТП бывают разные, нужны хотя бы номер, тема, цели и часы.\n"
                + "Отмена: /cancel", null);
    }

    /** A Word file arrived: read it as a KTP. */
    void onDocument(long uid, long chat, Document doc) {
        String name = doc.fileName() == null ? "" : doc.fileName();
        String low = name.toLowerCase(Locale.ROOT);
        if (!doc.fileId().startsWith("local:") && !low.endsWith(".docx") && !low.endsWith(".doc")) {
            bot.send(chat, "Для КТП пришлите файл Word (.docx или .doc).", null);
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
        Outcome o = KtpParser.parse(raw, null);
        LOG.infof("[ktp] user=%d file=\"%s\": %d table rows, columns %s", uid, fileName, raw.rows().size(),
                o.needMapping() ? "NOT recognised" : "recognised");
        Import d = new Import();
        d.fileName = fileName;
        if (o.needMapping()) {
            d.raw = raw;
            save(uid, KTP_MAP, d);
            bot.send(chat, mappingQuestion(o.preview()), null);
            return;
        }
        d.parsed = o.parsed();
        review(uid, chat, d);
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
                review(uid, chat, d);
            }
            case KTP_EDIT -> onEdit(uid, chat, st, text);
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
        if (m.group(2) != null) {
            String[] norm = KtpParser.normalizeObjectives(m.group(3));
            l.objectives = norm[0];
            l.codes = norm[1];
        } else {
            l.topic = m.group(3).strip();
        }
        ConvState.clear(uid);
        bot.send(chat, "✅ Урок " + l.seq + " обновлён:\n" + l.topic + (l.objectives == null || l.objectives.isBlank()
                ? "" : "\n" + l.objectives), Kb.row(btn("Мои КТП", "ktp:list")));
    }

    // ------------------------------------------------------------------ review and save

    private void review(long uid, long chat, Import d) {
        Parsed p = d.parsed;
        if (p.lessons.isEmpty()) {
            ConvState.clear(uid);
            bot.send(chat, "В таблице не нашлось уроков. " + String.join(" ", p.warnings)
                    + "\nПришлите другой файл: /ktp", null);
            return;
        }
        if (d.grade == null) d.grade = p.grade != null ? p.grade : KtpParser.gradeFromCodes(p);
        if (d.grade == null) {
            save(uid, KTP_GRADE, d);
            bot.send(chat, "Для какого класса этот КТП? Напишите число (1–11).", null);
            return;
        }
        List<UnitInfo> units = KtpService.units(p.lessons.stream().map(l -> l.unit).toList());
        StringBuilder sb = new StringBuilder("📘 КТП");
        if (p.title != null) sb.append(": ").append(Kb.trunc(p.title, 120));
        sb.append("\nКласс: ").append(d.grade);
        if (p.schoolYear != null) sb.append(" · учебный год ").append(p.schoolYear);
        sb.append("\nУроков: ").append(p.lessons.size()).append(" (часов ").append(p.totalHours()).append(")")
                .append(" · юнитов: ").append(units.size());
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
        long existing = Ktp.count("teacherId = ?1 and grade = ?2", uid, d.grade);
        if (existing > 0) sb.append("\n\nЭто заменит ваш сохранённый КТП ").append(d.grade).append(" класса.");
        save(uid, KTP_REVIEW, d);
        bot.send(chat, sb.toString(), Kb.rows()
                .add(btn("✅ Верно, сохранить", "ktp:ok"))
                .add(btn("📋 Показать юниты", "ktp:preview"), btn("✖ Отмена", "ktp:cancel"))
                .build());
    }

    private void askClasses(long uid, long chat, Long editMsg, Import d) {
        List<SchoolClass> fitting = SchoolClass.forTeacher(uid).stream().filter(c -> c.grade == d.grade).toList();
        if (fitting.isEmpty()) {
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
        Ktp k = ktps.save(uid, t == null ? null : t.subject, d.parsed, d.grade, d.fileName, d.classIds);
        ConvState.clear(uid);
        LOG.infof("[ktp] user=%d saved KTP #%d: grade %d, %d lessons, %d classes", uid, k.id, d.grade,
                d.parsed.lessons.size(), d.classIds.size());
        bot.send(chat, "✅ КТП сохранён: " + d.parsed.lessons.size() + " уроков, " + d.parsed.totalHours()
                + " часов, классов: " + d.classIds.size() + ".\n"
                + "Теперь в /plan можно взять урок прямо из КТП: выберите класс, и бот предложит юнит и тему.",
                Kb.row(btn("📋 Юниты и темы", "ktp:units:" + k.id)));
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
                Kb.Rows rows = Kb.rows();
                for (int i = 0; i < units.size(); i++) {
                    rows.add(btn(Kb.trunc(units.get(i).title() + " (" + units.get(i).count() + ")", 55),
                            "ktp:u:" + k.id + ":" + i));
                }
                bot.send(chat, unitsText(units, lessons.size()), rows.build());
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
                    sb.append('\n').append(l.seq).append(". ").append(l.topic);
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
                bot.send(chat, "Удалить КТП " + k.grade + " класса вместе со всеми уроками?",
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
        Kb.Rows rows = Kb.rows();
        for (int i = 0; i < units.size(); i++) {
            rows.add(btn(Kb.trunc(units.get(i).title() + " (" + units.get(i).count() + ")", 55),
                    "pl:ku:" + k.id + ":" + i));
        }
        rows.add(btn("Ввести вручную", "pl:kn"));
        String text = "📘 Взять урок из КТП (" + k.grade + " класс)? Выберите юнит:";
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
        Kb.Rows rows = Kb.rows();
        for (KtpLesson l : lessons) {
            if (l.seq < u.firstSeq() || l.seq > u.lastSeq()) continue;
            rows.add(btn(Kb.trunc(l.seq + ". " + l.topic + ("lesson".equals(l.kind) ? "" : " [" + l.kind + "]"), 58),
                    "pl:kl:" + l.id));
        }
        rows.add(btn("← Юниты", "pl:kb:" + k.id), btn("Ввести вручную", "pl:kn"));
        bot.edit(chat, msgId, "📘 " + u.title() + "\nВыберите урок:", rows.build());
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
