package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ydt.plangen.domain.Book;
import dev.ydt.plangen.domain.BookPage;
import dev.ydt.plangen.domain.ClassBook;
import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.domain.Teacher;
import dev.ydt.plangen.service.OkulykClient;
import dev.ydt.plangen.service.OkulykClient.CatalogBook;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The book catalog. A teacher pastes a link to a book on okulyk.kz, the bot reads the title, year and so on and
 * saves the book in the catalog. Linking a class to a book that has no loaded pages yet files a request; the
 * owner approves it (the loading costs money) and the loader utility (tools/book-ingest --watch) does the rest.
 * Books with loaded pages are shared by every teacher.
 */
@ApplicationScoped
public class CatalogFlow {

    private static final Logger LOG = Logger.getLogger(CatalogFlow.class);

    static final String CAT_URL = "CAT_URL";
    static final String CAT_NEW = "CAT_NEW";

    /** Rough price of loading one page, only used to tell the owner what an approval costs. */
    private static final double USD_PER_PAGE = 0.005;
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /** A book being added: the page data plus the answers the teacher gives step by step. */
    public static class NewBook {
        public String title;
        public String subject;
        public Integer grade;
        public String authors;
        public String publisher;
        public Integer year;
        public Integer pages;
        public String language;
        public String coverUrl;
        public String pdfUrl;
        public String sourceUrl;
        public boolean guessWorkbook;
        public String type;
        public boolean trackAsked;
        public String track;
        public Integer quarterFrom;
        public Integer quarterTo;
    }

    @Inject
    Messenger bot;

