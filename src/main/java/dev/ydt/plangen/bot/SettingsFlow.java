package dev.ydt.plangen.bot;

import static dev.ydt.plangen.bot.Kb.btn;

import dev.ydt.plangen.domain.TeacherSettings;
import dev.ydt.plangen.telegram.Dto.InlineButton;
import dev.ydt.plangen.telegram.Messenger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/** /settings: the mode and the time of the evening preparation. Callbacks: set:m:mode, set:t:HH. */
@ApplicationScoped
public class SettingsFlow {

    private static final Logger LOG = Logger.getLogger(SettingsFlow.class);
    private static final int[] HOURS = {16, 17, 18, 19, 20, 21};

    @Inject
    Messenger bot;

    void show(long uid, long chat, Long editMsg) {
        TeacherSettings s = TeacherSettings.of(uid);
        String mode = switch (s.mode) {
            case TeacherSettings.OFF -> "выключена";
            case TeacherSettings.AUTO -> "автомат: бот сам составляет планы на ближайший учебный день и присылает файлы";
            default -> "предложения: бот присылает список уроков и ждёт кнопку «Составить все»";
        };
        String text = "⚙️ Вечерняя подготовка\n\nВремя: " + s.eveningTime + "\nРежим: " + mode
                + "\n\nБот смотрит на даты уроков из КТП (/ktp) и расписания. В пятницу готовит понедельник, в "
                + "праздники молчит. То же самое можно сделать вручную: /tomorrow.";
        Kb.Rows rows = Kb.rows().add(
                btn(mark(TeacherSettings.OFF.equals(s.mode)) + "Выкл", "set:m:off"),
                btn(mark(TeacherSettings.SUGGEST.equals(s.mode)) + "Предложения", "set:m:suggest"),
                btn(mark(TeacherSettings.AUTO.equals(s.mode)) + "Автомат", "set:m:auto"));
        List<InlineButton> times = new ArrayList<>();
        for (int h : HOURS) {
            times.add(btn(mark(s.eveningTime.getHour() == h && s.eveningTime.getMinute() == 0) + h + ":00",
                    "set:t:" + h));
        }
        rows.add(times);
        if (editMsg != null) bot.edit(chat, editMsg, text, rows.build());
        else bot.send(chat, text, rows.build());
    }

    void onCallback(long uid, long chat, long msgId, String cbId, String[] p) {
        bot.answer(cbId, null);
        TeacherSettings s = TeacherSettings.of(uid);
        if ("m".equals(p[1]) && (TeacherSettings.OFF.equals(p[2]) || TeacherSettings.SUGGEST.equals(p[2])
                || TeacherSettings.AUTO.equals(p[2]))) {
            s.mode = p[2];
        } else if ("t".equals(p[1])) {
            s.eveningTime = LocalTime.of(Integer.parseInt(p[2]), 0);
        } else {
            return;
        }
        TeacherSettings.getEntityManager().merge(s);
        LOG.infof("[settings] user=%d mode=%s time=%s", uid, s.mode, s.eveningTime);
        show(uid, chat, msgId);
    }

    private static String mark(boolean on) {
        return on ? "✅ " : "";
    }
}
