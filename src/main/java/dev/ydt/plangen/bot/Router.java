package dev.ydt.plangen.bot;

import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.Teacher;
import dev.ydt.plangen.telegram.Dto.CallbackQuery;
import dev.ydt.plangen.telegram.Dto.Message;
import dev.ydt.plangen.telegram.Dto.Update;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Locale;
import org.jboss.logging.Logger;

/**
 * Callback data (max 64 bytes): prof:* / lang:xx / min:nn profile, cls:* classes, sch:day:n schedule,
 * pl:* plan flow.
 */
@ApplicationScoped
public class Router {

    static final String HELP = """
            Команды:
            /plan — составить план урока
            /classes — мои классы
            /schedule — расписание
            /books — каталог книг: добавить по ссылке okulyk.kz, привязать к классам
            /ktp — КТП: загрузить файл Word, посмотреть юниты и темы, расставить даты
            /week — уроки этой недели по КТП и готовность планов
            /next_week — уроки следующей недели
            /tomorrow — предложение планов на ближайший учебный день
            /calendar — четверти, каникулы, выходные (свои можно добавить)
            /settings — вечерняя подготовка: время и режим
            /history — прошлые планы
            /profile — Ф.И.О., школа, предмет, язык, длительность урока
            /cancel — отменить текущий ввод

            В /plan можно прислать данные одним сообщением:
            Раздел: ...
            Тема: ...
            Цели: коды и формулировки""";

    private static final Logger LOG = Logger.getLogger(Router.class);

    @Inject
    Messenger bot;

    @Inject
    ProfileFlow profile;

    @Inject
    ClassesFlow classes;

    @Inject
    ScheduleFlow schedule;

    @Inject
    PlanFlow plan;

    @Inject
    CatalogFlow catalog;

    @Inject
    KtpFlow ktp;

    @Inject
    EveningFlow evening;

    @Inject
    CalendarFlow calendar;

    @Inject
    SettingsFlow settings;

    @Transactional
    public void handle(Update u) {
        if (u.callbackQuery() != null) {
            callback(u.callbackQuery());
        } else if (u.message() != null && u.message().document() != null && u.message().from() != null) {
            document(u.message());
        } else if (u.message() != null && u.message().text() != null && u.message().from() != null) {
            text(u.message());
        }
    }

    /** A Word file is taken as a KTP and read; the teacher checks the result before anything is saved. */
    private void document(Message m) {
        long uid = m.from().id();
        long chat = m.chat().id();
        Teacher teacher = Teacher.findById(uid);
        if (teacher == null || !teacher.isComplete()) {
            profile.start(uid, chat);
            return;
        }
        LOG.infof("route: user=%d document \"%s\"", uid, m.document().fileName());
        ktp.onDocument(uid, chat, m.document());
    }

