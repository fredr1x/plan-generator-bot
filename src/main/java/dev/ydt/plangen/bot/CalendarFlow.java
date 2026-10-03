package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ydt.plangen.domain.ConvState;
import dev.ydt.plangen.domain.KtpSlot;
import dev.ydt.plangen.domain.NonTeachingDay;
import dev.ydt.plangen.domain.SchoolTerm;
import dev.ydt.plangen.domain.Teacher;
import dev.ydt.plangen.service.SlotService;
import dev.ydt.plangen.service.TimeService;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * /calendar: terms, vacations and days off. A teacher adds personal days off; the owner can add a day for all
 * teachers (a transfer of a holiday). Callbacks: cal:add, cal:me, cal:all, cal:del:id, cal:delok:id, cal:list.
 */
@ApplicationScoped
public class CalendarFlow {

    private static final Logger LOG = Logger.getLogger(CalendarFlow.class);

    static final String CAL_ADD = "CAL_ADD";
    static final String CAL_SCOPE = "CAL_SCOPE";

    private static final String D = "(\\d{1,2}\\.\\d{1,2}(?:\\.\\d{2,4})?)";
    private static final Pattern ENTRY = Pattern.compile("^\\s*" + D + "(?:\\s*[-–—]\\s*" + D + ")?\\s*(.*)$");

    /** A day off waiting for the choice "only me / everybody". */
    public static class Pending {
        public String from;
        public String to;
        public String title;
    }

    @Inject
    Messenger bot;

    @Inject
    SlotService slots;

