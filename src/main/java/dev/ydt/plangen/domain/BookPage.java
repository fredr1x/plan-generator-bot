package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.List;

/** One structured textbook page: see tools/book-ingest for how contentJson is produced. */
@Entity
@Table(name = "book_pages")
public class BookPage extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "book_id")
    public long bookId;

    @Column(name = "pdf_page")
    public int pdfPage;

    @Column(name = "printed_page")
    public Integer printedPage;

    public String unit;

    public String heading;

    public String kind;

    public String keywords;

    public String summary;

    @Column(name = "content_json")
    public String contentJson;

    @Column(name = "search_text")
    public String searchText;

    public static List<BookPage> byPrinted(long bookId, Collection<Integer> numbers) {
        return list("bookId = ?1 and printedPage in ?2 order by pdfPage", bookId, numbers);
    }

    public static List<BookPage> byPdf(long bookId, Collection<Integer> numbers) {
        return list("bookId = ?1 and pdfPage in ?2 order by pdfPage", bookId, numbers);
    }

    public static List<BookPage> ofBook(long bookId) {
        return list("bookId = ?1 order by pdfPage", bookId);
    }

    /** The number teachers use: the printed one when the page has it, otherwise the PDF position. */
    public int shownNumber() {
        return printedPage != null ? printedPage : pdfPage;
    }
}
