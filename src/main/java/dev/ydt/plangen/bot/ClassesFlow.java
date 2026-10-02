package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.ScheduleEntry;
import dev.ydt.plangen.domain.SchoolClass;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The teacher's classes with the number of students, so they never have to be typed again. */
@ApplicationScoped
public class ClassesFlow {

    static final String CLASSES_ADD = "CLASSES_ADD";

    private static final Pattern LINE =
            Pattern.compile("^\\s*(\\d{1,2})\\s*[-–]?\\s*(\\p{L})\\s*[-–:,]?\\s*(\\d{1,3})\\s*$");

    /** Latin letters typed by mistake in class names are mapped to their Cyrillic twins. */
    private static final Map<Character, Character> TWINS = Map.of(
            'A', 'А', 'B', 'В', 'C', 'С', 'E', 'Е', 'H', 'Н', 'K', 'К', 'M', 'М', 'O', 'О', 'P', 'Р', 'T', 'Т');

    @Inject
    Messenger bot;

    static String normLetter(String letter) {
        String up = letter.toUpperCase();
        char c = up.charAt(0);
        return String.valueOf(TWINS.getOrDefault(c, c));
    }

    public void list(long uid, long chat) {
        List<SchoolClass> classes = SchoolClass.forTeacher(uid);
        Kb.Rows rows = Kb.rows();
        StringBuilder sb = new StringBuilder();
        if (classes.isEmpty()) {
            sb.append("Классов пока нет.");
        } else {
            sb.append("🏫 Ваши классы:\n");
            for (SchoolClass c : classes) {
                sb.append("• ").append(c.label()).append(" — ").append(c.studentCount).append(" уч.\n");
                rows.add(btn("🗑 " + c.label(), "cls:del:" + c.id));
            }
        }
        rows.add(btn("➕ Добавить / обновить классы", "cls:add"));
        bot.send(chat, sb.toString().stripTrailing(), rows.build());
    }

    public void onCallback(long uid, long chat, String cbId, String[] p) {
        bot.answer(cbId, null);
        switch (p[1]) {
            case "add" -> {
                ConvState.set(uid, CLASSES_ADD, null);
                bot.send(chat, "Пришлите классы и количество учеников, каждый с новой строки:\n\n"
                        + "7 А 25\n7 Б 24\n8 А 27\n\n"
                        + "Если класс уже есть, количество обновится. Отмена: /cancel", null);
            }
            case "list" -> list(uid, chat);
            case "del" -> {
                SchoolClass c = owned(uid, Long.parseLong(p[2]));
                if (c == null) {
                    bot.send(chat, "Класс не найден.", null);
                    return;
                }
                bot.send(chat, "Удалить класс " + c.label() + "? Он также пропадёт из расписания.",
                        Kb.row(btn("Да, удалить", "cls:delok:" + c.id), btn("Нет", "cls:list")));
            }
            case "delok" -> {
                SchoolClass c = owned(uid, Long.parseLong(p[2]));
                if (c != null) {
                    ScheduleEntry.delete("classId = ?1", c.id);
                    c.delete();
                }
                list(uid, chat);
            }
            default -> { }
        }
    }

    public void onText(long uid, long chat, String text) {
        List<String> saved = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                bad.add(line.strip());
                continue;
            }
            int grade = Integer.parseInt(m.group(1));
            int count = Integer.parseInt(m.group(3));
            if (grade < 1 || grade > 11 || count < 1) {
                bad.add(line.strip());
                continue;
            }
            String letter = normLetter(m.group(2));
            SchoolClass c = SchoolClass.find("teacherId = ?1 and grade = ?2 and letter = ?3", uid, grade, letter)
                    .firstResult();
            if (c == null) {
                c = new SchoolClass();
                c.teacherId = uid;
                c.grade = grade;
                c.letter = letter;
            }
            c.studentCount = count;
            c.persist();
            saved.add(c.label() + " — " + count);
        }
        StringBuilder sb = new StringBuilder();
        if (!saved.isEmpty()) {
            sb.append("Сохранено:\n").append(String.join("\n", saved));
        }
        if (!bad.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("Не понял строки (формат «7 А 25»):\n").append(String.join("\n", bad));
        }
        if (saved.isEmpty()) {
            bot.send(chat, sb + "\n\nПопробуйте ещё раз или /cancel.", null);
            return;
        }
        ConvState.clear(uid);
        bot.send(chat, sb.toString(), Kb.row(btn("Мои классы", "cls:list")));
    }

    private SchoolClass owned(long uid, long id) {
        SchoolClass c = SchoolClass.findById(id);
        return c != null && c.teacherId == uid ? c : null;
    }
}
