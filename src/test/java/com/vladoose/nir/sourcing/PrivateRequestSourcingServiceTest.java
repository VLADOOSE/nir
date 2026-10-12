package com.vladoose.nir.sourcing;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse;
import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse.Supplier;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.PrivateRequestSourcingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

/** Уникальные бренды и вид изделия — в nirdb живут настоящие поставщики, их бренды в подбор не попадают. */
@SpringBootTest
@Transactional
class PrivateRequestSourcingServiceTest {

    @Autowired PrivateRequestSourcingService sourcing;
    @Autowired PrivateRequestService privateRequestService;
    @Autowired DistributorRepository distributorRepository;
    @Autowired EquipmentTypeRepository typeRepository;
    @Autowired EquipmentTypeSynonymRepository synonymRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    private Distributor dist(String name, String email, List<String> brands, List<EquipmentType> types) {
        return distributorRepository.save(Distributor.builder().name(name + " " + System.nanoTime()).email(email)
                .brands(new ArrayList<>(brands)).equipmentTypes(new ArrayList<>(types)).build());
    }

    private Supplier supplier(PrivateRequestSourcingResponse r, Distributor d) {
        return r.suppliers().stream().filter(s -> s.distributorId().equals(d.getId())).findFirst().orElseThrow();
    }

    @Test
    void brandFirst_typeAsWeakHint_unmatchedListed_requestedNotPreselected() {
        EquipmentType type = typeRepository.save(EquipmentType.builder().name("ZZ Вид подбора " + System.nanoTime()).build());
        synonymRepository.save(EquipmentTypeSynonym.builder().termNorm("zzклассификатор").equipmentType(type).build());
        Distributor brand = dist("ZZ Подбор Бренд", "b@zz.kz", List.of("ZZSRCBRAND"), List.of());
        Distributor brandNoMail = dist("ZZ Подбор Бренд без почты", null, List.of("ZZSRCBRAND"), List.of());
        Distributor byType = dist("ZZ Подбор Вид", "t@zz.kz", List.of(), List.of(type));
        Tender t = privateRequestService.createEmpty();
        privateRequestService.addLines(t.getId(), List.of(
                input("ZZ Монитор", "Завод ZZSRCBRAND", 2, null, null, null),
                input("ZZКЛАССИФИКАТОР модель 7", null, 1, null, null, null),
                input("ZZ Ничья позиция", "ZZНИКТО", 1, null, null, null)), LinesMode.APPEND);
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing(TenderLot::getLotNumber));

        PrivateRequestSourcingResponse r = sourcing.build(t.getId());

