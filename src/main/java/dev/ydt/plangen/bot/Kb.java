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

    /** Longest button label that is still readable on a phone; longer texts go to the message in full. */
    static final int BTN = 34;

    /** Button labels: when any text is too long for a button, all are numbered and cut. */
    static List<String> labels(List<String> full) {
        boolean cut = full.stream().anyMatch(s -> s.length() > BTN);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < full.size(); i++) {
            out.add(cut ? (i + 1) + ". " + trunc(full.get(i), BTN) : full.get(i));
        }
        return out;
    }

    /** The full texts as a numbered list for the message, or "" when every label fits a button. */
    static String legend(List<String> full) {
        if (full.stream().noneMatch(s -> s.length() > BTN)) return "";
        StringBuilder sb = new StringBuilder("\n");
        for (int i = 0; i < full.size(); i++) sb.append('\n').append(i + 1).append(". ").append(full.get(i));
        return sb.toString();
    }

    static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
