package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ChatMessage;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadDirection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** У сообщений нет рыночного фильтра: сюда ходят только с id чата, уже проверенного на рынок. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    boolean existsByChatIdAndExternalId(Long chatId, String externalId);

    Optional<ChatMessage> findByChatIdAndExternalId(Long chatId, String externalId);

    @Query("select m from ChatMessage m where m.chat.id = :chatId order by m.sentAt desc, m.id desc")
    List<ChatMessage> findLatest(@Param("chatId") Long chatId, Pageable pageable);

    /** Порция перед сообщением (sentAt, id) — keyset по тому же порядку, что findLatest. */
    @Query("""
           select m from ChatMessage m where m.chat.id = :chatId
             and (m.sentAt < :sentAt or (m.sentAt = :sentAt and m.id < :id))
           order by m.sentAt desc, m.id desc""")
    List<ChatMessage> findBefore(@Param("chatId") Long chatId, @Param("sentAt") OffsetDateTime sentAt,
                                 @Param("id") Long id, Pageable pageable);

    @Query("select m from ChatMessage m where m.chat.id = :chatId and m.sentAt >= :since order by m.sentAt desc, m.id desc")
    List<ChatMessage> findSince(@Param("chatId") Long chatId, @Param("since") OffsetDateTime since, Pageable pageable);

    @Query("select m from ChatMessage m where m.chat.id = :chatId and m.sentAt > :after order by m.sentAt asc, m.id asc")
    List<ChatMessage> findEarliestAfter(@Param("chatId") Long chatId, @Param("after") OffsetDateTime after, Pageable pageable);

    @Query("select m from ChatMessage m where m.chat.id = :chatId and m.direction = :direction order by m.sentAt desc, m.id desc")
    List<ChatMessage> findLatestByDirection(@Param("chatId") Long chatId, @Param("direction") LeadDirection direction,
                                            Pageable pageable);

    /** Чаты, где текст встречается в сообщениях. Без рыночного фильтра — вызывающий пересекает с чатами своего рынка. */
    @Query("select distinct m.chat.id from ChatMessage m where lower(m.body) like lower(concat('%', :q, '%'))")
    List<Long> findChatIdsByBody(@Param("q") String q);

    /** Самое позднее сообщение номера — отметка догонки WAHA (спека whatsapp-waha §5.3). Зовётся в потоке приёма с его рынком. */
    @Query("select max(m.sentAt) from ChatMessage m where m.chat.channel = :channel and m.chat.account = :account")
    OffsetDateTime findLatestSentAt(@Param("channel") LeadChannel channel, @Param("account") String account);
}
