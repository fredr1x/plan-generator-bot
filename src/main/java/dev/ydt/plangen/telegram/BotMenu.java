package dev.ydt.plangen.telegram;

import dev.ydt.plangen.telegram.Dto.BotCommand;
import dev.ydt.plangen.telegram.Dto.SetMyCommands;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Registers the command menu (the "/" button) in Telegram on every start. */
@ApplicationScoped
public class BotMenu {

    private static final Logger LOG = Logger.getLogger(BotMenu.class);

    private static final List<BotCommand> COMMANDS = List.of(
            new BotCommand("plan", "Составить план урока"),
            new BotCommand("classes", "Мои классы"),
            new BotCommand("schedule", "Расписание"),
            new BotCommand("ktp", "КТП: загрузка, юниты, даты"),
            new BotCommand("week", "Уроки недели"),
            new BotCommand("tomorrow", "Планы на ближайший учебный день"),
            new BotCommand("calendar", "Каникулы и выходные"),
            new BotCommand("settings", "Вечерняя подготовка"),
            new BotCommand("books", "Каталог книг"),
            new BotCommand("history", "Прошлые планы"),
            new BotCommand("profile", "Профиль"),
            new BotCommand("help", "Помощь"),
            new BotCommand("cancel", "Отменить текущий ввод"));

    @ConfigProperty(name = "app.bot.token")
    String token;

    @Inject
    @RestClient
    TelegramClient client;

    void onStart(@Observes StartupEvent ev) {
        if (token == null || token.isBlank() || token.equals("dev-token")) {
            LOG.info("BOT_TOKEN is not set, skipping command menu registration");
            return;
        }
        // off the startup thread so a slow Telegram never delays the app
        Thread.startVirtualThread(() -> {
            try {
                client.setMyCommands(new SetMyCommands(COMMANDS));
                LOG.info("Telegram command menu registered");
            } catch (RuntimeException e) {
                LOG.warnf("setMyCommands failed: %s", e.getMessage());
            }
        });
    }
}