    @Inject
    TimeService time;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "app.bot.admin-ids")
    Optional<List<Long>> adminIds;

    @ConfigProperty(name = "app.bot.allowed-ids")
    Optional<List<Long>> allowedIds;

    void show(long uid, long chat) {
        LocalDate today = time.today();
        StringBuilder sb = new StringBuilder("🗓 Календарь\n");
        List<SchoolTerm> terms = SchoolTerm.all().stream().filter(t -> !t.endsOn.isBefore(today.minusDays(1))).toList();
        if (terms.isEmpty()) {
            sb.append("\nУчебный год в календаре закончился или не загружен.");
        }
        for (SchoolTerm t : terms) {
            sb.append("\n").append(t.termNo).append(" четверть: ").append(t.startsOn.format(Dates.FULL)).append(" – ")
                    .append(t.endsOn.format(Dates.FULL));
        }
        for (int i = 0; i + 1 < terms.size(); i++) {
            sb.append("\nКаникулы: ").append(terms.get(i).endsOn.plusDays(1).format(Dates.SHORT)).append(" – ")
                    .append(terms.get(i + 1).startsOn.minusDays(1).format(Dates.SHORT));
        }
        List<NonTeachingDay> days = NonTeachingDay.visible(uid, today);
        sb.append("\n\nВыходные и праздники:");
        Kb.Rows rows = Kb.rows();
        if (days.isEmpty()) sb.append("\nпока нет");
        for (NonTeachingDay n : days) {
            sb.append("\n• ").append(n.day.format(Dates.SHORT));
            if (n.until != null) sb.append("–").append(n.until.format(Dates.SHORT));
            sb.append(" ").append(n.title == null ? "" : n.title);
            if (n.teacherId != null) sb.append(" (личный)");
            if (n.grades != null) sb.append(" (классы: ").append(n.grades).append(")");
            if (n.teacherId != null || isAdmin(uid)) {
                rows.add(btn("🗑 " + n.day.format(Dates.SHORT) + " " + Kb.trunc(n.title == null ? "" : n.title, 30),
                        "cal:del:" + n.id));
            }
        }
        sb.append("\n\nПереносы выходных на 2027 год публикуют в конце 2026 года: добавьте их здесь, даты уроков "
                + "пересчитаются сами.");
        rows.add(btn("➕ Добавить выходной", "cal:add"));
        bot.send(chat, Kb.trunc(sb.toString(), 3900), rows.build());
    }

    void onCallback(long uid, long chat, long msgId, String cbId, String[] p) {
        bot.answer(cbId, null);
        switch (p[1]) {
            case "add" -> {
                ConvState.set(uid, CAL_ADD, null);
                bot.send(chat, "Напишите дату или период и название, например:\n"
                        + "07.10 семинар\n12.10-14.10 больничный\nОтмена: /cancel", null);
            }
            case "list" -> show(uid, chat);
            case "me", "all" -> {
                ConvState st = ConvState.get(uid);
                Pending pe = st == null || !CAL_SCOPE.equals(st.state) ? null : read(st.payload);
                if (pe == null) {
                    bot.send(chat, "Сессия устарела: /calendar", null);
                    return;
                }
                if ("all".equals(p[1]) && !isAdmin(uid)) return;
                ConvState.clear(uid);
                saveDay(uid, chat, pe, "all".equals(p[1]));
            }
            case "del" -> {
                NonTeachingDay n = NonTeachingDay.findById(Long.parseLong(p[2]));
                if (n == null || !mayEdit(uid, n)) return;
                bot.send(chat, "Убрать выходной " + n.day.format(Dates.SHORT) + " " + (n.title == null ? "" : n.title)
                        + "?", Kb.row(btn("Да, убрать", "cal:delok:" + n.id), btn("Нет", "cal:list")));
            }
            case "delok" -> {
                NonTeachingDay n = NonTeachingDay.findById(Long.parseLong(p[2]));
                if (n == null || !mayEdit(uid, n)) return;
                n.delete();
                LOG.infof("[calendar] user=%d removed day off %s", uid, n.day);
                bot.send(chat, "Убрал. " + recalc(uid, n.teacherId == null), null);
            }
            default -> { }
        }
    }

    void onText(long uid, long chat, ConvState st, String text) {
        if (!CAL_ADD.equals(st.state)) {
            bot.send(chat, "Нажмите одну из кнопок выше или /cancel.", null);
            return;
        }
        Matcher m = ENTRY.matcher(text);
        boolean ok = m.matches();
        LocalDate today = time.today();
        Optional<LocalDate> from = ok ? SlotService.date(m.group(1), today) : Optional.empty();
        Optional<LocalDate> to = ok && m.group(2) != null ? SlotService.date(m.group(2), today) : Optional.empty();
        if (from.isEmpty() || (m.group(2) != null && (to.isEmpty() || to.get().isBefore(from.get())))) {
            bot.send(chat, "Не понял дату. Пример: «07.10 семинар» или «12.10-14.10 больничный».", null);
            return;
        }
        Pending pe = new Pending();
        pe.from = from.get().toString();
        pe.to = to.map(LocalDate::toString).orElse(null);
        pe.title = m.group(3).isBlank() ? "Выходной" : m.group(3).strip();
        if (!isAdmin(uid)) {
            ConvState.clear(uid);
            saveDay(uid, chat, pe, false);
            return;
        }
        try {
            ConvState.set(uid, CAL_SCOPE, mapper.writeValueAsString(pe));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        bot.send(chat, "Для кого этот выходной?", Kb.row(btn("Только для меня", "cal:me"),
                btn("Для всех учителей", "cal:all")));
    }

    private void saveDay(long uid, long chat, Pending pe, boolean everybody) {
        LocalDate from = LocalDate.parse(pe.from);
        LocalDate to = pe.to == null ? from : LocalDate.parse(pe.to);
        List<KtpSlot> hit = KtpSlot.live(uid, from, to);
        NonTeachingDay n = new NonTeachingDay();
        n.day = from;
        n.until = pe.to == null ? null : to;
        n.title = pe.title;
        n.kind = everybody ? "holiday" : NonTeachingDay.PERSONAL;
        n.teacherId = everybody ? null : uid;
        n.persist();
        LOG.infof("[calendar] user=%d added day off %s..%s \"%s\" everybody=%s, %d lesson(s) affected", uid, from, to,
                pe.title, everybody, hit.size());
        StringBuilder sb = new StringBuilder("✅ Добавил: ").append(from.format(Dates.SHORT));
        if (pe.to != null) sb.append("–").append(to.format(Dates.SHORT));
        sb.append(" ").append(pe.title).append(everybody ? " (для всех)" : " (личный)").append(".");
        if (!hit.isEmpty()) {
            sb.append("\nВ эти дни были уроки: ").append(hit.size()).append(". Сдвигаю их на ближайшие свободные.");
        }
        bot.send(chat, sb + "\n\n" + recalc(uid, everybody), null);
    }

    /** Dates are placed again after the calendar changed; the text tells what came out. */
    private String recalc(long uid, boolean forEverybody) {
        if (forEverybody) {
            // a day off for all teachers moves the lessons of the other teachers too (silently)
            for (Teacher t : Teacher.<Teacher>listAll()) {
                if (t.id != uid) slots.rebuildAll(t.id);
            }
        }
        var built = slots.rebuildAll(uid);
        return built.isEmpty() ? "Дат уроков по КТП пока нет, пересчитывать нечего." : SlotService.describeAll(built);
    }

    private boolean mayEdit(long uid, NonTeachingDay n) {
        return n.teacherId == null ? isAdmin(uid) : n.teacherId == uid;
    }

    private boolean isAdmin(long uid) {
        List<Long> explicit = adminIds.orElse(List.of());
        if (!explicit.isEmpty()) return explicit.contains(uid);
        List<Long> allowed = allowedIds.orElse(List.of());
        return !allowed.isEmpty() && allowed.get(0) == uid;
    }

    private Pending read(String payload) {
        try {
            return payload == null ? null : mapper.readValue(payload, Pending.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
