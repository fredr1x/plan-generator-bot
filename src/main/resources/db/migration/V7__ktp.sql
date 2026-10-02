-- Calendar-thematic plans (KTP): one per teacher, grade and subject, with its lessons in order.
CREATE TABLE ktps (
    id           BIGSERIAL PRIMARY KEY,
    teacher_id   BIGINT      NOT NULL REFERENCES teachers (id) ON DELETE CASCADE,
    subject      TEXT,
    grade        INT,
    school_year  TEXT,
    title        TEXT,
    source_name  TEXT,
    total_hours  INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ktp_terms (
    id      BIGSERIAL PRIMARY KEY,
    ktp_id  BIGINT NOT NULL REFERENCES ktps (id) ON DELETE CASCADE,
    term_no INT    NOT NULL,
    hours   INT,
    UNIQUE (ktp_id, term_no)
);

CREATE TABLE ktp_lessons (
    id          BIGSERIAL PRIMARY KEY,
    ktp_id      BIGINT NOT NULL REFERENCES ktps (id) ON DELETE CASCADE,
    seq         INT    NOT NULL,                       -- position in the plan, starting from 1
    term_no     INT,
    unit_title  TEXT,
    topic       TEXT   NOT NULL,
    objectives  TEXT,                                  -- one line per objective: "4.1.1.1 distinguish ..."
    codes       TEXT,                                  -- "4.1.1.1, 4.3.1.1"
    hours       INT    NOT NULL DEFAULT 1,
    kind        TEXT   NOT NULL DEFAULT 'lesson',      -- lesson | project | clil | checkpoint | interim
    source_date DATE,                                  -- the date written in the file, if any
    book_refs   TEXT,                                  -- confirmed textbook pages: [{"bookId":1,"pages":"45-46"}]
    UNIQUE (ktp_id, seq)
);

-- the classes that follow a KTP (parallel classes share one plan)
CREATE TABLE ktp_classes (
    id       BIGSERIAL PRIMARY KEY,
    ktp_id   BIGINT NOT NULL REFERENCES ktps (id) ON DELETE CASCADE,
    class_id BIGINT NOT NULL REFERENCES school_classes (id) ON DELETE CASCADE,
    UNIQUE (ktp_id, class_id)
);
