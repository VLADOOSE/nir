package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.dto.response.ChatFileRow;
import com.vladoose.nir.entity.ChatAttachment;
import com.vladoose.nir.entity.LeadDirection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {

    @Query("""
           select new com.vladoose.nir.dto.response.ChatAttachmentMeta(
               a.id, a.message.id, a.fileName, a.mimeType, a.sizeBytes, a.notStoredReason)
           from ChatAttachment a where a.message.id in :messageIds order by a.id""")
    List<ChatAttachmentMeta> findMetaByMessageIds(@Param("messageIds") Collection<Long> messageIds);

    /** Файлы сообщений чата одного направления в окне [from, to) с сохранёнными байтами — новые сверху, без байтов. */
    @Query("""
           select new com.vladoose.nir.dto.response.ChatFileRow(a.id, a.fileName, a.mimeType, a.sizeBytes,
               a.offerHintDismissed, m.sentAt, m.subject, m.body, c.channel)
           from ChatAttachment a join a.message m join m.chat c
           where c.id = :chatId and m.direction = :direction and m.sentAt >= :from and m.sentAt < :to
             and a.content is not null and m.deleted = false
           order by m.sentAt desc, a.id desc""")
    List<ChatFileRow> findOurFiles(@Param("chatId") Long chatId, @Param("direction") LeadDirection direction,
                                   @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to, Pageable page);
}
