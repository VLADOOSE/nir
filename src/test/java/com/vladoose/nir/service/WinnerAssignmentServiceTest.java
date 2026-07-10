package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.AssignWinnerResponse;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class WinnerAssignmentServiceTest {

    @Autowired WinnerAssignmentService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired ActivityApplyRepository applyRepository;
    @Autowired ApplyItemRepository applyItemRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository priceRequestItemRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired FacilityRepository facilityRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private String u(String p) { return p + "-" + UUID.randomUUID().toString().substring(0, 8); }

    /** Создаёт тендер с 1 лотом и 1 КП-ответом цены; возвращает [tenderId, lotId, priceRequestId]. */
    private long[] fixture(BigDecimal price, boolean withEquip) {
        Facility fac = facilityRepository.save(Facility.builder().name(u("Клин")).build());
        Tender t = tenderRepository.save(Tender.builder().tenderNumber(u("T")).facility(fac).status("ACTIVE").build());
        TenderLot lot = TenderLot.builder().tender(t).lotNumber(1).equipName(u("Лот")).quantity(2).build();
        t.getLots().add(lot);
        t = tenderRepository.save(t);
        TenderLot savedLot = t.getLots().get(0);
        MedEquipment me = withEquip ? medEquipmentRepository.save(
                MedEquipment.builder().name(u("Обор")).manufact("M").build()) : null;
        Distributor d = distributorRepository.save(Distributor.builder().name(u("Дистр")).build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status("RESPONDED").build();
        pr.getItems().add(PriceRequestItem.builder()
                .priceRequest(pr).tenderLot(savedLot).medEquipment(me).requestedQuantity(1).responsePrice(price).build());
        pr = priceRequestRepository.save(pr);
        return new long[]{ t.getId(), savedLot.getId(), pr.getId() };
    }

    @Test
    void assign_createsDraftApply_andItem_withProcurementPrice() {
        MarketContext.set(Market.KZ);
        long[] f = fixture(BigDecimal.valueOf(850_000), true);

        AssignWinnerResponse resp = service.assignWinner(f[0], f[1], f[2], null);

        assertThat(resp.applyId()).isNotNull();
        assertThat(resp.offeredCost()).isEqualByComparingTo("850000");   // закупка, без наценки
        ActivityApply apply = applyRepository.findById(resp.applyId()).orElseThrow();
        assertThat(apply.getStatus()).isEqualTo("DRAFT");
        List<ApplyItem> items = applyItemRepository.findByApplyId(resp.applyId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getTenderLot().getId()).isEqualTo(f[1]);
        assertThat(items.get(0).getQuantity()).isEqualTo(2);             // из lot.quantity
    }

    @Test
    void assign_reusesDraftApply_andUpsertsByLot_noDuplicate() {
        MarketContext.set(Market.KZ);
        long[] f = fixture(BigDecimal.valueOf(900_000), true);
        // второй поставщик по тому же лоту, дешевле
        Distributor d2 = distributorRepository.save(Distributor.builder().name(u("Дистр2")).build());
        Tender t = tenderRepository.findById(f[0]).orElseThrow();
        TenderLot lot = lotRepository.findById(f[1]).orElseThrow();
        MedEquipment me2 = medEquipmentRepository.save(MedEquipment.builder().name(u("Обор2")).manufact("M").build());
        PriceRequest pr2 = PriceRequest.builder().tender(t).distributor(d2).status("RESPONDED").build();
        pr2.getItems().add(PriceRequestItem.builder().priceRequest(pr2).tenderLot(lot)
                .medEquipment(me2).requestedQuantity(1).responsePrice(BigDecimal.valueOf(800_000)).build());
        pr2 = priceRequestRepository.save(pr2);

        AssignWinnerResponse first = service.assignWinner(f[0], f[1], f[2], null);      // 900k поставщик
        AssignWinnerResponse second = service.assignWinner(f[0], f[1], pr2.getId(), null); // заменить на 800k

        assertThat(second.applyId()).isEqualTo(first.applyId());          // та же заявка
        List<ApplyItem> items = applyItemRepository.findByApplyId(first.applyId());
        assertThat(items).hasSize(1);                                     // upsert, не дубль
        assertThat(items.get(0).getOfferedCost()).isEqualByComparingTo("800000");
        assertThat(items.get(0).getDistributor().getId()).isEqualTo(d2.getId());
    }

    @Test
    void assign_manualOverride_picksNonCheapest() {
        MarketContext.set(Market.KZ);
        long[] f = fixture(BigDecimal.valueOf(900_000), true);           // назначаем именно 900k (не минимум)
        AssignWinnerResponse resp = service.assignWinner(f[0], f[1], f[2], null);
        assertThat(resp.offeredCost()).isEqualByComparingTo("900000");
    }

    @Test
    void assign_appliesMarkup_whenProvided() {
        MarketContext.set(Market.KZ);
        long[] f = fixture(BigDecimal.valueOf(1_000_000), true);
        AssignWinnerResponse resp = service.assignWinner(f[0], f[1], f[2], 20.0);
        assertThat(resp.offeredCost()).isEqualByComparingTo("1200000");  // ×1.2
    }

    @Test
    void assign_rejectsWhenNoEquipment() {
        MarketContext.set(Market.KZ);
        long[] f = fixture(BigDecimal.valueOf(500_000), false);          // med_equipment == null
        assertThatThrownBy(() -> service.assignWinner(f[0], f[1], f[2], null))
                .isInstanceOf(BadRequestException.class);
    }
}
