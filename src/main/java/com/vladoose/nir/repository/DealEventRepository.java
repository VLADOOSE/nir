package com.vladoose.nir.repository;

import com.vladoose.nir.entity.DealEvent;
import com.vladoose.nir.entity.DealEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DealEventRepository extends JpaRepository<DealEvent, Long> {
    List<DealEvent> findByTenderIdOrderByOccurredAtAscIdAsc(Long tenderId);

    /** Письма сделки (EMAIL) — переписки, которые лента показывает с первого письма (спека 2026-10-19-client-mail-reply §7.1). */
    List<DealEvent> findByTenderIdAndTypeOrderByOccurredAtAscIdAsc(Long tenderId, DealEventType type);
    Optional<DealEvent> findFirstByTenderIdAndCodeOrderByOccurredAtDescIdDesc(Long tenderId, String code);

    /** Последняя смена статуса этого КП — у принятого сейчас КП это само принятие. */
    Optional<DealEvent> findFirstByTenderIdAndCodeAndRefIdOrderByOccurredAtDescIdDesc(Long tenderId, String code, Long refId);

    /** События одного КП (смена статуса) — одним запросом: КП удалено (DealEventService.forgetOffer). */
    @Modifying
    @Query("delete from DealEvent e where e.tenderId = :tenderId and e.code = :code and e.refId = :refId")
    int deleteRefEvents(@Param("tenderId") Long tenderId, @Param("code") String code, @Param("refId") Long refId);
}
