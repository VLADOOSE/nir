package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** V26: новые колонки заявки, позиции и запроса цен читаются обратно; у даты создания есть умолчание базы. */
@SpringBootTest
@Transactional
class PrivateRequestSchemaTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void newColumnsRoundTrip() {
        Tender t = Tender.builder().tenderNumber("ZZ-SCHEMA-1").status(PrivateRequestStatus.NEW.name())
                .source(Source.PRIVATE_REQUEST).contactName("Айгуль Сапарова").build();
        t.getLots().add(TenderLot.builder().tender(t).lotNumber(1).equipName("Тонометр OMRON M2").manufact("OMRON")
                .quantity(5).unit("упак").productUrl("https://westmed.kz/product/omron-m2")
                .regStatus(LineRegStatus.SUGGESTED).regNumber("РК-МИ-ZZ-1").regScore(new BigDecimal("0.812")).build());
        tenderRepository.save(t);
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Схема поставщик " + System.nanoTime()).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT")
                .replyBy(LocalDate.of(2026, 10, 15)).sentTo("a@b.kz").letterStatus(LetterStatus.SENT).build());
        em.flush();
        em.clear();

        Tender back = tenderRepository.findById(t.getId()).orElseThrow();
        assertThat(back.getContactName()).isEqualTo("Айгуль Сапарова");
        assertThat(back.getCreatedAt()).isNotNull();
        TenderLot lot = back.getLots().get(0);
        assertThat(lot.getUnit()).isEqualTo("упак");
        assertThat(lot.getProductUrl()).isEqualTo("https://westmed.kz/product/omron-m2");
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.SUGGESTED);
        assertThat(lot.getRegNumber()).isEqualTo("РК-МИ-ZZ-1");
        assertThat(lot.getRegScore()).isEqualByComparingTo("0.812");
        PriceRequest prBack = priceRequestRepository.findById(pr.getId()).orElseThrow();
        assertThat(prBack.getReplyBy()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(prBack.getSentTo()).isEqualTo("a@b.kz");
        assertThat(prBack.getLetterStatus()).isEqualTo(LetterStatus.SENT);
    }

    @Test
    void createdAtHasDatabaseDefaultForRawInserts() {
        String def = jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
                + "WHERE table_name = 'tender' AND column_name = 'created_at'", String.class);
        assertThat(def).contains("now()");
    }

    @Test
    void privateRequestStatusReadsUnknownAsNew() {
        assertThat(PrivateRequestStatus.of(null)).isEqualTo(PrivateRequestStatus.NEW);
        assertThat(PrivateRequestStatus.of("ACTIVE")).isEqualTo(PrivateRequestStatus.NEW);
        assertThat(PrivateRequestStatus.of("WON").closed()).isTrue();
        assertThat(PrivateRequestStatus.NEW.closed()).isFalse();
    }
}
