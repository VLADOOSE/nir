package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ReplyTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplyTemplateRepository extends JpaRepository<ReplyTemplate, Long> {
    List<ReplyTemplate> findByMarketOrderByPositionAscIdAsc(Market market);
}
