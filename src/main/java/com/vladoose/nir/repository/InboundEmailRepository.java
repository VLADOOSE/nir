package com.vladoose.nir.repository;

import com.vladoose.nir.entity.InboundEmail;
import com.vladoose.nir.entity.NotifyStatus;
import com.vladoose.nir.service.mail.PendingNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InboundEmailRepository extends JpaRepository<InboundEmail, Long> {
    /** «Входящие» — последние 300 писем рынка, новые сверху: список не растёт без предела. */
    List<InboundEmail> findTop300ByOrderByReceivedAtDesc();

    boolean existsByMailboxAndMessageId(String mailbox, String messageId);

    @Query("select new com.vladoose.nir.service.mail.PendingNotification(e.id, e.notifyText, e.notifySilent, e.notifyQueuedAt) "
            + "from InboundEmail e where e.notifyStatus = :status order by e.id")
    List<PendingNotification> findPendingNotifications(@Param("status") NotifyStatus status, Pageable page);

    long countByNotifyStatus(NotifyStatus status);
}
