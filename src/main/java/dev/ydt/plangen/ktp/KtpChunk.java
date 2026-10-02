package dev.ydt.plangen.ktp;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * What the model returns for one chunk of KTP rows (the JSON schema is generated from these records).
 * Names stay camelCase in JSON, whatever the global snake_case setting says.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record KtpChunk(
        @Description("The lessons of the rows, in the order of the rows")
        List<AiLesson> lessons,
        @Description("Term (quarter) header rows found in these rows")
        List<AiTerm> terms,
        @Description("The total number of hours if a total row is present, otherwise null")
        Integer total,
        @Description("Short notes about problems in the source rows; empty when everything is fine")
        List<String> notes) {

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record AiLesson(
            @Description("Lesson number from the number cell, null if the cell is empty")
            Integer number,
            @Description("Unit title copied verbatim (inherited from the previous row when the cell is empty)")
            String unit,
            @Description("Topic of the lesson, copied verbatim")
            String topic,
            @Description("Learning objectives of the lesson, each with its code and verbatim text")
            List<AiObjective> objectives,
            @Description("Hours from the hours cell, null if empty")
            Integer hours,
            @Description("Date copied from the date cell, empty string if none")
            String date,
            @Description("Term (quarter) number of the lesson, null if the table has no term rows")
            Integer term) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record AiObjective(
            @Description("Code written as four numbers with dots and no spaces, e.g. 4.1.1.1; empty if the cell has no code")
            String code,
            @Description("Objective text copied verbatim, without the code")
            String text) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record AiTerm(
            @Description("Term number")
            Integer number,
            @Description("Hours of the term as written, null if not written")
            Integer hours) {}
}
