package dev.ydt.plangen.llm;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * Structured answer expected from the model; the JSON schema is generated from these records.
 * Field names stay camelCase in JSON (the schema uses them), regardless of the global snake_case
 * setting that Telegram payloads need.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record LessonContent(
        @Description("Lesson objectives (assessment criteria): 2-3 short sentences, each starting with a bare verb so "
                + "that it continues \"Learners will be able to:\", e.g. \"Learn and practise vocabulary for holidays\"")
        List<String> lessonObjectives,
        @Description("Rows of the lesson procedure in order: one \"Beginning\" row, then one \"Middle\" row per "
                + "assessed exercise or activity, then one \"End\" row")
        List<Stage> stages,
        @Description("2-3 values of the Birtutas tarbiye programme that the lesson develops, each with the observable "
                + "learner action that shows it in this lesson")
        List<ValueLink> values) {

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Stage(
            @Description("Exactly \"Beginning\", \"Middle\" or \"End\"")
            String name,
            @Description("Length of this row in minutes, an integer")
            int minutes,
            @Description("Teacher actions: starts with the exercise reference (\"Ex. 1, p. 32\") when it is a textbook "
                    + "exercise, then short concrete steps")
            String teacherActions,
            @Description("Student actions: what exactly they do, then ANSWERS with the real answers item by item")
            String studentActions,
            @Description("Assessment: for a Middle row \"Exercise N — X points\" with one-point descriptors; for "
                    + "Beginning and End oral feedback")
            String assessment,
            @Description("Resources: coursebook, cards, pictures, LED screen etc., without invented pages or links")
            String resources) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ValueLink(
            @Description("The value of the Birtutas tarbiye programme")
            ProgramValue value,
            @Description("What learners do in this lesson that develops the value, one short phrase, e.g. \"listen to a "
                    + "partner and discuss travel choices respectfully\"")
            String action) {}
}
