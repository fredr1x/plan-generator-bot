package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ydt.plangen.bot.Draft.BookRef;
import dev.ydt.plangen.domain.Book;
import dev.ydt.plangen.domain.BookPage;
import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.service.BookService;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The textbook step of /plan. Books are filtered by the grade, by the track of the class (grades 10-11) and
 * sorted so that the ones covering the lesson's quarter come first. A lesson may use a student's book and a
 * workbook, so after the pages of one book the teacher can add another. The methods that return boolean answer
 * "is the step finished?": true means the plan flow can go on, false means it waits for the teacher.
 */
@ApplicationScoped
public class BookFlow {

    private static final Logger LOG = Logger.getLogger(BookFlow.class);

    static final String PLAN_BOOK = "PLAN_BOOK";
    static final String PLAN_PAGES = "PLAN_PAGES";

    private static final int MAX_BOOKS = 3;

    @Inject
    Messenger bot;

    @Inject
    BookService books;

    @Inject
    ObjectMapper mapper;

    /**
     * Asks about the book, unless no book fits (then the step is silently done).
     * Returns true when a question was sent and the flow has to wait.
     */
    boolean offer(long uid, long chat, Draft d, List<SchoolClass> classes, LocalDate lessonDate) {
        List<Book> candidates = candidates(d, classes, lessonDate);
        if (candidates.isEmpty()) {
            d.bookAsked = true;
            return false;
        }
        if (candidates.size() == 1) {
            d.pendingBook = candidates.get(0).id;
            askPages(uid, chat, d, candidates.get(0));
            return true;
        }
        askBook(uid, chat, d, candidates, "📚 Взять задания из учебника? Выберите книгу:");
        return true;
    }

    /** pl:bk:&lt;id|none|more|done&gt;. */
    boolean onChoose(long uid, long chat, Draft d, List<SchoolClass> classes, LocalDate lessonDate, String arg) {
        switch (arg) {
            case "none", "done" -> {
                d.bookAsked = true;
                d.pendingBook = null;
                return true;
            }
            case "more" -> {
                List<Book> left = candidates(d, classes, lessonDate);
                if (left.isEmpty()) {
                    d.bookAsked = true;
                    return true;
                }
                askBook(uid, chat, d, left, "Какую ещё книгу добавить?");
                return false;
            }
            default -> {
                Book b = Book.findById(Long.parseLong(arg));
                if (b == null) {
                    d.bookAsked = true;
                    return true;
                }
                d.pendingBook = b.id;
                askPages(uid, chat, d, b);
                return false;
            }
        }
    }

    /** "Find by topic" button: the best page ranges of the pending book for the topic and objectives. */
    void onFind(long uid, long chat, Draft d) {
        if (d.pendingBook == null) {
            return;
        }
        var found = books.suggest(d.pendingBook, d.topic, d.objectives);
        LOG.infof("[book] search in book %d for topic \"%s\": %s", d.pendingBook, Kb.trunc(d.topic, 60),
                found.isEmpty() ? "nothing" : found.stream().map(s -> s.spec() + " " + s.label())
                        .collect(java.util.stream.Collectors.joining(" | ")));
        if (found.isEmpty()) {
            bot.send(chat, "Не нашёл подходящих страниц по теме. Напишите номера страниц, например 45 или 45-46.",
                    null);
            return;
        }
        Kb.Rows rows = Kb.rows();
        for (BookService.Suggestion s : found) {
            rows.add(btn(Kb.trunc("стр. " + s.spec() + (s.label().isBlank() ? "" : " · " + s.label()), 60),
                    "pl:pg:" + s.spec()));
        }
        rows.add(btn("Без учебника", "pl:bk:none"));
        save(uid, PLAN_PAGES, d);
        bot.send(chat, "Нашёл по теме «" + Kb.trunc(d.topic, 50) + "». Выберите страницы или напишите свои:",
                rows.build());
    }

