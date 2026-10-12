package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** V27: настройки запроса цен, отметки поставщика и тексты запроса на бланке читаются обратно; умолчания базы — «нет». */
@SpringBootTest
@Transactional
class PrivateRequestStage2SchemaTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired EmailTemplateRepository templateRepository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void newColumnsRoundTrip() {
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ V27 " + System.nanoTime())
                .trusted(true).manufacturer(true).build());
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("ZZ-V27-1").status("NEW")
                .source(Source.PRIVATE_REQUEST).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT")
                .discloseClient(true).withDocument(true).withStamp(true).emailOverride(true).sentTo("x@zz.kz").build());
        templateRepository.deleteAllInBatch();   // строка рынка одна (UNIQUE market): в тесте — своя, откатится
        templateRepository.save(EmailTemplate.builder().market(Market.KZ).subjectTemplate("Т").bodyTemplate("Б")
                .rfqTitle("ЗАПРОС").rfqIntro("Вводная").rfqAsk("Модель\nЦена").rfqClientLine("Для: {{клиент}}").build());
        em.flush();
        em.clear();

        Distributor dBack = distributorRepository.findById(d.getId()).orElseThrow();
        assertThat(dBack.isTrusted()).isTrue();
        assertThat(dBack.isManufacturer()).isTrue();
        PriceRequest prBack = priceRequestRepository.findById(pr.getId()).orElseThrow();
        assertThat(prBack.isDiscloseClient()).isTrue();
        assertThat(prBack.isWithDocument()).isTrue();
        assertThat(prBack.isWithStamp()).isTrue();
        assertThat(prBack.isEmailOverride()).isTrue();
        EmailTemplate tBack = templateRepository.findByMarket(Market.KZ).orElseThrow();
        assertThat(tBack.getRfqTitle()).isEqualTo("ЗАПРОС");
        assertThat(tBack.getRfqIntro()).isEqualTo("Вводная");
        assertThat(tBack.getRfqAsk()).isEqualTo("Модель\nЦена");
        assertThat(tBack.getRfqClientLine()).isEqualTo("Для: {{клиент}}");
    }

    @Test
    void databaseDefaultsAreFalse() {
        String name = "ZZ V27 по умолчанию " + System.nanoTime();
        jdbc.update("INSERT INTO distributor (name, market) VALUES (?, 'KZ')", name);
        assertThat(jdbc.queryForObject("SELECT trusted OR manufacturer FROM distributor WHERE name = ?", Boolean.class, name))
                .isFalse();
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("ZZ-V27-2").status("NEW")
                .source(Source.PRIVATE_REQUEST).build());
        em.flush();
        Long distId = jdbc.queryForObject("SELECT id FROM distributor WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO price_request (tender_id, distributor_id, status, market) VALUES (?, ?, 'SENT', 'KZ')",
                t.getId(), distId);
        assertThat(jdbc.queryForObject("SELECT disclose_client OR with_document OR with_stamp OR email_override "
                + "FROM price_request WHERE tender_id = ?", Boolean.class, t.getId())).isFalse();
    }
}
