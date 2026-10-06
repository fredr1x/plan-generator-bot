package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.List;

/**
 * One lesson of the weekly timetable. The timetable has versions: a row belongs to the version that starts on
 * {@code validFrom} and lasts until the next version of the teacher starts.
 */
@Entity
@Table(name = "schedule_entries")
public class ScheduleEntry extends PanacheEntityBase {

    /** The first version of a timetable covers the whole past. */
    public static final LocalDate ALWAYS = LocalDate.of(2000, 1, 1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "teacher_id")
    public long teacherId;

    /** 1 = Monday .. 7 = Sunday. */
    public int weekday;

    @Column(name = "lesson_no")
    public int lessonNo;

    @Column(name = "class_id")
    public long classId;

    @Column(name = "valid_from")
    public LocalDate validFrom = ALWAYS;

    /** The dates from which the teacher's timetable versions start, oldest first. */
    public static List<LocalDate> versionDates(long teacherId) {
        return getEntityManager()
                .createQuery("select distinct e.validFrom from ScheduleEntry e where e.teacherId = ?1 "
                        + "order by e.validFrom", LocalDate.class)
                .setParameter(1, teacherId)
                .getResultList();
    }

    /** The start date of the version that is in force on the date, or null when there is none yet. */
    public static LocalDate versionAt(long teacherId, LocalDate date) {
        LocalDate found = null;
        for (LocalDate v : versionDates(teacherId)) {
            if (!v.isAfter(date)) found = v;
        }
        return found;
    }

    /** The whole timetable that is in force on the date. */
    public static List<ScheduleEntry> at(long teacherId, LocalDate date) {
        LocalDate v = versionAt(teacherId, date);
        return v == null ? List.of()
                : list("teacherId = ?1 and validFrom = ?2 order by weekday, lessonNo, classId", teacherId, v);
    }

    /** The lessons of one weekday in the timetable that is in force on the date. */
    public static List<ScheduleEntry> forDay(long teacherId, int weekday, LocalDate date) {
        return at(teacherId, date).stream().filter(e -> e.weekday == weekday).toList();
    }
}
