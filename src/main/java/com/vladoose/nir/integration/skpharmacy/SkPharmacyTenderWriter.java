package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Upsert объявления СК-Фармации в Tender (platform=SK_PHARMACY). Отдельный @Transactional-бин (сеть вне tx, §6). */
@Component
public class SkPharmacyTenderWriter {

    private final TenderRepository tenderRepository;
    private final RegionResolver regionResolver;

    public SkPharmacyTenderWriter(TenderRepository tenderRepository, RegionResolver regionResolver) {
        this.tenderRepository = tenderRepository;
        this.regionResolver = regionResolver;
    }

    public enum Result { CREATED, UPDATED }

    @Transactional
    public Result upsert(SkAnnounce a, List<SkLot> lots, SkGeneral general) {
        Tender t = tenderRepository.findBySourceExtId(a.numberAnno()).orElse(null);
        boolean isNew = t == null;
        if (isNew) { t = new Tender(); t.setSourceExtId(a.numberAnno()); }

        t.setTenderNumber(trunc(a.numberAnno(), 50));
        t.setSource(Source.PUBLIC_TENDER);
        t.setPlatform(TenderPlatform.SK_PHARMACY);
        t.setMarket(Market.KZ);
        t.setCurrency("KZT");
        t.setFacility(null);
        t.setCustomerName(trunc(a.organizer(), 500));
        t.setDescription(a.nameRu());
        t.setTotalCost(a.totalSum());
        t.setPurchaseType(trunc(a.purchaseType(), 50));
        t.setPublishDate(dateOf(a.acceptStart()));
        LocalDate deadline = dateOf(a.acceptEnd());
        t.setDeadline(deadline);
        t.setStatus(statusFrom(a.status(), deadline));
        applyGeneral(t, a, general);

        rebuildLots(t, lots);
        tenderRepository.save(t);
        return isNew ? Result.CREATED : Result.UPDATED;
    }

    /**
     * Поля вкладки «Общие сведения» (?tab=general). Регион — организатора (единый дистрибьютор СК-Фармации в Астане,
     * лизингодатель и т.п.), как у goszakup для республиканских заказчиков: заполняем, только когда нашли значение
     * (не затираем уже сохранённое null'ом при переимпорте без general). Регион считаем по имени+адресу через RegionResolver.
     */
    private void applyGeneral(Tender t, SkAnnounce a, SkGeneral g) {
        if (g == null) return;
        if (g.customerBin() != null) t.setCustomerBin(trunc(g.customerBin(), 20));
        if (g.regionKato() != null) t.setRegionKato(trunc(g.regionKato(), 20));
        if (g.contactEmail() != null) t.setContactEmail(g.contactEmail());
        if (g.contactName() != null) t.setContactLastName(trunc(g.contactName(), 100));
        String region = regionResolver.resolve(a.organizer(), g.legalAddress());
        if (region != null) t.setRegion(region);
    }

    /** §7/§14: лоты ТОЛЬКО через коллекцию (orphanRemoval), не repository.delete. */
    private void rebuildLots(Tender t, List<SkLot> lots) {
        t.getLots().clear();
        if (lots == null) return;
        int n = 1;
        for (SkLot l : lots) {
            TenderLot lot = new TenderLot();
            lot.setTender(t);
            lot.setLotNumber(n++);
            lot.setSourceLotCode(trunc(l.code(), 50));    // «1040409-Т1» — ключ связи с ТЗ-файлами
            lot.setEquipName(trunc(l.name(), 255));
            lot.setQuantity(l.quantity());
            lot.setMaxCost(priceOrNull(l.unitPrice()));   // 0/overflow → null (CHECK max_cost>0, NUMERIC(15,2))
            t.getLots().add(lot);
        }
    }

    /** «2026-07-27 10:00:00» → LocalDate; пусто/битое → null. */
    private static LocalDate dateOf(String s) {
        if (s == null || s.length() < 10) return null;
        try { return LocalDate.parse(s.trim().substring(0, 10)); } catch (Exception e) { return null; }
    }

    /** Приём/опубликовано → ACTIVE; дедлайн прошёл → COMPLETED; отменён → CANCELLED. */
    private static String statusFrom(String portalStatus, LocalDate deadline) {
        String s = portalStatus == null ? "" : portalStatus.toLowerCase();
        if (s.contains("отмен")) return "CANCELLED";
        if (deadline != null && deadline.isBefore(LocalDate.now())) return "COMPLETED";
        if (s.contains("заверш") || s.contains("итог") || s.contains("рассмотр")) return "COMPLETED";
        return "ACTIVE";
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Цена лота: null/≤0/переполнение NUMERIC(15,2) целой части >13 → null (колонка nullable, CHECK max_cost>0). */
    private static java.math.BigDecimal priceOrNull(java.math.BigDecimal p) {
        if (p == null || p.signum() <= 0) return null;
        if (p.precision() - p.scale() > 13) return null;
        return p;
    }
}
