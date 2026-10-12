package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LineRegistryAction;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestLineField;
import com.vladoose.nir.dto.request.PrivateRequestLineInput;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PrivateRequestLinesTest {

    @Autowired PrivateRequestService service;
    @Autowired MedRegistryRepository registryRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    @Autowired EntityManager em;

    /** Уникальные токены — в реестре nirdb им ничто не созвучно, подсказка гарантированно наша. */
    private static final String VENDOR = "ZZPRLVENDOR";
    private static final String MODEL = "Аппарат ZZPRLMODEL QX7";

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        registryRepository.save(MedRegistry.builder().regNumber("ZZ-PRL-RU-1").name(MODEL)
                .producer(VENDOR + " Ltd").country("ЯПОНИЯ").unlimited(true).build());
        registryRepository.save(MedRegistry.builder().regNumber("ZZ-PRL-RU-2").name("Другое изделие ZZPRLOTHER")
                .producer("ZZPRLOTHERVENDOR").country("КИТАЙ").unlimited(true).build());
        registryRepository.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static List<TenderLot> sorted(Tender t) {
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing(TenderLot::getLotNumber));
        return lots;
    }

    private void requestPrices(Tender t, TenderLot lot) {
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Позиции поставщик " + System.nanoTime()).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").build());
        itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1).build());
        em.flush();
    }

    @Test
    void appendNumbersSavesFieldsAndSuggestsRegistration() {
        Tender t = service.createEmpty();

        service.addLines(t.getId(), List.of(
                input(MODEL, VENDOR, 2, "упак", "Класс IIa", "https://westmed.kz/product/zz"),
                input("ZZНетТакого изделия QQQ", "ZZНетБренда", null, null, null, null)), LinesMode.APPEND);

        List<TenderLot> lots = sorted(t);
        assertThat(lots).extracting(TenderLot::getLotNumber).containsExactly(1, 2);
        TenderLot a = lots.get(0);
        assertThat(a.getQuantity()).isEqualTo(2);
        assertThat(a.getUnit()).isEqualTo("упак");
        assertThat(a.getRequiredSpec()).isEqualTo("Класс IIa");
        assertThat(a.getProductUrl()).isEqualTo("https://westmed.kz/product/zz");
        assertThat(a.getRegStatus()).isEqualTo(LineRegStatus.SUGGESTED);
        assertThat(a.getRegNumber()).isEqualTo("ZZ-PRL-RU-1");
        assertThat(a.getRegScore()).isNotNull();
        TenderLot b = lots.get(1);
        assertThat(b.getQuantity()).isEqualTo(1);
        assertThat(b.getRegStatus()).isEqualTo(LineRegStatus.NOT_FOUND);
        assertThat(b.getRegNumber()).isNull();
    }

    @Test
    void brandAloneIsNotASuggestion() {
        Tender t = service.createEmpty();

        service.addLines(t.getId(), List.of(input("Совсем другое изделие ZZRAZNOE", VENDOR, 1, null, null, null)),
                LinesMode.APPEND);

        assertThat(t.getLots().get(0).getRegStatus()).isEqualTo(LineRegStatus.NOT_FOUND);
    }

    @Test
    void otherModelOfSameBrandIsNotASuggestion() {
        registryRepository.save(MedRegistry.builder().regNumber("ZZ-PRL-ECG-1").name("Электрокардиограф ZZPRLECG R900, ZZPRLECG R700")
                .producer("ZZPRLECGVENDOR Ltd").country("КИТАЙ").unlimited(true).build());
        registryRepository.flush();
        Tender t = service.createEmpty();

        service.addLines(t.getId(), List.of(input("Электрокардиограф ZZPRLECG R12", "ZZPRLECGVENDOR", 1, null, null, null),
                input("Электрокардиограф ZZPRLECG R900", "ZZPRLECGVENDOR", 1, null, null, null)), LinesMode.APPEND);

        List<TenderLot> lots = sorted(t);
        assertThat(lots.get(0).getRegStatus()).isEqualTo(LineRegStatus.NOT_FOUND);
        assertThat(lots.get(1).getRegStatus()).isEqualTo(LineRegStatus.SUGGESTED);
        assertThat(lots.get(1).getRegNumber()).isEqualTo("ZZ-PRL-ECG-1");
    }

    @Test
    void rfMarketLeavesRegistrationEmpty() {
        MarketContext.set(Market.RF);
        Tender t = service.createEmpty();

        service.addLines(t.getId(), List.of(input(MODEL, VENDOR, 1, null, null, null)), LinesMode.APPEND);

        assertThat(t.getLots().get(0).getRegStatus()).isNull();
        assertThat(service.lineCandidates(t.getId(), t.getLots().get(0).getId())).isEmpty();
    }

    @Test
    void replaceKeepsRequestedLines() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Старая 1", null, 1, null, null, null),
                input("ZZ Старая 2", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot requested = sorted(t).get(1);
        requestPrices(t, requested);

        service.addLines(t.getId(), List.of(input("ZZ Новая", null, 3, null, null, null)), LinesMode.REPLACE);

        assertThat(sorted(t)).extracting(TenderLot::getEquipName).containsExactly("ZZ Старая 2", "ZZ Новая");
        assertThat(sorted(t)).extracting(TenderLot::getLotNumber).containsExactly(1, 2);
    }

    @Test
    void nameOrBrandChangeRecomputesSuggestion_sameValueKeepsConfirmation() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input(MODEL, VENDOR, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.CONFIRM, null);
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.CONFIRMED);

        service.updateLineField(t.getId(), lot.getId(), PrivateRequestLineField.NAME, MODEL);
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.CONFIRMED);

        service.updateLineField(t.getId(), lot.getId(), PrivateRequestLineField.BRAND, "ZZДругойБренд");
        assertThat(lot.getRegStatus()).isNotEqualTo(LineRegStatus.CONFIRMED);

        service.updateLineField(t.getId(), lot.getId(), PrivateRequestLineField.QUANTITY, "7");
        service.updateLineField(t.getId(), lot.getId(), PrivateRequestLineField.UNIT, "компл");
        service.updateLineField(t.getId(), lot.getId(), PrivateRequestLineField.SPEC, "Две насадки");
        assertThat(lot.getQuantity()).isEqualTo(7);
        assertThat(lot.getUnit()).isEqualTo("компл");
        assertThat(lot.getRequiredSpec()).isEqualTo("Две насадки");
    }

    @Test
    void invalidLineValuesAre400() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Позиция", null, 1, null, null, null)), LinesMode.APPEND);
        Long lotId = t.getLots().get(0).getId();

        assertThatThrownBy(() -> service.updateLineField(t.getId(), lotId, PrivateRequestLineField.QUANTITY, "0"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateLineField(t.getId(), lotId, PrivateRequestLineField.QUANTITY, "два"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateLineField(t.getId(), lotId, PrivateRequestLineField.NAME, " "))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.addLines(t.getId(),
                List.of(input("ZZ", null, 1, null, null, "javascript:alert(1)")), LinesMode.APPEND))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("http");
        assertThatThrownBy(() -> service.addLines(t.getId(), List.of(), LinesMode.APPEND))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void longNameIsTruncatedTo255() {
        Tender t = service.createEmpty();

        service.addLines(t.getId(), List.of(input("Я".repeat(400), null, 1, null, null, null)), LinesMode.APPEND);

        assertThat(t.getLots().get(0).getEquipName()).hasSize(255);
    }

    @Test
    void cannotExceedMaxLines() {
        MarketContext.set(Market.RF);   // на РФ подсказки РУ нет — 500 позиций без 500 запросов к реестру
        Tender t = service.createEmpty();
        List<PrivateRequestLineInput> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) many.add(input("ZZ Массовая " + i, null, 1, null, null, null));
        service.addLines(t.getId(), many, LinesMode.APPEND);
        service.addLines(t.getId(), many, LinesMode.APPEND);

        assertThatThrownBy(() -> service.addLines(t.getId(), List.of(input("ZZ Лишняя", null, 1, null, null, null)),
                LinesMode.APPEND)).isInstanceOf(BadRequestException.class).hasMessageContaining("500");
        assertThat(t.getLots()).hasSize(500);
    }

    @Test
    void deleteRenumbers_requestedLineIs400_foreignLotIs404() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ 1", null, 1, null, null, null), input("ZZ 2", null, 1, null, null, null),
                input("ZZ 3", null, 1, null, null, null)), LinesMode.APPEND);
        List<TenderLot> lots = sorted(t);
        requestPrices(t, lots.get(2));

        service.deleteLine(t.getId(), lots.get(0).getId());

        assertThat(sorted(t)).extracting(TenderLot::getEquipName).containsExactly("ZZ 2", "ZZ 3");
        assertThat(sorted(t)).extracting(TenderLot::getLotNumber).containsExactly(1, 2);
        assertThatThrownBy(() -> service.deleteLine(t.getId(), lots.get(2).getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("запросы цен");

        Tender other = service.createEmpty();
        assertThatThrownBy(() -> service.deleteLine(other.getId(), lots.get(1).getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void registryActions() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input(MODEL, VENDOR, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);

        service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.CONFIRM, "ZZ-PRL-RU-2");
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.CONFIRMED);
        assertThat(lot.getRegNumber()).isEqualTo("ZZ-PRL-RU-2");
        assertThat(lot.getRegScore()).isNull();

        service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.NOT_REQUIRED, null);
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.NOT_REQUIRED);
        assertThat(lot.getRegNumber()).isNull();

        assertThatThrownBy(() -> service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.CONFIRM, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.CONFIRM, "ZZ-НЕТ-ТАКОГО"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("не найдено");

        service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.RESET, null);
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.SUGGESTED);
        assertThat(lot.getRegNumber()).isEqualTo("ZZ-PRL-RU-1");

        assertThat(service.lineCandidates(t.getId(), lot.getId())).extracting(c -> c.getRegNumber()).contains("ZZ-PRL-RU-1");
    }

    @Test
    void registryCheckFillsOnlyUnchecked() {
        Tender t = service.createEmpty();
        t.getLots().add(TenderLot.builder().tender(t).lotNumber(1).equipName(MODEL).manufact(VENDOR).quantity(1).build());
        t.getLots().add(TenderLot.builder().tender(t).lotNumber(2).equipName("ZZ Ручная").quantity(1)
                .regStatus(LineRegStatus.NOT_REQUIRED).build());
        em.flush();

        service.registryCheck(t.getId());

        List<TenderLot> lots = sorted(t);
        assertThat(lots.get(0).getRegStatus()).isEqualTo(LineRegStatus.SUGGESTED);
        assertThat(lots.get(1).getRegStatus()).isEqualTo(LineRegStatus.NOT_REQUIRED);
    }
}
