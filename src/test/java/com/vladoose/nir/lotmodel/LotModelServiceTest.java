package com.vladoose.nir.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.DealEvent;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.DealEventRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.RegistryMatchService;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.lotmodel.LotModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** «Модель в работе» (спека 2026-10-20-lot-model-in-work §5): все пути — через одну службу, «кто/когда», событие. */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class LotModelServiceTest {

    static final String REG = "ZZ РК МИ (МТ)-0№099001";

    @Autowired LotModelService service;
    @Autowired RegistryMatchService registryMatch;
    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired MedRegistryRepository registry;
    @Autowired DealEventRepository events;

    TenderLot lot;
    SiteProduct dki;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Дефибриллятор");
        registry.saveAndFlush(MedRegistry.builder().regNumber(REG).name("ZZ Дефибриллятор ДКИ-Н-12").producer("Аксион")
                .unlimited(true).build());
        registry.saveAndFlush(MedRegistry.builder().regNumber("ZZ РК МИ (МТ)-0№099002").name("ZZ Дефибриллятор BeneHeart D3")
                .producer("Mindray").unlimited(true).build());
        // на сайте номер записан иначе, чем в реестре: регистр, двойной пробел, пробел в конце
        dki = products.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name("ZZ Дефибриллятор ДКИ-Н-12")
                .brand("Аксион").slug("zz-dki").shortDescription("Дефибриллятор-монитор").registrationCert("zz рк ми  (мт)-0№099001 ")
                .syncedAt(OffsetDateTime.now()).build());
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    List<DealEvent> modelEvents(TenderLot l) {
        return events.findByTenderIdOrderByOccurredAtAscIdAsc(l.getTender().getId()).stream()
                .filter(e -> DealEventService.MODEL.equals(e.getCode())).toList();
    }

    @Test
    void pinSiteProduct_linksRegistryByNormalizedNumber_andWritesEvent() {
        TenderLot r = service.pinSiteProduct(lot.getId(), dki.getId(), "  ИИ: подходит 30 из 34  ");
        MedEquipment e = r.getProposedEquipment();
        assertThat(e.getSiteProduct().getId()).isEqualTo(dki.getId());
        assertThat(e.getManufact()).isEqualTo("Аксион");
        assertThat(e.getSpec()).isEqualTo("Дефибриллятор-монитор");
        assertThat(e.getRegistration()).isNotNull();
        assertThat(e.getRegistration().getRegNumber()).isEqualTo(REG);
        assertThat(r.getModelNote()).isEqualTo("ИИ: подходит 30 из 34");
        assertThat(r.getModelSetBy()).isEqualTo("vlad");
        assertThat(r.getModelSetAt()).isNotNull();
        assertThat(modelEvents(lot)).singleElement().satisfies(ev -> {
            assertThat(ev.getText()).isEqualTo("Лот «ZZ Дефибриллятор»: модель в работе — ZZ Дефибриллятор ДКИ-Н-12 (Аксион)");
            assertThat(ev.getAuthor()).isEqualTo("vlad");
            assertThat(ev.getRefId()).isEqualTo(lot.getId());
        });
    }

    @Test
    void sameSiteProduct_onePosition_renameFollows() {
        Long first = service.pinSiteProduct(lot.getId(), dki.getId(), null).getProposedEquipment().getId();
        dki.setName("ZZ Дефибриллятор ДКИ-Н-12 «Аксион»");
        TenderLot other = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Второй лот");
        MedEquipment e = service.pinSiteProduct(other.getId(), dki.getId(), null).getProposedEquipment();
        assertThat(e.getId()).isEqualTo(first);
        assertThat(e.getName()).isEqualTo("ZZ Дефибриллятор ДКИ-Н-12 «Аксион»");
    }

    @Test
    void notes_sameModel_newModel() {
        service.pinSiteProduct(lot.getId(), dki.getId(), "почему");
        OffsetDateTime at = lot.getModelSetAt();
        service.pinSiteProduct(lot.getId(), dki.getId(), null);                       // та же модель без заметки — ничего
        assertThat(lot.getModelNote()).isEqualTo("почему");
        assertThat(lot.getModelSetAt()).isEqualTo(at);
        service.pinSiteProduct(lot.getId(), dki.getId(), "другая причина");           // та же модель с заметкой — только заметка
        assertThat(lot.getModelNote()).isEqualTo("другая причина");
        assertThat(modelEvents(lot)).hasSize(1);
        service.pinRegistry(lot.getId(), "ZZ РК МИ (МТ)-0№099002", null);            // другая модель без заметки — заметка очищена
        assertThat(lot.getModelNote()).isNull();
        assertThat(lot.getProposedEquipment().getRegistration().getRegNumber()).isEqualTo("ZZ РК МИ (МТ)-0№099002");
        assertThat(modelEvents(lot)).hasSize(2);
    }

    @Test
    void clearModel_andNoteRules() {
        service.pinRegistry(lot.getId(), REG, null);
        assertThat(service.setNote(lot.getId(), "  ИИ советует  ").getModelNote()).isEqualTo("ИИ советует");
        assertThatThrownBy(() -> service.setNote(lot.getId(), "x".repeat(1001))).isInstanceOf(BadRequestException.class);
        TenderLot r = service.clearModel(lot.getId());
        assertThat(r.getProposedEquipment()).isNull();
        assertThat(r.getModelNote()).isNull();
        assertThat(modelEvents(lot)).last().satisfies(ev -> assertThat(ev.getText()).isEqualTo("Лот «ZZ Дефибриллятор»: модель снята"));
        assertThatThrownBy(() -> service.setNote(lot.getId(), "x")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void guards() {
        TenderLot rf = LotModelFixtures.lot(tenders, Market.RF, "ZZ РФ");
        assertThatThrownBy(() -> service.pinSiteProduct(rf.getId(), dki.getId(), null)).isInstanceOf(NotFoundException.class);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.pinRegistry(rf.getId(), REG, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Только для Казахстана");
        MarketContext.set(Market.KZ);
        dki.setRemovedAt(OffsetDateTime.now());
        assertThatThrownBy(() -> service.pinSiteProduct(lot.getId(), dki.getId(), null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("снят с сайта");
        assertThatThrownBy(() -> service.pinRegistry(lot.getId(), "ZZ-НЕТ-ТАКОГО", null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void oldPathAdoptForLot_goesThroughService_once() {
        TenderLot r = registryMatch.adoptForLot(lot.getId(), REG);
        assertThat(r.getModelSetBy()).isEqualTo("vlad");
        assertThat(modelEvents(lot)).hasSize(1);
        registryMatch.adoptForLot(lot.getId(), REG);                                   // повторно — без второго события
        assertThat(modelEvents(lot)).hasSize(1);
    }
}
