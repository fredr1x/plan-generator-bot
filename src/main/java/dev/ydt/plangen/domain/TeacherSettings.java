package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;

/** Evening preparation settings of a teacher; defaults apply while there is no row. */
@Entity
@Table(name = "teacher_settings")
public class TeacherSettings extends PanacheEntityBase {

    public static final String OFF = "off";
    public static final String SUGGEST = "suggest";
    public static final String AUTO = "auto";

    @Id
    @Column(name = "teacher_id")
    public Long teacherId;

    @Column(name = "evening_time")
    public LocalTime eveningTime = LocalTime.of(18, 0);

    public String mode = SUGGEST;

    /** The date from which changes of the timetable take effect; null or in the past means the default. */
    @Column(name = "schedule_from")
    public java.time.LocalDate scheduleFrom;

    /** The stored settings or the defaults (not persisted until changed). */
    public static TeacherSettings of(long teacherId) {
        TeacherSettings s = findById(teacherId);
        if (s == null) {
            s = new TeacherSettings();
            s.teacherId = teacherId;
        }
        return s;
    }
}
