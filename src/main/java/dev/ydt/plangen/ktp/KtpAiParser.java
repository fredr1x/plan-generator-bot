package dev.ydt.plangen.ktp;

import dev.langchain4j.model.output.TokenUsage;
import dev.ydt.plangen.ktp.KtpChunk.AiLesson;
import dev.ydt.plangen.ktp.KtpChunk.AiObjective;
import dev.ydt.plangen.ktp.KtpChunk.AiTerm;
import dev.ydt.plangen.ktp.KtpParser.Lesson;
import dev.ydt.plangen.ktp.KtpParser.Parsed;
import dev.ydt.plangen.ktp.KtpParser.Term;
import dev.ydt.plangen.ktp.KtpReader.RawDoc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reads a KTP with the model: the rows go to the model in chunks and come back as structured lessons. The model
 * is only trusted for what the file really says: every topic and objective text is looked up in the file text,
 * and what is not found there is replaced by the plain code parser's value (with a warning). The code parser
 * also cross-checks the number of lessons and the objective codes.
 */
@ApplicationScoped
public class KtpAiParser {

    private static final Logger LOG = Logger.getLogger(KtpAiParser.class);

    /** Rows per request: small enough that the answer is short and exact. */
    private static final int CHUNK_ROWS = 30;

    /** A PDF has no table rows, only text lines (about nine per lesson), so its chunks are bigger. */
    private static final int CHUNK_LINES = 240;

    /** What the model is told about the lines of a PDF; the column names follow it. */
    private static final String PDF_HEADER = "This is the text of a PDF, so there are no cell borders. Every line is "
            + "\"x | text\", where x is the horizontal start position of a fragment of one table cell in points: "
            + "fragments of the same column start at about the same x, and one cell's text may be wrapped over "
            + "several consecutive lines. A lesson row starts with its number at the leftmost x; the cells that "
            + "follow (unit, topic, objectives, hours, date) are listed from left to right, in the column order of "
            + "the header lines below. First lines of the file: ";

    public record Result(Parsed parsed, int tokensIn, int tokensOut, int chunks) {}

    private final KtpModel model;

    @ConfigProperty(name = "app.pricing.input-per-million", defaultValue = "0.25")
    double priceIn;

    @ConfigProperty(name = "app.pricing.output-per-million", defaultValue = "2.00")
    double priceOut;

    @Inject
    public KtpAiParser(KtpModel model) {
        this.model = model;
    }

    /** det is the plain code parse of the same file (may be null); it is used for checks and as the fallback. */
    public Result parse(RawDoc raw, Parsed det) {
        List<List<String>> rows = raw.rows();
        boolean flat = raw.flat();
        int headerIdx = flat ? -1 : KtpParser.headerRowIndex(rows);
        String header = flat ? PDF_HEADER + rows.stream().limit(14).map(r -> String.join(" | ", r))
                        .collect(Collectors.joining(" // "))
                : headerIdx >= 0 ? line(headerIdx, rows.get(headerIdx))
                : "not detected; the first rows are " + rows.stream().limit(3).map(r -> String.join(" | ", r))
                        .collect(Collectors.joining(" // "));
        List<Integer> data = new ArrayList<>();
        for (int i = headerIdx + 1; i < rows.size(); i++) {
            if (!String.join("", rows.get(i)).isBlank()) data.add(i);
        }

        List<AiLesson> lessons = new ArrayList<>();
        Map<Integer, Integer> terms = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Integer total = null;
        String unit = "";
        Integer term = null;
        Integer lastNumber = null;
        int tokensIn = 0;
        int tokensOut = 0;
        int chunks = 0;
        long started = System.nanoTime();

        int numCol = flat ? -1 : KtpParser.numberColumn(rows);
        boolean[] flatStart = flat ? flatLessonStarts(rows) : null;
        int chunkRows = flat ? CHUNK_LINES : CHUNK_ROWS;
        int from = 0;
        while (from < data.size()) {
            int to = Math.min(from + chunkRows, data.size());
            // never cut a lesson in two: its objectives may continue on the next row, so a chunk ends only
            // before a row that starts a new lesson (a row with a lesson number)
            while (to < data.size() && (flat ? !flatStart[data.get(to)]
                    : numCol >= 0 && !startsLesson(rows.get(data.get(to)), numCol))) to++;
            List<Integer> part = data.subList(from, to);
            from = to;
            String text = part.stream().map(i -> line(i, rows.get(i))).collect(Collectors.joining("\n"));
            String previous = lessons.isEmpty() ? "none (these are the first rows)"
                    : "unit = \"" + unit + "\", term = " + term + ", last lesson number = " + lastNumber;
            dev.langchain4j.service.Result<KtpChunk> res = call(header, previous, text);
            chunks++;
            TokenUsage u = res.tokenUsage();
            int in = u == null || u.inputTokenCount() == null ? 0 : u.inputTokenCount();
            int out = u == null || u.outputTokenCount() == null ? 0 : u.outputTokenCount();
            tokensIn += in;
            tokensOut += out;
            KtpChunk c = res.content();
            LOG.infof("[ktp-ai] chunk %d: %d rows, %d lessons, tokens in=%d out=%d, finish=%s", chunks, part.size(),
                    c == null || c.lessons() == null ? 0 : c.lessons().size(), in, out, res.finishReason());
            if (c == null) continue;
            if (c.lessons() != null) {
                for (AiLesson l : c.lessons()) {
                    lessons.add(l);
                    if (l.unit() != null && !l.unit().isBlank()) unit = l.unit();
                    if (l.term() != null) term = l.term();
                    if (l.number() != null) lastNumber = l.number();
                }
            }
            if (c.terms() != null) {
                for (AiTerm t : c.terms()) {
                    if (t.number() != null) terms.merge(t.number(), t.hours() == null ? -1 : t.hours(), (a, b) -> b);
                }
            }
            if (c.total() != null) total = c.total();
            if (c.notes() != null) notes.addAll(c.notes());
        }

        Parsed p = build(raw, lessons, terms, total, notes, det);
        double cost = tokensIn * priceIn / 1_000_000.0 + tokensOut * priceOut / 1_000_000.0;
        LOG.infof("[ktp-ai] done: %d lessons from %d chunks, tokens in=%d out=%d, est. cost $%.4f, %d ms",
                p.lessons.size(), chunks, tokensIn, tokensOut, cost, (System.nanoTime() - started) / 1_000_000);
        return new Result(p, tokensIn, tokensOut, chunks);
    }