    /**
     * Pages typed by the teacher or taken from a suggestion. True when they were accepted and there is nothing
     * more to ask; false when they were refused or when the teacher is asked about one more book.
     */
    boolean onPages(long uid, long chat, Draft d, List<SchoolClass> classes, LocalDate lessonDate, String spec) {
        if (d.pendingBook == null) {
            d.bookAsked = true;
            return true;
        }
        List<Integer> wanted = BookService.parseSpec(spec);
        if (wanted.isEmpty()) {
            bot.send(chat, "Не понял страницы. Пример: 45 или 45-46 или 45, 48.", null);
            return false;
        }
        if (wanted.size() > BookService.MAX_PAGES) {
            bot.send(chat, "Слишком много страниц, не больше " + BookService.MAX_PAGES + " за урок.", null);
            return false;
        }
        Book book = Book.findById(d.pendingBook);
        BookService.Found found = books.find(d.pendingBook, spec);
        if (found.pages().isEmpty()) {
            bot.send(chat, "В книге «" + (book == null ? "?" : book.title)
                    + "» нет таких страниц среди загруженных. Попробуйте другие номера.", null);
            return false;
        }
        String pages = BookService.canonical(found.pages().stream().map(BookPage::shownNumber).sorted().distinct()
                .toList());
        d.books.removeIf(r -> r.bookId.equals(d.pendingBook));
        d.books.add(new BookRef(d.pendingBook, pages));
        d.pendingBook = null;

        StringBuilder msg = new StringBuilder("✅ Беру из учебника")
                .append(book == null ? "" : " (" + book.typeLabel() + ")").append(":\n")
                .append(books.describe(found.pages()));
        if (!found.missing().isEmpty()) {
            msg.append("\n⚠️ Нет в загруженных: ").append(BookService.canonical(found.missing()));
        }
        LOG.infof("[book] user=%d book=%d pages=%s", uid, book == null ? -1 : book.id, pages);

        if (d.books.size() < MAX_BOOKS && !candidates(d, classes, lessonDate).isEmpty()) {
            save(uid, PLAN_BOOK, d);
            bot.send(chat, msg + "\n\nДобавить страницы из другой книги (например, рабочей тетради)?", Kb.rows()
                    .add(btn("➕ Другая книга", "pl:bk:more"))
                    .add(btn("Дальше →", "pl:bk:done"))
                    .build());
            return false;
        }
        d.bookAsked = true;
        bot.send(chat, msg.toString(), null);
        return true;
    }

    /** Back to "no book" and ask again (the confirmation screen button). */
    void reset(Draft d) {
        d.bookAsked = false;
        d.books = new ArrayList<>();
        d.pendingBook = null;
    }

    /** Lines for the confirmation screen, or "" when no book is used. */
    String summaryLine(Draft d) {
        StringBuilder sb = new StringBuilder();
        for (BookRef r : d.books) {
            Book b = Book.findById(r.bookId);
            sb.append("\nУчебник: ").append(b == null ? "?" : b.title + " (" + b.typeLabel() + ")")
                    .append(", стр. ").append(r.pages);
        }
        return sb.toString();
    }

