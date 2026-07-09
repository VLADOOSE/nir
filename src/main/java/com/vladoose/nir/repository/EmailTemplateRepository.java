package com.vladoose.nir.repository;

import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {
    Optional<EmailTemplate> findByMarket(Market market);
}
