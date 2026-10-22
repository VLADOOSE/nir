package com.vladoose.nir.service.market;

import com.vladoose.nir.entity.MarketContract;
import com.vladoose.nir.entity.MarketLookup;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ContractUnitRow;
import com.vladoose.nir.repository.MarketContractRepository;
import com.vladoose.nir.repository.MarketLookupRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Строки набора цен рынка (спека 2026-10-22-tender-results-market-prices §9): состоявшийся лот + последний подписанный не
 * расторгнутый договор по нему; ключ — id лота площадки (повторный сбор обновляет). Договор без строк или без цены — мимо.
 */
@Component
public class MarketContractWriter {

    private record Pick(ContractRow row, ContractUnitRow unit) {}

    private final MarketContractRepository contracts;
    private final MarketLookupRepository lookups;
    private final RegionResolver regions;
    private final TenderLotRepository lots;

    public MarketContractWriter(MarketContractRepository contracts, MarketLookupRepository lookups, RegionResolver regions,
                                TenderLotRepository lots) {
        this.contracts = contracts;
        this.lookups = lookups;
        this.regions = regions;
        this.lots = lots;
    }

    @Transactional
    public int upsert(List<ClosedLot> lots, List<ContractRow> rows, OffsetDateTime now) {
        Map<Long, Pick> best = new HashMap<>();
        for (ContractRow c : rows) {
            if (!c.concluded()) continue;
            for (ContractUnitRow u : c.units()) {
                if (u.lotId() == null || u.unitPrice() == null || u.unitPrice().signum() <= 0) continue;
                Pick cur = best.get(u.lotId());
                if (cur == null || signed(c).isAfter(signed(cur.row()))) best.put(u.lotId(), new Pick(c, u));
            }
        }
        if (best.isEmpty()) return 0;
        Map<Long, MarketContract> existing = contracts.findByLotExtIdIn(best.keySet()).stream()
                .collect(Collectors.toMap(MarketContract::getLotExtId, Function.identity()));
        int n = 0;
        for (ClosedLot l : lots) {
            Pick p = l.id() == null ? null : best.get(l.id());
            if (p == null) continue;
            MarketContract m = existing.getOrDefault(l.id(), MarketContract.builder().lotExtId(l.id()).build());
            m.setAnno(cut(l.anno(), 30));
            m.setLotNumber(cut(l.lotNumber(), 50));
            m.setEnstruCode(cut(l.enstruCode() != null ? l.enstruCode() : p.unit().enstruCode(), 30));
            m.setName(cut(l.nameRu(), 500));
            m.setDescription(cut(l.descriptionRu(), 1000));
            m.setExtraDesc(l.extraDesc());
            m.setUnit(cut(l.unit(), 50));
            m.setCustomerBin(cut(l.customerBin(), 20));
            m.setCustomerName(cut(l.customerName(), 500));
            m.setRegion(cut(regions.resolve(l.deliveryPlace(), l.customerName()), 100));
            m.setQuantity(l.count());
            m.setBudgetUnitPrice(budget(l));
            m.setUnitPrice(p.unit().unitPrice());
            m.setSupplierBin(cut(p.row().supplierBin(), 20));
            LocalDate signedAt = signed(p.row());
            m.setSignedAt(signedAt.equals(LocalDate.MIN) ? null : signedAt);
            m.setContractNumber(cut(p.row().number(), 60));
            m.setTradeMethod(cut(l.tradeMethodName(), 150));
            m.setFetchedAt(now);
            existing.put(l.id(), contracts.save(m));
            n++;
        }
        return n;
    }

    /** Бюджет за единицу: сумма лота / количество; нет количества — пусто (Review Focus 4). */
    public static BigDecimal budget(ClosedLot l) {
        if (l.amount() == null || l.count() == null || l.count().signum() <= 0) return null;
        return l.amount().divide(l.count(), 2, RoundingMode.HALF_UP);
    }

    private static LocalDate signed(ContractRow c) {
        LocalDateTime t = ImportDeadline.parse(c.signDate());
        return t == null ? LocalDate.MIN : t.toLocalDate();
    }

    @Transactional(readOnly = true)
    public Optional<MarketLookup> lookup(String key) {
        return lookups.findById(key);
    }

    @Transactional
    public void mark(String key, int rows, String error, OffsetDateTime now) {
        MarketLookup l = lookups.findById(key).orElseGet(() -> MarketLookup.builder().lookupKey(key).build());
        l.setFetchedAt(now);
        l.setRowsCount(rows);
        l.setError(cut(error, 300));
        lookups.save(l);
    }

    /** Код ЕНС ТРУ и единица лота с площадки (живой запрос цены у лота, у которого их ещё нет). */
    @Transactional
    public void fillLot(long lotId, ClosedLot l) {
        lots.findById(lotId).ifPresent(lot -> {
            if (lot.getEnstruCode() == null && l.enstruCode() != null) lot.setEnstruCode(cut(l.enstruCode(), 30));
            if (lot.getPlanUnit() == null && l.unit() != null) lot.setPlanUnit(cut(l.unit(), 50));
        });
    }

    /** Что нужно о лоте для строки рынка — одной транзакцией чтения (потоки уведомлений — без сессии). */
    public record LotInfo(long lotId, String name, String code, Long extLotId, boolean goszakup, BigDecimal budget, String unit) {}

    @Transactional(readOnly = true)
    public List<LotInfo> lotInfos(Collection<Long> lotIds) {
        return lots.findAllById(lotIds).stream().map(l -> new LotInfo(l.getId(), l.getEquipName(), l.getEnstruCode(), l.getExtLotId(),
                l.getTender().getPlatform() == TenderPlatform.GOSZAKUP,
                l.getTender().getSource() == Source.PUBLIC_TENDER ? l.getMaxCost() : null,
                l.getUnit() != null && !l.getUnit().isBlank() ? l.getUnit() : l.getPlanUnit())).toList();
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
