package com.vladoose.nir.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.lotmodel.LotModelReader;
import com.vladoose.nir.service.lotmodel.LotModelService;
import com.vladoose.nir.service.lotmodel.ModelSnapshot;
import com.vladoose.nir.service.productwatch.ProductMatchService;
import com.vladoose.nir.service.productwatch.fit.SpecUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Чтение модели в работе для сверки (спека §6): наш товар (и снятый), реестр (карточка РУ), каталог. */
@SpringBootTest
@Transactional
class LotModelReaderTest {

    @Autowired LotModelReader reader;
    @Autowired LotModelService lotModel;
    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired MedRegistryRepository registry;
    @Autowired MedEquipmentRepository equipment;

    @BeforeEach
    void kz() { MarketContext.set(Market.KZ); }

    @AfterEach
    void clear() { MarketContext.clear(); }

    @Test
    void siteProduct_removedAfterPin_cardFromDb() {
        TenderLot lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Аспиратор");
        SiteProduct p = products.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("ZZ Аспиратор хирургический").brand("ZZ").slug("zz-asp").syncedAt(OffsetDateTime.now()).build());
        lotModel.apply(lot, lotModel.equipmentForSiteProduct(p), null);
        p.setRemovedAt(OffsetDateTime.now());
        ModelSnapshot s = reader.read(lot.getId());
        assertThat(s.card()).isNotNull();
        assertThat(s.card().name()).isEqualTo("ZZ Аспиратор хирургический");
        assertThat(s.model().source()).isEqualTo("SITE");
        assertThat(s.model().url()).endsWith("zz-asp");
        assertThat(s.needsDetail()).isFalse();
    }

    @Test
    void registryModel_needsDetailUntilFetched() {
        TenderLot lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Дефибриллятор");
        MedRegistry r = registry.saveAndFlush(MedRegistry.builder().regNumber("ZZ-РУ-READ-1").name("ZZ Дефибриллятор D3")
                .producer("ZZ").unlimited(true).build());
        lotModel.apply(lot, lotModel.equipmentForRegistry(r), null);
        assertThat(reader.read(lot.getId()).needsDetail()).isTrue();
        r.setTechChars("Масса не более 6 кг");
        r.setDetailFetchedAt(OffsetDateTime.now());
        ModelSnapshot s = reader.read(lot.getId());
        assertThat(s.needsDetail()).isFalse();
        assertThat(s.model().source()).isEqualTo("REGISTRY");
        assertThat(s.model().regStatus()).isEqualTo("ACTIVE");
        assertThat(s.card().facts()).anySatisfy(f -> assertThat(f.unit()).isEqualTo(SpecUnit.KG));
    }

    @Test
    void catalogModel_cardOnlyWithSpec_andLotText() {
        TenderLot lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Монитор");
        lot.setRequiredSpec("масса не более 8 кг");
        MedEquipment bare = equipment.save(MedEquipment.builder().name("ZZ Монитор без описания").manufact("ZZ").market(Market.KZ).build());
        lotModel.apply(lot, bare, null);
        ModelSnapshot s = reader.read(lot.getId());
        assertThat(s.model().source()).isEqualTo("CATALOG");
        assertThat(s.card()).isNull();
        assertThat(s.text()).isEqualTo(ProductMatchService.lotFitText(lot));
        MedEquipment withSpec = equipment.save(MedEquipment.builder().name("ZZ Монитор 2").manufact("ZZ").market(Market.KZ)
                .spec("Масса не более 4 кг").build());
        lotModel.apply(lot, withSpec, null);
        assertThat(reader.read(lot.getId()).card()).isNotNull();
    }

    @Test
    void otherMarketLot_notFound() {
        TenderLot rf = LotModelFixtures.lot(tenders, Market.RF, "ZZ РФ");
        assertThatThrownBy(() -> reader.read(rf.getId())).isInstanceOf(NotFoundException.class);
    }
}
