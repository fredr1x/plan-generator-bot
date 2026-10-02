-- number of students per class at the moment the plan was made: "7 А=25;7 Б=24"
-- (a plan for several classes shows them one by one in "Number present" and "Absent")
ALTER TABLE lesson_plans ADD COLUMN class_counts TEXT;
