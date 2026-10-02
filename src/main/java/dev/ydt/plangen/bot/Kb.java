package dev.ydt.plangen.bot;

import dev.ydt.plangen.telegram.Dto.InlineButton;
import dev.ydt.plangen.telegram.Dto.InlineKeyboard;
import java.util.ArrayList;
import java.util.List;

final class Kb {
    private Kb() {}

    static InlineButton btn(String text, String data) {
        return new InlineButton(text, data);
    }

    static InlineKeyboard of(List<List<InlineButton>> rows) {
        return new InlineKeyboard(rows);
    }

    /** One row with the given buttons. */
    static InlineKeyboard row(InlineButton... buttons) {
        return new InlineKeyboard(List.of(List.of(buttons)));
    }

    /** Builder for several rows. */
    static Rows rows() {
        return new Rows();
    }

    static final class Rows {
        private final List<List<InlineButton>> rows = new ArrayList<>();

        Rows add(InlineButton... buttons) {
            rows.add(List.of(buttons));
            return this;
        }

        Rows add(List<InlineButton> buttons) {
            rows.add(buttons);
            return this;
        }

        InlineKeyboard build() {
            return new InlineKeyboard(rows);
        }
    }

    static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
