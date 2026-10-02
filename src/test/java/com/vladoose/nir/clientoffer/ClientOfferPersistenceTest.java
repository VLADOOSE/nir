package com.vladoose.nir.clientoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.CompanyProfileRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V23: сид реквизитов обоих рынков, JSON-колонки, строки через коллекцию, журнал по рынку (§6 CLAUDE.md). */
@SpringBootTest
@Transactional
class ClientOfferPersistenceTest {

    @Autowired CompanyProfileRepository profiles;
    @Autowired ClientOfferRepository offers;
    @Autowired EntityManager em;

    @AfterEach
    void clearMarket() {
        MarketContext.clear();
    }

    @Test
    void seedHasWestMedAndRegionMedProfiles() {
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        CompanyProfile rf = profiles.findByMarket(Market.RF).orElseThrow();
        // Всегда — только то, чего не ломает ни одна законная правка. nextNumber — счётчик: выдача «исх. №» двигает его
        // мимо отметок времени, поэтому точного значения у него нет и в ветке сида.
        assertThat(kz.getShortName()).isNotBlank();
        assertThat(kz.getNextNumber()).isPositive();
        assertThat(rf.getShortName()).isNotBlank();
        assertThat(rf.getNextNumber()).isPositive();

        // Точные значения V23 — только у нетронутой строки: nirdb постоянная, а реквизиты правят проверки вживую и
        // оператор со страницы реквизитов. Сам V23 на существующей базе не изменится (контрольные суммы Flyway),
        // а на свежей базе эта ветка выполняется всегда.
        if (untouchedSinceSeed(kz)) {
            assertThat(kz.getShortName()).isEqualTo("ТОО «West-Med»");
            assertThat(kz.getHeaderLeft()).isEqualTo("Жауапкершілігі\nшектеулі серіктестігі");
            assertThat(kz.getIdsLine()).isEqualTo("РНН 271 800 059 535 БИН 121 040 000 303");
            assertThat(kz.getBankName()).isEqualTo("АО \"Alatau City Bank\"");
            assertThat(kz.getBik()).isEqualTo("TSESKZKA");
            assertThat(kz.getVatRates()).containsExactly(new BigDecimal("5"), new BigDecimal("16"), null);
            assertThat(kz.getVatDefault()).isEqualByComparingTo("5");
            assertThat(kz.getVatRegistered()).isEqualByComparingTo("5");
            assertThat(kz.getVatNotRegistrable()).isEqualByComparingTo("16");
            assertThat(kz.getDefaultColumns()).extracting(OfferColumn::getKey)
                    .containsExactly("NUM", "NAME", "UNIT", "QTY", "PRICE", "VAT_RATE", "SUM", "REGISTRATION");
            assertThat(kz.getDefaultColumns().get(4).getLabel()).isEqualTo("Цена за ед., тг");
            assertThat(kz.getDefaultTerms()).hasSize(5);
            assertThat(kz.getDefaultTermsStyle()).isEqualTo(TermsStyle.LIST);
            assertThat(kz.getStampSizeMm()).isEqualTo(40);
        }
        if (untouchedSinceSeed(rf)) {
            assertThat(rf.getShortName()).isEqualTo("ООО «РЕГИОН-МЕД»");
            assertThat(rf.getIdsLine()).isEqualTo("ИНН 6318000846 КПП 631801001 ОГРН 1146318039218");
            assertThat(rf.getVatRates()).containsExactly(null, new BigDecimal("10"), new BigDecimal("22"));
            assertThat(rf.getVatRegistered()).isNull();
        }
    }

    /** Строку не сохраняли со страницы реквизитов (updated_at) и не меняли в ней картинки (images_updated_at). */
    private static boolean untouchedSinceSeed(CompanyProfile p) {
        return p.getUpdatedAt() == null && p.getImagesUpdatedAt() == null;
    }

    @Test
    void savesOfferWithItemsJsonAndStampsMarket() {
        MarketContext.set(Market.KZ);
        ClientOffer offer = ClientOfferTestData.newOffer(7);
        offer.getItems().add(ClientOfferTestData.item(offer, 1, "Пульсоксиметр", "105000", "5"));
        offer.getItems().add(ClientOfferTestData.item(offer, 2, "Гигрометр", "13515", "16"));
        offers.saveAndFlush(offer);
        em.clear();

        ClientOffer back = offers.findById(offer.getId()).orElseThrow();
        assertThat(back.getMarket()).isEqualTo(Market.KZ);
        assertThat(back.getTableColumns()).extracting(OfferColumn::getKey).containsExactly("NUM", "NAME", "SUM");
        assertThat(back.getTerms()).extracting(OfferTerm::getValue).containsExactly("100% предоплата");
        assertThat(back.getItems()).extracting(ClientOfferItem::getName).containsExactly("Пульсоксиметр", "Гигрометр");
        ClientOfferItem first = back.getItems().get(0);
        assertThat(first.getKind()).isEqualTo(ClientOfferItemKind.ITEM);
        assertThat(first.isPurchaseVatSame()).isTrue();
        assertThat(first.getUnit()).isEqualTo("шт");
        assertThat(first.getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.UNCHECKED);
        assertThat(back.getVersion()).isZero();
        assertThat(back.getCreatedAt()).isNotNull();
    }

    @Test
    void journalIsScopedToMarket() {
        MarketContext.set(Market.KZ);
        ClientOffer kz = offers.saveAndFlush(ClientOfferTestData.newOffer(901));
        MarketContext.set(Market.RF);
        ClientOffer rf = offers.saveAndFlush(ClientOfferTestData.newOffer(902));

        List<ClientOffer> rfJournal = offers.findJournal(EnumSet.allOf(ClientOfferStatus.class), PageRequest.of(0, 300));
        assertThat(rfJournal).extracting(ClientOffer::getId).contains(rf.getId()).doesNotContain(kz.getId());
    }

    @Test
    void searchFindsByItemNameAndNumber() {
        MarketContext.set(Market.KZ);
        ClientOffer offer = ClientOfferTestData.newOffer(4431);
        offer.getItems().add(ClientOfferTestData.item(offer, 1, "Аппарат ИВЛ А-ИВЛ-Э-03", "2721000", "5"));
        offers.saveAndFlush(offer);

        var statuses = EnumSet.allOf(ClientOfferStatus.class);
        assertThat(offers.searchJournal(statuses, "%ивл%", null, PageRequest.of(0, 300)))
                .extracting(ClientOffer::getId).contains(offer.getId());
        assertThat(offers.searchJournal(statuses, "%нет-такого%", 4431, PageRequest.of(0, 300)))
                .extracting(ClientOffer::getId).contains(offer.getId());
        assertThat(offers.searchJournal(statuses, "%нет-такого%", null, PageRequest.of(0, 300)))
                .extracting(ClientOffer::getId).doesNotContain(offer.getId());
    }
}
