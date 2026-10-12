package dev.ydt.plangen.bot;

import dev.ydt.plangen.domain.NonTeachingDay;
import dev.ydt.plangen.domain.ScheduleEntry;
import dev.ydt.plangen.domain.SchoolTerm;
import dev.ydt.plangen.domain.Teacher;
import dev.ydt.plangen.domain.TeacherLink;
import dev.ydt.plangen.domain.TeacherSettings;
import dev.ydt.plangen.service.SlotService;
import dev.ydt.plangen.service.TimeService;
import dev.ydt.plangen.telegram.Messenger;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Looks every five minutes whether a teacher's evening time has come. Once per teacher and day (the claim is a
 * row in scheduler_runs, so a restart cannot repeat it) it sends the suggestion for the nearest lesson day.
 * The free hosting sleeps, but the external pinger keeps the service awake from 05:00 to 23:45; a run that was
 * missed is made up as soon as the service is awake again the same evening.
 */
@ApplicationScoped
public class EveningJob {

    private static final Logger LOG = Logger.getLogger(EveningJob.class);

    /** A suggestion is only sent when the next lesson day is this close (Friday evening: Monday). */
    private static final int MAX_DAYS_AHEAD = 3;

    @Inject
    TimeService time;

    @Inject
    EveningFlow evening;

    @Inject
    SlotService slots;

    @Inject
    Messenger bot;

    private ScheduledExecutorService scheduler;

    void onStart(@Observes StartupEvent e) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evening-job");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, 1, 5, TimeUnit.MINUTES);
    }

    void onStop(@Observes ShutdownEvent e) {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void tick() {
        var ctx = Arc.container().requestContext();
        ctx.activate();
        try {
            List<Long> teachers = QuarkusTransaction.requiringNew().call(
                    () -> Teacher.<Teacher>listAll().stream().map(t -> t.id).toList());
            for (Long id : teachers) {
                try {
                    QuarkusTransaction.requiringNew().run(() -> runFor(id));
                } catch (RuntimeException ex) {
                    LOG.errorf(ex, "[evening-job] teacher %d failed", id);
                }
            }
        } catch (RuntimeException ex) {
            LOG.error("[evening-job] tick failed", ex);
        } finally {
            ctx.terminate();
        }
    }

    /** Public so that the same check can be started by hand. */
    void runFor(long uid) {
        TeacherSettings st = TeacherSettings.of(uid);
        if (TeacherSettings.OFF.equals(st.mode)) return;
        LocalDateTime now = LocalDateTime.now(time.zone());
        if (now.toLocalTime().isBefore(st.eveningTime)) return;
        LocalDate today = now.toLocalDate();

        Optional<LocalDate> next = slots.nextLessonDay(uid, today);
        if (next.isPresent() && next.get().isBefore(today.plusDays(MAX_DAYS_AHEAD + 1))) {
            if (claim(today, uid, "evening")) {
                LOG.infof("[evening-job] user=%d: lessons on %s, mode=%s", uid, next.get(), st.mode);
                List<Long> chats = TeacherLink.chatsOf(uid);
                if (today.getDayOfWeek().getValue() >= 6 && !TeacherSettings.AUTO.equals(st.mode)) {
                    // weekend: the whole next week with the "make all plans" button
                    for (Long chatId : chats) evening.week(uid, chatId, 1);
                    return;
                }
                evening.propose(uid, chats.get(0), next.get(), true);
                if (!TeacherSettings.AUTO.equals(st.mode)) {
                    for (Long other : chats.subList(1, chats.size())) {
                        evening.propose(uid, other, next.get(), false);
                    }
                }
            }
            return;
        }
        // nothing to prepare: say once if tomorrow is a day off on which the timetable has lessons
        LocalDate tomorrow = today.plusDays(1);
        boolean inTerm = SchoolTerm.all().stream().anyMatch(t -> t.contains(tomorrow));
        if (!inTerm || ScheduleEntry.forDay(uid, tomorrow.getDayOfWeek().getValue(), tomorrow).isEmpty()) return;
        NonTeachingDay off = NonTeachingDay.visible(uid, tomorrow).stream().filter(n -> n.covers(tomorrow))
                .findFirst().orElse(null);
        if (off != null && claim(today, uid, "holiday")) {
            for (Long chatId : TeacherLink.chatsOf(uid)) {
                bot.send(chatId, "Завтра выходной: " + (off.title == null ? "день без уроков" : off.title)
                        + ". Уроки по КТП сдвинуты на следующие дни.", null);
            }
        }
    }

    /** True for the first caller of the day: the row is the lock. */
    private boolean claim(LocalDate day, long uid, String kind) {
        int n = TeacherSettings.getEntityManager().createNativeQuery(
                        "insert into plangen.scheduler_runs (run_date, teacher_id, kind) values (?1, ?2, ?3) "
                                + "on conflict do nothing")
                .setParameter(1, day).setParameter(2, uid).setParameter(3, kind).executeUpdate();
        return n > 0;
    }
}
