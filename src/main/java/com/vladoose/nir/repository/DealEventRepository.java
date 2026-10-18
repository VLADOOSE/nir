package com.vladoose.nir.repository;

import com.vladoose.nir.entity.DealEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DealEventRepository extends JpaRepository<DealEvent, Long> {
    List<DealEvent> findByTenderIdOrderByOccurredAtAscIdAsc(Long tenderId);
    Optional<DealEvent> findFirstByTenderIdAndCodeOrderByOccurredAtDescIdDesc(Long tenderId, String code);
}
