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
        List<Stage> stages,
        @Description("The one value of the Birtutas tarbiye programme that the lesson activities develop most "
                + "(group or pair work: UNITY_AND_SOLIDARITY; rules, laws, civic topics: LAW_AND_ORDER; homeland, "
                + "history, symbols: INDEPENDENCE_AND_PATRIOTISM; fairness, duty: JUSTICE_AND_RESPONSIBILITY; "
                + "effort, professions: HARD_WORK_AND_PROFESSIONALISM; projects, new ideas, technology: "
                + "CREATIVITY_AND_INNOVATION)")
        ProgramValue programValue) {

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
