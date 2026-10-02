package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;

/** A book the teacher uses with a class. /plan then offers these books first. */
@Entity
@Table(name = "class_books")
public class ClassBook extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "class_id")
    public long classId;

    @Column(name = "book_id")
    public long bookId;

    public static ClassBook find(long classId, long bookId) {
        return find("classId = ?1 and bookId = ?2", classId, bookId).firstResult();
    }

    public static List<ClassBook> ofClass(long classId) {
        return list("classId = ?1", classId);
    }
}
