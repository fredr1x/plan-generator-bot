-- A KTP of grades 10-11 belongs to a track (natural_math or social_humanities); NULL for the other grades.
ALTER TABLE ktps ADD COLUMN track TEXT;
