package dev.ydt.plangen.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * A Telegram account that works with the data (classes, timetable, KTP, plans) of another account: the same
 * teacher with two accounts. The bot replaces the user id by the owner id before it looks at any data.
 */
@Entity
@Table(name = "teacher_links")
public class TeacherLink extends PanacheEntityBase {

    @Id
    @Column(name = "user_id")
    public Long userId;

    @Column(name = "owner_id")
    public Long ownerId;

    /** The id whose data this Telegram user works with: the owner when the account is linked, else the user. */
    public static long resolve(long userId) {
        TeacherLink l = findById(userId);
        return l == null ? userId : l.ownerId;
    }

    /** The chats that belong to the owner: the owner itself and every account linked to it. */
    public static List<Long> chatsOf(long ownerId) {
        List<Long> out = new ArrayList<>();
        out.add(ownerId);
        for (TeacherLink l : TeacherLink.<TeacherLink>list("ownerId", ownerId)) out.add(l.userId);
        return out;
    }
}
