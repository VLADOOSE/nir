package com.vladoose.nir.repository;

import com.vladoose.nir.entity.WhatsappInboxEvent;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface WhatsappInboxRepository extends JpaRepository<WhatsappInboxEvent, Long> {

    /** Следующее к разбору: самое раннее из «отлежавшихся» до settled (спека §5.2: правка не обгонит оригинал). */
    @Query(value = """
            SELECT * FROM whatsapp_inbox
            WHERE status = 'PENDING' AND provider = :provider AND event_at <= :settled
            ORDER BY event_at, id
            LIMIT 1""", nativeQuery = true)
    Optional<WhatsappInboxEvent> findNextPending(@Param("provider") String provider, @Param("settled") OffsetDateTime settled);

    @Modifying
    @Query("delete from WhatsappInboxEvent e where e.status = :status and e.processedAt < :before")
    int deleteProcessedBefore(@Param("status") WhatsappInboxStatus status, @Param("before") OffsetDateTime before);

    List<WhatsappInboxEvent> findByRequestId(String requestId);

    List<WhatsappInboxEvent> findByMessageKey(String messageKey);
}
