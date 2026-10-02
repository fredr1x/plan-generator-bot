CREATE TABLE teachers (
    id             BIGINT PRIMARY KEY,                -- Telegram user id
    full_name      TEXT,
    school         TEXT,
    subject        TEXT,
    lang           TEXT        NOT NULL DEFAULT 'ru',  -- plan language: ru | kk | en
    lesson_minutes INT         NOT NULL DEFAULT 45,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE school_classes (
    id            BIGSERIAL PRIMARY KEY,
    teacher_id    BIGINT NOT NULL REFERENCES teachers (id) ON DELETE CASCADE,
    grade         INT    NOT NULL,
    letter        TEXT   NOT NULL,
    student_count INT    NOT NULL,
    UNIQUE (teacher_id, grade, letter)
);

CREATE TABLE schedule_entries (
    id         BIGSERIAL PRIMARY KEY,
    teacher_id BIGINT NOT NULL REFERENCES teachers (id) ON DELETE CASCADE,
    weekday    INT    NOT NULL,                        -- 1 = Monday .. 7 = Sunday
    lesson_no  INT    NOT NULL,
    class_id   BIGINT NOT NULL REFERENCES school_classes (id) ON DELETE CASCADE,
    UNIQUE (teacher_id, weekday, lesson_no, class_id)
);

CREATE TABLE conv_state (
    user_id    BIGINT PRIMARY KEY,
    state      TEXT        NOT NULL,
    payload    TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE lesson_plans (
    id           BIGSERIAL PRIMARY KEY,
    teacher_id   BIGINT NOT NULL REFERENCES teachers (id) ON DELETE CASCADE,
    plan_date    DATE   NOT NULL,
    classes      TEXT   NOT NULL,                      -- "7 А, 7 Б"
    students     INT    NOT NULL,
    section      TEXT,
    topic        TEXT   NOT NULL,
    objectives   TEXT,
    content_json TEXT   NOT NULL,
    model        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX lesson_plans_teacher_created ON lesson_plans (teacher_id, created_at DESC);

CREATE TABLE processed_updates (
    update_id    BIGINT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
