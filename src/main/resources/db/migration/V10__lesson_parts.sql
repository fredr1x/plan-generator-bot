-- A KTP lesson of several hours is stored as several lessons of one hour (part of parts).
ALTER TABLE ktp_lessons ADD COLUMN part  INT NOT NULL DEFAULT 1;
ALTER TABLE ktp_lessons ADD COLUMN parts INT NOT NULL DEFAULT 1;
