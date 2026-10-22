package com.vladoose.nir.service.market;

import com.vladoose.nir.dto.response.CustomerHistory;
import com.vladoose.nir.entity.GoszakupSubject;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MarketContract;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.repository.MarketContractRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** История заказчика по нажатию (спека §8): обновить его договоры (сутки), показать до 30 строк — своя категория, медизделия. */
@Service
public class CustomerHistoryService {

    static final int MAX_ROWS = 30;

    private final MarketCollector collector;
    private final MarketContractRepository contracts;
    private final GoszakupSubjectRepository subjects;
    private final Clock clock;

    @Autowired
    public CustomerHistoryService(MarketCollector collector, MarketContractRepository contracts, GoszakupSubjectRepository subjects) {
        this(collector, contracts, subjects, Clock.systemUTC());
    }

    public CustomerHistoryService(MarketCollector collector, MarketContractRepository contracts, GoszakupSubjectRepository subjects,
                                  Clock clock) {
        this.collector = collector;
        this.contracts = contracts;
        this.subjects = subjects;
        this.clock = clock;
    }

    public CustomerHistory forTender(Tender t) {
        String bin = t.getCustomerBin();
        if (bin == null || !bin.matches("\\d{12}")) return new CustomerHistory(bin, List.of(), null, "БИН заказчика неизвестен");
        MarketCollector.Lookup l = collector.ensureCustomer(bin);
        Set<String> codes = t.getLots().stream().map(TenderLot::getEnstruCode).filter(Objects::nonNull).collect(Collectors.toSet());
        LocalDate from = LocalDate.now(clock.withZone(Market.KZ.zone())).minusYears(MarketCollector.YEARS);
        List<MarketContract> all = contracts.findByCustomerBinAndSignedAtGreaterThanEqualOrderBySignedAtDesc(bin, from);
        List<MarketContract> same = all.stream().filter(m -> m.getEnstruCode() != null && codes.contains(m.getEnstruCode())).toList();
        List<MarketContract> medical = all.stream().filter(m -> !same.contains(m)).filter(m -> !service(m))
                .filter(m -> MedicalGoodsVocabulary.hasDeviceStrong(text(m))).toList();
        List<MarketContract> shown = Stream.concat(same.stream(), medical.stream()).limit(MAX_ROWS).toList();
        Map<String, String> names = subjects.findByBinIn(shown.stream().map(MarketContract::getSupplierBin).filter(Objects::nonNull)
                        .collect(Collectors.toSet())).stream().filter(s -> s.getNameRu() != null)
                .collect(Collectors.toMap(GoszakupSubject::getBin, GoszakupSubject::getNameRu, (a, b) -> a));
        List<CustomerHistory.Row> rows = shown.stream().map(m -> new CustomerHistory.Row(m.getSignedAt(), m.getName(), m.getQuantity(),
                m.getUnit(), m.getUnitPrice(), m.getSupplierBin() == null ? null : names.getOrDefault(m.getSupplierBin(), "БИН " + m.getSupplierBin()),
                m.getRegion(), same.contains(m), m.getAnno())).toList();
        return new CustomerHistory(bin, rows, l.fetchedAt(), l.ok() ? null : l.error());
    }

    /** Работа или услуга по названию («Услуги по обучению» с «эндоскопической диагностикой» в описании) — не медизделие. */
    private static boolean service(MarketContract m) {
        String name = Objects.toString(m.getName(), "");
        return MedicalGoodsVocabulary.hasWorksServices(name)
                && !MedicalGoodsVocabulary.isMedicalWasteContainer(name, Objects.toString(m.getDescription(), ""));
    }

    private static String text(MarketContract m) {
        return String.join(" ", Objects.toString(m.getName(), ""), Objects.toString(m.getDescription(), ""),
                Objects.toString(m.getExtraDesc(), ""));
    }
}
