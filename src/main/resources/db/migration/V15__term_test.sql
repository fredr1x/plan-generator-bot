-- A lesson plan for the term summative assessment (TZhB / SOCh): the tasks are the teacher's own text.
ALTER TABLE lesson_plans ADD COLUMN IF NOT EXISTS term_test BOOLEAN NOT NULL DEFAULT FALSE;