    /**
     * PDF lines that start a lesson: a number alone or followed by text, at the leftmost x among such lines
     * (the hours cell also holds a bare number, but far to the right).
     */
    private static boolean[] flatLessonStarts(List<List<String>> rows) {
        boolean[] numeric = new boolean[rows.size()];
        int minX = Integer.MAX_VALUE;
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() == 2 && r.get(1).matches("\\d{1,3}") && r.get(0).matches("\\d+")) {
                numeric[i] = true;
                minX = Math.min(minX, Integer.parseInt(r.get(0)));
            }
        }
        boolean[] out = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            out[i] = numeric[i] && Integer.parseInt(rows.get(i).get(0)) <= minX + 20;
        }
        return out;
    }

    private static boolean startsLesson(List<String> cells, int numCol) {
        return numCol < cells.size() && cells.get(numCol).strip().matches("\\d{1,3}");
    }

    private dev.langchain4j.service.Result<KtpChunk> call(String header, String previous, String rows) {
        try {
            return model.extract(header, previous, rows);
        } catch (RuntimeException first) {
            LOG.warnf("[ktp-ai] request failed, trying once more: %s", first.getMessage());
            return model.extract(header, previous, rows);
        }
    }

    // ------------------------------------------------------------------ result and checks

    private Parsed build(RawDoc raw, List<AiLesson> ai, Map<Integer, Integer> terms, Integer total,
                         List<String> notes, Parsed det) {
        Parsed p = new Parsed();
        KtpParser.readMeta(raw, p);
        p.declaredTotal = total;
        terms.forEach((no, hours) -> {
            Term t = new Term();
            t.no = no;
            t.hours = hours < 0 ? null : hours;
            p.terms.add(t);
        });

        // the x positions of a PDF are not part of the text the model has to reproduce
        String source = flat(raw.rows().stream().flatMap(r -> raw.flat() ? r.stream().skip(1) : r.stream())
                .collect(Collectors.joining(" ")));
        boolean aligned = det != null && det.lessons.size() == ai.stream()
                .filter(a -> a.topic() != null && !a.topic().isBlank()).count();
        int blankHours = 0;
        List<String> codeMismatch = new ArrayList<>();

        for (AiLesson a : ai) {
            if (a.topic() == null || a.topic().isBlank()) {
                p.warnings.add("ИИ вернул строку без темы (номер " + a.number() + "), она пропущена.");
                continue;
            }
            Lesson l = new Lesson();
            l.seq = p.lessons.size() + 1;
            l.term = a.term();
            l.unit = a.unit() == null || a.unit().isBlank() ? null
                    : tidy(a.unit()).replaceAll("(?i)^(unit\\s*\\d+)\\s*:\\s*", "$1: ");
            l.topic = tidy(a.topic());
            fillObjectives(l, a.objectives());
            if (a.hours() != null && a.hours() >= 1) l.hours = a.hours();
            else blankHours++;
            l.date = KtpParser.isoDate(a.date());
            l.kind = KtpParser.kindOf(l.topic);

            // never trust a text that the file does not contain
            Lesson d = aligned ? det.lessons.get(l.seq - 1) : null;
            if (!source.contains(flat(l.topic))) {
                if (d != null) {
                    p.warnings.add("Урок " + l.seq + ": тема от ИИ не найдена в файле дословно, взята из разбора кодом.");
                    l.topic = d.topic;
                } else {
                    p.warnings.add("Урок " + l.seq + " «" + clip(l.topic, 40) + "»: тема не найдена в файле дословно.");
                }
            }
            if (!objectivesInFile(a.objectives(), source)) {
                if (d != null) {
                    p.warnings.add("Урок " + l.seq + ": цели от ИИ не найдены в файле дословно, взяты из разбора кодом.");
                    l.objectives = d.objectives;
                    l.codes = d.codes;
                } else {
                    p.warnings.add("Урок " + l.seq + ": тексты целей не найдены в файле дословно, проверьте.");
                }
            }
            if (d != null && !codeSet(d.codes).equals(codeSet(l.codes))) {
                // the codes are the most important part of the objectives: on any disagreement the file's own
                // parse wins, so a dropped or altered code can never reach the plan
                codeMismatch.add(String.valueOf(l.seq));
                l.objectives = d.objectives;
                l.codes = d.codes;
            }
            if (l.objectives == null || l.objectives.isBlank()) {
                p.warnings.add("Урок " + l.seq + " «" + clip(l.topic, 40) + "»: нет целей обучения.");
            }
            p.lessons.add(l);
        }

        if (det != null && det.lessons.size() != p.lessons.size()) {
            p.warnings.add("ИИ нашёл " + p.lessons.size() + " уроков, а разбор кодом " + det.lessons.size()
                    + ". Проверьте список (кнопка «Разобрать без ИИ» покажет второй вариант).");
        }
        if (!codeMismatch.isEmpty()) {
            p.warnings.add("Коды целей от ИИ не совпали с файлом в уроках: "
                    + String.join(", ", codeMismatch.stream().limit(10).toList())
                    + ". Цели в них взяты из разбора кодом.");
        }
        if (det == null && raw.flat()) assignTermsByHours(p);
        notes.stream().limit(5).forEach(n -> p.warnings.add("ИИ: " + n));
        KtpParser.finish(p, blankHours);
        return p;
    }

    /**
     * Where the file has no usable term rows per lesson (a PDF), the term of a lesson follows from the hours of
     * each term: lessons are taken in order until the hours of the term are used up. Only done when the hours of
     * the terms add up to the hours of all lessons, otherwise the terms from the model stay.
     */
    private static void assignTermsByHours(Parsed p) {
        int lessonHours = p.lessons.stream().mapToInt(l -> l.hours).sum();
        int termHours = 0;
        for (Term t : p.terms) {
            if (t.hours == null) return;
            termHours += t.hours;
        }
        if (p.terms.isEmpty() || termHours != lessonHours) return;
        int ti = 0;
        int used = 0;
        int changed = 0;
        for (Lesson l : p.lessons) {
            while (ti < p.terms.size() - 1 && used >= p.terms.get(ti).hours) {
                used = 0;
                ti++;
            }
            Integer no = p.terms.get(ti).no;
            if (!java.util.Objects.equals(l.term, no)) changed++;
            l.term = no;
            used += l.hours;
        }
        if (changed > 0) {
            p.warnings.add("Четверти уроков пересчитаны по часам четвертей (" + changed + " ур. изменено).");
        }
    }

    private static void fillObjectives(Lesson l, List<AiObjective> items) {
        StringBuilder lines = new StringBuilder();
        StringBuilder codes = new StringBuilder();
        if (items != null) {
            for (AiObjective o : items) {
                String code = o.code() == null ? "" : KtpParser.normCode(o.code());
                if (!code.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}")) code = "";
                // the separators between objectives (; ,) are not part of the objective text
                String text = o.text() == null ? "" : tidy(o.text()).replaceAll("[;,\\s]+$", "");
                if (code.isEmpty() && text.isEmpty()) continue;
                if (lines.length() > 0) lines.append('\n');
                lines.append(code.isEmpty() ? text : code + (text.isEmpty() ? "" : " " + text));
                if (!code.isEmpty() && !(", " + codes + ",").contains(", " + code + ",")) {
                    if (codes.length() > 0) codes.append(", ");
                    codes.append(code);
                }
            }
        }
        l.objectives = lines.toString();
        l.codes = codes.toString();
    }

    private static boolean objectivesInFile(List<AiObjective> items, String source) {
        if (items == null) return true;
        for (AiObjective o : items) {
            if (o.text() != null && !o.text().isBlank() && !source.contains(flat(o.text()))) return false;
        }
        return true;
    }

    private static List<String> codeSet(String codes) {
        if (codes == null || codes.isBlank()) return List.of();
        return java.util.Arrays.stream(codes.split(",\\s*")).sorted().toList();
    }

    /** Lower case letters and digits only: the comparison ignores spaces, punctuation and quote styles. */
    static String flat(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String tidy(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static String line(int index, List<String> cells) {
        return "R" + (index + 1) + ": " + String.join(" | ", cells);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
