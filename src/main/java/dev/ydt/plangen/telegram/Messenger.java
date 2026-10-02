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
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Outgoing side of the bot. Failures are logged, never thrown into the dialog flow. */
@ApplicationScoped
public class Messenger {

    private static final Logger LOG = Logger.getLogger(Messenger.class);

    @Inject
    @RestClient
    TelegramClient client;

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
