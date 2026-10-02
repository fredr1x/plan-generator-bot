-- Book catalog: metadata from okulyk.kz (or typed by hand), the load status of the pages, and the links
-- between a teacher's classes and the books they use.
ALTER TABLE books
    ADD COLUMN authors      TEXT,
    ADD COLUMN publisher    TEXT,
    ADD COLUMN pub_year     INT,
    ADD COLUMN language     TEXT,
    ADD COLUMN source_url   TEXT,                      -- the book page on okulyk.kz
    ADD COLUMN cover_url    TEXT,
    ADD COLUMN pdf_url      TEXT,                      -- direct link to the PDF used by the loader
    -- catalog (metadata only) -> requested (a teacher linked it) -> approved (owner agreed to pay for the
    -- loading) -> loading -> loaded | failed
    ADD COLUMN status       TEXT NOT NULL DEFAULT 'loaded',
    ADD COLUMN requested_by BIGINT,
    ADD COLUMN status_note  TEXT;

CREATE TABLE class_books (
    id       BIGSERIAL PRIMARY KEY,
    class_id BIGINT NOT NULL REFERENCES school_classes (id) ON DELETE CASCADE,
    book_id  BIGINT NOT NULL REFERENCES books (id) ON DELETE CASCADE,
    UNIQUE (class_id, book_id)
);
CREATE INDEX class_books_book ON class_books (book_id);
