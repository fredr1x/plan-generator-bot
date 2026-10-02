package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "lesson_plans")
public class LessonPlanRecord extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "teacher_id")
    public long teacherId;

    @Column(name = "plan_date")
    public LocalDate planDate;

    public String classes;

    public int students;

    /** "7 А=25;7 Б=24": students per class, so the document can list them one by one. */
    @Column(name = "class_counts")
    public String classCounts;

    public String section;

    public String topic;

    public String objectives;

    @Column(name = "content_json")
    public String contentJson;

    public String model;

    /** Textbook pages the plan was built from: JSON like [{"bookId":1,"pages":"45-46"}]. */
    @Column(name = "book_refs")
    public String bookRefs;

    @Column(name = "tokens_in")
    public int tokensIn;

    @Column(name = "tokens_out")
    public int tokensOut;

    public int attempts = 1;

    public int millis;

    @Column(name = "created_at")
    public Instant createdAt = Instant.now();

    public static List<LessonPlanRecord> latest(long teacherId, int limit) {
        return find("teacherId = ?1 order by createdAt desc", teacherId).page(0, limit).list();
    }

    /** Model usage since the given moment: {plans, tokens in, tokens out}. Copies are not model calls. */
    public static long[] usageSince(long teacherId, Instant since) {
        Object[] row = getEntityManager().createQuery(
                        "select count(p), coalesce(sum(p.tokensIn), 0), coalesce(sum(p.tokensOut), 0) "
                                + "from LessonPlanRecord p where p.teacherId = ?1 and p.createdAt >= ?2 "
                                + "and p.model <> 'copy'", Object[].class)
                .setParameter(1, teacherId)
                .setParameter(2, since)
                .getSingleResult();
        return new long[] {((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue()};
    }
}