    private void text(Message m) {
        long uid = m.from().id();
        long chat = m.chat().id();
        String t = m.text().strip();
        if (t.isEmpty()) {
            return;
        }
        String cmd = t.split("\\s+", 2)[0].toLowerCase(Locale.ROOT).replaceFirst("^/", "").replaceFirst("@.*$", "");
        boolean slash = t.startsWith("/");
        if (slash) {
            LOG.infof("route: user=%d command /%s", uid, cmd);
        }

        if (slash) {
            switch (cmd) {
                case "start" -> {
                    ConvState.clear(uid);
                    profile.start(uid, chat);
                    return;
                }
                case "help" -> {
                    bot.send(chat, HELP, null);
                    return;
                }
                case "cancel" -> {
                    ConvState.clear(uid);
                    bot.send(chat, "Отменено.", null);
                    return;
                }
                default -> { }
            }
            Teacher teacher = Teacher.findById(uid);
            if (teacher == null || !teacher.isComplete()) {
                ConvState.clear(uid);
                profile.start(uid, chat);
                return;
            }
            switch (cmd) {
                case "plan", "new" -> {
                    ConvState.clear(uid);
                    plan.start(uid, chat);
                    return;
                }
                case "classes" -> {
                    ConvState.clear(uid);
                    classes.list(uid, chat);
                    return;
                }
                case "schedule" -> {
                    ConvState.clear(uid);
                    schedule.show(uid, chat);
                    return;
                }
                case "books" -> {
                    ConvState.clear(uid);
                    catalog.list(uid, chat);
                    return;
                }
                case "ktp" -> {
                    ConvState.clear(uid);
                    ktp.start(uid, chat);
                    return;
                }
                case "week" -> {
                    ConvState.clear(uid);
                    evening.week(uid, chat, 0);
                    return;
                }
                case "next_week", "nextweek" -> {
                    ConvState.clear(uid);
                    evening.week(uid, chat, 1);
                    return;
                }
                case "tomorrow" -> {
                    ConvState.clear(uid);
                    evening.tomorrow(uid, chat);
                    return;
                }
                case "calendar" -> {
                    ConvState.clear(uid);
                    calendar.show(uid, chat);
                    return;
                }
                case "settings" -> {
                    ConvState.clear(uid);
                    settings.show(uid, chat, null);
                    return;
                }
                case "history" -> {
                    ConvState.clear(uid);
                    plan.history(uid, chat);
                    return;
                }
                case "profile" -> {
                    ConvState.clear(uid);
                    profile.show(uid, chat);
                    return;
                }
                default -> {
                    bot.send(chat, "Не знаю такую команду.\n\n" + HELP, null);
                    return;
                }
            }
        }

        ConvState st = ConvState.get(uid);
        LOG.infof("route: user=%d free text, state=%s", uid, st == null ? "none" : st.state);
        if (st == null) {
            Teacher teacher = Teacher.findById(uid);
            if (teacher == null || !teacher.isComplete()) {
                profile.start(uid, chat);
            } else {
                bot.send(chat, "Чтобы составить план, нажмите /plan.\n\n" + HELP, null);
            }
            return;
        }
        if (ProfileFlow.handles(st.state)) {
            profile.onText(uid, chat, st.state, t);
        } else if (ClassesFlow.CLASSES_ADD.equals(st.state)) {
            classes.onText(uid, chat, t);
        } else if (ScheduleFlow.SCHEDULE_ADD.equals(st.state)) {
            schedule.onText(uid, chat, st, t);
        } else if (st.state.startsWith("PLAN_")) {
            plan.onText(uid, chat, st, t);
        } else if (st.state.startsWith("CAT_")) {
            catalog.onText(uid, chat, st, t);
        } else if (st.state.startsWith("KTP_")) {
            ktp.onText(uid, chat, st, t);
        } else if (st.state.startsWith("CAL_")) {
            calendar.onText(uid, chat, st, t);
        }
    }

    private void callback(CallbackQuery cb) {
        if (cb.message() == null || cb.data() == null || cb.from() == null) {
            return;
        }
        long uid = cb.from().id();
        long chat = cb.message().chat().id();
        long msg = cb.message().messageId();
        String[] p = cb.data().split(":");
        try {
            switch (p[0]) {
                case "prof", "lang", "min", "sub" -> profile.onCallback(uid, chat, cb.id(), p);
                case "cls" -> classes.onCallback(uid, chat, cb.id(), p);
                case "sch" -> schedule.onCallback(uid, chat, cb.id(), p);
                case "pl" -> plan.onCallback(uid, chat, msg, cb.id(), p);
                case "cat" -> catalog.onCallback(uid, chat, msg, cb.id(), p);
                case "ktp" -> ktp.onCallback(uid, chat, msg, cb.id(), p);
                case "ev" -> evening.onCallback(uid, chat, msg, cb.id(), p);
                case "cal" -> calendar.onCallback(uid, chat, msg, cb.id(), p);
                case "set" -> settings.onCallback(uid, chat, msg, cb.id(), p);
                default -> bot.answer(cb.id(), null);
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            bot.answer(cb.id(), "Некорректная кнопка");
        }
    }
}
