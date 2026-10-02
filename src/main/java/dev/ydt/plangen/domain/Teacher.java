package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "teachers")
public class Teacher extends PanacheEntityBase {

    @Id
    public Long id; // Telegram user id

    @Column(name = "full_name")
    public String fullName;

    public String school;

    public String subject;

    /** Plan language: en | ru | kk. English by default (the teacher teaches English). */
    public String lang = "en";

    @Column(name = "lesson_minutes")
    public int lessonMinutes = 45;

    @Column(name = "created_at")
    public Instant createdAt = Instant.now();

    public boolean isComplete() {
        return notBlank(fullName) && notBlank(school) && notBlank(subject);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
