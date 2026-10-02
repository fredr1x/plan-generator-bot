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

/** The KTP lesson that a class has on a given day and lesson number. */
@Entity
@Table(name = "ktp_slots")
public class KtpSlot extends PanacheEntityBase {

    public static final String PLANNED = "planned";
    public static final String PREPARED = "prepared";
    public static final String DONE = "done";
    public static final String SKIPPED = "skipped";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "ktp_id")
    public long ktpId;

    @Column(name = "class_id")
    public long classId;

    @Column(name = "lesson_id")
    public long lessonId;

    @Column(name = "slot_date")
    public LocalDate slotDate;

    @Column(name = "lesson_no")
    public int lessonNo;

    public String status = PLANNED;

    @Column(name = "plan_id")
    public Long planId;

    /** Live slots (planned or already prepared) of the teacher's classes in a date range, in lesson order. */
    public static List<KtpSlot> live(long teacherId, LocalDate from, LocalDate to) {
        return list("slotDate >= ?1 and slotDate <= ?2 and status in ('planned', 'prepared') "
                + "and classId in (select c.id from SchoolClass c where c.teacherId = ?3) "
                + "order by slotDate, lessonNo, classId", from, to, teacherId);
    }
}
