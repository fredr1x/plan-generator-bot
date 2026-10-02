package dev.ydt.bookingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Loads a textbook PDF into the plan-generator database.
 *
 * Every page is rendered to an image and sent to a vision model once; the answer is a structured page:
 * text, exercises with answer keys (expected answers for open questions), reading texts and descriptions
 * of the pictures. Run it on your own computer: it talks to the database and to OpenAI directly.
 *
 *   java -jar book-ingest.jar --pdf book.pdf --title "English Plus 7" --subject "English language" --grade 7
 *        [--type student|workbook] [--track natural|social] [--quarters 1-4] [--part "first half"]
 *        [--from 1] [--to 200] [--pages 22,24,30-32] [--dpi 130] [--parallel 3] [--model gpt-5-mini]
 *        [--env path/to/.env] [--force]
 *
 * --type: student's book (default) or workbook. --track: only for grades 10-11 (natural-mathematical or
 * social-humanitarian), leave it out for books that fit every track. --quarters: the quarters the book covers,
 * e.g. 2 or 1-4 (default 1-4); --part is a free note such as "first half".
 */
public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        if (!opt.containsKey("pdf") || !opt.containsKey("title")) {
            System.out.println("""
                    Usage: java -jar book-ingest.jar --pdf <file.pdf> --title "<book title>" [--subject "..."] [--grade 7]
                           [--type student|workbook] [--track natural|social] [--quarters 1-4] [--part "first half"]
                           [--from 1] [--to 200] [--dpi 130] [--parallel 3] [--model gpt-5-mini] [--env .env] [--force]
                    Needs DB_URL, DB_USER, DB_PASSWORD and OPENAI_API_KEY (from the environment or the --env file).""");
            System.exit(2);
        }
        Map<String, String> env = loadEnv(opt.getOrDefault("env", ".env"));
        String dbUrl = need(env, "DB_URL");
        String dbUser = need(env, "DB_USER");
        String dbPass = need(env, "DB_PASSWORD");
        String apiKey = need(env, "OPENAI_API_KEY");
        String model = opt.getOrDefault("model", env.getOrDefault("OPENAI_MODEL", "gpt-5-mini"));
        double priceIn = Double.parseDouble(env.getOrDefault("PRICE_INPUT_PER_M", "0.25"));
        double priceOut = Double.parseDouble(env.getOrDefault("PRICE_OUTPUT_PER_M", "2.00"));
        int dpi = Integer.parseInt(opt.getOrDefault("dpi", "130"));
        int parallel = Integer.parseInt(opt.getOrDefault("parallel", "3"));
        boolean force = opt.containsKey("force");
        String subject = opt.getOrDefault("subject", "");
        Integer grade = opt.containsKey("grade") ? Integer.valueOf(opt.get("grade")) : null;
        String title = opt.get("title");
        Kind kind = Kind.parse(opt);

        try (PDDocument pdf = Loader.loadPDF(new File(opt.get("pdf")))) {
            int total = pdf.getNumberOfPages();
            int from = Math.max(1, Integer.parseInt(opt.getOrDefault("from", "1")));
            int to = Math.min(total, Integer.parseInt(opt.getOrDefault("to", String.valueOf(total))));
            long bookId;
            Set<Integer> done;
            try (Connection c = connect(dbUrl, dbUser, dbPass)) {
                bookId = upsertBook(c, title, subject, grade, total, kind);
                done = force ? Set.of() : existingPages(c, bookId);
            }
            List<Integer> todo = new ArrayList<>();
            if (opt.containsKey("pages")) {
                // an explicit list such as 22,24,30-32 is always (re)loaded, even if the page exists
                for (String part : opt.get("pages").split(",")) {
                    String[] r = part.trim().split("[-–]");
                    int a1 = Integer.parseInt(r[0].trim());
                    int b1 = r.length > 1 ? Integer.parseInt(r[1].trim()) : a1;
                    for (int p = Math.max(1, a1); p <= Math.min(total, b1); p++) {
                        if (!todo.contains(p)) todo.add(p);
                    }
                }
            } else {
                for (int p = from; p <= to; p++) {
                    if (!done.contains(p)) todo.add(p);
                }
            }
            System.out.printf("Book \"%s\" (id %d): %s, %s, quarters %d-%d%s%n", title, bookId, kind.type,
                    kind.track == null ? "all tracks" : "track " + kind.track, kind.quarterFrom, kind.quarterTo,
                    kind.part.isBlank() ? "" : " (" + kind.part + ")");
            System.out.printf("%d pages in the PDF, loading %d-%d, %d to do, %d already loaded%n",
                    total, from, to, todo.size(), done.size());
            System.out.printf("Model %s, %d dpi, %d in parallel%n", model, dpi, parallel);
            if (todo.isEmpty()) {
                System.out.println("Nothing to do.");
                return;
            }

            PDFRenderer renderer = new PDFRenderer(pdf);
            ExecutorService pool = Executors.newFixedThreadPool(parallel);
            AtomicInteger finished = new AtomicInteger();
            AtomicLong tokIn = new AtomicLong();
            AtomicLong tokOut = new AtomicLong();
            List<Integer> failed = new ArrayList<>();
            List<Future<?>> futures = new ArrayList<>();
            long started = System.currentTimeMillis();

            for (int page : todo) {
                // rendering touches the PDF document, so it is done on the main thread, one page at a time
                byte[] jpeg = renderJpeg(renderer, page - 1, dpi);
                futures.add(pool.submit(() -> {
                    try {
                        Page result = readPage(apiKey, model, jpeg, subject, grade, title);
                        try (Connection c = connect(dbUrl, dbUser, dbPass)) {
                            savePage(c, bookId, page, result);
                        }
                        tokIn.addAndGet(result.tokensIn);
                        tokOut.addAndGet(result.tokensOut);
                        int n = finished.incrementAndGet();
                        System.out.printf("[%d/%d] pdf page %d ok: printed=%s kind=%s exercises=%d pictures=%d "
                                        + "tokens in=%d out=%d heading=\"%s\"%n",
                                n, todo.size(), page, result.node.path("printedPage").isNull() ? "-"
                                        : result.node.path("printedPage").asText(),
                                result.node.path("kind").asText(), result.node.path("exercises").size(),
                                result.node.path("pictures").size(), result.tokensIn, result.tokensOut,
                                clip(result.node.path("heading").asText(), 50));
                    } catch (Exception e) {
                        synchronized (failed) {
                            failed.add(page);
                        }
                        System.out.printf("[FAILED] pdf page %d: %s%n", page, e.getMessage());
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();

            double cost = tokIn.get() * priceIn / 1_000_000.0 + tokOut.get() * priceOut / 1_000_000.0;
            System.out.printf("%nDone in %d s: %d pages ok, %d failed. Tokens in=%d out=%d, estimated cost $%.3f%n",
                    (System.currentTimeMillis() - started) / 1000, finished.get(), failed.size(), tokIn.get(),
                    tokOut.get(), cost);
            if (!failed.isEmpty()) {
                failed.sort(Integer::compare);
                System.out.println("Failed pages (run the same command again, loaded pages are skipped): " + failed);
            }
        }
    }

    // ------------------------------------------------------------------ vision call

    private record Page(JsonNode node, int tokensIn, int tokensOut) {}

    private static Page readPage(String apiKey, String model, byte[] jpeg, String subject, Integer grade,
                                 String title) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        ObjectNode user = messages.addObject().put("role", "user");
        var parts = user.putArray("content");
        parts.addObject().put("type", "text").put("text", "Book: " + title
                + (subject.isBlank() ? "" : ", subject: " + subject) + (grade == null ? "" : ", grade " + grade)
                + ". Extract this page.");
        ObjectNode img = parts.addObject().put("type", "image_url").putObject("image_url");
        img.put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg));
        img.put("detail", "high");
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode js = format.putObject("json_schema");
        js.put("name", "book_page");
        js.put("strict", true);
        js.set("schema", JSON.readTree(PAGE_SCHEMA));
        body.put("reasoning_effort", "low");
        body.put("max_completion_tokens", 12000);

        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                        .timeout(Duration.ofSeconds(240))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (res.statusCode() == 429 || res.statusCode() >= 500) {
                    throw new RuntimeException("HTTP " + res.statusCode() + " " + clip(res.body(), 200));
                }
                if (res.statusCode() != 200) {
                    // a 4xx will not get better by retrying (bad key, unknown model, ...)
                    throw new IllegalStateException("HTTP " + res.statusCode() + " " + clip(res.body(), 400));
                }
                JsonNode root = JSON.readTree(res.body());
                JsonNode msg = root.path("choices").path(0).path("message");
                String content = msg.path("content").asText("");
                if (content.isBlank()) {
                    throw new RuntimeException("empty answer, finish_reason="
                            + root.path("choices").path(0).path("finish_reason").asText());
                }
                JsonNode usage = root.path("usage");
                return new Page(JSON.readTree(content), usage.path("prompt_tokens").asInt(),
                        usage.path("completion_tokens").asInt());
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                Thread.sleep(2000L * attempt * attempt);
            }
        }
        throw last;
    }

    // ------------------------------------------------------------------ pdf -> jpeg

    private static byte[] renderJpeg(PDFRenderer renderer, int index, int dpi) throws Exception {
        BufferedImage img = renderer.renderImageWithDPI(index, dpi, ImageType.RGB);
        int max = 2000;
        int w = img.getWidth();
        int h = img.getHeight();
        if (Math.max(w, h) > max) {
            double k = (double) max / Math.max(w, h);
            BufferedImage small = new BufferedImage((int) (w * k), (int) (h * k), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(img, 0, 0, small.getWidth(), small.getHeight(), null);
            g.dispose();
            img = small;
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.85f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ database

    private static Connection connect(String url, String user, String pass) throws Exception {
        return DriverManager.getConnection(url, user, pass);
    }

    /** Kind of the book: student's book or workbook, the track of grades 10-11, the quarters it covers. */
    private record Kind(String type, String track, int quarterFrom, int quarterTo, String part) {
        static Kind parse(Map<String, String> opt) {
            String t = opt.getOrDefault("type", "student").toLowerCase();
            String type = t.startsWith("w") ? "workbook" : "student_book";
            String tr = opt.getOrDefault("track", "").toLowerCase();
            String track = tr.isBlank() ? null
                    : (tr.startsWith("nat") || tr.startsWith("em") || tr.startsWith("ем") || tr.startsWith("ест"))
                            ? "natural_math"
                    : (tr.startsWith("soc") || tr.startsWith("hum") || tr.startsWith("og") || tr.startsWith("ог")
                            || tr.startsWith("общ")) ? "social_humanities" : null;
            if (!tr.isBlank() && track == null) {
                System.err.println("Unknown --track " + tr + " (use natural or social)");
                System.exit(2);
            }
            String q = opt.getOrDefault("quarters", "1-4");
            String[] parts = q.split("[-–]");
            int from = Integer.parseInt(parts[0].trim());
            int to = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : from;
            if (from < 1 || to > 4 || from > to) {
                System.err.println("--quarters must be like 2 or 1-4 (quarters 1..4)");
                System.exit(2);
            }
            return new Kind(type, track, from, to, opt.getOrDefault("part", ""));
        }
    }

    private static long upsertBook(Connection c, String title, String subject, Integer grade, int pages, Kind kind)
            throws Exception {
        // the same title and grade with another type is another book (student's book and workbook)
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM plangen.books WHERE title = ? "
                + "AND coalesce(grade, 0) = coalesce(?, 0) AND book_type = ?")) {
            ps.setString(1, title);
            if (grade == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setInt(2, grade);
            ps.setString(3, kind.type);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    try (PreparedStatement up = c.prepareStatement("UPDATE plangen.books SET pages = ?, subject = ?, "
                            + "track = ?, quarter_from = ?, quarter_to = ?, part_note = ? WHERE id = ?")) {
                        up.setInt(1, pages);
                        up.setString(2, subject);
                        up.setString(3, kind.track);
                        up.setInt(4, kind.quarterFrom);
                        up.setInt(5, kind.quarterTo);
                        up.setString(6, kind.part);
                        up.setLong(7, id);
                        up.executeUpdate();
                    }
                    return id;
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO plangen.books (title, subject, grade, pages, "
                + "book_type, track, quarter_from, quarter_to, part_note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "RETURNING id")) {
            ps.setString(1, title);
            ps.setString(2, subject);
            if (grade == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setInt(3, grade);
            ps.setInt(4, pages);
            ps.setString(5, kind.type);
            ps.setString(6, kind.track);
            ps.setInt(7, kind.quarterFrom);
            ps.setInt(8, kind.quarterTo);
            ps.setString(9, kind.part);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Set<Integer> existingPages(Connection c, long bookId) throws Exception {
        Set<Integer> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT pdf_page FROM plangen.book_pages WHERE book_id = ?")) {
            ps.setLong(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        }
        return out;
    }

    private static void savePage(Connection c, long bookId, int pdfPage, Page p) throws Exception {
        JsonNode n = p.node;
        String sql = "INSERT INTO plangen.book_pages (book_id, pdf_page, printed_page, unit, heading, kind, keywords, "
                + "summary, content_json, search_text, tokens_in, tokens_out) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (book_id, pdf_page) DO UPDATE SET printed_page = EXCLUDED.printed_page, "
                + "unit = EXCLUDED.unit, heading = EXCLUDED.heading, kind = EXCLUDED.kind, "
                + "keywords = EXCLUDED.keywords, summary = EXCLUDED.summary, content_json = EXCLUDED.content_json, "
                + "search_text = EXCLUDED.search_text, tokens_in = EXCLUDED.tokens_in, tokens_out = EXCLUDED.tokens_out";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, bookId);
            ps.setInt(2, pdfPage);
            if (n.path("printedPage").isNull()) ps.setNull(3, java.sql.Types.INTEGER);
            else ps.setInt(3, n.path("printedPage").asInt());
            ps.setString(4, clean(n.path("unit").asText("")));
            ps.setString(5, clean(n.path("heading").asText("")));
            ps.setString(6, clean(n.path("kind").asText("")));
            ps.setString(7, clean(n.path("keywords").asText("")));
            ps.setString(8, clean(n.path("summary").asText("")));
            ps.setString(9, clean(JSON.writeValueAsString(n)));
            ps.setString(10, clean(searchText(n)));
            ps.setInt(11, p.tokensIn);
            ps.setInt(12, p.tokensOut);
            ps.executeUpdate();
        }
    }

    /** Postgres text cannot hold NUL characters, which a model sometimes emits for odd glyphs. */
    private static String clean(String s) {
        // the raw character and its JSON escape (the content is stored as JSON text)
        return s == null ? "" : s.replace("\u0000", "").replace("\\u0000", "");
    }

    /** Everything that helps to find the page by a topic, flattened into one string. */
    private static String searchText(JsonNode n) {
        StringBuilder sb = new StringBuilder();
        for (String f : new String[] {"unit", "heading", "keywords", "summary"}) {
            sb.append(n.path(f).asText("")).append('\n');
        }
        sb.append(clip(n.path("theory").asText(""), 800)).append('\n');
        sb.append(clip(n.path("readingText").asText(""), 300)).append('\n');
        for (JsonNode e : n.path("exercises")) {
            sb.append(e.path("instruction").asText("")).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(key, args[++i]);
            } else {
                m.put(key, "true");
            }
        }
        return m;
    }

    private static Map<String, String> loadEnv(String file) throws Exception {
        Map<String, String> m = new HashMap<>(System.getenv());
        Path p = Path.of(file);
        if (Files.exists(p)) {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String l = line.strip();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq < 1) continue;
                m.putIfAbsent(l.substring(0, eq).strip(), l.substring(eq + 1).strip());
            }
        }
        return m;
    }

    private static String need(Map<String, String> env, String key) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            System.err.println("Missing " + key + " (set it in the environment or in the --env file)");
            System.exit(2);
        }
        return v;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    // ------------------------------------------------------------------ prompt and schema

    private static final String SYSTEM_PROMPT = """
            You turn ONE page of a school textbook into structured data for a teacher assistant that writes lesson plans.

            Be faithful. Copy instructions, exercise items, reading texts and word lists verbatim, in the language of the
            book. Never invent exercises, texts or pictures that are not on the page.

            Fields:
            - printedPage: the page number printed on the page (header or footer), or null if there is none.
            - unit: the unit or chapter title with its number, as printed ("" if none).
            - heading: the title of the lesson or section on this page.
            - kind: lesson, grammar, vocabulary, reading, exercises, review, contents, cover or other.
            - keywords: 5-10 lowercase keywords for searching: grammar points, vocabulary themes, skills (in English).
            - summary: one or two sentences in English about what the page teaches.
            - theory: grammar boxes, rules, word lists, key phrases and tips as plain text (everything that is not an
              exercise). For a table-of-contents page put the whole contents (units and their pages) here.
            - readingText: the full text of any reading passage, article, letter or dialogue, verbatim, or "".
            - pictures: pages can have many photos, so keep this short. One entry per picture that an exercise refers
              to or that carries content (vocabulary photos, comic strips, diagrams, maps, a scene to talk about). Give
              1-2 concrete sentences (at most 40 words): who or what is shown, actions, visible words, colours and
              positions when exercises refer to them. A set of similar photos (for example one photo per vocabulary
              item) is ONE entry that lists the items in order. In usedBy write the exercise number the picture belongs
              to, or "". Skip decorative photos, icons, logos and backgrounds.
              EXCEPTION: when an exercise can only be solved by looking at a picture (where things are, what a person
              does, what is wrong in a scene, which things are or are not shown), describe that picture in as much
              detail as the exercise needs, up to 120 words: every object, person or detail that the exercise names or
              could ask about, and where it is relative to the other things (on, under, next to, between, in front of,
              behind, left, right, top, bottom). List the items of a picture that is a set of small photos one by one.
            - exercises: EVERY numbered task or activity on the page, in order. number as printed ("1", "2a", "Task 3");
              instruction verbatim; content = all items, sentences, questions or options verbatim with their numbering,
              for example "1) She ___ (go) to school. 2) They ___ (not play) football."; kind of the task.
            - answerKey: solve each exercise carefully using the page. For closed tasks write the correct answer for every
              item, for example "1) went 2) did not play". If the page prints answers, use them. For open tasks (opinion,
              speaking, writing, discussion) write a short expected answer a student could give, and set answerType to
              "sample". When an exercise depends on a picture, look at the picture carefully, describe it in the pictures
              field as explained above, and then solve the exercise from what you see (answerType "exact"; if a few items
              are really not readable, give your best answer for them and say so inside the answerKey). Use
              "not_available" with an empty answerKey only for listening tasks without a script on the page, and for
              exercises that need information that is not on this page (for example "the photo on page 19").
              Otherwise answerType is "exact".

            If the page is a cover, a table of contents or blank, fill what applies and leave the arrays empty.
            """;

    private static final String PAGE_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["printedPage", "unit", "heading", "kind", "keywords", "summary", "theory", "readingText",
                           "pictures", "exercises"],
              "properties": {
                "printedPage": {"type": ["integer", "null"]},
                "unit": {"type": "string"},
                "heading": {"type": "string"},
                "kind": {"type": "string",
                         "enum": ["lesson", "grammar", "vocabulary", "reading", "exercises", "review", "contents",
                                  "cover", "other"]},
                "keywords": {"type": "string"},
                "summary": {"type": "string"},
                "theory": {"type": "string"},
                "readingText": {"type": "string"},
                "pictures": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["description", "usedBy"],
                    "properties": {"description": {"type": "string"}, "usedBy": {"type": "string"}}
                  }
                },
                "exercises": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["number", "kind", "instruction", "content", "answerType", "answerKey"],
                    "properties": {
                      "number": {"type": "string"},
                      "kind": {"type": "string",
                               "enum": ["fill_gaps", "multiple_choice", "matching", "true_false", "question_answer",
                                        "speaking", "writing", "listening", "vocabulary", "project", "other"]},
                      "instruction": {"type": "string"},
                      "content": {"type": "string"},
                      "answerType": {"type": "string", "enum": ["exact", "sample", "not_available"]},
                      "answerKey": {"type": "string"}
                    }
                  }
                }
              }
            }
            """;
}
