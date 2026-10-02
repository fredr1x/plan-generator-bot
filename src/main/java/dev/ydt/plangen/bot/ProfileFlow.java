package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.Teacher;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Onboarding (name, school, subject, plan language, lesson length) and later profile edits. */
@ApplicationScoped
public class ProfileFlow {

    static final String ONB_NAME = "ONB_NAME";
    static final String ONB_SCHOOL = "ONB_SCHOOL";
    static final String ONB_SUBJECT = "ONB_SUBJECT";
    static final String ONB_LANG = "ONB_LANG";
    static final String ONB_MIN = "ONB_MIN";
    static final String SET_NAME = "SET_NAME";
    static final String SET_SCHOOL = "SET_SCHOOL";
    static final String SET_SUBJECT = "SET_SUBJECT";

    @Inject
    Messenger bot;

    static boolean handles(String state) {
        return state.startsWith("ONB_") || state.startsWith("SET_");
    }

    public Teacher teacher(long uid) {
        Teacher t = Teacher.findById(uid);
        if (t == null) {
            t = new Teacher();
            t.id = uid;
            t.persist();
        }
        return t;
    }

    public void start(long uid, long chat) {
        Teacher t = teacher(uid);
        if (t.isComplete()) {
            bot.send(chat, "С возвращением! Что делаем?\n\n" + Router.HELP, null);
            return;
        }
        ConvState.set(uid, ONB_NAME, null);
        bot.send(chat, "Здравствуйте! Я составляю краткосрочные (поурочные) планы.\n"
                + "Сначала несколько вопросов, это нужно один раз.\n\n"
                + "Как вас зовут? Напишите Ф.И.О. так, как оно должно стоять в плане.", null);
    }

    public void onText(long uid, long chat, String state, String text) {
        Teacher t = teacher(uid);
        String v = Kb.trunc(text.trim(), 200);
        switch (state) {
            case ONB_NAME -> {
                t.fullName = v;
                ConvState.set(uid, ONB_SCHOOL, null);
                bot.send(chat, "Название школы (как оно должно быть в плане)?", null);
            }
            case ONB_SCHOOL -> {
                t.school = v;
                ConvState.set(uid, ONB_SUBJECT, null);
                bot.send(chat, "Какой предмет вы преподаёте? (например: Английский язык, Математика)", null);
            }
            case ONB_SUBJECT -> {
                t.subject = v;
                t.lang = "en"; // plans are in English by default; changeable in /profile
                ConvState.set(uid, ONB_MIN, null);
                askMinutes(chat);
            }
            case SET_NAME -> {
                t.fullName = v;
                ConvState.clear(uid);
                show(uid, chat);
            }
            case SET_SCHOOL -> {
                t.school = v;
                ConvState.clear(uid);
                show(uid, chat);
            }
            case SET_SUBJECT -> {
                t.subject = v;
                ConvState.clear(uid);
                show(uid, chat);
            }
            default -> bot.send(chat, "Не понял. Нажмите кнопку выше или /cancel.", null);
        }
    }

    public void onCallback(long uid, long chat, String cbId, String[] p) {
        bot.answer(cbId, null);
        Teacher t = teacher(uid);
        switch (p[0]) {
            case "prof" -> profileAction(uid, chat, p.length > 1 ? p[1] : "");
            case "lang" -> {
                t.lang = p[1];
                ConvState st = ConvState.get(uid);
                if (st != null && ONB_LANG.equals(st.state)) {
                    ConvState.set(uid, ONB_MIN, null);
                    askMinutes(chat);
                } else {
                    show(uid, chat);
                }
            }
            case "min" -> {
                t.lessonMinutes = Integer.parseInt(p[1]);
                ConvState st = ConvState.get(uid);
                if (st != null && ONB_MIN.equals(st.state)) {
                    ConvState.clear(uid);
                    bot.send(chat, "Готово ✅\n\nДальше:\n"
                            + "1) /classes — добавьте свои классы и количество учеников\n"
                            + "2) /schedule — расписание (необязательно)\n"
                            + "3) /plan — составить план урока", null);
                } else {
                    show(uid, chat);
                }
            }
            default -> { }
        }
    }

    private void profileAction(long uid, long chat, String what) {
        switch (what) {
            case "name" -> {
                ConvState.set(uid, SET_NAME, null);
                bot.send(chat, "Новое Ф.И.О.:", null);
            }
            case "school" -> {
                ConvState.set(uid, SET_SCHOOL, null);
                bot.send(chat, "Название школы:", null);
            }
            case "subject" -> {
                ConvState.set(uid, SET_SUBJECT, null);
                bot.send(chat, "Предмет:", null);
            }
            case "lang" -> askLang(chat);
            case "min" -> askMinutes(chat);
            default -> show(uid, chat);
        }
    }

    public void show(long uid, long chat) {
        Teacher t = teacher(uid);
        String text = "👤 Профиль\n"
                + "Ф.И.О.: " + orDash(t.fullName) + "\n"
                + "Школа: " + orDash(t.school) + "\n"
                + "Предмет: " + orDash(t.subject) + "\n"
                + "Язык планов: " + langName(t.lang) + "\n"
                + "Длительность урока: " + t.lessonMinutes + " мин";
        bot.send(chat, text, Kb.rows()
                .add(btn("Ф.И.О.", "prof:name"), btn("Школа", "prof:school"), btn("Предмет", "prof:subject"))
                .add(btn("Язык планов", "prof:lang"), btn("Длительность урока", "prof:min"))
                .build());
    }

    private void askLang(long chat) {
        bot.send(chat, "На каком языке составлять планы?", Kb.row(
                btn("English", "lang:en"), btn("Русский", "lang:ru"), btn("Қазақша", "lang:kk")));
    }

    private void askMinutes(long chat) {
        bot.send(chat, "Сколько минут длится урок? Сумма времени этапов в плане будет равна этому числу.",
                Kb.row(btn("35", "min:35"), btn("40", "min:40"), btn("45", "min:45")));
    }

    static String langName(String lang) {
        return switch (lang == null ? "en" : lang) {
            case "kk" -> "Қазақша";
            case "ru" -> "Русский";
            default -> "English";
        };
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
