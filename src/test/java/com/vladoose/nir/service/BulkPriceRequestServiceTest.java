package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
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
 * Интеграционные тесты {@link BulkPriceRequestService}.
 *
 * Запускаются на PostgreSQL (jdbc:postgresql://localhost:5432/nirdb из application.yaml).
 * Каждый тест помечен @Transactional — изменения откатываются после выполнения.
 *
 * Важно: data.sql сидирует начальные данные (4 дистрибьютора-универсала, 8 моделей,
 * 3 тендера) на старте контекста. Поэтому утверждения построены на ПРИСУТСТВИИ
 * созданных тестом сущностей, а не на их точном количестве в результате.
 */
@SpringBootTest
@Transactional
class BulkPriceRequestServiceTest {

    @Autowired BulkPriceRequestService bulkService;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
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

    private TenderLot newLot(Tender t, EquipmentType type, int lotNumber, BigDecimal maxCost) {
        return tenderLotRepository.save(TenderLot.builder()
                .tender(t)
                .lotNumber(lotNumber)
                .equipName(uniq("Лот"))
                .equipmentType(type)
                .quantity(1)
                .maxCost(maxCost)
                .build());
    }

    private MedEquipment newEquipment(EquipmentType type, int cost) {
        return medEquipmentRepository.save(MedEquipment.builder()
                .name(uniq("Оборудование"))
                .manufact("ТестПроизводитель")
                .equipmentType(type)
                .build());
    }

    private Distributor newDistributor(List<EquipmentType> specialization) {
        return distributorRepository.save(Distributor.builder()
                .name(uniq("Дистрибьютор"))
                .equipmentTypes(specialization)
                .build());
    }

    /**
     * Тест 1: универсальный дистрибьютор (без специализации) должен попасть в группы
     * наряду с дистрибьютором, специализирующимся на типе оборудования из лота.
     */
    @Test
    void buildPreview_universalDistributorIncludedAlongsideSpecialized() {
        EquipmentType uzi = equipmentTypeRepository.findByName("УЗИ").orElseThrow();

        Facility facility = newFacility();
        Tender tender = newTender(facility);
        TenderLot lot = newLot(tender, uzi, 1, BigDecimal.valueOf(2_000_000));
        newEquipment(uzi, 500_000); // подходящее оборудование

        Distributor distSpec = newDistributor(List.of(uzi));
        Distributor distUniv = newDistributor(List.of()); // универсал — пустая специализация

        BulkPriceRequestService.Preview preview = bulkService.buildPreview(tender.getId());

        List<Long> groupDistIds = preview.groups().stream()
                .map(g -> g.distributor().getId())
                .toList();

        assertThat(groupDistIds)
                .as("специализированный дистрибьютор должен быть в группах")
                .contains(distSpec.getId());
        assertThat(groupDistIds)
                .as("универсальный дистрибьютор (без специализации) должен быть в группах")
                .contains(distUniv.getId());
        assertThat(preview.lotsWithoutMatch()).isEmpty();
        assertThat(preview.lotsWithoutDistributor()).isEmpty();
    }

    /**
     * Тест 2: лот, для типа которого в каталоге нет ни одной модели,
     * должен попасть в lotsWithoutMatch.
     */
    @Test
    void buildPreview_lotWithoutMatchingEquipmentGoesToLotsWithoutMatch() {
        // Создаём тип, которого гарантированно нет ни в data.sql, ни в DataInitializer.
        EquipmentType rareType = equipmentTypeRepository.save(EquipmentType.builder()
                .name(uniq("ОченьРедкийТипОборудования"))
                .build());

        Facility facility = newFacility();
        Tender tender = newTender(facility);
        TenderLot lot = newLot(tender, rareType, 1, BigDecimal.valueOf(1_000_000));
        // НЕ создаём оборудование этого типа

        BulkPriceRequestService.Preview preview = bulkService.buildPreview(tender.getId());

        List<Long> lotsNoMatchIds = preview.lotsWithoutMatch().stream()
                .map(TenderLot::getId)
                .toList();

        assertThat(lotsNoMatchIds)
                .as("лот без подходящего оборудования должен быть в lotsWithoutMatch")
                .contains(lot.getId());
        assertThat(preview.groups())
                .as("без подходящего оборудования групп быть не должно")
                .isEmpty();
    }

    /**
     * Тест 3: дистрибьютор, специализирующийся на двух типах, покрывает оба лота —
     * в его группе должно оказаться минимум по одному элементу из каждого лота.
     */
    @Test
    void buildPreview_oneDistributorCoversLotsOfDifferentTypes() {
        EquipmentType uzi = equipmentTypeRepository.findByName("УЗИ").orElseThrow();
        EquipmentType xray = equipmentTypeRepository.findByName("Рентген").orElseThrow();

        Facility facility = newFacility();
        Tender tender = newTender(facility);
        TenderLot lotUzi = newLot(tender, uzi, 1, BigDecimal.valueOf(2_000_000));
        TenderLot lotXray = newLot(tender, xray, 2, BigDecimal.valueOf(5_000_000));

        newEquipment(uzi, 600_000);
        newEquipment(xray, 3_000_000);

        Distributor distBoth = newDistributor(List.of(uzi, xray));

        BulkPriceRequestService.Preview preview = bulkService.buildPreview(tender.getId());

        BulkPriceRequestService.DistributorGroup myGroup = preview.groups().stream()
                .filter(g -> g.distributor().getId().equals(distBoth.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("дистрибьютор должен быть в группах"));

        List<Long> coveredLotIds = myGroup.items().stream()
                .map(it -> it.lot().getId())
                .distinct()
                .toList();

        assertThat(coveredLotIds)
                .as("группа дистрибьютора должна покрывать оба лота")
                .contains(lotUzi.getId(), lotXray.getId());
        assertThat(preview.lotsWithoutMatch()).isEmpty();
        assertThat(preview.lotsWithoutDistributor()).isEmpty();
    }
}
