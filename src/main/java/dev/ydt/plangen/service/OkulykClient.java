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
}
