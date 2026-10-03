package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

/** A calendar-thematic plan of a teacher for one grade. */
@Entity
@Table(name = "ktps")
public class Ktp extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "teacher_id")
    public long teacherId;

    public String subject;

    public Integer grade;

    /** Grades 10-11 only: natural_math or social_humanities, otherwise null. */
    public String track;

    @Column(name = "school_year")
    public String schoolYear;

    public String title;

    @Column(name = "source_name")
    public String sourceName;

    @Column(name = "total_hours")
    public int totalHours;

    @Column(name = "created_at")
    public Instant createdAt = Instant.now();

    /** "10 класс, естественно-математическое" or "4 класс". */
    public String gradeLabel() {
        return (grade == null ? "?" : grade) + " класс" + (track == null ? "" : ", " + Book.trackLabel(track));
    }

    public static List<Ktp> forTeacher(long teacherId) {
        return list("teacherId = ?1 order by grade, id", teacherId);
    }
}
