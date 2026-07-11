package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Upsert объявления СК-Фармации в Tender (platform=SK_PHARMACY). Отдельный @Transactional-бин (сеть вне tx, §6). */
@Component
public class SkPharmacyTenderWriter {

    private final TenderRepository tenderRepository;

    public SkPharmacyTenderWriter(TenderRepository tenderRepository) {
        this.tenderRepository = tenderRepository;
    }

    public enum Result { CREATED, UPDATED }

    @Transactional
    public Result upsert(SkAnnounce a, List<SkLot> lots) {
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

        rebuildLots(t, lots);
        tenderRepository.save(t);
        return isNew ? Result.CREATED : Result.UPDATED;
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
            lot.setEquipName(trunc(l.name(), 255));
            lot.setQuantity(l.quantity());
            lot.setMaxCost(l.unitPrice());
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
}
