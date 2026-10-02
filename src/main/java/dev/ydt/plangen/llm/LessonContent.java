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
        @Description("Lesson objectives: 2-3 short sentences, each starting with a bare verb so that it continues "
                + "\"Learners will be able to:\", e.g. \"Learn the affirmative and negative forms of the Present Simple\"")
        List<String> lessonObjectives,
        @Description("Exactly three stages in order: beginning, middle, end of the class")
        List<Stage> stages) {

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Stage(
            @Description("Stage name, exactly one of: \"Beginning of the class\", \"Middle of the class\", \"End of the class\"")
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
