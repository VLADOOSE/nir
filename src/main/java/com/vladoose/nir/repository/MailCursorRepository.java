package com.vladoose.nir.repository;

import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.entity.MailCursorKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public interface MailCursorRepository extends JpaRepository<MailCursor, MailCursorKey> {

    /**
     * Сдвиг курсора папки: в пределах одного UIDVALIDITY назад не ходит, новый UIDVALIDITY — отсчёт заново. Зовётся
     * внутри транзакции записи письма — курсор встаёт вместе с письмом.
     */
    default void advance(MailCursorKey key, long uidValidity, long uid) {
        MailCursor c = findById(key).orElse(null);
        if (c == null || c.getUidValidity() != uidValidity) {
            c = MailCursor.builder().mailbox(key.getMailbox()).folder(key.getFolder())
                    .uidValidity(uidValidity).lastUid(uid).build();
        } else if (uid > c.getLastUid()) {
            c.setLastUid(uid);
        }
        c.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        save(c);
    }
}
