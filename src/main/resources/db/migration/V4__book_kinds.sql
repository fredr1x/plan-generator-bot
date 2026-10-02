-- Book kind (student's book / workbook), the track of grades 10-11 and the quarters a book covers.
ALTER TABLE books
    ADD COLUMN book_type    TEXT NOT NULL DEFAULT 'student_book',   -- student_book | workbook
    ADD COLUMN track        TEXT,                                   -- NULL = every track | natural_math | social_humanities
    ADD COLUMN quarter_from INT  NOT NULL DEFAULT 1,
    ADD COLUMN quarter_to   INT  NOT NULL DEFAULT 4,
    ADD COLUMN part_note    TEXT;                                   -- e.g. "first half of quarter 2"

-- grades 10-11 are split into tracks; the class remembers its own
ALTER TABLE school_classes ADD COLUMN track TEXT;

-- a plan can use pages of several books: JSON like [{"bookId":1,"pages":"45-46"}]
ALTER TABLE lesson_plans
    DROP COLUMN book_id,
    DROP COLUMN book_pages,
    ADD COLUMN book_refs TEXT;
