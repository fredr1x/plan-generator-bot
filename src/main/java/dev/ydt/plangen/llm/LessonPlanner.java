package dev.ydt.plangen.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/** Stateless AI service: no chat memory, every call is independent. Application scoped: it is called off-request. */
@ApplicationScoped
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface LessonPlanner {

    @SystemMessage(fromResource = "prompts/lesson-system.txt")
    @UserMessage(fromResource = "prompts/lesson-user.txt")
    LessonContent plan(
            @V("subject") String subject,
            @V("language") String language,
            @V("minutes") int minutes,
            @V("classes") String classes,
            @V("students") int students,
            @V("section") String section,
            @V("topic") String topic,
            @V("objectives") String objectives,
            @V("notes") String notes,
            @V("previous") String previous,
            @V("feedback") String feedback);
}