        Supplier sBrand = supplier(r, brand);
        assertThat(sBrand.preselected()).isTrue();
        assertThat(sBrand.hasEmail()).isTrue();
        assertThat(sBrand.lines()).singleElement().satisfies(l -> {
            assertThat(l.lotId()).isEqualTo(lots.get(0).getId());
            assertThat(l.reason()).isEqualTo("BRAND");
            assertThat(l.label()).isEqualTo("возит ZZSRCBRAND");
            assertThat(l.preselected()).isTrue();
            assertThat(l.requestedAt()).isNull();
        });
        Supplier sNoMail = supplier(r, brandNoMail);
        assertThat(sNoMail.preselected()).isFalse();
        assertThat(sNoMail.hasEmail()).isFalse();
        Supplier sType = supplier(r, byType);
        assertThat(sType.preselected()).isFalse();
        assertThat(sType.lines()).singleElement().satisfies(l -> {
            assertThat(l.lotId()).isEqualTo(lots.get(1).getId());
            assertThat(l.reason()).isEqualTo("TYPE");
            assertThat(l.label()).startsWith("по виду: ZZ Вид подбора");
            assertThat(l.preselected()).isFalse();
        });
        assertThat(r.unmatched()).extracting(PrivateRequestSourcingResponse.LineRef::lotId).containsExactly(lots.get(2).getId());
        assertThat(r.suppliers().indexOf(sBrand)).isLessThan(r.suppliers().indexOf(sType));

        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(brand).status("SENT")
                .sentAt(OffsetDateTime.now()).build());
        itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lots.get(0)).requestedQuantity(2).build());
        em.flush();

        Supplier again = supplier(sourcing.build(t.getId()), brand);
        assertThat(again.preselected()).isFalse();
        assertThat(again.lines().get(0).requestedAt()).isNotNull();
        assertThat(again.lines().get(0).preselected()).isFalse();
    }

    @Test
    void otherMarketSuppliersAreNotOffered() {
        MarketContext.set(Market.RF);
        Distributor rf = dist("ZZ Подбор РФ", "rf@zz.ru", List.of("ZZSRCRFONLY"), List.of());
        MarketContext.set(Market.KZ);
        Tender t = privateRequestService.createEmpty();
        privateRequestService.addLines(t.getId(), List.of(input("ZZ Позиция", "ZZSRCRFONLY", 1, null, null, null)),
                LinesMode.APPEND);

        PrivateRequestSourcingResponse r = sourcing.build(t.getId());

        assertThat(r.suppliers()).noneMatch(s -> s.distributorId().equals(rf.getId()));
        assertThat(r.unmatched()).hasSize(1);
    }

    @Test
    void manufacturerPreselected_brandDealersShownUnchecked_flagsInResponse() {
        Distributor maker = distributorRepository.save(Distributor.builder().name("ZZ Производитель " + System.nanoTime())
                .email("m@zz.kz").brands(new ArrayList<>(List.of("ZZMKR"))).manufacturer(true).build());
        Distributor dealer = distributorRepository.save(Distributor.builder().name("ZZ Дилер " + System.nanoTime())
                .email("d@zz.kz").brands(new ArrayList<>(List.of("ZZMKR"))).trusted(true).build());
        Tender t = privateRequestService.createEmpty();
        privateRequestService.addLines(t.getId(), List.of(input("ZZ Облучатель", "Завод ZZMKR", 1, null, null, null)),
                LinesMode.APPEND);

        PrivateRequestSourcingResponse r = sourcing.build(t.getId());

        Supplier m = supplier(r, maker);
        assertThat(m.manufacturer()).isTrue();
        assertThat(m.preselected()).isTrue();
        assertThat(m.lines()).singleElement().satisfies(l -> {
            assertThat(l.reason()).isEqualTo("MANUFACTURER");
            assertThat(l.label()).isEqualTo("производитель");
            assertThat(l.preselected()).isTrue();
        });
        Supplier d = supplier(r, dealer);
        assertThat(d.trusted()).isTrue();
        assertThat(d.preselected()).isFalse();
        assertThat(d.lines()).singleElement().satisfies(l -> {
            assertThat(l.reason()).isEqualTo("BRAND");
            assertThat(l.label()).isEqualTo("возит ZZMKR");
            assertThat(l.preselected()).isFalse();
        });
        assertThat(r.suppliers().indexOf(m)).isLessThan(r.suppliers().indexOf(d));
    }

    @Test
    void manufacturerWithoutEmailLeavesDealersPreselected() {
        Distributor maker = distributorRepository.save(Distributor.builder().name("ZZ Производитель без почты " + System.nanoTime())
                .brands(new ArrayList<>(List.of("ZZNOMAIL"))).manufacturer(true).build());
        Distributor dealer = distributorRepository.save(Distributor.builder().name("ZZ Дилер с почтой " + System.nanoTime())
                .email("dd@zz.kz").brands(new ArrayList<>(List.of("ZZNOMAIL"))).build());
        Tender t = privateRequestService.createEmpty();
        privateRequestService.addLines(t.getId(), List.of(input("ZZ Прибор", "ZZNOMAIL", 1, null, null, null)), LinesMode.APPEND);

        PrivateRequestSourcingResponse r = sourcing.build(t.getId());

        assertThat(supplier(r, maker).preselected()).isFalse();
        assertThat(supplier(r, dealer).preselected()).isTrue();
    }
}
