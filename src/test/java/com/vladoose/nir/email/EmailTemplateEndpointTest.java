package com.vladoose.nir.email;

import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.EmailTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class EmailTemplateEndpointTest {

    @Autowired EmailTemplateRepository repository;
    @Autowired com.vladoose.nir.controller.EmailTemplateController controller;

    @org.junit.jupiter.api.AfterEach
    void clearMarket() { com.vladoose.nir.context.MarketContext.clear(); }

    @Test
    void savesAndFindsByMarket() {
        repository.save(EmailTemplate.builder()
                .market(Market.KZ).subjectTemplate("S").bodyTemplate("B").build());
        assertThat(repository.findByMarket(Market.KZ)).isPresent()
                .get().extracting(EmailTemplate::getBodyTemplate).isEqualTo("B");
        assertThat(repository.findByMarket(Market.RF)).isEmpty();
    }

    @Test
    void getReturnsDefaultWhenNoRow() {
        com.vladoose.nir.context.MarketContext.set(Market.KZ);
        var resp = controller.get();
        assertThat(resp.getSubject()).isEqualTo(com.vladoose.nir.service.KpEmailComposer.DEFAULT_SUBJECT);
        assertThat(resp.getBody()).contains("{{позиции}}");
        assertThat(resp.getMarket()).isEqualTo("KZ");
    }

    @Test
    void putUpsertsAndGetReturnsSaved() {
        com.vladoose.nir.context.MarketContext.set(Market.KZ);
        var req = new com.vladoose.nir.dto.request.EmailTemplateRequest();
        req.setSubject("Моя тема"); req.setBody("Тело {{позиции}} конец");
        controller.put(req);
        assertThat(controller.get().getSubject()).isEqualTo("Моя тема");
        // upsert — второй PUT не плодит строк
        req.setSubject("Тема 2"); controller.put(req);
        assertThat(repository.findByMarket(Market.KZ)).isPresent();
        assertThat(controller.get().getSubject()).isEqualTo("Тема 2");
    }

    @Test
    void putWarnsWhenBodyLacksPositions() {
        com.vladoose.nir.context.MarketContext.set(Market.KZ);
        var req = new com.vladoose.nir.dto.request.EmailTemplateRequest();
        req.setSubject("t"); req.setBody("нет плейсхолдера позиций");
        var resp = controller.put(req);
        assertThat(resp.getWarnings()).contains("no-positions");
    }

    @Test
    void defaultEndpointReturnsCodeDefault() {
        com.vladoose.nir.context.MarketContext.set(Market.RF);
        var resp = controller.getDefault();
        assertThat(resp.getSubject()).isEqualTo(com.vladoose.nir.service.KpEmailComposer.DEFAULT_SUBJECT);
        assertThat(resp.getBody()).isEqualTo(com.vladoose.nir.service.KpEmailComposer.DEFAULT_BODY);
    }
}
