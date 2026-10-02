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
        List<Book> all = list("grade is null or grade = ?1", grade);
        List<Book> out = new ArrayList<>();
        for (Book b : all) {
            if (b.track == null || tracks.isEmpty() || tracks.contains(b.track)) {
                out.add(b);
            }
        }
        out.sort(Comparator.<Book, Boolean>comparing(b -> !b.coversQuarter(quarter))
                .thenComparing(b -> WORKBOOK.equals(b.bookType))
                .thenComparing(b -> b.title));
        return out;
    }

    public static List<Book> allByTitle() {
        return list("order by title");
    }
}
