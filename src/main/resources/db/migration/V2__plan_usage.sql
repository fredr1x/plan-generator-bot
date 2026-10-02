-- what each plan cost: tokens over all attempts, number of model calls, wall-clock time
ALTER TABLE lesson_plans
    ADD COLUMN tokens_in  INT NOT NULL DEFAULT 0,
    ADD COLUMN tokens_out INT NOT NULL DEFAULT 0,
    ADD COLUMN attempts   INT NOT NULL DEFAULT 1,
    ADD COLUMN millis     INT NOT NULL DEFAULT 0;
