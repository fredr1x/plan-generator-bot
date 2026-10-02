package dev.ydt.plangen.webhook;

import dev.ydt.plangen.bot.Router;
import dev.ydt.plangen.service.Dedup;
import dev.ydt.plangen.telegram.Dto.Update;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("/webhook")
public class WebhookResource {

    private static final Logger LOG = Logger.getLogger(WebhookResource.class);

    @ConfigProperty(name = "app.bot.webhook-secret")
    String secret;

    @ConfigProperty(name = "app.bot.allowed-ids")
    Optional<List<Long>> allowedIds;

    @Inject
    Dedup dedup;

    @Inject
    Router router;

    @Inject
    Messenger bot;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receive(@HeaderParam("X-Telegram-Bot-Api-Secret-Token") String token, Update update) {
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("webhook call rejected: wrong or missing secret token");
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        Long from = update.fromId();
        if (from == null || !allowedIds.orElse(List.of()).contains(from)) {
            LOG.warnf("update %d ignored: user %s is not in ALLOWED_IDS", update.updateId(), from);
            return Response.ok().build(); // closed bot: silently ignore strangers
        }
        long started = System.nanoTime();
        try {
            if (dedup.firstTime(update.updateId())) {
                LOG.infof("update %d <- user %d: %s", update.updateId(), from, describe(update));
                router.handle(update);
                LOG.infof("update %d handled in %d ms", update.updateId(), (System.nanoTime() - started) / 1_000_000);
            } else {
                LOG.infof("update %d ignored: duplicate delivery", update.updateId());
            }
        } catch (Exception e) {
            LOG.error("update " + update.updateId() + " failed", e);
            Long chat = update.chatId();
            if (chat != null) {
                bot.send(chat, "⚠️ Что-то пошло не так, попробуйте ещё раз.", null);
            }
        }
        // always 200: failures are logged, Telegram must not retry forever
        return Response.ok().build();
    }

    /** One-line summary for the log: callback data or the first characters of the text. */
    private static String describe(Update u) {
        if (u.callbackQuery() != null) {
            return "button " + u.callbackQuery().data();
        }
        if (u.message() != null && u.message().text() != null) {
            String t = u.message().text().replace('\n', ' ');
            return "text \"" + (t.length() > 80 ? t.substring(0, 80) + "..." : t) + "\"";
        }
        return "other";
    }
}
