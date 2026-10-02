-- Textbooks loaded by tools/book-ingest: one row per book, one row per page with everything the plan
-- generator needs (exercises with answer keys, picture descriptions, reading texts).
CREATE TABLE books (
    id         BIGSERIAL PRIMARY KEY,
    title      TEXT        NOT NULL,
    subject    TEXT,
    grade      INT,
    pages      INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE book_pages (
    id           BIGSERIAL PRIMARY KEY,
    book_id      BIGINT      NOT NULL REFERENCES books (id) ON DELETE CASCADE,
    pdf_page     INT         NOT NULL,          -- position in the PDF, starting from 1
    printed_page INT,                           -- the number printed on the page (what teachers say)
    unit         TEXT,
    heading      TEXT,
    kind         TEXT,
    keywords     TEXT,
    summary      TEXT,
    content_json TEXT        NOT NULL,          -- full structured page, see tools/book-ingest
    search_text  TEXT        NOT NULL,          -- flattened text used to find pages by topic
    tokens_in    INT         NOT NULL DEFAULT 0,
    tokens_out   INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (book_id, pdf_page)
);
CREATE INDEX book_pages_printed ON book_pages (book_id, printed_page);

-- which book pages a plan was built from (so "another variant" can reuse them)
ALTER TABLE lesson_plans
    ADD COLUMN book_id    BIGINT,
    ADD COLUMN book_pages TEXT;
