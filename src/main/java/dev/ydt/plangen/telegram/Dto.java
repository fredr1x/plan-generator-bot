package dev.ydt.plangen.telegram;

import java.util.List;

/** Telegram Bot API payloads (snake_case mapping is configured globally in Jackson). */
public final class Dto {
    private Dto() {}

    public record Update(long updateId, Message message, CallbackQuery callbackQuery) {
        public Long fromId() {
            if (callbackQuery != null && callbackQuery.from() != null) return callbackQuery.from().id();
            if (message != null && message.from() != null) return message.from().id();
            return null;
        }

        public Long chatId() {
            if (callbackQuery != null && callbackQuery.message() != null) return callbackQuery.message().chat().id();
            if (message != null && message.chat() != null) return message.chat().id();
            return null;
        }
    }

    public record Message(long messageId, User from, Chat chat, String text) {}

    public record User(long id) {}

    public record Chat(long id) {}

    public record CallbackQuery(String id, User from, Message message, String data) {}

    public record InlineButton(String text, String callbackData) {}

    public record InlineKeyboard(List<List<InlineButton>> inlineKeyboard) {}

    public record SendMessage(long chatId, String text, InlineKeyboard replyMarkup) {}

    public record EditMessage(long chatId, long messageId, String text, InlineKeyboard replyMarkup) {}

    public record DeleteMessage(long chatId, long messageId) {}

    public record AnswerCallback(String callbackQueryId, String text) {}

    public record SendResult(boolean ok, Message result) {}

    public record BotCommand(String command, String description) {}

    public record SetMyCommands(List<BotCommand> commands) {}
}
