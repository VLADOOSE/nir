package com.vladoose.nir.email;

import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.EmailTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class EmailTemplateEndpointTest {

    @Autowired EmailTemplateRepository repository;

    @Test
    void savesAndFindsByMarket() {
        repository.save(EmailTemplate.builder()
                .market(Market.KZ).subjectTemplate("S").bodyTemplate("B").build());
        assertThat(repository.findByMarket(Market.KZ)).isPresent()
                .get().extracting(EmailTemplate::getBodyTemplate).isEqualTo("B");
        assertThat(repository.findByMarket(Market.RF)).isEmpty();
    }
}
