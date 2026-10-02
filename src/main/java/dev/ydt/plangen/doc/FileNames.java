package dev.ydt.plangen.doc;

import java.time.LocalDate;
import java.util.Map;

/**
 * File name of the generated plan: plan-date-class-topic.docx, e.g. plan-2026-10-02-7A-Past-Simple.docx.
 * ASCII only (Cyrillic is transliterated), so it is safe on any server locale and in Telegram.
 */
public final class FileNames {
    private FileNames() {}

    private static final int MAX_TOPIC = 50;

    private static final Map<Character, String> TRANSLIT = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"),
            Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('ё', "e"), Map.entry('ж', "zh"),
            Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('й', "y"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"),
            Map.entry('п', "p"), Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"),
            Map.entry('у', "u"), Map.entry('ф', "f"), Map.entry('х', "kh"), Map.entry('ц', "ts"),
            Map.entry('ч', "ch"), Map.entry('ш', "sh"), Map.entry('щ', "shch"), Map.entry('ъ', ""),
            Map.entry('ы', "y"), Map.entry('ь', ""), Map.entry('э', "e"), Map.entry('ю', "yu"),
            Map.entry('я', "ya"),
            // Kazakh
            Map.entry('ә', "a"), Map.entry('ғ', "g"), Map.entry('қ', "k"), Map.entry('ң', "n"),
            Map.entry('ө', "o"), Map.entry('ұ', "u"), Map.entry('ү', "u"), Map.entry('һ', "h"),
            Map.entry('і', "i"));

    /** classes is the label list as shown in the plan, e.g. "7 А, 7 Б". */
    public static String docx(LocalDate date, String classes, String topic) {
        StringBuilder sb = new StringBuilder("plan-").append(date);
        // "7 А, 7 Б" -> "7A-7B": the space inside a class label is dropped, labels are joined with a dash
        StringBuilder cls = new StringBuilder();
        for (String label : (classes == null ? "" : classes).split(",")) {
            String s = slug(label.replace(" ", ""));
            if (!s.isEmpty()) {
                if (!cls.isEmpty()) cls.append('-');
                cls.append(s);
            }
        }
        if (!cls.isEmpty()) {
            sb.append('-').append(cls);
        }
        String top = slug(topic);
        if (top.length() > MAX_TOPIC) {
            // cut at a word boundary when there is one
            String cut = top.substring(0, MAX_TOPIC);
            int dash = cut.lastIndexOf('-');
            top = (dash > MAX_TOPIC / 2 ? cut.substring(0, dash) : cut).replaceAll("-+$", "");
        }
        if (!top.isEmpty()) {
            sb.append('-').append(top);
        }
        return sb.append(".docx").toString();
    }

    /** Transliterates to ASCII and joins the words with dashes. */
    static String slug(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            String t = TRANSLIT.get(Character.toLowerCase(c));
            if (t == null) {
                out.append(c);
            } else if (!t.isEmpty()) {
                out.append(Character.isUpperCase(c) ? Character.toUpperCase(t.charAt(0)) + t.substring(1) : t);
            }
        }
        return out.toString().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
