package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;

/** The hours a KTP gives to a term (quarter), as written in the file. */
@Entity
@Table(name = "ktp_terms")
public class KtpTerm extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "ktp_id")
    public long ktpId;

    @Column(name = "term_no")
    public int termNo;

    public Integer hours;

    public static List<KtpTerm> ofKtp(long ktpId) {
        return list("ktpId = ?1 order by termNo", ktpId);
    }
}
