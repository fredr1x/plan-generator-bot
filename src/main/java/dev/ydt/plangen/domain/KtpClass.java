package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;

/** A class that follows a KTP (parallel classes share one plan). */
@Entity
@Table(name = "ktp_classes")
public class KtpClass extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "ktp_id")
    public long ktpId;

    @Column(name = "class_id")
    public long classId;

    public static List<KtpClass> ofKtp(long ktpId) {
        return list("ktpId = ?1", ktpId);
    }
}
