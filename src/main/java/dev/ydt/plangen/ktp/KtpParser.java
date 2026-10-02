package dev.ydt.plangen.ktp;

import dev.ydt.plangen.ktp.KtpReader.RawDoc;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the rows of a KTP table into lessons. KTPs are laid out differently from school to school, so only the
 * columns every plan has are used: number, topic, objectives (with codes), hours, date, and the unit when there
 * is one. Everything else is ignored. When the columns cannot be recognised the caller gets the first rows back
 * and asks the teacher which column is which.
 */
public final class KtpParser {
    private KtpParser() {}

    /** Zero-based column of every field, -1 when the table has no such column. */
    public record Mapping(int number, int unit, int topic, int objectives, int hours, int date) {}

    public static class Lesson {
        public int seq;
        public Integer term;
        public String unit;
        public String topic;
        /** One line per objective: "4.1.1.1 distinguish phonemically distinct words". */
        public String objectives;
        /** "4.1.1.1, 4.3.1.1". */
        public String codes;
        public int hours = 1;
        public String kind = "lesson";
        /** ISO date written in the file, or null. */
        public String date;
    }

    public static class Term {
        public int no;
        public Integer hours;
    }

    public static class Parsed {
        public String title;
        public Integer grade;
        public String schoolYear;
        public Integer declaredTotal;
        public List<Lesson> lessons = new ArrayList<>();
        public List<Term> terms = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();

        public int totalHours() {
            return lessons.stream().mapToInt(l -> l.hours).sum();
        }
    }

    /** needMapping is true when the columns were not recognised; preview then holds the first rows. */
    public record Outcome(Parsed parsed, boolean needMapping, List<List<String>> preview) {}

    // ------------------------------------------------------------------ columns

    private static final List<String> NUMBER = List.of("№", "no", "n", "#", "п/п", "номер", "нөмір", "№ п/п");
    private static final List<String> HOURS = List.of("hour", "час", "сағат", "кол-во", "количество", "hrs");
    private static final List<String> DATE = List.of("date", "дата", "күні", "мерзім");
    private static final List<String> OBJECTIVES = List.of("objective", "цел", "мақсат", "код", "outcome",
            "результат", "learning");
    private static final List<String> UNIT = List.of("unit", "раздел", "юнит", "бөлім", "модуль", "module", "section",
            "блок");
    private static final List<String> TOPIC = List.of("theme", "topic", "тема", "тақырып", "содержание", "мазмұн",
            "lesson", "урок", "сабақ");

    /** Finds the header row and the columns, or null when this is not a recognisable KTP table. */
    static Object[] detectHeader(List<List<String>> rows) {
        for (int i = 0; i < Math.min(rows.size(), 10); i++) {
            List<String> cells = rows.get(i);
            if (cells.size() < 3) continue;
            int[] col = {-1, -1, -1, -1, -1, -1}; // number unit topic objectives hours date
            for (int c = 0; c < cells.size(); c++) {
                String h = cells.get(c).toLowerCase(Locale.ROOT).strip();
                if (h.isEmpty() || h.length() > 60) continue;
                if (col[0] < 0 && isNumberHeader(h)) col[0] = c;
                else if (col[4] < 0 && containsAny(h, HOURS)) col[4] = c;
                else if (col[5] < 0 && containsAny(h, DATE)) col[5] = c;
                else if (col[3] < 0 && containsAny(h, OBJECTIVES)) col[3] = c;
                else if (col[1] < 0 && containsAny(h, UNIT)) col[1] = c;
                else if (col[2] < 0 && containsAny(h, TOPIC)) col[2] = c;
            }
            if (col[2] >= 0 && (col[3] >= 0 || col[4] >= 0)) {
                return new Object[] {i, new Mapping(col[0], col[1], col[2], col[3], col[4], col[5])};
            }
        }
        return null;
    }

    private static boolean isNumberHeader(String h) {
        return NUMBER.contains(h) || h.startsWith("№") || h.matches("(no|n)\\.?");
    }

    private static boolean containsAny(String h, List<String> words) {
        for (String w : words) if (h.contains(w)) return true;
        return false;
    }

    // ------------------------------------------------------------------ rows

