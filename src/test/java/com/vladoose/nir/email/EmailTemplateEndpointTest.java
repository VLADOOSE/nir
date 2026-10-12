package com.vladoose.nir.email;

import com.vladoose.nir.dto.request.EmailTemplateRequest;
import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.service.RfqTexts;
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

    // Изоляция: убрать закоммиченные строки шаблона (оператор мог сохранить через UI → строка в БД),
    // иначе save(KZ) падает на UNIQUE, а getReturnsDefaultWhenNoRow видит сохранённую строку вместо дефолта.
    // deleteAllInBatch — НЕМЕДЛЕННЫЙ bulk DELETE (не через persistence context): иначе Hibernate ставит
    // INSERT теста перед отложенным DELETE в одном flush → конфликт UNIQUE (тот же гочи, что §ComplectWriter).
    // Внутри @Transactional-теста удаление откатывается вместе с тестом.
    @org.junit.jupiter.api.BeforeEach
    void cleanTemplates() { repository.deleteAllInBatch(); }

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

    @Test
    void rfqTextsAreDefaultsUntilSaved_textsLikeDefaultsAreNotStored() {
        com.vladoose.nir.context.MarketContext.set(Market.KZ);
        var resp = controller.get();
        assertThat(resp.getRfqTitle()).isEqualTo(RfqTexts.DEFAULT_TITLE);
        assertThat(resp.getRfqIntro()).isEqualTo(RfqTexts.DEFAULT_INTRO);
        assertThat(resp.getRfqAsk()).isEqualTo(RfqTexts.defaultAsk(Market.KZ));
        assertThat(resp.getRfqClientLine()).isEqualTo(RfqTexts.DEFAULT_CLIENT_LINE);

        EmailTemplateRequest req = new EmailTemplateRequest();
        req.setSubject("Т");
        req.setBody("{{позиции}}");
        req.setRfqTitle("ЗАПРОС ЦЕН");
        req.setRfqAsk(RfqTexts.defaultAsk(Market.KZ));           // как зашитый — не хранится
        req.setRfqClientLine("Для: {{клиент}}");
        controller.put(req);

        EmailTemplate row = repository.findByMarket(Market.KZ).orElseThrow();
        assertThat(row.getRfqTitle()).isEqualTo("ЗАПРОС ЦЕН");
        assertThat(row.getRfqAsk()).isNull();
        assertThat(row.getRfqClientLine()).isEqualTo("Для: {{клиент}}");
        assertThat(controller.get().getRfqTitle()).isEqualTo("ЗАПРОС ЦЕН");
        assertThat(controller.get().getRfqAsk()).isEqualTo(RfqTexts.defaultAsk(Market.KZ));
    }

    @Test
    void rfqFieldsNotSentKeepSavedValues() {
        com.vladoose.nir.context.MarketContext.set(Market.KZ);
        EmailTemplateRequest first = new EmailTemplateRequest();
        first.setSubject("Т");
        first.setBody("{{позиции}}");
        first.setRfqIntro("Своя вводная");
        controller.put(first);
        EmailTemplateRequest second = new EmailTemplateRequest();   // старый экран: rfq-полей не шлёт
        second.setSubject("Т2");
        second.setBody("{{позиции}}");
        controller.put(second);
        assertThat(repository.findByMarket(Market.KZ).orElseThrow().getRfqIntro()).isEqualTo("Своя вводная");
    }

    @Test
    void defaultEndpointReturnsRfqDefaultsOfTheMarket() {
        com.vladoose.nir.context.MarketContext.set(Market.RF);
        var resp = controller.getDefault();
        assertThat(resp.getRfqAsk()).isEqualTo(RfqTexts.defaultAsk(Market.RF)).contains("Росздравнадзора");
    }
}
