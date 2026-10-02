package dev.ydt.plangen.llm;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/** Structured answer expected from the model; the JSON schema is generated from these records. */
public record LessonContent(
        @Description("Lesson objectives for the group \"All learners will be able to\": 2-3 measurable basic-level objectives")
        List<String> allLearners,
        @Description("Lesson objectives for the group \"Most learners will be able to\": 2-3 advanced-level objectives")
        List<String> mostLearners,
        @Description("Lesson objectives for the group \"Some learners will be able to\": 2-3 high-level objectives")
        List<String> someLearners,
        @Description("Stages of the lesson procedure in order, from 4 to 6 stages")
        List<Stage> stages) {

    public record Stage(
            @Description("Stage name with the part of the lesson, e.g. \"Start of the lesson. Warm-up\"")
            String name,
            @Description("Length of the stage in minutes, an integer")
            int minutes,
            @Description("Teacher actions: concrete, with the text of the tasks and examples")
            String teacherActions,
            @Description("Student actions: what exactly they do, say, write, and in which form they work")
            String studentActions,
            @Description("Assessment: technique, criterion and descriptors")
            String assessment,
            @Description("Resources: coursebook, cards, presentation etc., without invented pages or links")
            String resources) {}
}
