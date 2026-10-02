package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Выборки — HQL, поэтому рыночный фильтр аспекта их режет (§6 CLAUDE.md). findById фильтр обходит — гард в сервисе. */
public interface ClientOfferRepository extends JpaRepository<ClientOffer, Long> {

    @Query("select o from ClientOffer o left join fetch o.facility "
            + "where o.status in :statuses order by o.offerDate desc, o.id desc")
    List<ClientOffer> findJournal(@Param("statuses") Collection<ClientOfferStatus> statuses, Pageable pageable);

    /** like — уже в нижнем регистре, с экранированными % и _ и обёрнутый в %; number — точный «исх. №» или null. */
    @Query("""
            select distinct o from ClientOffer o left join fetch o.facility f left join o.items i
            where o.status in :statuses
              and (lower(coalesce(o.recipient, '')) like :like escape '\\'
                or lower(coalesce(f.name, '')) like :like escape '\\'
                or lower(coalesce(o.subject, '')) like :like escape '\\'
                or lower(i.name) like :like escape '\\'
                or o.number = :number)
            order by o.offerDate desc, o.id desc""")
    List<ClientOffer> searchJournal(@Param("statuses") Collection<ClientOfferStatus> statuses,
                                    @Param("like") String like, @Param("number") Integer number, Pageable pageable);
}
