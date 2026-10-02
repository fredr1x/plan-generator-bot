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
@Table(name = "school_classes")
public class SchoolClass extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "teacher_id")
    public long teacherId;

    public int grade;

    public String letter;

    @Column(name = "student_count")
    public int studentCount;

    /** "7 А". */
    public String label() {
        return grade + " " + letter;
    }

    public static List<SchoolClass> forTeacher(long teacherId) {
        return list("teacherId = ?1 order by grade, letter", teacherId);
    }
}
