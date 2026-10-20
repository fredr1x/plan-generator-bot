package dev.ydt.plangen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ydt.plangen.domain.Book;
import dev.ydt.plangen.domain.BookPage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds textbook pages (by number or by topic) and turns them into the text block that goes into the
 * model prompt. There is no vector search: a book has a few hundred pages, so plain word matching over the
 * page data (heading, keywords, summary, exercises) is enough and easy to reason about.
 */
@ApplicationScoped
public class BookService {

    public static final int MAX_PAGES = 6;

    private static final int MAX_CONTEXT_CHARS = 14_000;
    private static final Pattern RANGE = Pattern.compile("(\\d{1,4})\\s*[-–—]\\s*(\\d{1,4})");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,4}");

    public record Found(List<BookPage> pages, List<Integer> missing) {}

    public record Suggestion(String spec, String label) {}

    /** Section ("Unit 2. Holidays and travel") and topic ("At home") of a page, either may be empty. */
    public record Heading(String section, String topic) {}

    /** An exercise key from the page data: shown page number, exercise number, "exact" or "sample", the key. */
    public record Answer(int page, String number, String type, String key) {}

    @Inject
    ObjectMapper mapper;

    // ------------------------------------------------------------------ page numbers

    /** "45", "45-46", "45, 48" or "45 - 47 49" into a sorted list of numbers (ranges expanded, at most 200). */
    public static List<Integer> parseSpec(String spec) {
        Set<Integer> out = new TreeSet<>();
        if (spec == null) return List.of();
        Matcher r = RANGE.matcher(spec);
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (r.find()) {
            rest.append(spec, last, r.start()).append(' ');
            last = r.end();
            int a = Integer.parseInt(r.group(1));
            int b = Integer.parseInt(r.group(2));
            for (int p = Math.min(a, b); p <= Math.max(a, b) && out.size() < 200; p++) out.add(p);
        }
        rest.append(spec.substring(last));
        Matcher n = NUMBER.matcher(rest);
        while (n.find()) out.add(Integer.parseInt(n.group()));
        return new ArrayList<>(out);
    }

    /** [45, 46, 48] -> "45-46,48". */
    public static String canonical(List<Integer> numbers) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < numbers.size()) {
            int j = i;
            while (j + 1 < numbers.size() && numbers.get(j + 1) == numbers.get(j) + 1) j++;
            if (sb.length() > 0) sb.append(',');
            sb.append(numbers.get(i));
            if (j > i) sb.append('-').append(numbers.get(j));
            i = j + 1;
        }
        return sb.toString();
    }

    /** Teachers name the number printed on the page; the PDF position is the fallback. */
    public Found find(long bookId, String spec) {
        List<Integer> wanted = parseSpec(spec);
        if (wanted.isEmpty()) return new Found(List.of(), List.of());
        List<BookPage> pages = BookPage.byPrinted(bookId, wanted);
        if (pages.isEmpty()) {
            pages = BookPage.byPdf(bookId, wanted);
        }
        Set<Integer> have = new LinkedHashSet<>();
        pages.forEach(p -> have.add(p.shownNumber()));
        List<Integer> missing = wanted.stream().filter(w -> !have.contains(w)).toList();
        return new Found(pages, missing);
    }

    public String pagesLabel(List<BookPage> pages) {
        return canonical(pages.stream().map(BookPage::shownNumber).sorted().distinct().toList());
    }

    // ------------------------------------------------------------------ search by topic

    public List<Suggestion> suggest(long bookId, String topic, String objectives) {
        return suggest(bookId, topic, null, objectives);
    }

    /**
     * Up to three page ranges (the best page and the one after it) that match the topic and objectives. A word
     * that occurs on many pages ("grammar", "unit") counts less than a rare one (idf); the unit title of the KTP
     * lesson adds a little for pages of that unit.
     */
    public List<Suggestion> suggest(long bookId, String topic, String unit, String objectives) {
        List<String> topicStems = stems(topic);
        List<String> unitStems = stems(unit);
        List<String> objectiveStems = stems(objectives);
        if (topicStems.isEmpty() && objectiveStems.isEmpty()) return List.of();

        record Scored(BookPage page, double score) {}
        record Text(BookPage page, String head, String summary, String body) {}
        List<BookPage> all = BookPage.ofBook(bookId);
        List<Text> texts = new ArrayList<>();
        for (BookPage p : all) {
            texts.add(new Text(p, lower(p.unit) + " " + lower(p.heading) + " " + lower(p.keywords), lower(p.summary),
                    lower(p.searchText)));
        }
        java.util.Map<String, Double> idf = new java.util.HashMap<>();
        for (List<String> words : List.of(topicStems, unitStems, objectiveStems)) {
            for (String w : words) {
                idf.computeIfAbsent(w, k -> {
                    long df = texts.stream().filter(t -> t.head().contains(k) || t.summary().contains(k)
                            || t.body().contains(k)).count();
                    return Math.log(1.0 + (double) texts.size() / (1 + df));
                });
            }
        }
        List<Scored> scored = new ArrayList<>();
        for (Text t : texts) {
            BookPage p = t.page();
            double s = 0;
            for (String w : topicStems) s += idf.get(w) * match(t.head(), t.summary(), t.body(), w);
            for (String w : objectiveStems) s += 0.4 * idf.get(w) * match(t.head(), t.summary(), t.body(), w);
            for (String w : unitStems) s += 0.5 * idf.get(w) * (t.head().contains(w) ? 1 : 0);
            if ("contents".equals(p.kind) || "cover".equals(p.kind)) s *= 0.3;
            if (s > 0) scored.add(new Scored(p, s));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<Suggestion> out = new ArrayList<>();
        Set<Integer> used = new LinkedHashSet<>();
        for (Scored sc : scored) {
            if (out.size() >= 3) break;
            BookPage p = sc.page();
            if (used.contains(p.pdfPage)) continue;
            BookPage next = all.stream().filter(x -> x.pdfPage == p.pdfPage + 1).findFirst().orElse(null);
            boolean sameLesson = next != null && !(lessonCode(p.heading) != null && lessonCode(next.heading) != null
                    && !lessonCode(p.heading).equals(lessonCode(next.heading)));
            List<BookPage> range = sameLesson && !used.contains(next.pdfPage) ? List.of(p, next) : List.of(p);
            range.forEach(x -> used.add(x.pdfPage));
            String label = (blank(p.unit) ? "" : p.unit + " · ") + (blank(p.heading) ? "" : p.heading);
            out.add(new Suggestion(pagesLabel(range), oneLine(label)));
        }
        return out;
    }

    private static int match(String head, String summary, String body, String w) {
        return (head.contains(w) ? 4 : 0) + (summary.contains(w) ? 2 : 0) + (body.contains(w) ? 1 : 0);
    }

    private static final Pattern LESSON_CODE = Pattern.compile("^\\s*(\\d{1,2}[a-f])\\b", Pattern.CASE_INSENSITIVE);

    /** "3b Apps on the go" -> "3b": the lesson of the unit that the page belongs to, or null. */
    private static String lessonCode(String heading) {
        if (heading == null) return null;
        Matcher m = LESSON_CODE.matcher(heading);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static List<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return List.of();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.length() < 4) continue;
            // crude stemming: drop a plural "s", then compare by the first five letters (home / homes / homework)
            String base = w.length() > 4 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
            out.add(base.length() > 5 ? base.substring(0, 5) : base);
        }
        return new ArrayList<>(out);
    }

    /** Headings of scanned pages may contain line breaks; buttons and logs need one line. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    // ------------------------------------------------------------------ what the teacher sees

    /** One line for the confirmation screen: pages, headings and the number of exercises. */
    public String describe(List<BookPage> pages) {
        StringBuilder sb = new StringBuilder();
        for (BookPage p : pages) {
            List<String> ex = new ArrayList<>();
            try {
                for (JsonNode e : mapper.readTree(p.contentJson).path("exercises")) {
                    ex.add(e.path("number").asText("?") + " " + e.path("kind").asText("other"));
                }
            } catch (IOException ignored) {
                // shown without the exercises
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append("• стр. ").append(p.shownNumber());
            if (!blank(p.heading)) sb.append(" «").append(oneLine(p.heading)).append('»');
            sb.append(" — упражнений: ").append(ex.size());
            if (!ex.isEmpty()) sb.append(" (").append(String.join(", ", ex)).append(')');
        }
        return sb.toString();
    }

    private static final Pattern UNIT_OPENER = Pattern.compile("^\\s*(\\d{1,2})\\s*[.:)\\-–]?\\s+(\\p{L}.{2,})$");
    private static final Pattern SKILL_PREFIX = Pattern.compile(
            "(?i)^(vocabulary and listening|vocabulary puzzles|vocabulary|grammar|reading for pleasure|reading|"
                    + "listening|speaking|writing|communication|culture|clil|review|skills?)\\b[\\s■▪•\\-–—:?]*");
    private static final Pattern CAN_DO = Pattern.compile("(?i)\\s*\\bI can\\b.*$");

    /**
     * The section and topic of a lesson as the textbook names them. The unit comes from the nearest unit opener
     * (a page whose unit starts with the unit number, "2 Holidays and travel") at or before the page, because the
     * following pages of a unit do not repeat it; the topic is the page heading without the skill label
     * ("VOCABULARY At home" becomes "At home") and without the "I can ..." line.
     */
    public Heading headingFor(BookPage page) {
        String section = "";
        List<BookPage> all = BookPage.ofBook(page.bookId);
        for (int i = all.size() - 1; i >= 0 && section.isEmpty(); i--) {
            BookPage p = all.get(i);
            if (p.pdfPage > page.pdfPage || blank(p.unit)) continue;
            Matcher m = UNIT_OPENER.matcher(oneLine(p.unit));
            if (m.matches()) {
                section = "Unit " + m.group(1) + ". " + m.group(2).strip();
            }
        }
        if (section.isEmpty() && !blank(page.unit) && !oneLine(page.unit).equalsIgnoreCase(oneLine(page.heading))
                && !SKILL_PREFIX.matcher(oneLine(page.unit)).find()) {
            section = oneLine(page.unit);
        }
        String topic = oneLine(page.heading);
        String stripped = CAN_DO.matcher(SKILL_PREFIX.matcher(topic).replaceFirst("")).replaceFirst("").strip();
        if (!stripped.isEmpty()) {
            topic = stripped;
        }
        return new Heading(section, topic);
    }

    /** The answer keys of the exercises on the pages (exercises without a key are left out). */
    public List<Answer> answers(List<BookPage> pages) {
        List<Answer> out = new ArrayList<>();
        for (BookPage p : pages) {
            JsonNode n;
            try {
                n = mapper.readTree(p.contentJson);
            } catch (IOException e) {
                continue;
            }
            for (JsonNode e : n.path("exercises")) {
                String type = e.path("answerType").asText("");
                String key = e.path("answerKey").asText("");
                if (blank(key) || "not_available".equals(type)) continue;
                out.add(new Answer(p.shownNumber(), e.path("number").asText("").strip(), type, key));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the prompt block

    /** All data of the pages for the model: theory, texts, picture descriptions, exercises with answer keys. */
    public String context(Book book, List<BookPage> pages) {
        StringBuilder sb = new StringBuilder();
        for (BookPage p : pages) {
            JsonNode n;
            try {
                n = mapper.readTree(p.contentJson);
            } catch (IOException e) {
                continue;
            }
            sb.append("=== ").append(book.typeLabel()).append(" \"").append(book.title).append("\", page ")
                    .append(p.shownNumber());
            if (!blank(p.unit) || !blank(p.heading)) {
                sb.append(" (").append(blank(p.unit) ? "" : p.unit).append(blank(p.unit) || blank(p.heading) ? "" : " | ")
                        .append(blank(p.heading) ? "" : p.heading).append(')');
            }
            sb.append(" ===\n");
            appendIf(sb, "Theory", n.path("theory").asText(""), 1200);
            appendIf(sb, "Reading text", n.path("readingText").asText(""), 1800);
            JsonNode pictures = n.path("pictures");
            if (pictures.size() > 0) {
                sb.append("Pictures:\n");
                for (JsonNode pic : pictures) {
                    String used = pic.path("usedBy").asText("");
                    sb.append("- ").append(used.isBlank() ? "" : "(exercise " + used + ") ")
                            .append(clip(pic.path("description").asText(""), 400)).append('\n');
                }
            }
            for (JsonNode e : n.path("exercises")) {
                sb.append("Exercise ").append(e.path("number").asText("?")).append(" [")
                        .append(e.path("kind").asText("other")).append("]: ")
                        .append(clip(e.path("instruction").asText(""), 300)).append('\n');
                String content = e.path("content").asText("");
                if (!content.isBlank()) sb.append(clip(content, 1000)).append('\n');
                String type = e.path("answerType").asText("exact");
                String key = e.path("answerKey").asText("");
                if ("not_available".equals(type) || key.isBlank()) {
                    sb.append("Answer key: not available\n");
                } else {
                    sb.append("Answer key (").append(type).append("): ").append(clip(key, 700)).append('\n');
                }
            }
            sb.append('\n');
        }
        String text = sb.toString().strip();
        return text.length() <= MAX_CONTEXT_CHARS ? text : text.substring(0, MAX_CONTEXT_CHARS) + "\n[cut]";
    }

    private static void appendIf(StringBuilder sb, String name, String value, int max) {
        if (!blank(value)) sb.append(name).append(": ").append(clip(value, max)).append('\n');
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String one = s.strip();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
