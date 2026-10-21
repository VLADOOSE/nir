package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.LotSourcingService;
import com.vladoose.nir.service.lotmodel.LotModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Панель «КП» тендера (спека 2026-10-21-tender-telegram-bot §10 п. 2): завод бренда модели с почтой — первым и отмеченным,
 * дилеры бренда — без отметки. Бренды «ZZБренд…» — ни один бренд поставщиков nirdb в них не входит подстрокой.
 */
@SpringBootTest
@Transactional
class LotSourcingMakerTest {

    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired MedEquipmentRepository equipment;
    @Autowired LotModelService models;
    @Autowired LotSourcingService sourcing;

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void makerWithMailIsFirstAndOnlyPreselected() {
        MarketContext.set(Market.KZ);
        TenderLot lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ ГБ");
        models.apply(lot, equipment.saveAndFlush(MedEquipment.builder().name("ZZ ДКИ").manufact("ZZБренд").market(Market.KZ).build()), null);
        Distributor dealer = TgFixtures.distributor(distributors, "ZZ Дилер", "d@x.kz", false, false, "ZZБренд");
        Distributor maker = TgFixtures.distributor(distributors, "ZZ Завод", "m@x.kz", true, false, "ZZБренд");

        LotSourcingResponse r = sourcing.build(lot.getTender().getId(), List.of(lot.getId()), null);

        LotSourcingResponse.Entry first = r.getDistributors().get(0);
        assertThat(first.getDistributor().getId()).isEqualTo(maker.getId());
        assertThat(first.isPreselect()).isTrue();
        assertThat(first.getReasons().get(0).getKind()).isEqualTo("MAKER");
        assertThat(r.getDistributors()).filteredOn(e -> e.getDistributor().getId().equals(dealer.getId()))
                .singleElement().satisfies(e -> assertThat(e.isPreselect()).isFalse());
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void withoutMaker_dealerStaysPreselected() {
        MarketContext.set(Market.KZ);
        TenderLot lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ ГБ");
        models.apply(lot, equipment.saveAndFlush(MedEquipment.builder().name("ZZ ДКИ").manufact("ZZБренд2").market(Market.KZ).build()), null);
        Distributor dealer = TgFixtures.distributor(distributors, "ZZ Дилер", "d@x.kz", false, false, "ZZБренд2");
        LotSourcingResponse r = sourcing.build(lot.getTender().getId(), List.of(lot.getId()), null);
        assertThat(r.getDistributors()).filteredOn(e -> e.getDistributor().getId().equals(dealer.getId()))
                .singleElement().satisfies(e -> assertThat(e.isPreselect()).isTrue());
    }
}