    @Inject
    OkulykClient okulyk;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "app.bot.admin-ids")
    Optional<List<Long>> adminIds;

    @ConfigProperty(name = "app.bot.allowed-ids")
    Optional<List<Long>> allowedIds;

    // ------------------------------------------------------------------ the list

    /** /books: the catalog with load status, buttons to add a book and to open the books of a class. */
    void list(long uid, long chat) {
        List<Book> all = Book.allByTitle();
        StringBuilder sb = new StringBuilder("📚 Каталог книг\n");
        if (all.isEmpty()) {
            sb.append("\nПока пусто. Добавьте книгу по ссылке с okulyk.kz.");
        }
        for (Book b : all) {
            sb.append("\n• ").append(b.title).append("\n  ").append(b.typeLabel());
            if (b.grade != null) sb.append(" · ").append(b.grade).append(" класс");
            if (b.pubYear != null) sb.append(" · ").append(b.pubYear);
            if (b.track != null) sb.append(" · ").append(Book.trackLabel(b.track));
            sb.append(" · ").append(b.quartersLabel()).append("\n  ").append(b.statusLabel());
            if (b.isLoaded()) {
                sb.append(" (").append(BookPage.count("bookId = ?1", b.id)).append(" из ").append(b.pages)
                        .append(" стр.)");
            }
        }
        Kb.Rows rows = Kb.rows().add(btn("➕ Добавить книгу по ссылке", "cat:add"));
        for (SchoolClass c : SchoolClass.forTeacher(uid)) {
            rows.add(btn("📚 Книги для " + c.label(), "cat:cls:" + c.id));
        }
        bot.send(chat, sb.toString(), rows.build());
    }

    // ------------------------------------------------------------------ adding a book

    public void onText(long uid, long chat, ConvState st, String text) {
        if (!CAT_URL.equals(st.state)) {
            bot.send(chat, "Нажмите одну из кнопок выше или /cancel.", null);
            return;
        }
        Matcher m = URL.matcher(text);
        if (!m.find()) {
            bot.send(chat, "Пришлите ссылку на страницу книги на okulyk.kz (или /cancel).", null);
            return;
        }
        String url = m.group().replaceAll("[)\\],.;]+$", "");
        if (!OkulykClient.isAllowed(url)) {
            bot.send(chat, "Пока принимаю только ссылки на okulyk.kz (https://okulyk.kz/...). Другие книги можно "
                    + "добавить через владельца бота.", null);
            return;
        }
        Book existing = Book.find("sourceUrl", url).firstResult();
        if (existing != null) {
            ConvState.clear(uid);
            bot.send(chat, "Эта книга уже в каталоге: " + existing.title + " (" + existing.statusLabel() + ").",
                    classButtons(uid, existing));
            return;
        }
        CatalogBook cb;
        try {
            Optional<CatalogBook> found = okulyk.fetch(url);
            if (found.isEmpty()) {
                bot.send(chat, "Не получилось прочитать страницу книги. Проверьте ссылку или попробуйте позже.", null);
                return;
            }
            cb = found.get();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warnf("okulyk fetch failed for %s: %s", url, e.getMessage());
            bot.send(chat, "Сайт okulyk.kz сейчас не отвечает. Попробуйте позже.", null);
            return;
        }
        NewBook n = new NewBook();
        n.title = cb.title();
        n.subject = cb.subject();
        n.grade = cb.grade();
        n.authors = cb.authors();
        n.publisher = cb.publisher();
        n.year = cb.year();
        n.pages = cb.pages();
        n.language = cb.language();
        n.coverUrl = cb.coverUrl();
        n.pdfUrl = cb.pdfUrl();
        n.sourceUrl = cb.sourceUrl();
        n.guessWorkbook = OkulykClient.looksLikeWorkbook(cb.title());
        LOG.infof("[catalog] user=%d read \"%s\" (%s, grade %s, year %s, %s pages)", uid, n.title, n.publisher,
                n.grade, n.year, n.pages);
        bot.send(chat, card(n), null);
        next(uid, chat, n);
    }

    private void next(long uid, long chat, NewBook n) {
        if (n.type == null) {
            save(uid, n);
            bot.send(chat, "Что это за книга?", Kb.rows()
                    .add(btn((n.guessWorkbook ? "" : "✅ ") + "Student's book (учебник)", "cat:type:student"))
                    .add(btn((n.guessWorkbook ? "✅ " : "") + "Workbook (рабочая тетрадь)", "cat:type:workbook"))
                    .add(btn("✖ Отмена", "cat:cancel"))
                    .build());
        } else if (n.grade != null && n.grade >= 10 && !n.trackAsked) {
            save(uid, n);
            bot.send(chat, "Для какого направления эта книга?", Kb.rows()
                    .add(btn("Естественно-математическое", "cat:track:nm"))
                    .add(btn("Общественно-гуманитарное", "cat:track:sh"))
                    .add(btn("Для обоих направлений", "cat:track:all"))
                    .build());
        } else if (n.quarterFrom == null) {
            save(uid, n);
            bot.send(chat, "На какие четверти рассчитана книга?", Kb.rows()
                    .add(btn("Весь год (1–4)", "cat:q:1-4"))
                    .add(btn("1–2", "cat:q:1-2"), btn("3–4", "cat:q:3-4"))
                    .add(btn("1", "cat:q:1"), btn("2", "cat:q:2"), btn("3", "cat:q:3"), btn("4", "cat:q:4"))
                    .build());
        } else {
            create(uid, chat, n);
        }
    }

    private void create(long uid, long chat, NewBook n) {
        Book b = new Book();
        b.title = n.title;
        b.subject = n.subject;
        b.grade = n.grade;
        b.pages = n.pages == null ? 0 : n.pages;
        b.authors = n.authors;
        b.publisher = n.publisher;
        b.pubYear = n.year;
        b.language = n.language;
        b.sourceUrl = n.sourceUrl;
        b.coverUrl = n.coverUrl;
        b.pdfUrl = n.pdfUrl;
        b.bookType = n.type;
        b.track = n.track;
        b.quarterFrom = n.quarterFrom;
        b.quarterTo = n.quarterTo;
        b.status = Book.STATUS_CATALOG;
        b.persist();
        ConvState.clear(uid);
        LOG.infof("[catalog] user=%d added book #%d \"%s\" (%s)", uid, b.id, b.title, b.bookType);
        bot.send(chat, "✅ Добавлено в каталог: " + b.title + " (" + b.typeLabel() + ", " + b.quartersLabel()
                + ").\nТеперь привяжите её к своим классам: тогда я запрошу загрузку страниц.", classButtons(uid, b));
    }

    // ------------------------------------------------------------------ callbacks

    public void onCallback(long uid, long chat, long msgId, String cbId, String[] p) {
        switch (p[1]) {
            case "add" -> {
                bot.answer(cbId, null);
                ConvState.set(uid, CAT_URL, null);
                bot.send(chat, "Пришлите ссылку на страницу книги на okulyk.kz, например "
                        + "https://okulyk.kz/anglijskij-jazyk/165/ (отмена: /cancel).", null);
            }
            case "type", "track", "q" -> {
                bot.answer(cbId, null);
                NewBook n = load(uid);
                if (n == null) {
                    bot.send(chat, "Сессия устарела, начните заново: /books", null);
                    return;
                }
                answerStep(p, n);
                next(uid, chat, n);
            }
            case "cancel" -> {
                bot.answer(cbId, "Отменено");
                ConvState.clear(uid);
                bot.edit(chat, msgId, "Отменено.", null);
            }
            case "cls" -> {
                bot.answer(cbId, null);
                SchoolClass c = ownedClass(uid, Long.parseLong(p[2]));
                if (c == null) {
                    bot.send(chat, "Класс не найден.", null);
                    return;
                }
                bot.send(chat, classScreenText(c), classScreen(c));
            }
            case "tog" -> toggle(uid, chat, msgId, cbId, Long.parseLong(p[2]), Long.parseLong(p[3]));
            case "ok" -> decide(uid, chat, msgId, cbId, Long.parseLong(p[2]), true);
            case "no" -> decide(uid, chat, msgId, cbId, Long.parseLong(p[2]), false);
            case "close" -> {
                bot.answer(cbId, null);
                bot.edit(chat, msgId, "Готово. Книги класса учитываются в /plan.", null);
            }
            default -> bot.answer(cbId, null);
        }
    }

    private void answerStep(String[] p, NewBook n) {
        switch (p[1]) {
            case "type" -> n.type = "workbook".equals(p[2]) ? Book.WORKBOOK : Book.STUDENT_BOOK;
            case "track" -> {
                n.trackAsked = true;
                n.track = switch (p[2]) {
                    case "nm" -> Book.NATURAL_MATH;
                    case "sh" -> Book.SOCIAL_HUMANITIES;
                    default -> null;
                };
            }
            default -> {
                String[] r = p[2].split("-");
                n.quarterFrom = Integer.parseInt(r[0]);
                n.quarterTo = r.length > 1 ? Integer.parseInt(r[1]) : n.quarterFrom;
            }
        }
    }

    // ------------------------------------------------------------------ class <-> books

    private void toggle(long uid, long chat, long msgId, String cbId, long classId, long bookId) {
        SchoolClass c = ownedClass(uid, classId);
        Book b = Book.findById(bookId);
        if (c == null || b == null) {
            bot.answer(cbId, "Не найдено");
            return;
        }
        ClassBook link = ClassBook.find(classId, bookId);
        String note = null;
        if (link != null) {
            link.delete();
            note = "Отвязано";
        } else {
            ClassBook nl = new ClassBook();
            nl.classId = classId;
            nl.bookId = bookId;
            nl.persist();
            LOG.infof("[catalog] user=%d linked class %s to book #%d (%s)", uid, c.label(), b.id, b.status);
            if (Book.STATUS_CATALOG.equals(b.status) || Book.STATUS_FAILED.equals(b.status)) {
                requestLoading(uid, b);
                note = "Привязано. Запросил загрузку страниц, жду одобрения";
            } else if (b.isLoaded()) {
                note = "Привязано, страницы уже загружены";
            } else {
                note = "Привязано (" + b.statusLabel() + ")";
            }
        }
        bot.answer(cbId, note);
        bot.edit(chat, msgId, classScreenText(c), classScreen(c));
    }

    private String classScreenText(SchoolClass c) {
        return "📚 Книги для " + c.label() + (c.track == null ? "" : " (" + Book.trackLabel(c.track) + ")")
                + "\nОтметьте, по каким книгам вы ведёте этот класс. В /plan я сначала предложу их.";
    }

    private dev.ydt.plangen.telegram.Dto.InlineKeyboard classScreen(SchoolClass c) {
        Kb.Rows rows = Kb.rows();
        List<Book> fitting = Book.fittingClass(c.grade, c.track);
        for (Book b : fitting) {
            boolean linked = ClassBook.find(c.id, b.id) != null;
            rows.add(btn(Kb.trunc((linked ? "✅ " : "▫️ ") + b.title + " · " + b.typeLabel()
                    + (b.pubYear == null ? "" : " · " + b.pubYear) + (b.isLoaded() ? "" : " ⏳"), 60),
                    "cat:tog:" + c.id + ":" + b.id));
        }
        if (fitting.isEmpty()) {
            rows.add(btn("Для этого класса книг нет, добавить", "cat:add"));
        }
        rows.add(btn("Готово", "cat:close"));
        return rows.build();
    }

    // ------------------------------------------------------------------ loading requests

    /** Marks the book as requested and asks the owner to approve the loading (it costs money). */
    private void requestLoading(long uid, Book b) {
        b.status = Book.STATUS_REQUESTED;
        b.requestedBy = uid;
        b.statusNote = null;
        Teacher t = Teacher.findById(uid);
        double cost = Math.max(b.pages, 1) * USD_PER_PAGE;
        String text = "📚 Запрос на загрузку учебника\n" + b.title + "\n" + b.typeLabel()
                + (b.grade == null ? "" : ", " + b.grade + " класс") + (b.pubYear == null ? "" : ", " + b.pubYear)
                + ", " + b.pages + " стр.\nЗапросил: " + (t == null || t.fullName == null ? uid : t.fullName)
                + "\nПримерная стоимость загрузки: $" + String.format(Locale.ROOT, "%.2f", cost)
                + " (≈ $" + USD_PER_PAGE + " за страницу). Загрузит утилита book-ingest в режиме --watch.";
        for (Long admin : admins()) {
            bot.send(admin, text, Kb.row(btn("✅ Одобрить", "cat:ok:" + b.id), btn("✖ Отклонить", "cat:no:" + b.id)));
        }
        LOG.infof("[catalog] loading of book #%d requested by %d, asked %d admin(s)", b.id, uid, admins().size());
    }

    private void decide(long uid, long chat, long msgId, String cbId, long bookId, boolean approve) {
        if (!admins().contains(uid)) {
            bot.answer(cbId, "Это решает владелец бота");
            return;
        }
        Book b = Book.findById(bookId);
        if (b == null) {
            bot.answer(cbId, "Книга не найдена");
            return;
        }
        if (!Book.STATUS_REQUESTED.equals(b.status)) {
            bot.answer(cbId, "Уже обработано: " + b.statusLabel());
            return;
        }
        b.status = approve ? Book.STATUS_APPROVED : Book.STATUS_CATALOG;
        b.statusNote = approve ? null : "rejected";
        bot.answer(cbId, approve ? "Одобрено" : "Отклонено");
        bot.edit(chat, msgId, (approve ? "✅ Одобрено: " : "✖ Отклонено: ") + b.title, null);
        LOG.infof("[catalog] book #%d %s by %d", b.id, approve ? "approved" : "rejected", uid);
        if (b.requestedBy != null && b.requestedBy != chat) {
            bot.send(b.requestedBy, approve
                    ? "✅ Загрузка книги «" + b.title + "» одобрена. Страницы появятся, как только загрузчик их "
                            + "обработает; я напишу."
                    : "Загрузка книги «" + b.title + "» не одобрена. Она остаётся в каталоге без страниц.", null);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Buttons that open the book screen of each of the teacher's classes of the book's grade. */
    private dev.ydt.plangen.telegram.Dto.InlineKeyboard classButtons(long uid, Book b) {
        Kb.Rows rows = Kb.rows();
        for (SchoolClass c : SchoolClass.forTeacher(uid)) {
            if (b.grade == null || b.grade == c.grade) {
                rows.add(btn("📚 Привязать к " + c.label(), "cat:cls:" + c.id));
            }
        }
        rows.add(btn("Позже", "cat:close"));
        return rows.build();
    }

    private String card(NewBook n) {
        StringBuilder sb = new StringBuilder("📖 ").append(n.title);
        line(sb, "Авторы", n.authors);
        line(sb, "Издательство", n.publisher);
        line(sb, "Год", n.year == null ? null : String.valueOf(n.year));
        line(sb, "Класс", n.grade == null ? null : String.valueOf(n.grade));
        line(sb, "Предмет", n.subject);
        line(sb, "Язык", n.language);
        line(sb, "Страниц", n.pages == null ? null : String.valueOf(n.pages));
        return sb.toString();
    }

    private static void line(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) sb.append('\n').append(name).append(": ").append(value);
    }

    private SchoolClass ownedClass(long uid, long id) {
        SchoolClass c = SchoolClass.findById(id);
        return c != null && c.teacherId == uid ? c : null;
    }

    /** Who may approve loadings: ADMIN_IDS, or the first allowed id when ADMIN_IDS is not set. */
    private List<Long> admins() {
        List<Long> explicit = adminIds.orElse(List.of());
        if (!explicit.isEmpty()) return explicit;
        List<Long> allowed = allowedIds.orElse(List.of());
        return allowed.isEmpty() ? new ArrayList<>() : List.of(allowed.get(0));
    }

    private void save(long uid, NewBook n) {
        try {
            ConvState.set(uid, CAT_NEW, mapper.writeValueAsString(n));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private NewBook load(long uid) {
        ConvState st = ConvState.get(uid);
        if (st == null || !CAT_NEW.equals(st.state) || st.payload == null) return null;
        try {
            return mapper.readValue(st.payload, NewBook.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
