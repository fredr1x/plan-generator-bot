package dev.ydt.bookingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
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

    /** Everything the loader needs from the environment and the command line. */
    private record Cfg(String dbUrl, String dbUser, String dbPass, String apiKey, String model, double priceIn,
                       double priceOut, int dpi, int parallel, String botToken) {
        static Cfg from(Map<String, String> env, Map<String, String> opt, boolean needKey) {
            return new Cfg(need(env, "DB_URL"), need(env, "DB_USER"), need(env, "DB_PASSWORD"),
                    needKey ? need(env, "OPENAI_API_KEY") : env.getOrDefault("OPENAI_API_KEY", ""),
                    opt.getOrDefault("model", env.getOrDefault("OPENAI_MODEL", "gpt-5-mini")),
                    Double.parseDouble(env.getOrDefault("PRICE_INPUT_PER_M", "0.25")),
                    Double.parseDouble(env.getOrDefault("PRICE_OUTPUT_PER_M", "2.00")),
                    Integer.parseInt(opt.getOrDefault("dpi", "130")),
                    Integer.parseInt(opt.getOrDefault("parallel", "3")),
                    env.getOrDefault("BOT_TOKEN", ""));
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        boolean catalogMode = opt.containsKey("watch") || opt.containsKey("once") || opt.containsKey("book-id");
        if (!catalogMode && (!opt.containsKey("pdf") || !opt.containsKey("title"))) {
            System.out.println("""
                    Manual:  java -jar book-ingest.jar --pdf <file.pdf> --title "<book title>" [--subject "..."] [--grade 7]
                             [--type student|workbook] [--track natural|social] [--quarters 1-4] [--part "first half"]
                             [--from 1] [--to 200] [--pages 22,24] [--dpi 130] [--parallel 3] [--model gpt-5-mini]
                             [--env .env] [--force]
                    Catalog: java -jar book-ingest.jar --watch [--poll 60]     (loads the books the owner approved, forever)
                             java -jar book-ingest.jar --once                  (loads the approved books and exits)
                             java -jar book-ingest.jar --book-id 5 [--dry-run] (loads one catalog book from its PDF link)
                    Needs DB_URL, DB_USER, DB_PASSWORD and OPENAI_API_KEY (from the environment or the --env file);
                    BOT_TOKEN is optional: with it the loader tells the teacher when the book is ready.
                    --dry-run downloads the PDF and counts its pages, without any paid call.""");
            System.exit(2);
        }
        Map<String, String> env = loadEnv(opt.getOrDefault("env", ".env"));
        if (catalogMode) {
            catalog(opt, Cfg.from(env, opt, !opt.containsKey("dry-run")));
        } else {
            manual(opt, Cfg.from(env, opt, true));
        }
    }

    private static void manual(Map<String, String> opt, Cfg cfg) throws Exception {
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
            try (Connection c = connect(cfg)) {
                bookId = upsertBook(c, title, subject, grade, total, kind);
                // the same PDF under another title or entry is not read again: its pages are copied
                reuseTwin(c, bookId, sha256(Path.of(opt.get("pdf"))), !force && !opt.containsKey("pages"));
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
            System.out.printf("Model %s, %d dpi, %d in parallel%n", cfg.model, cfg.dpi, cfg.parallel);
            if (todo.isEmpty()) {
                System.out.println("Nothing to do.");
                return;
            }
            Summary s = ingest(pdf, bookId, title, subject, grade, todo, cfg);
            if (!s.failed.isEmpty()) {
                System.out.println("Failed pages (run the same command again, loaded pages are skipped): " + s.failed);
            }
        }
    }

    // ------------------------------------------------------------------ the page loop

    private record Summary(int ok, List<Integer> failed, long tokIn, long tokOut) {}

    /** Renders the pages, reads each with the model and saves it; returns what happened. */
    private static Summary ingest(PDDocument pdf, long bookId, String title, String subject, Integer grade,
                                  List<Integer> todo, Cfg cfg) throws Exception {
        PDFRenderer renderer = new PDFRenderer(pdf);
        ExecutorService pool = Executors.newFixedThreadPool(cfg.parallel);
        AtomicInteger finished = new AtomicInteger();
        AtomicLong tokIn = new AtomicLong();
        AtomicLong tokOut = new AtomicLong();
        List<Integer> failed = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        long started = System.currentTimeMillis();

        for (int page : todo) {
            // rendering touches the PDF document, so it is done on the calling thread, one page at a time
            byte[] jpeg = renderJpeg(renderer, page - 1, cfg.dpi);
            futures.add(pool.submit(() -> {
                try {
                    Page result = readPage(cfg.apiKey, cfg.model, jpeg, subject, grade, title);
                    try (Connection c = connect(cfg)) {
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
        failed.sort(Integer::compare);

        double cost = tokIn.get() * cfg.priceIn / 1_000_000.0 + tokOut.get() * cfg.priceOut / 1_000_000.0;
        System.out.printf("%nDone in %d s: %d pages ok, %d failed. Tokens in=%d out=%d, estimated cost $%.3f%n",
                (System.currentTimeMillis() - started) / 1000, finished.get(), failed.size(), tokIn.get(),
                tokOut.get(), cost);
        return new Summary(finished.get(), failed, tokIn.get(), tokOut.get());
    }

    // ------------------------------------------------------------------ catalog mode

    /** A catalog row the loader works on. */
    private record CatalogRow(long id, String title, String subject, Integer grade, String pdfUrl, String status,
                              Long requestedBy) {}

    /**
     * --watch: every poll interval, load the books the owner approved (status approved). --once does one round.
     * --book-id N loads that book at once (the owner's explicit command, no approval needed).
     * The loader runs on a computer of the owner: it is the only place that downloads PDFs and pays for pages.
     */
    private static void catalog(Map<String, String> opt, Cfg cfg) throws Exception {
        boolean dry = opt.containsKey("dry-run");
        if (opt.containsKey("book-id")) {
            processBook(Long.parseLong(opt.get("book-id")), cfg, dry, true);
            return;
        }
        long pollMs = Long.parseLong(opt.getOrDefault("poll", "60")) * 1000;
        System.out.println(opt.containsKey("watch") ? "Watching for approved books every " + pollMs / 1000 + " s ..."
                : "Looking for approved books ...");
        while (true) {
            List<Long> ids = approvedBookIds(cfg);
            for (long id : ids) {
                try {
                    processBook(id, cfg, dry, false);
                } catch (Exception e) {
                    System.out.println("[FAILED] book " + id + ": " + e.getMessage());
                }
            }
            if (!opt.containsKey("watch")) {
                if (ids.isEmpty()) System.out.println("No approved books.");
                return;
            }
            Thread.sleep(pollMs);
        }
    }

    private static void processBook(long id, Cfg cfg, boolean dry, boolean manual) throws Exception {
        CatalogRow row;
        try (Connection c = connect(cfg)) {
            row = readRow(c, id);
        }
        if (row == null) {
            System.out.println("No book with id " + id);
            return;
        }
        if (!manual && !"approved".equals(row.status)) {
            return;
        }
        System.out.printf("Book #%d \"%s\" (%s)%n", row.id, row.title, row.status);
        if (row.pdfUrl == null || row.pdfUrl.isBlank()) {
            finish(cfg, row, "failed", "the catalog has no PDF link for this book", null);
            return;
        }
        if (!dry) {
            setStatus(cfg, row.id, "loading", null);
        }
        Path file = null;
        try {
            file = download(row.pdfUrl, 300L * 1024 * 1024);
            System.out.printf("Downloaded %d KB from %s%n", Files.size(file) / 1024, row.pdfUrl);
            String sha = sha256(file);
            long twin;
            try (Connection c = connect(cfg)) {
                twin = sameLoadedBook(c, row.id, sha);
            }
            if (twin > 0) {
                System.out.printf("This PDF is identical to the PDF of book #%d, which has pages already: they are "
                        + "copied, the model is called only for pages that are still missing.%n", twin);
            }
            try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
                int total = pdf.getNumberOfPages();
                if (dry) {
                    System.out.printf("Dry run: the PDF has %d pages. No model was called, nothing was saved.%n", total);
                    return;
                }
                Set<Integer> done;
                try (Connection c = connect(cfg)) {
                    reuseTwin(c, row.id, sha, true);
                    done = existingPages(c, row.id);
                    setPages(c, row.id, total);
                }
                List<Integer> todo = new ArrayList<>();
                for (int p = 1; p <= total; p++) {
                    if (!done.contains(p)) todo.add(p);
                }
                System.out.printf("%d pages, %d to load, model %s%n", total, todo.size(), cfg.model);
                Summary s = todo.isEmpty() ? new Summary(0, List.of(), 0, 0)
                        : ingest(pdf, row.id, row.title, row.subject == null ? "" : row.subject, row.grade, todo, cfg);
                if (s.failed.isEmpty()) {
                    finish(cfg, row, "loaded", null, total);
                } else {
                    finish(cfg, row, "failed", s.failed.size() + " pages failed: " + s.failed
                            + " (the owner can run --book-id " + row.id + " again, loaded pages are skipped)", total);
                }
            }
        } catch (Exception e) {
            finish(cfg, row, "failed", e.getClass().getSimpleName() + ": " + clip(e.getMessage(), 200), null);
            throw e;
        } finally {
            if (file != null) Files.deleteIfExists(file);
        }
    }

    /** Saves the final status and tells the teacher who asked for the book. */
    private static void finish(Cfg cfg, CatalogRow row, String status, String note, Integer total) throws Exception {
        setStatus(cfg, row.id, status, note);
        System.out.printf("Book #%d is now %s%s%n", row.id, status, note == null ? "" : " (" + note + ")");
        String text = "loaded".equals(status)
                ? "✅ Учебник «" + row.title + "» загружен (" + total + " стр.). Теперь он доступен в /plan."
                : "❌ Не удалось загрузить учебник «" + row.title + "»: " + (note == null ? "ошибка" : note);
        if (row.requestedBy != null) {
            notifyTelegram(cfg, row.requestedBy, text);
        }
    }

    private static void notifyTelegram(Cfg cfg, long chatId, String text) {
        if (cfg.botToken.isBlank()) return;
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("chat_id", chatId);
            body.put("text", text);
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + cfg.botToken
                            + "/sendMessage"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.out.println("Could not notify the teacher: " + e.getMessage());
        }
    }

    private static final HttpClient DOWNLOAD = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Downloads a PDF into a temp file: only https links of okulyk.kz / okulyk.com, at most maxBytes. */
    private static Path download(String url, long maxBytes) throws Exception {
        URI u = URI.create(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        boolean trusted = host.equals("okulyk.kz") || host.endsWith(".okulyk.kz") || host.equals("okulyk.com")
                || host.endsWith(".okulyk.com");
        if (!"https".equals(u.getScheme()) || !trusted) {
            throw new IllegalArgumentException("only https links of okulyk.kz / okulyk.com are downloaded: " + url);
        }
        HttpRequest req = HttpRequest.newBuilder(u)
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Mozilla/5.0 (plan-generator-bot book loader)")
                .GET()
                .build();
        HttpResponse<InputStream> res = DOWNLOAD.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("download answered HTTP " + res.statusCode());
        }
        long declared = res.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maxBytes) {
            throw new IllegalStateException("the PDF is too large: " + declared / 1024 / 1024 + " MB");
        }
        Path tmp = Files.createTempFile("book-", ".pdf");
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            boolean first = true;
            while ((n = in.read(buf)) > 0) {
                if (first) {
                    first = false;
                    if (n < 5 || buf[0] != '%' || buf[1] != 'P' || buf[2] != 'D' || buf[3] != 'F') {
                        throw new IllegalStateException("the link did not return a PDF file");
                    }
                }
                total += n;
                if (total > maxBytes) throw new IllegalStateException("the PDF is larger than the limit");
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return tmp;
    }

    private static List<Long> approvedBookIds(Cfg cfg) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection c = connect(cfg);
             PreparedStatement ps = c.prepareStatement("SELECT id FROM plangen.books WHERE status = 'approved' "
                     + "AND pdf_url IS NOT NULL ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getLong(1));
        }
        return ids;
    }

    private static CatalogRow readRow(Connection c, long id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, title, subject, grade, pdf_url, status, "
                + "requested_by FROM plangen.books WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int g = rs.getInt(4);
                boolean noGrade = rs.wasNull();
                long rb = rs.getLong(7);
                boolean noRb = rs.wasNull();
                return new CatalogRow(rs.getLong(1), rs.getString(2), rs.getString(3), noGrade ? null : g,
                        rs.getString(5), rs.getString(6), noRb ? null : rb);
            }
        }
    }

    private static void setStatus(Cfg cfg, long id, String status, String note) throws Exception {
        try (Connection c = connect(cfg);
             PreparedStatement ps = c.prepareStatement("UPDATE plangen.books SET status = ?, status_note = ? "
                     + "WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, note);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    private static void setPages(Connection c, long id, int pages) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE plangen.books SET pages = ? WHERE id = ?")) {
            ps.setInt(1, pages);
            ps.setLong(2, id);
            ps.executeUpdate();
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

    private static Connection connect(Cfg cfg) throws Exception {
        return DriverManager.getConnection(cfg.dbUrl, cfg.dbUser, cfg.dbPass);
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

    // ------------------------------------------------------------------ the same PDF is read once

    private static String sha256(Path file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    /** Another book that was read from the same PDF and has the most pages, or -1. */
    private static long sameLoadedBook(Connection c, long bookId, String sha) throws Exception {
        try {
            return findTwin(c, bookId, sha);
        } catch (java.sql.SQLException e) {
            // the column comes with migration V12 of the bot: until it is deployed there is no duplicate check
            System.out.println("Duplicate check skipped (" + clip(e.getMessage(), 120) + "). Deploy the bot first "
                    + "(migration V12) to enable it.");
            return -1;
        }
    }

    private static long findTwin(Connection c, long bookId, String sha) throws Exception {
        String sql = "SELECT b.id FROM plangen.books b WHERE b.pdf_sha256 = ? AND b.id <> ? "
                + "AND EXISTS (SELECT 1 FROM plangen.book_pages p WHERE p.book_id = b.id) "
                + "ORDER BY (SELECT count(*) FROM plangen.book_pages p WHERE p.book_id = b.id) DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sha);
            ps.setLong(2, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    /**
     * Remembers the hash of the PDF of this book and, when asked, copies the pages of another book that was read
     * from the very same PDF (no model call, no cost). Pages that already exist are kept.
     */
    private static void reuseTwin(Connection c, long bookId, String sha, boolean copy) throws Exception {
        long twin = copy ? sameLoadedBook(c, bookId, sha) : -1;
        try (PreparedStatement ps = c.prepareStatement("UPDATE plangen.books SET pdf_sha256 = ? WHERE id = ?")) {
            ps.setString(1, sha);
            ps.setLong(2, bookId);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            System.out.println("The hash of the PDF was not saved (" + clip(e.getMessage(), 120) + "). Deploy the bot "
                    + "first (migration V12).");
            return;
        }
        if (twin > 0) {
            String sql = "INSERT INTO plangen.book_pages (book_id, pdf_page, printed_page, unit, heading, kind, "
                    + "keywords, summary, content_json, search_text, tokens_in, tokens_out) "
                    + "SELECT ?, pdf_page, printed_page, unit, heading, kind, keywords, summary, content_json, "
                    + "search_text, 0, 0 FROM plangen.book_pages WHERE book_id = ? "
                    + "ON CONFLICT (book_id, pdf_page) DO NOTHING";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, bookId);
                ps.setLong(2, twin);
                int n = ps.executeUpdate();
                System.out.printf("Same PDF as book #%d: copied %d pages, no model call.%n", twin, n);
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
