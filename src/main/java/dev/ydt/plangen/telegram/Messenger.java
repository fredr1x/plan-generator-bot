package dev.ydt.plangen.telegram;

import dev.ydt.plangen.telegram.Dto.AnswerCallback;
import dev.ydt.plangen.telegram.Dto.DeleteMessage;
import dev.ydt.plangen.telegram.Dto.EditMessage;
import dev.ydt.plangen.telegram.Dto.InlineKeyboard;
import dev.ydt.plangen.telegram.Dto.SendMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Outgoing side of the bot. Failures are logged, never thrown into the dialog flow. */
@ApplicationScoped
public class Messenger {

    private static final Logger LOG = Logger.getLogger(Messenger.class);

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newHttpClient();

    @Inject
    @RestClient
    TelegramClient client;

    @ConfigProperty(name = "app.bot.token")
    String token;

    @ConfigProperty(name = "app.bot.allow-local-files", defaultValue = "false")
    boolean allowLocalFiles;

    /** Returns the new message id, or null when sending failed. */
    public Long send(long chatId, String text, InlineKeyboard keyboard) {
        try {
            var res = client.sendMessage(new SendMessage(chatId, text, keyboard));
            return res != null && res.result() != null ? res.result().messageId() : null;
        } catch (RuntimeException e) {
            LOG.errorf("sendMessage failed: %s", describe(e));
            return null;
        }
    }

    public void edit(long chatId, long messageId, String text, InlineKeyboard keyboard) {
        try {
            client.editMessageText(new EditMessage(chatId, messageId, text, keyboard));
        } catch (RuntimeException e) {
            // most common: 400 "message is not modified" when the same button is tapped twice
            LOG.debugf("editMessageText failed: %s", describe(e));
        }
    }

    public void delete(long chatId, long messageId) {
        try {
            client.deleteMessage(new DeleteMessage(chatId, messageId));
        } catch (RuntimeException e) {
            LOG.debugf("deleteMessage failed: %s", describe(e));
        }
    }

    public void answer(String callbackId, String text) {
        try {
            client.answerCallbackQuery(new AnswerCallback(callbackId, text));
        } catch (RuntimeException e) {
            LOG.debugf("answerCallbackQuery failed: %s", describe(e));
        }
    }

    public boolean sendDocument(long chatId, File file, String caption) {
        try {
            client.sendDocument(String.valueOf(chatId), caption, file);
            return true;
        } catch (RuntimeException e) {
            LOG.errorf("sendDocument failed: %s", describe(e));
            return false;
        }
    }

    /**
     * Downloads a file the user sent (the bot API allows up to 20 MB). In the dev profile a file id of the form
     * "local:C:/path/file.doc" reads a local file instead, so the KTP import can be tried without Telegram.
     */
    public byte[] download(String fileId, long maxBytes) throws IOException {
        if (allowLocalFiles && fileId.startsWith("local:")) {
            return Files.readAllBytes(Path.of(fileId.substring("local:".length())));
        }
        Dto.FileResult res;
        try {
            res = client.getFile(new Dto.GetFile(fileId));
        } catch (RuntimeException e) {
            throw new IOException("Telegram не отдал файл: " + describe(e), e);
        }
        if (res == null || !res.ok() || res.result() == null || res.result().filePath() == null) {
            throw new IOException("Telegram не отдал файл");
        }
        if (res.result().fileSize() != null && res.result().fileSize() > maxBytes) {
            throw new IOException("Файл слишком большой");
        }
        try {
            HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(
                            URI.create("https://api.telegram.org/file/bot" + token + "/" + res.result().filePath()))
                    .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200 || r.body().length > maxBytes) {
                throw new IOException("Не удалось скачать файл (HTTP " + r.statusCode() + ")");
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Скачивание прервано", e);
        }
    }

    /** Telegram explains 4xx errors in the response body. */
    private static String describe(RuntimeException e) {
        if (e instanceof WebApplicationException w && w.getResponse() != null) {
            try {
                return w.getResponse().getStatus() + " " + w.getResponse().readEntity(String.class);
            } catch (RuntimeException ignored) {
                // body already consumed
            }
        }
        return String.valueOf(e);
    }
}
