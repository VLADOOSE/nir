package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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

    long countByStatus(LeadStatus status);

    List<Lead> findTop5ByPhoneNormAndIdNotOrderByReceivedAtDesc(String phoneNorm, Long id);

    List<Lead> findBySourceAndExtStatusPendingIsNotNull(String source);

    Optional<Lead> findFirstByPrivateRequestId(Long privateRequestId);
}
