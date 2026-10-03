package dev.ydt.plangen.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the public page of one book on okulyk.kz (the site has no API): title, authors, publisher, year, grade,
 * pages, language, cover and the direct PDF link. One page per request, only when a teacher pastes a link;
 * nothing is crawled and no PDF is downloaded here.
 */
@ApplicationScoped
public class OkulykClient {

    /** What the book page tells; any field may be null when the page does not have it. */
    public record CatalogBook(String title, String subject, Integer grade, String authors, String publisher,
                              Integer year, Integer pages, String language, String coverUrl, String pdfUrl,
                              String sourceUrl) {}

    private static final Set<String> HOSTS = Set.of("okulyk.kz", "www.okulyk.kz", "okulyk.com", "www.okulyk.com");
    private static final int MAX_HTML = 2_000_000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern H1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<td>\\s*([^<:]{2,30}):\\s*</td>\\s*<td>(.*?)</td>",
            Pattern.DOTALL);
    private static final Pattern PDF = Pattern.compile("data-src=\"(https?://[^\"]+\\.pdf)\"");
    private static final Pattern COVER = Pattern.compile("<img[^>]*class=\"cover\"[^>]*src=\"([^\"]+)\"");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&#(\\d{2,5});");

    /** True for an https link to a page of okulyk.kz / okulyk.com (nothing else is ever requested). */
    public static boolean isAllowed(String url) {
        try {
            URI u = URI.create(url.strip());
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
                    && HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public Optional<CatalogBook> fetch(String url) throws IOException, InterruptedException {
        if (!isAllowed(url)) {
            return Optional.empty();
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.strip()))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (plan-generator-bot; reads one book page when a teacher shares a link)")
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200 || res.body().length() > MAX_HTML) {
            return Optional.empty();
        }
        return Optional.ofNullable(parse(res.body(), url.strip()));
    }

    /** Pure parsing of the book page, null when it does not look like a book page. */
    public static CatalogBook parse(String html, String sourceUrl) {
        Matcher h = H1.matcher(html);
        if (!h.find()) {
            return null;
        }
        String authors = rowValue(html, "Авторы");
        String title = clean(h.group(1));
        title = title.replaceAll("(?i)\\s*учебник для \\d+ класса\\s*$", "").strip();
        if (authors != null) {
            // the heading ends with the first author's name, which is already in the authors field
            String first = authors.split(",")[0].strip();
            if (!first.isEmpty() && title.endsWith(first)) {
                title = title.substring(0, title.length() - first.length()).strip();
            }
        }
        if (title.isEmpty()) {
            return null;
        }
        Matcher pdf = PDF.matcher(html);
        Matcher cover = COVER.matcher(html);
        return new CatalogBook(title, rowValue(html, "Предмет"), firstInt(rowValue(html, "Класс")), authors,
                rowValue(html, "Издательство"), firstInt(rowValue(html, "Год")),
                firstInt(rowValue(html, "Страниц")), rowValue(html, "Язык"),
                cover.find() ? cover.group(1) : null, pdf.find() ? pdf.group(1) : null, sourceUrl);
    }

    /** True when the title says it is a workbook (English, Russian or Kazakh wording). */
    public static boolean looksLikeWorkbook(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return t.contains("workbook") || t.contains("рабочая тетрадь") || t.contains("рабочая тетрад")
                || t.contains("жұмыс дәптері") || t.contains("практикум");
    }

    private static String rowValue(String html, String label) {
        Matcher m = ROW.matcher(html);
        while (m.find()) {
            if (m.group(1).strip().equalsIgnoreCase(label)) {
                String v = clean(m.group(2));
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private static Integer firstInt(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("\\d{1,4}").matcher(s);
        return m.find() ? Integer.valueOf(m.group()) : null;
    }

    private static String clean(String s) {
        String t = TAG.matcher(s).replaceAll(" ");
        Matcher m = ENTITY.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replaceAll("\\s+", " ").strip();
    }

    // ------------------------------------------------------------------ the list of books of a grade

    /** One book in a list: id and slug make the link https://okulyk.kz/slug/id/. */
    public record Listed(String id, String slug, String url, String title, Integer year, String language) {}

    private static final Pattern HEADING = Pattern.compile("<h([1-6])[^>]*>(.*?)</h\\1>", Pattern.DOTALL);
    private static final Pattern LISTED = Pattern.compile(
            "<a href=\"(https://okulyk\\.kz/([a-z0-9-]+)/(\\d+)/)\"[^>]*class=\"book\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern LISTED_TITLE = Pattern.compile("<span class=\"book-title\">(.*?)</span>", Pattern.DOTALL);
    private static final Pattern LISTED_YEAR = Pattern.compile("<span class=\"new-year[^>]*>\\s*(\\d{4})", Pattern.DOTALL);
    private static final Pattern LISTED_FLAG = Pattern.compile("<span class=\"flag[^\"]*\">(.*?)</span>", Pattern.DOTALL);

    private static final long LIST_TTL_MS = 6L * 60 * 60 * 1000;
    private final java.util.Map<Integer, java.util.Map.Entry<Long, java.util.Map<String, java.util.List<Listed>>>>
            listCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The books of one subject for a grade, from the page okulyk.kz/N-class/ (one request, kept for six hours).
     * Empty when the page has no section with that subject name.
     */
    public java.util.List<Listed> listGrade(int grade, String subject) throws IOException, InterruptedException {
        if (grade < 1 || grade > 11 || subject == null || subject.isBlank()) {
            return java.util.List.of();
        }
        var cached = listCache.get(grade);
        java.util.Map<String, java.util.List<Listed>> sections;
        if (cached != null && System.currentTimeMillis() - cached.getKey() < LIST_TTL_MS) {
            sections = cached.getValue();
        } else {
            String url = "https://okulyk.kz/" + grade + "-class/";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (plan-generator-bot; reads one list when a teacher opens it)")
                    .GET()
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200 || res.body().length() > 4_000_000) {
                return java.util.List.of();
            }
            sections = parseGrade(res.body());
            listCache.put(grade, java.util.Map.entry(System.currentTimeMillis(), sections));
        }
        String want = subject.strip().toLowerCase(Locale.ROOT);
        for (var e : sections.entrySet()) {
            if (e.getKey().equalsIgnoreCase(want)) return e.getValue();
        }
        // the name in the bot's list and the heading on the site may differ a little ("Русский язык" and
        // "Русский язык и литература"): sections that contain one another are shown together
        java.util.List<Listed> close = new java.util.ArrayList<>();
        for (var e : sections.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (key.contains(want) || want.contains(key)) close.addAll(e.getValue());
        }
        return close;
    }

    /** Pure parsing: subject heading to the books that follow it until the next heading. */
    public static java.util.Map<String, java.util.List<Listed>> parseGrade(String html) {
        java.util.TreeMap<Integer, String> headings = new java.util.TreeMap<>();
        Matcher h = HEADING.matcher(html);
        while (h.find()) {
            String text = clean(h.group(2));
            if (!text.isEmpty()) headings.put(h.start(), text);
        }
        java.util.Map<String, java.util.List<Listed>> out = new java.util.LinkedHashMap<>();
        Matcher b = LISTED.matcher(html);
        while (b.find()) {
            var head = headings.floorEntry(b.start());
            if (head == null) continue;
            String inner = b.group(4);
            Matcher t = LISTED_TITLE.matcher(inner);
            if (!t.find()) continue;
            String title = clean(t.group(1));
            Matcher y = LISTED_YEAR.matcher(inner);
            Matcher f = LISTED_FLAG.matcher(inner);
            java.util.List<Listed> list = out.computeIfAbsent(head.getValue(), k -> new java.util.ArrayList<>());
            boolean dup = list.stream().anyMatch(x -> x.id().equals(b.group(3)));
            if (!dup && !title.isEmpty()) {
                list.add(new Listed(b.group(3), b.group(2), b.group(1), title,
                        y.find() ? Integer.valueOf(y.group(1)) : null, f.find() ? clean(f.group(1)) : null));
            }
        }
        return out;
    }
}
