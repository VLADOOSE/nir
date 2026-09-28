package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Chat;
import com.vladoose.nir.entity.LeadChannel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** Выборки — HQL: рыночный фильтр аспекта их режет (§6 CLAUDE.md). findById фильтр обходит — гард в сервисе. */
public interface ChatRepository extends JpaRepository<Chat, Long> {

    Optional<Chat> findByChannelAndAccountAndExternalChatId(LeadChannel channel, String account, String externalChatId);

    @Query("select c from Chat c order by c.lastMessageAt desc nulls last, c.id desc")
    List<Chat> findRecent(Pageable pageable);
}