    /** The prompt block for the model, or null when there are no books or none of the pages is loaded. */
    String contextFor(List<BookRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (BookRef r : refs) {
            Book b = r.bookId == null ? null : Book.findById(r.bookId);
            if (b == null || r.pages == null || r.pages.isBlank()) continue;
            List<BookPage> found = books.find(b.id, r.pages).pages();
            if (found.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(books.context(b, found));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Section and topic as the first chosen page names them, or null when there is no book or when they are
     * the same as what the teacher already has.
     */
    BookService.Heading proposal(Draft d) {
        if (d.books.isEmpty()) return null;
        BookRef r = d.books.get(0);
        if (r.bookId == null || r.pages == null || r.pages.isBlank()) return null;
        List<BookPage> pages = books.find(r.bookId, r.pages).pages();
        if (pages.isEmpty()) return null;
        BookService.Heading h = books.headingFor(pages.get(0));
        boolean sameSection = h.section().isBlank() || h.section().equalsIgnoreCase(nz(d.section));
        boolean sameTopic = h.topic().isBlank() || h.topic().equalsIgnoreCase(nz(d.topic));
        return sameSection && sameTopic ? null : h;
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }

    /** The answer keys of all exercises on the chosen pages, for AnswerKeys.inject. */
    List<BookService.Answer> answersFor(List<BookRef> refs) {
        List<BookService.Answer> out = new ArrayList<>();
        if (refs == null) return out;
        for (BookRef r : refs) {
            if (r.bookId == null || r.pages == null || r.pages.isBlank()) continue;
            out.addAll(books.answers(books.find(r.bookId, r.pages).pages()));
        }
        return out;
    }

    String toJson(List<BookRef> refs) {
        if (refs == null || refs.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(refs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    List<BookRef> fromJson(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(json, new TypeReference<List<BookRef>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    /** /books. */
    void list(long chat) {
        List<Book> all = Book.allByTitle();
        if (all.isEmpty()) {
            bot.send(chat, "Учебников пока нет. Их загружают отдельной утилитой (tools/book-ingest).", null);
            return;
        }
        StringBuilder sb = new StringBuilder("📚 Учебники:\n");
        for (Book b : all) {
            long loaded = BookPage.count("bookId = ?1", b.id);
            sb.append("• ").append(b.title).append(" — ").append(b.typeLabel());
            if (b.grade != null) sb.append(", ").append(b.grade).append(" класс");
            if (b.track != null) sb.append(", ").append(Book.trackLabel(b.track));
            sb.append(", ").append(b.quartersLabel());
            sb.append(" — загружено ").append(loaded).append(" из ").append(b.pages).append(" стр.\n");
        }
        bot.send(chat, sb.toString().stripTrailing(), null);
    }

    // ------------------------------------------------------------------ internals

    /** Books that fit the classes and are not used in this plan yet. */
    private List<Book> candidates(Draft d, List<SchoolClass> classes, LocalDate lessonDate) {
        int grade = classes.stream().mapToInt(c -> c.grade).min().orElse(0);
        Set<String> tracks = new HashSet<>();
        for (SchoolClass c : classes) {
            if (c.track != null) tracks.add(c.track);
        }
        Set<Long> used = new HashSet<>();
        d.books.forEach(r -> used.add(r.bookId));
        List<Book> out = new ArrayList<>();
        for (Book b : Book.candidates(grade, tracks, Book.quarterOf(lessonDate))) {
            if (!used.contains(b.id)) out.add(b);
        }
        return out;
    }

    private void askBook(long uid, long chat, Draft d, List<Book> list, String question) {
        Kb.Rows rows = Kb.rows();
        for (Book b : list) {
            rows.add(btn(Kb.trunc(b.title + " · " + b.typeLabel() + " · " + b.quartersLabel(), 58),
                    "pl:bk:" + b.id));
        }
        rows.add(btn(d.books.isEmpty() ? "Без учебника" : "Дальше →", d.books.isEmpty() ? "pl:bk:none" : "pl:bk:done"));
        save(uid, PLAN_BOOK, d);
        bot.send(chat, question, rows.build());
    }

    private void askPages(long uid, long chat, Draft d, Book b) {
        save(uid, PLAN_PAGES, d);
        bot.send(chat, "📚 «" + b.title + "» (" + b.typeLabel() + "). Напишите страницы урока (например 45 или "
                        + "45-46) или найдём их по теме:",
                Kb.rows()
                        .add(btn("🔎 Найти по теме", "pl:find"))
                        .add(btn(d.books.isEmpty() ? "Без учебника" : "Пропустить эту книгу",
                                d.books.isEmpty() ? "pl:bk:none" : "pl:bk:done"))
                        .build());
    }

    private void save(long uid, String state, Draft d) {
        try {
            ConvState.set(uid, state, mapper.writeValueAsString(d));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
