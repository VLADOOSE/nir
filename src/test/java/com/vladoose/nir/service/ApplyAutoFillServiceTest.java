package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.AutoFillResponse;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.PriceRequestItem;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.ApplyItemRepository;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.EquipmentTypeRepository;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты {@link ApplyAutoFillService}.
 *
 * Запускаются на PostgreSQL (jdbc:postgresql://localhost:5432/nirdb из application.yaml).
 * Каждый тест помечен @Transactional — изменения откатываются после выполнения.
 *
 * Важно: data.sql сидирует начальные данные на старте контекста. Поэтому утверждения
 * построены на свежесозданных тестом сущностях, а не на их количестве в БД.
 */
@SpringBootTest
@Transactional
class ApplyAutoFillServiceTest {

    @Autowired ApplyAutoFillService autoFillService;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired ActivityApplyRepository applyRepository;
    @Autowired ApplyItemRepository applyItemRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository priceRequestItemRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired EquipmentTypeRepository equipmentTypeRepository;

    private String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Facility newFacility() {
        return facilityRepository.save(Facility.builder()
                .name(uniq("ТестУчреждение"))
                .build());
    }

    private Tender newTender(Facility f) {
        return tenderRepository.save(Tender.builder()
                .tenderNumber(uniq("TND"))
                .facility(f)
                .status("ACTIVE")
                .purchaseType("ELECTRONIC_AUCTION")
                .deadline(LocalDate.now().plusMonths(1))
                .build());
    }

    private TenderLot newLot(Tender t, EquipmentType type, int lotNumber, BigDecimal maxCost, String equipName, int quantity) {
        return tenderLotRepository.save(TenderLot.builder()
                .tender(t)
                .lotNumber(lotNumber)
                .equipName(equipName)
                .equipmentType(type)
                .quantity(quantity)
                .maxCost(maxCost)
                .build());
    }

    private MedEquipment newEquipment(EquipmentType type, int cost) {
        return medEquipmentRepository.save(MedEquipment.builder()
                .name(uniq("Оборудование"))
                .manufact("ТестПроизводитель")
                .equipmentType(type)
                .cost(cost)
                .build());
    }

    private Distributor newDistributor() {
        return distributorRepository.save(Distributor.builder()
                .name(uniq("Дистрибьютор"))
                .build());
    }

    private ActivityApply newApply(Tender t) {
        return applyRepository.save(ActivityApply.builder()
                .tender(t)
                .status("DRAFT")
                .build());
    }

    private PriceRequest newPriceRequest(Tender t, Distributor d) {
        return priceRequestRepository.save(PriceRequest.builder()
                .tender(t)
                .distributor(d)
                .status("RESPONDED")
                .build());
    }

    private PriceRequestItem newPriceRequestItem(PriceRequest pr, TenderLot lot, MedEquipment me, BigDecimal responsePrice) {
        return priceRequestItemRepository.save(PriceRequestItem.builder()
                .priceRequest(pr)
                .tenderLot(lot)
                .medEquipment(me)
                .requestedQuantity(1)
                .responsePrice(responsePrice)
                .build());
    }

    /**
     * Тест A: при наличии двух ответов на один лот (900 000 и 850 000)
     * сервис должен выбрать минимальный и взять дистрибьютора этого ответа.
     */
    @Test
    void autoFill_picksCheapestResponsePerLot() {
        EquipmentType uzi = equipmentTypeRepository.findByName("УЗИ").orElseThrow();
        Facility facility = newFacility();
        Tender tender = newTender(facility);
        TenderLot lot = newLot(tender, uzi, 1, BigDecimal.valueOf(2_000_000), uniq("ЛотУЗИ"), 2);

        MedEquipment equipExpensive = newEquipment(uzi, 800_000);
        MedEquipment equipCheap = newEquipment(uzi, 700_000);

        Distributor distExpensive = newDistributor();
        Distributor distCheap = newDistributor();

        PriceRequest prExpensive = newPriceRequest(tender, distExpensive);
        PriceRequest prCheap = newPriceRequest(tender, distCheap);

        newPriceRequestItem(prExpensive, lot, equipExpensive, BigDecimal.valueOf(900_000));
        newPriceRequestItem(prCheap, lot, equipCheap, BigDecimal.valueOf(850_000));

        ActivityApply apply = newApply(tender);

        AutoFillResponse resp = autoFillService.autoFill(apply.getId());

        assertThat(resp.getAddedItems()).isEqualTo(1);
        assertThat(resp.getLotsWithoutResponse()).isEmpty();

        List<ApplyItem> items = applyItemRepository.findByApplyId(apply.getId());
        assertThat(items).hasSize(1);
        ApplyItem item = items.get(0);
        assertThat(item.getTenderLot().getId()).isEqualTo(lot.getId());
        assertThat(item.getOfferedCost()).isEqualByComparingTo(BigDecimal.valueOf(850_000));
        assertThat(item.getDistributor().getId()).isEqualTo(distCheap.getId());
        assertThat(item.getMedEquipment().getId()).isEqualTo(equipCheap.getId());
        assertThat(item.getQuantity()).isEqualTo(2);
    }

    /**
     * Тест B: если для лота уже есть ApplyItem в заявке — autoFill его не трогает
     * и не добавляет дубликата (addedItems = 0).
     */
    @Test
    void autoFill_skipsLotsWithExistingItems() {
        EquipmentType uzi = equipmentTypeRepository.findByName("УЗИ").orElseThrow();
        Facility facility = newFacility();
        Tender tender = newTender(facility);
        TenderLot lot = newLot(tender, uzi, 1, BigDecimal.valueOf(2_000_000), uniq("ЛотУЗИ"), 1);

        MedEquipment manualEquip = newEquipment(uzi, 500_000);
        MedEquipment kpEquip = newEquipment(uzi, 700_000);
        Distributor manualDist = newDistributor();
        Distributor kpDist = newDistributor();

        ActivityApply apply = newApply(tender);

        // вручную добавленная позиция в заявку
        ApplyItem existing = applyItemRepository.save(ApplyItem.builder()
                .apply(apply)
                .tenderLot(lot)
                .medEquipment(manualEquip)
                .distributor(manualDist)
                .offeredCost(BigDecimal.valueOf(550_000))
                .quantity(1)
                .build());

        // и КП с ответом дешевле — но autoFill всё равно не должен дублировать
        PriceRequest pr = newPriceRequest(tender, kpDist);
        newPriceRequestItem(pr, lot, kpEquip, BigDecimal.valueOf(700_000));

        AutoFillResponse resp = autoFillService.autoFill(apply.getId());

        assertThat(resp.getAddedItems()).isZero();

        List<ApplyItem> items = applyItemRepository.findByApplyId(apply.getId());
        assertThat(items).hasSize(1);
        ApplyItem onlyItem = items.get(0);
        assertThat(onlyItem.getId()).isEqualTo(existing.getId());
        assertThat(onlyItem.getDistributor().getId()).isEqualTo(manualDist.getId());
        assertThat(onlyItem.getOfferedCost()).isEqualByComparingTo(BigDecimal.valueOf(550_000));
    }

    /**
     * Тест C: для тендера с двумя лотами по одному из них есть ответ КП,
     * по второму — нет ни одного запроса. autoFill добавляет 1 позицию,
     * второй лот попадает в lotsWithoutResponse.
     */
    @Test
    void autoFill_reportsLotsWithoutResponse() {
        EquipmentType uzi = equipmentTypeRepository.findByName("УЗИ").orElseThrow();
        EquipmentType xray = equipmentTypeRepository.findByName("Рентген").orElseThrow();

        Facility facility = newFacility();
        Tender tender = newTender(facility);

        TenderLot lotWithResponse = newLot(tender, uzi, 1, BigDecimal.valueOf(2_000_000), uniq("ЛотУЗИ"), 1);
        TenderLot lotWithoutResponse = newLot(tender, xray, 2, BigDecimal.valueOf(5_000_000), uniq("ЛотРентген"), 1);

        MedEquipment uziEquip = newEquipment(uzi, 600_000);
        Distributor dist = newDistributor();

        PriceRequest pr = newPriceRequest(tender, dist);
        newPriceRequestItem(pr, lotWithResponse, uziEquip, BigDecimal.valueOf(720_000));

        ActivityApply apply = newApply(tender);

        AutoFillResponse resp = autoFillService.autoFill(apply.getId());

        assertThat(resp.getAddedItems()).isEqualTo(1);
        assertThat(resp.getLotsWithoutResponse())
                .hasSize(1)
                .first()
                .satisfies(s -> {
                    assertThat(s).contains("Лот " + lotWithoutResponse.getLotNumber());
                    assertThat(s).contains(lotWithoutResponse.getEquipName());
                });

        List<ApplyItem> items = applyItemRepository.findByApplyId(apply.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getTenderLot().getId()).isEqualTo(lotWithResponse.getId());
        assertThat(items.get(0).getOfferedCost()).isEqualByComparingTo(BigDecimal.valueOf(720_000));
    }
}
