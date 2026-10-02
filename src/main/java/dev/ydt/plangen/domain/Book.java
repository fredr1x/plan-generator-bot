package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** A textbook loaded with tools/book-ingest. Shared by all teachers of this deployment. */
@Entity
@Table(name = "books")
public class Book extends PanacheEntityBase {

    public static final String STUDENT_BOOK = "student_book";
    public static final String WORKBOOK = "workbook";

    public static final String NATURAL_MATH = "natural_math";
    public static final String SOCIAL_HUMANITIES = "social_humanities";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String title;

    public String subject;

    public Integer grade;

    public int pages;

    @Column(name = "book_type")
    public String bookType = STUDENT_BOOK;

    /** Null means the book fits every track. */
    public String track;

    @Column(name = "quarter_from")
    public int quarterFrom = 1;

    @Column(name = "quarter_to")
    public int quarterTo = 4;

    @Column(name = "part_note")
    public String partNote;

    public String authors;

    public String publisher;

    @Column(name = "pub_year")
    public Integer pubYear;

    public String language;

    @Column(name = "source_url")
    public String sourceUrl;

    @Column(name = "cover_url")
    public String coverUrl;

    @Column(name = "pdf_url")
    public String pdfUrl;

    /** catalog | requested | approved | loading | loaded | failed. */
    public String status = STATUS_LOADED;

    @Column(name = "requested_by")
    public Long requestedBy;

    @Column(name = "status_note")
    public String statusNote;

    public static final String STATUS_CATALOG = "catalog";
    public static final String STATUS_REQUESTED = "requested";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_LOADING = "loading";
    public static final String STATUS_LOADED = "loaded";
    public static final String STATUS_FAILED = "failed";

    public boolean isLoaded() {
        return STATUS_LOADED.equals(status);
    }

    /** Icon and short words for the book list. */
    public String statusLabel() {
        return switch (status == null ? STATUS_LOADED : status) {
            case STATUS_CATALOG -> "📋 в каталоге, страницы не загружены";
            case STATUS_REQUESTED -> "⏳ ждёт одобрения загрузки";
            case STATUS_APPROVED -> "🟡 одобрена, ждёт загрузки";
            case STATUS_LOADING -> "🟡 загружается";
            case STATUS_FAILED -> "❌ загрузка не удалась";
            default -> "✅ загружена";
        };
    }

    @Column(name = "created_at")
    public Instant createdAt = Instant.now();

    public String typeLabel() {
        return WORKBOOK.equals(bookType) ? "Workbook" : "Student's book";
    }

    /** "Q1–4", "Q2" or "Q2 (first half)". */
    public String quartersLabel() {
        String q = quarterFrom == quarterTo ? "Q" + quarterFrom : "Q" + quarterFrom + "–" + quarterTo;
        return partNote == null || partNote.isBlank() ? q : q + " (" + partNote + ")";
    }

    public static String trackLabel(String track) {
        if (NATURAL_MATH.equals(track)) return "естественно-математическое";
        if (SOCIAL_HUMANITIES.equals(track)) return "общественно-гуманитарное";
        return "";
    }

    /** School quarter of a date, by month (the exact dates change every year, a month is accurate enough). */
    public static int quarterOf(LocalDate d) {
        return switch (d.getMonthValue()) {
            case 9, 10, 8 -> 1;
            case 11, 12 -> 2;
            case 1, 2, 3 -> 3;
            default -> 4;
        };
    }

    public boolean coversQuarter(int quarter) {
        return quarter >= quarterFrom && quarter <= quarterTo;
    }

    /**
     * Books for the grade and the tracks of the chosen classes (books without a track fit every track).
     * The ones covering the lesson's quarter come first, then student's books before workbooks.
     */
    public static List<Book> candidates(int grade, Set<String> tracks, int quarter) {
        List<Book> all = list("status = 'loaded' and (grade is null or grade = ?1)", grade);
        List<Book> out = new ArrayList<>();
        for (Book b : all) {
            if (b.track == null || tracks.isEmpty() || tracks.contains(b.track)) {
                out.add(b);
            }
        }
        sortForQuarter(out, quarter);
        return out;
    }

    /** Books covering the lesson's quarter first, then student's books before workbooks, then by title. */
    public static void sortForQuarter(List<Book> books, int quarter) {
        books.sort(Comparator.<Book, Boolean>comparing(b -> !b.coversQuarter(quarter))
                .thenComparing(b -> WORKBOOK.equals(b.bookType))
                .thenComparing(b -> b.title));
    }

    public static List<Book> allByTitle() {
        return list("order by title");
    }

    /** Loaded books the teacher linked to any of these classes. */
    public static List<Book> linkedLoaded(java.util.Collection<Long> classIds) {
        if (classIds.isEmpty()) return new ArrayList<>();
        return list("status = 'loaded' and id in (select cb.bookId from ClassBook cb where cb.classId in ?1) "
                + "order by title", classIds);
    }

    /** Catalog books that fit a class: same grade (or no grade) and a matching track for grades 10-11. */
    public static List<Book> fittingClass(int grade, String track) {
        List<Book> out = new ArrayList<>();
        for (Book b : Book.<Book>list("grade is null or grade = ?1 order by title", grade)) {
            if (b.track == null || track == null || b.track.equals(track)) out.add(b);
        }
        return out;
    }
}
