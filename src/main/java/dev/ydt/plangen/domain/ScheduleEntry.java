package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "schedule_entries")
public class ScheduleEntry extends PanacheEntityBase {

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

    public static List<ScheduleEntry> forDay(long teacherId, int weekday) {
        return list("teacherId = ?1 and weekday = ?2 order by lessonNo, classId", teacherId, weekday);
    }

    public static List<ScheduleEntry> forTeacher(long teacherId) {
        return list("teacherId = ?1 order by weekday, lessonNo, classId", teacherId);
    }
}
