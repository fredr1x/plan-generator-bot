package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** A day or a range of days without lessons: a holiday, an extra vacation of some grades, a personal day off. */
@Entity
@Table(name = "non_teaching_days")
public class NonTeachingDay extends PanacheEntityBase {

    public static final String PERSONAL = "personal";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public LocalDate day;

    /** Last day of a range; null for a single day. */
    public LocalDate until;

    public String title;

    public String kind = "holiday";

    /** Null = every grade, otherwise "1" or "1,2". */
    public String grades;

    @Column(name = "teacher_id")
    public Long teacherId;

    public LocalDate last() {
        return until == null ? day : until;
    }

    public boolean covers(LocalDate d) {
        return !d.isBefore(day) && !d.isAfter(last());
    }

    public boolean appliesTo(int grade) {
        return grades == null || grades.isBlank()
                || Arrays.stream(grades.split(",")).map(String::strip).anyMatch(g -> g.equals(String.valueOf(grade)));
    }

    /** Public days and the personal days of this teacher, from the given date on. */
    public static List<NonTeachingDay> visible(long teacherId, LocalDate from) {
        return list("(teacherId is null or teacherId = ?1) and (coalesce(until, day) >= ?2) order by day",
                teacherId, from);
    }
}
