package dev.ydt.plangen.ktp;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/** Stateless AI service that reads one chunk of KTP rows. Application scoped: it runs off the request thread. */
@ApplicationScoped
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface KtpModel {

    @SystemMessage(fromResource = "prompts/ktp-system.txt")
    @UserMessage(fromResource = "prompts/ktp-user.txt")
    Result<KtpChunk> extract(@V("header") String header, @V("previous") String previous,
                             @V("expected") String expected, @V("rows") String rows);
}
