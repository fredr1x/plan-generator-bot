-- The timetable has versions: every row belongs to the version that is in force from valid_from on.
-- Existing rows become the first version, valid "always" (2000-01-01).
ALTER TABLE schedule_entries ADD COLUMN valid_from DATE NOT NULL DEFAULT DATE '2000-01-01';

-- the old unique rule (teacher, weekday, lesson, class) must now include the version
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'plangen.schedule_entries'::regclass AND contype = 'u' LOOP
        EXECUTE format('ALTER TABLE plangen.schedule_entries DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

ALTER TABLE schedule_entries
    ADD CONSTRAINT schedule_entries_version_uq UNIQUE (teacher_id, valid_from, weekday, lesson_no, class_id);

-- the date from which the teacher's next timetable changes take effect (chosen in /schedule)
ALTER TABLE teacher_settings ADD COLUMN schedule_from DATE;
