package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Пишет ОДИН импортный тендер в собственной транзакции (отдельный бин → Spring-прокси
 * даёт привязанную сессию для marketFilter и per-item коммит). subject/lots уже получены
 * ВНЕ транзакции и переданы сюда — транзакция короткая, без блокирующего сетевого I/O.
 */
@Component
public class GoszakupTenderWriter {

    public enum Result { CREATED, UPDATED }

    private final TenderRepository tenderRepository;
    private final RegionResolver regionResolver;

    public GoszakupTenderWriter(TenderRepository tenderRepository, RegionResolver regionResolver) {
        this.tenderRepository = tenderRepository;
        this.regionResolver = regionResolver;
    }

    @Transactional
    public Result upsertOne(TrdBuyDto d, SubjectDto subj, List<LotDto> lots) {
        Tender t = tenderRepository.findBySourceExtId(d.getNumberAnno()).orElse(null);
        boolean isNew = (t == null);
        if (isNew) { t = new Tender(); t.setSourceExtId(d.getNumberAnno()); }
        applyFields(t, d);
        applyRegion(t, subj);
        rebuildLots(t, lots);
        tenderRepository.save(t);
        return isNew ? Result.CREATED : Result.UPDATED;
    }

    private void applyFields(Tender t, TrdBuyDto d) {
        t.setTenderNumber(d.getNumberAnno());
        t.setSource(Source.PUBLIC_TENDER);
        t.setMarket(Market.KZ);
        t.setCurrency("KZT");
        t.setFacility(null);
        t.setStatus(mapStatus(d.getRefBuyStatusId()));
        t.setDescription(d.getNameRu());
        t.setTotalCost(d.getTotalSum());
        t.setPublishDate(GoszakupParse.localDate(d.getPublishDate()));
        t.setDeadline(GoszakupParse.localDate(d.getEndDate()));
        t.setCustomerBin(d.getCustomerBin());
    }

    private void applyRegion(Tender t, SubjectDto subj) {
        String customerName = subj != null ? subj.getNameRu() : null;
        String address = subj != null ? subj.getAddress() : null;
        String kato = subj != null ? subj.getKatoId() : null;
        t.setCustomerName(customerName);
        t.setRegionKato(kato);
        if (address != null) t.setDeliveryAddress(address);
        t.setRegion(regionResolver.resolve(customerName, address)); // null допустим
    }

    private void rebuildLots(Tender t, List<LotDto> lots) {
        // §7/§14: лоты ТОЛЬКО через коллекцию (orphanRemoval), не repository.delete
        t.getLots().clear();
        if (lots == null) return;
        for (LotDto l : lots) {
            TenderLot lot = new TenderLot();
            lot.setTender(t);
            lot.setLotNumber(GoszakupParse.intOrNull(l.getLotNumber()));
            lot.setEquipName(l.getNameRu());
            lot.setQuantity(l.getCount());
            lot.setMaxCost(l.getAmount());
            t.getLots().add(lot);
        }
    }

    static String mapStatus(Integer refBuyStatusId) {
        // TODO(T9): map real ref_buy_status_id → domain status (ids confirmed against live API token)
        return "ACTIVE";
    }
}
