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

/** One term of a school year; the gaps between terms are vacations. */
@Entity
@Table(name = "school_terms")
public class SchoolTerm extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "year_name")
    public String yearName;

    @Column(name = "term_no")
    public int termNo;

    @Column(name = "starts_on")
    public LocalDate startsOn;

    @Column(name = "ends_on")
    public LocalDate endsOn;

    public boolean contains(LocalDate d) {
        return !d.isBefore(startsOn) && !d.isAfter(endsOn);
    }

    public static List<SchoolTerm> all() {
        return list("order by startsOn");
    }
}
