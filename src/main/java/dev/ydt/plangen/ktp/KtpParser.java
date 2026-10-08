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

    private static final Pattern DATEISH = Pattern.compile("^\\d{1,2}\\.\\d{1,2}(?:\\.\\d{2,4})?\\s*(?:\\(.*\\))?$");

    /**
     * Columns of a table that has no header row (some schools paste only the lessons): the number column holds
     * different small integers, the objectives column holds objective codes, the hours column holds the same
     * small number, the date column holds dd.MM, and the topic is the other text column. Null when the table does
     * not look like that.
     */
    static Mapping detectByContent(List<List<String>> rows) {
        List<List<String>> lessonRows = new ArrayList<>();
        for (List<String> r : rows) {
            if (r.size() >= 4 && r.stream().anyMatch(c -> CODE.matcher(c).find())) lessonRows.add(r);
        }
        if (lessonRows.size() < 3) return null;
        // merged rows (unit and term headings) have fewer cells: the most common width is the lesson width
        Map<Integer, Integer> widths = new LinkedHashMap<>();
        for (List<String> r : lessonRows) widths.merge(r.size(), 1, Integer::sum);
        int width = widths.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        List<List<String>> data = lessonRows.stream().filter(r -> r.size() == width).toList();
        int n = data.size();
        int number = -1;
        int hours = -1;
        int date = -1;
        int objectives = -1;
        int bestObjectives = 0;
        int bestDistinct = 0;
        for (int c = 0; c < width; c++) {
            final int col = c;
            long numeric = data.stream().filter(r -> r.get(col).strip().matches("\\d{1,3}")).count();
            long dates = data.stream().filter(r -> DATEISH.matcher(r.get(col).strip()).matches()).count();
            long codes = data.stream().filter(r -> CODE.matcher(r.get(col)).find()).count();
            long distinct = data.stream().map(r -> r.get(col).strip()).distinct().count();
            if (codes > bestObjectives && codes >= 0.8 * n) {
                objectives = c;
                bestObjectives = (int) codes;
            } else if (dates >= 0.5 * n && date < 0) {
                date = c;
            } else if (numeric >= 0.6 * n) {
                if (distinct >= 0.5 * n && distinct > bestDistinct) {
                    number = c;
                    bestDistinct = (int) distinct;
                } else if (distinct <= 3 && hours < 0) {
                    hours = c;
                }
            }
        }
        if (number < 0 || objectives < 0) return null;
        int topic = -1;
        double bestLen = 0;
        for (int c = 0; c < width; c++) {
            if (c == number || c == objectives || c == hours || c == date) continue;
            final int col = c;
            long filled = data.stream().filter(r -> !r.get(col).isBlank()).count();
            double avg = data.stream().mapToInt(r -> r.get(col).length()).average().orElse(0);
            if (filled >= 0.6 * n && avg > bestLen) {
                topic = c;
                bestLen = avg;
            }
        }
        return topic < 0 ? null : new Mapping(number, -1, topic, objectives, hours, date);
    }

    /** The header row and its columns, or the columns found from the content of a table without a header. */
    private static Object[] detect(List<List<String>> rows) {
        Object[] found = detectHeader(rows);
        Mapping byContent = detectByContent(rows);
        if (found != null) {
            // a header like "№ | Changeable themes | Themes | ..." can point at the wrong column: it is trusted only
            // when the columns it names are filled in the lesson rows
            if (byContent == null || mappingFits(rows, (Mapping) found[1])) return found;
            return new Object[] {found[0], byContent};
        }
        return byContent == null ? null : new Object[] {-1, byContent};
    }

    /** True when most rows that have objective codes also have a topic and the codes in the mapped columns. */
    private static boolean mappingFits(List<List<String>> rows, Mapping m) {
        int lessons = 0;
        int ok = 0;
        for (List<String> r : rows) {
            if (r.stream().noneMatch(c -> CODE.matcher(c).find())) continue;
            lessons++;
            if (m.topic() >= 0 && m.topic() < r.size() && !r.get(m.topic()).isBlank() && m.objectives() >= 0
                    && m.objectives() < r.size() && CODE.matcher(r.get(m.objectives())).find()) ok++;
        }
        return lessons == 0 || ok >= 0.6 * lessons;
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
    private static final Pattern TERM_ROW = Pattern.compile(
            "(?i)(?:\\bterm\\s*[-–—]?\\s*(IV|III|II|I|\\d)\\b|\\b(IV|III|II|I|\\d)\\s*(?:st|nd|rd|th)?\\s*term\\b)"
                    + "\\s*[-–—]?\\s*\\(?\\s*[-–—]?\\s*(\\d+)?\\s*(?:hours?|h)?\\s*\\)?");
    // "Unit 6" anywhere in a heading, but not inside brackets: "The natural world (Unit 6 p.68)" is a unit cell
    private static final Pattern UNIT_ANY = Pattern.compile("(?i)(?<!\\w)(unit|module)\\s*(\\d+)");
    private static final Pattern TOTAL = Pattern.compile("(?i)^\\s*(total|итого|всего|барлығы)\\b[^\\d]*(\\d+)?");
    private static final Pattern GRADE = Pattern.compile("(?i)(?:grade|класс|сынып)\\s*(\\d{1,2})|(\\d{1,2})\\s*[- ]?(?:класс|сынып|grade)");
    private static final Pattern YEAR = Pattern.compile("(20\\d\\d)\\s*[-–/]\\s*(20\\d\\d)");
    private static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("d.M.uuuu");

    public static Outcome parse(RawDoc doc, Mapping forced) {
        List<List<String>> rows = doc.rows();
        Mapping map = forced;
        int headerRow = -1;
        Object[] found = detect(rows);
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

            // a row without a lesson that names a unit and/or a term: "Unit 3 Holidays and Travel",
            // "Term III (30 hours)Unit 5 Reading for pleasure", "Term IV 24 hours"
            // (the heading may stand in the number column too, it is then not a number)
            boolean headingInTopic = number.isBlank() && !topic.isBlank() && objectives.isBlank()
                    && cell(cells, map.hours()).isBlank() && cell(cells, map.date()).isBlank()
                    && (TERM_ROW.matcher(topic).find() || UNIT_ANY.matcher(topic).find());
            if (((number.isBlank() || !number.matches("\\d{1,3}")) && topic.isBlank() || headingInTopic)
                    && !CODE.matcher(joined).find()) {
                String rest = joined;
                boolean heading = false;
                Matcher tr = TERM_ROW.matcher(rest);
                if (tr.find()) {
                    currentTerm = romanOrInt(tr.group(1) != null ? tr.group(1) : tr.group(2));
                    Term t = new Term();
                    t.no = currentTerm;
                    t.hours = tr.group(3) == null ? null : Integer.valueOf(tr.group(3));
                    out.terms.add(t);
                    rest = (rest.substring(0, tr.start()) + " " + rest.substring(tr.end())).strip();
                    heading = true;
                }
                String unitHeading = unitHeading(rest);
                if (unitHeading != null) {
                    currentUnit = unitHeading;
                    heading = true;
                }
                if (heading) continue;
            }
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
            if (!unit.isBlank()) currentUnit = unglue(unit).replaceAll("(?i)^(unit\\s*\\d+)\\s*:\\s*", "$1: ");

            Lesson l = new Lesson();
            l.seq = out.lessons.size() + 1;
            l.term = currentTerm;
            l.unit = currentUnit;
            l.topic = unglue(topic);
            String hoursText = cell(cells, map.hours());
            if (hoursText.matches("\\d{1,2}")) {
                l.hours = Math.max(1, Integer.parseInt(hoursText));
            } else {
                blankHours++;
            }
            l.date = parseDate(cell(cells, map.date()), out.schoolYear);
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
        assignLeadingTerm(out);
        validate(out, blankHours);
        return new Outcome(out, false, preview(rows));
    }

    /**
     * A table that starts in the middle of the year has no row for the term it starts in ("Term III" comes
     * first): the lessons before the first term row belong to the term before it, and their hours are its hours.
     */
    private static void assignLeadingTerm(Parsed out) {
        if (out.terms.isEmpty()) return;
        int first = out.terms.get(0).no;
        if (first <= 1) return;
        int hours = 0;
        for (Lesson l : out.lessons) {
            if (l.term != null) break;
            l.term = first - 1;
            hours += l.hours;
        }
        if (hours > 0) {
            Term t = new Term();
            t.no = first - 1;
            t.hours = hours;
            out.terms.add(0, t);
        }
    }

    private static void validate(Parsed out, int blankHours) {
        if (out.lessons.isEmpty()) {
            out.warnings.add("Не нашёл ни одного урока в таблице.");
            return;
        }
        if (blankHours > 0) {
            out.warnings.add("Часы не указаны в " + blankHours + " уроках, считаю по 1 часу.");
        }
        // a whole KTP always has 102 lessons (3 a week) or 68 (2 a week), starts in September and ends in May
        int hours = out.totalHours();
        if (hours != 102 && hours != 68) {
            out.warnings.add("В КТП " + hours + " часов, а полный КТП содержит 102 урока (3 в неделю) или 68 (2 в "
                    + "неделю). Возможно, часть файла не прочитана: проверьте, что в нём нет нескольких таблиц.");
        }
        String firstDate = out.lessons.stream().map(l -> l.date).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        String lastDate = out.lessons.stream().map(l -> l.date).filter(java.util.Objects::nonNull)
                .reduce((x, y) -> y).orElse(null);
        if (firstDate != null && !firstDate.substring(5, 7).equals("09")) {
            out.warnings.add("КТП начинается не в сентябре (первая дата " + firstDate + "): КТП всегда начинается с "
                    + "сентября, проверьте файл.");
        }
        if (lastDate != null && !lastDate.substring(5, 7).equals("05")) {
            out.warnings.add("КТП заканчивается не в мае (последняя дата " + lastDate + "): КТП всегда заканчивается "
                    + "в мае, проверьте файл.");
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

    /** Row index of the header (the row that names the columns), or -1. */
    public static int headerRowIndex(List<List<String>> rows) {
        Object[] found = detectHeader(rows);
        return found == null ? -1 : (int) found[0];
    }

    /** Column of the lesson number in the header row, or -1 when it is unknown. */
    public static int numberColumn(List<List<String>> rows) {
        Object[] found = detect(rows);
        return found == null ? -1 : ((Mapping) found[1]).number();
    }

    /** Title, grade and school year from the lines above the table. */
    public static void readMeta(RawDoc doc, Parsed out) {
        readTitle(doc, out);
    }

    /** The checks that need all lessons: hours and totals against the file (adds warnings). */
    public static void finish(Parsed out, int blankHours) {
        validate(out, blankHours);
    }

    /** "4. 1. 1. 1." or "4.1.1.1" into "4.1.1.1"; the input itself when it is not a code. */
    public static String normCode(String s) {
        if (s == null) return "";
        Matcher m = CODE.matcher(s);
        return m.find() ? m.group(1) + "." + m.group(2) + "." + m.group(3) + "." + m.group(4) : s.strip();
    }

    /** ISO date from a cell like 05.10.2026 or 2026-10-05, null otherwise. */
    public static String isoDate(String s) {
        return parseDate(s, null);
    }

    /** As above, and a day and month without a year (05.11) take the year from the school year "2026-2027". */
    public static String isoDate(String s, String schoolYear) {
        return parseDate(s, schoolYear);
    }

    /**
     * "Unit 3 Holidays and Travel", "Tradition and language. Unit 6 p.p. 68 - 79 (10 hours)" and "Travel and tourism.
     * Unit 8" all give "Unit N: Title"; null when the text names no unit. The title is the text before the word
     * "Unit" when there is some, otherwise the text after the number.
     */
    static String unitHeading(String text) {
        Matcher m = UNIT_ANY.matcher(text);
        if (!m.find()) return null;
        String head = text.substring(0, m.start()).stripTrailing();
        if (head.endsWith("(")) head = head.substring(0, head.length() - 1); // "Charities and conflict (Unit 4 )"
        String before = head.replaceAll("[\\s.,;:–-]+$", "").strip();
        String after = text.substring(m.end()).replaceAll("^[\\s.:–-]+", "").strip();
        String title = before.isEmpty() ? after : before;
        title = title.replaceAll("\\(\\s*\\d+\\s*hours?\\s*\\)\\s*$", "").strip();
        // a closing bracket that has no opening bracket is a leftover of "(Unit 4 )"; a balanced one is part of the title
        while (title.endsWith(")") && title.chars().filter(ch -> ch == ')').count() > title.chars().filter(ch -> ch == '(').count()) {
            title = title.substring(0, title.length() - 1).strip();
        }
        title = unglue(title);
        String word = m.group(1).substring(0, 1).toUpperCase(Locale.ROOT) + m.group(1).substring(1).toLowerCase(Locale.ROOT);
        return word + " " + m.group(2) + (title.isEmpty() ? "" : ": " + title);
    }

    private static int romanOrInt(String r) {
        return switch (r.toUpperCase(Locale.ROOT)) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            default -> Integer.parseInt(r);
        };
    }

    /**
     * Words typed together ("FocusPresent", "Unit 3Holidays", "guide.Summative") get a space at the joint: between
     * a lower case and an upper case letter, a digit and an upper case letter, and after a full stop.
     */
    public static String unglue(String s) {
        if (s == null) return "";
        return s.replaceAll("(?<=[a-z])(?=[A-Z])", " ").replaceAll("(?<=\\d)(?=[A-Z])", " ")
                .replaceAll("(?<=[a-z]\\.)(?=[A-Z])", " ").replaceAll("\\s+", " ").strip();
    }

    public static String kindOf(String topic) {
        String t = topic.toLowerCase(Locale.ROOT);
        if (t.contains("interim")) return "interim";
        // a summative work "for the term N" is the term assessment (40 minutes), not the one of a unit
        if (t.contains("summative") && (t.contains("term") || t.contains("четверт") || t.contains("тоқсан"))) {
            return "interim";
        }
        if (t.contains("checkpoint") || t.contains("check point") || t.contains("summative")
                || t.contains("контрольн") || t.contains("суммативн")) return "checkpoint";
        if (t.contains("project") || t.contains("проект")) return "project";
        if (t.contains("clil")) return "clil";
        return "lesson";
    }

    private static String parseDate(String s, String schoolYear) {
        if (s == null || s.isBlank()) return null;
        try {
            // 05.11 or 05.03(08.03): the first date counts (the one in brackets is the date the lesson was moved from)
            Matcher dm = Pattern.compile("^\\s*(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?\\s*(?:\\(.*\\))?\\s*$").matcher(s);
            if (dm.matches()) {
                int month = Integer.parseInt(dm.group(2));
                Integer year = dm.group(3) == null ? null : Integer.valueOf(dm.group(3));
                if (year == null) {
                    Matcher y = schoolYear == null ? null : YEAR.matcher(schoolYear);
                    if (y == null || !y.find()) return null;
                    year = Integer.parseInt(month >= 8 ? y.group(1) : y.group(2));
                }
                return LocalDate.of(year, month, Integer.parseInt(dm.group(1))).toString();
            }
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
