package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Все выборки — HQL, поэтому рыночный фильтр аспекта их режет (§6 CLAUDE.md). */
public interface LeadRepository extends JpaRepository<Lead, Long> {

    boolean existsBySourceAndExternalId(String source, String externalId);

    Optional<Lead> findBySourceAndExternalId(String source, String externalId);

    List<Lead> findByStatusInOrderByReceivedAtDescIdDesc(Collection<LeadStatus> statuses, Pageable pageable);

    List<Lead> findByStatusInAndChannelOrderByReceivedAtDescIdDesc(Collection<LeadStatus> statuses,
                                                                  LeadChannel channel, Pageable pageable);

    /**
     * «Активные» (2026-10-16): статусы open — и «превращённые» (converted), у которых частная заявка ещё в работе
     * (tender.status = requestOpen). Превращённое без заявки и с закрытой заявкой — не активно.
     */
    @Query("select l from Lead l left join l.privateRequest pr "
            + "where l.status in :open or (l.status = :converted and pr.status = :requestOpen) "
            + "order by l.receivedAt desc, l.id desc")
    List<Lead> findActive(@Param("open") Collection<LeadStatus> open, @Param("converted") LeadStatus converted,
                          @Param("requestOpen") String requestOpen, Pageable pageable);

    @Query("select l from Lead l left join l.privateRequest pr "
            + "where l.channel = :channel "
            + "and (l.status in :open or (l.status = :converted and pr.status = :requestOpen)) "
            + "order by l.receivedAt desc, l.id desc")
    List<Lead> findActiveByChannel(@Param("open") Collection<LeadStatus> open, @Param("converted") LeadStatus converted,
                                   @Param("requestOpen") String requestOpen, @Param("channel") LeadChannel channel,
                                   Pageable pageable);

    long countByStatus(LeadStatus status);

    List<Lead> findTop5ByPhoneNormAndIdNotOrderByReceivedAtDesc(String phoneNorm, Long id);

    List<Lead> findBySourceAndExtStatusPendingIsNotNull(String source);

    Optional<Lead> findFirstByPrivateRequestId(Long privateRequestId);

    List<Lead> findByChatIdAndStatusIn(Long chatId, Collection<LeadStatus> statuses);

    List<Lead> findByPhoneNormAndStatusIn(String phoneNorm, Collection<LeadStatus> statuses);

    List<Lead> findByChatIdIn(Collection<Long> chatIds);

    /** Обращения адреса почты (переписка info@ склеивается с заявкой с сайта того же адреса) — без учёта регистра. */
    List<Lead> findByContactEmailIgnoreCaseAndStatusIn(String contactEmail, Collection<LeadStatus> statuses);

    List<Lead> findByPrivateRequestIdIn(Collection<Long> privateRequestIds);
}
