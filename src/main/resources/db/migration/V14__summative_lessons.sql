-- A lesson plan made for a unit summative assessment (BZhB): a 20-minute lesson.
ALTER TABLE lesson_plans ADD COLUMN IF NOT EXISTS summative BOOLEAN NOT NULL DEFAULT FALSE;