    private static final Pattern CODE = Pattern.compile("(\\d{1,2})\\s*\\.\\s*(\\d{1,2})\\s*\\.\\s*(\\d{1,2})\\s*\\.\\s*(\\d{1,2})\\.?");
    private static final Pattern TERM = Pattern.compile(
            "(?i)^\\s*(\\d)\\s*(?:st|nd|rd|d|th|-?я|-?й)?\\s*(?:term|quarter|четверть|тоқсан)\\b[^\\d]*(\\d+)?");
    private static final Pattern TOTAL = Pattern.compile("(?i)^\\s*(total|итого|всего|барлығы)\\b[^\\d]*(\\d+)?");
    private static final Pattern GRADE = Pattern.compile("(?i)(?:grade|класс|сынып)\\s*(\\d{1,2})|(\\d{1,2})\\s*[- ]?(?:класс|сынып|grade)");
    private static final Pattern YEAR = Pattern.compile("(20\\d\\d)\\s*[-–/]\\s*(20\\d\\d)");
    private static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("d.M.uuuu");

    public static Outcome parse(RawDoc doc, Mapping forced) {
        List<List<String>> rows = doc.rows();
        Mapping map = forced;
        int headerRow = -1;
        Object[] found = detectHeader(rows);
        if (found != null) {
            headerRow = (int) found[0];
            if (map == null) map = (Mapping) found[1];
        }
        if (map == null || map.topic() < 0) {
            return new Outcome(null, true, preview(rows));
        }

        Parsed out = new Parsed();
        readTitle(doc, out);
        Lesson last = null;
        String currentUnit = null;
        Integer currentTerm = null;
        int blankHours = 0;
        List<String> rawObjectives = new ArrayList<>();

        for (int r = headerRow + 1; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            String joined = String.join(" ", cells).strip();
            if (joined.isBlank()) continue;
            int filled = (int) cells.stream().filter(c -> !c.isBlank()).count();

            Matcher total = TOTAL.matcher(joined);
            if (filled <= 2 && total.find()) {
                if (total.group(2) != null) out.declaredTotal = Integer.valueOf(total.group(2));
                continue;
            }
            Matcher term = TERM.matcher(joined);
            if (filled <= 2 && term.find()) {
                currentTerm = Integer.valueOf(term.group(1));
                Term t = new Term();
                t.no = currentTerm;
                t.hours = term.group(2) == null ? null : Integer.valueOf(term.group(2));
                out.terms.add(t);
                continue;
            }

            String number = cell(cells, map.number());
            String topic = cell(cells, map.topic());
            String unit = cell(cells, map.unit());
            String objectives = cell(cells, map.objectives());
            boolean numeric = number.matches("\\d{1,3}");
            if (!number.isBlank() && !numeric) continue; // the header again, or a note
            if (number.isBlank() && topic.isBlank()) {
                // the objectives of the previous lesson continued on their own row
                if (!objectives.isBlank() && last != null) {
                    rawObjectives.set(rawObjectives.size() - 1, rawObjectives.get(rawObjectives.size() - 1) + " "
                            + objectives);
                }
                continue;
            }
            if (topic.isBlank()) {
                out.warnings.add("В строке " + number + " нет темы, строка пропущена.");
                continue;
            }
            if (!unit.isBlank()) currentUnit = unit.replaceAll("(?i)^(unit\\s*\\d+)\\s*:\\s*", "$1: ");

            Lesson l = new Lesson();
            l.seq = out.lessons.size() + 1;
            l.term = currentTerm;
            l.unit = currentUnit;
            l.topic = topic;
            String hoursText = cell(cells, map.hours());
            if (hoursText.matches("\\d{1,2}")) {
                l.hours = Math.max(1, Integer.parseInt(hoursText));
            } else {
                blankHours++;
            }
            l.date = parseDate(cell(cells, map.date()));
            l.kind = kindOf(topic);
            out.lessons.add(l);
            rawObjectives.add(objectives);
            last = l;
        }

        for (int i = 0; i < out.lessons.size(); i++) {
            Lesson l = out.lessons.get(i);
            List<String[]> items = objectiveItems(rawObjectives.get(i));
            StringBuilder lines = new StringBuilder();
            StringBuilder codes = new StringBuilder();
            for (String[] it : items) {
                if (lines.length() > 0) lines.append('\n');
                lines.append(it[0].isEmpty() ? it[1] : (it[0] + (it[1].isEmpty() ? "" : " " + it[1])));
                if (!it[0].isEmpty()) {
                    if (codes.length() > 0) codes.append(", ");
                    codes.append(it[0]);
                }
            }
            l.objectives = lines.toString();
            l.codes = codes.toString();
            if (l.objectives.isBlank()) {
                out.warnings.add("Урок " + l.seq + " «" + clip(l.topic, 40) + "»: нет целей обучения.");
            }
        }
        validate(out, blankHours);
        return new Outcome(out, false, preview(rows));
    }

