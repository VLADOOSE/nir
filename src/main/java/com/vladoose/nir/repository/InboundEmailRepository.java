package com.vladoose.nir.repository;

import com.vladoose.nir.entity.InboundEmail;
import com.vladoose.nir.entity.NotifyStatus;
import com.vladoose.nir.service.mail.PendingNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface InboundEmailRepository extends JpaRepository<InboundEmail, Long> {
    /** «Входящие» — последние 300 писем рынка, новые сверху: список не растёт без предела. */
    List<InboundEmail> findTop300ByOrderByReceivedAtDesc();

    /** Последние 300 писем ящика (адрес нижним регистром). */
    List<InboundEmail> findTop300ByMailboxOrderByReceivedAtDesc(String mailbox);

    /** Ящики, из которых есть письма рынка, — фильтр «Входящих». HQL: рыночный фильтр аспекта его режет. */
    @Query("select distinct e.mailbox from InboundEmail e where e.mailbox is not null order by e.mailbox")
    List<String> findMailboxes();

    boolean existsByMailboxAndMessageId(String mailbox, String messageId);

    @Query("select new com.vladoose.nir.service.mail.PendingNotification(e.id, e.notifyText, e.notifySilent, e.notifyQueuedAt) "
            + "from InboundEmail e where e.notifyStatus = :status order by e.id")
    List<PendingNotification> findPendingNotifications(@Param("status") NotifyStatus status, Pageable page);

    long countByNotifyStatus(NotifyStatus status);

    /**
     * Последний возврат по каждому запросу цен: (id запроса, время). Нативный SQL рыночным фильтром не режется — и не
     * должен: возврат на запрос РФ лежит строкой ящика KZ (R17, CLAUDE.md §8).
     */
    @Query(value = "SELECT matched_price_request_id, max(received_at) FROM inbound_email WHERE type = 'BOUNCE' "
            + "AND matched_price_request_id IN (:ids) GROUP BY matched_price_request_id", nativeQuery = true)
    List<Object[]> findLastBounces(@Param("ids") Collection<Long> ids);

    /**
     * Письма-ответы на запрос цен — для экрана «Ответ поставщика». Нативно: строка «Входящих» — рынка ЯЩИКА, а ответ на
     * запрос Регион-Мед приходит в ящик West-Med (R17, CLAUDE.md §8) — фильтр рынка скрыл бы его; сам запрос уже прошёл гард.
     */
    @Query(value = "SELECT id, received_at, from_address, subject, excerpt FROM inbound_email "
            + "WHERE matched_price_request_id = :prId AND type = 'SUPPLIER_RESPONSE' ORDER BY received_at, id", nativeQuery = true)
    List<Object[]> findSupplierReplies(@Param("prId") Long prId);
}
