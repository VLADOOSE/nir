package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.ChatAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {

    @Query("""
           select new com.vladoose.nir.dto.response.ChatAttachmentMeta(
               a.id, a.message.id, a.fileName, a.mimeType, a.sizeBytes, a.notStoredReason)
           from ChatAttachment a where a.message.id in :messageIds""")
    List<ChatAttachmentMeta> findMetaByMessageIds(@Param("messageIds") Collection<Long> messageIds);
}
