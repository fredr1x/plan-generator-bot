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

/** One lesson of a KTP: unit, topic, objectives with codes. */
@Entity
@Table(name = "ktp_lessons")
public class KtpLesson extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "ktp_id")
    public long ktpId;

    public int seq;

    @Column(name = "term_no")
    public Integer termNo;

    @Column(name = "unit_title")
    public String unitTitle;

    public String topic;

    public String objectives;

    public String codes;

    public int hours = 1;

    /** A lesson of several hours in the file is stored as {@code parts} lessons of one hour; this is number {@code part}. */
    public int part = 1;

    public int parts = 1;

    /** The topic with "(2/3)" for a lesson that is one hour of several, for lists and messages. */
    public String title() {
        return parts > 1 ? topic + " (" + part + "/" + parts + ")" : topic;
    }

    public String kind = "lesson";

    @Column(name = "source_date")
    public LocalDate sourceDate;

    @Column(name = "book_refs")
    public String bookRefs;

    public static List<KtpLesson> ofKtp(long ktpId) {
        return list("ktpId = ?1 order by seq", ktpId);
    }
}
