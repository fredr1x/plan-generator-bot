package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Dialog state kept in the DB because the instance may restart or sleep between messages. */
@Entity
@Table(name = "conv_state")
public class ConvState extends PanacheEntityBase {

    @Id
    @Column(name = "user_id")
    public long userId;

    public String state;

    public String payload;

    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();

    public static void set(long userId, String state, String payload) {
        ConvState s = new ConvState();
        s.userId = userId;
        s.state = state;
        s.payload = payload;
        getEntityManager().merge(s);
    }

    public static ConvState get(long userId) {
        return findById(userId);
    }

    public static void clear(long userId) {
        deleteById(userId);
    }
}