    private static void validate(Parsed out, int blankHours) {
        if (out.lessons.isEmpty()) {
            out.warnings.add("Не нашёл ни одного урока в таблице.");
            return;
        }
        if (blankHours > 0) {
            out.warnings.add("Часы не указаны в " + blankHours + " уроках, считаю по 1 часу.");
        }
        if (out.declaredTotal != null && out.declaredTotal != out.totalHours()) {
            out.warnings.add("В файле указано всего " + out.declaredTotal + " часов, а в уроках получилось "
                    + out.totalHours() + ".");
        }
        for (Term t : out.terms) {
            int sum = out.lessons.stream().filter(l -> l.term != null && l.term == t.no).mapToInt(l -> l.hours).sum();
            if (t.hours != null && t.hours != sum) {
                out.warnings.add(t.no + " четверть: в заголовке " + t.hours + " ч., в уроках " + sum + " ч.");
            }
        }
    }

    // ------------------------------------------------------------------ pieces

    private static void readTitle(RawDoc doc, Parsed out) {
        String title = null;
        for (String line : doc.headText()) {
            String low = line.toLowerCase(Locale.ROOT);
            if (title == null && (low.contains("plan") || low.contains("план") || low.contains("жоспар"))) {
                title = line;
            }
        }
        if (title == null && !doc.headText().isEmpty()) title = doc.headText().get(0);
        out.title = title == null ? null : clip(title, 200);
        String all = String.join(" ", doc.headText());
        Matcher g = GRADE.matcher(all);
        if (g.find()) out.grade = Integer.valueOf(g.group(1) != null ? g.group(1) : g.group(2));
        Matcher y = YEAR.matcher(all);
        if (y.find()) out.schoolYear = y.group(1) + "-" + y.group(2);
    }

    /** The grade is also the first number of every objective code (4.1.1.1 belongs to grade 4). */
    public static Integer gradeFromCodes(Parsed p) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Lesson l : p.lessons) {
            Matcher m = CODE.matcher(l.codes == null ? "" : l.codes);
            while (m.find()) counts.merge(Integer.parseInt(m.group(1)), 1, Integer::sum);
        }
        return counts.entrySet().stream().filter(e -> e.getKey() >= 1 && e.getKey() <= 11)
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** Objectives as typed by a teacher into the lines-and-codes form: [lines, codes]. */
    public static String[] normalizeObjectives(String raw) {
        StringBuilder lines = new StringBuilder();
        StringBuilder codes = new StringBuilder();
        for (String[] it : objectiveItems(raw)) {
            if (lines.length() > 0) lines.append('\n');
            lines.append(it[0].isEmpty() ? it[1] : (it[0] + (it[1].isEmpty() ? "" : " " + it[1])));
            if (!it[0].isEmpty()) {
                if (codes.length() > 0) codes.append(", ");
                codes.append(it[0]);
            }
        }
        return new String[] {lines.toString(), codes.toString()};
    }

    /** [code, text] pairs. Codes are written with spaces in many files ("4. 1. 1. 1") and are normalised. */
    static List<String[]> objectiveItems(String raw) {
        List<String[]> items = new ArrayList<>();
        if (raw == null || raw.isBlank()) return items;
        Matcher m = CODE.matcher(raw);
        List<int[]> spans = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            codes.add(m.group(1) + "." + m.group(2) + "." + m.group(3) + "." + m.group(4));
        }
        if (spans.isEmpty()) {
            items.add(new String[] {"", trimSep(raw)});
            return items;
        }
        String lead = trimSep(raw.substring(0, spans.get(0)[0]));
        if (!lead.isEmpty()) items.add(new String[] {"", lead});
        for (int i = 0; i < spans.size(); i++) {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : raw.length();
            items.add(new String[] {codes.get(i), trimSep(raw.substring(spans.get(i)[1], end))});
        }
        return items;
    }

    private static String trimSep(String s) {
        return s.replaceAll("^[\\s;,.:–-]+|[\\s;,:–-]+$", "").strip();
    }

    static String kindOf(String topic) {
        String t = topic.toLowerCase(Locale.ROOT);
        if (t.contains("interim")) return "interim";
        if (t.contains("checkpoint") || t.contains("check point") || t.contains("summative")
                || t.contains("контрольн") || t.contains("суммативн")) return "checkpoint";
        if (t.contains("project") || t.contains("проект")) return "project";
        if (t.contains("clil")) return "clil";
        return "lesson";
    }

    private static String parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) return LocalDate.parse(s, DateTimeFormatter.ofPattern("uuuu-M-d")).toString();
            if (s.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{4}")) return LocalDate.parse(s, DOTTED).toString();
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String cell(List<String> cells, int idx) {
        return idx < 0 || idx >= cells.size() ? "" : cells.get(idx);
    }

    private static List<List<String>> preview(List<List<String>> rows) {
        return rows.subList(0, Math.min(rows.size(), 6));
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
