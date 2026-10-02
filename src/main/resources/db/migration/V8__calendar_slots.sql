-- School calendar, dates of KTP lessons, evening preparation settings.

-- Terms of a school year. The gaps between terms are the school vacations.
CREATE TABLE school_terms (
    id        BIGSERIAL PRIMARY KEY,
    year_name TEXT NOT NULL,
    term_no   INT  NOT NULL,
    starts_on DATE NOT NULL,
    ends_on   DATE NOT NULL,
    UNIQUE (year_name, term_no)
);

-- Days without lessons inside a term: public holidays and transfers, extra vacations of one grade, personal days off.
CREATE TABLE non_teaching_days (
    id         BIGSERIAL PRIMARY KEY,
    day        DATE NOT NULL,
    until      DATE,                       -- last day of a range, NULL = one day
    title      TEXT,
    kind       TEXT NOT NULL DEFAULT 'holiday',   -- holiday | vacation | personal
    grades     TEXT,                       -- NULL = all grades, "1" or "1,2" = only these
    teacher_id BIGINT REFERENCES teachers (id) ON DELETE CASCADE   -- NULL = for everybody
);

-- Which KTP lesson a class has on which day. One row per lesson (a two-hour lesson takes the next slot too).
CREATE TABLE ktp_slots (
    id        BIGSERIAL PRIMARY KEY,
    ktp_id    BIGINT NOT NULL REFERENCES ktps (id) ON DELETE CASCADE,
    class_id  BIGINT NOT NULL REFERENCES school_classes (id) ON DELETE CASCADE,
    lesson_id BIGINT NOT NULL REFERENCES ktp_lessons (id) ON DELETE CASCADE,
    slot_date DATE   NOT NULL,
    lesson_no INT    NOT NULL,
    status    TEXT   NOT NULL DEFAULT 'planned',   -- planned | prepared | done | skipped
    plan_id   BIGINT,
    UNIQUE (class_id, slot_date, lesson_no)
);
CREATE INDEX ktp_slots_date ON ktp_slots (slot_date);

CREATE TABLE teacher_settings (
    teacher_id   BIGINT PRIMARY KEY REFERENCES teachers (id) ON DELETE CASCADE,
    evening_time TIME NOT NULL DEFAULT '18:00',
    mode         TEXT NOT NULL DEFAULT 'suggest'   -- off | suggest | auto
);

-- one row per teacher, day and kind of run: a restart cannot send the same message twice
CREATE TABLE scheduler_runs (
    run_date   DATE   NOT NULL,
    teacher_id BIGINT NOT NULL,
    kind       TEXT   NOT NULL,
    PRIMARY KEY (run_date, teacher_id, kind)
);

-- Kazakhstan 2026-2027 (from the published calendar; check against the official order)
INSERT INTO school_terms (year_name, term_no, starts_on, ends_on) VALUES
    ('2026-2027', 1, '2026-09-01', '2026-10-25'),
    ('2026-2027', 2, '2026-11-02', '2026-12-27'),
    ('2026-2027', 3, '2027-01-11', '2027-03-21'),
    ('2026-2027', 4, '2027-03-29', '2027-05-25');

INSERT INTO non_teaching_days (day, until, title, kind, grades) VALUES
    ('2026-12-16', NULL, 'День Независимости', 'holiday', NULL),
    ('2027-02-08', '2027-02-14', 'Дополнительные каникулы 1 класса', 'vacation', '1'),
    ('2027-03-08', NULL, 'Международный женский день', 'holiday', NULL),
    ('2027-05-03', NULL, 'Перенос с 1 мая (проверьте по приказу)', 'holiday', NULL),
    ('2027-05-07', NULL, 'День защитника Отечества', 'holiday', NULL),
    ('2027-05-10', NULL, 'Перенос с 9 мая (проверьте по приказу)', 'holiday', NULL);
