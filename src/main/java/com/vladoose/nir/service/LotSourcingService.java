package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.util.BrandMatch;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Подсказки поставщиков для запроса КП по лотам тендера:
 * бренд предложенной модели лота, а без модели — производители топ-кандидатов реестра НЦЭЛС,
 * матчатся на бренды дистрибьюторов активного рынка.
 */
@Service
public class LotSourcingService {

    static final int REGISTRY_TOP = 5;
    static final double REGISTRY_SCORE_MIN = 0.35;

    private final TenderLotService tenderLotService;
    private final DistributorService distributorService;
    private final RegistryMatchService registryMatchService;
    private final DistributorMapper distributorMapper;

    public LotSourcingService(TenderLotService tenderLotService,
                              DistributorService distributorService,
                              RegistryMatchService registryMatchService,
                              DistributorMapper distributorMapper) {
        this.tenderLotService = tenderLotService;
        this.distributorService = distributorService;
        this.registryMatchService = registryMatchService;
        this.distributorMapper = distributorMapper;
    }

    public LotSourcingResponse build(Long tenderId, List<Long> lotIds) {
        if (lotIds == null || lotIds.isEmpty()) throw new BadRequestException("Не выбраны лоты");

        record BrandSource(Long lotId, String text, String via) {}
        List<BrandSource> sources = new ArrayList<>();
        for (Long lotId : lotIds) {
            TenderLot lot = tenderLotService.findById(lotId);
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + lotId + " не принадлежит тендеру " + tenderId);
            }
            if (lot.getProposedEquipment() != null) {
                sources.add(new BrandSource(lotId, lot.getProposedEquipment().getManufact(), "PROPOSED_MODEL"));
            } else {
                for (RegistryCandidateResponse c : registryMatchService.candidatesForLot(lotId, REGISTRY_TOP)) {
                    if (c.getScore() != null && c.getScore() >= REGISTRY_SCORE_MIN && c.getProducer() != null) {
                        sources.add(new BrandSource(lotId, c.getProducer(), "REGISTRY"));
                    }
                }
            }
        }

        List<LotSourcingResponse.Entry> entries = new ArrayList<>();
        for (Distributor d : distributorService.findAll()) {
            List<LotSourcingResponse.BrandHit> hits = new ArrayList<>();
            for (BrandSource s : sources) {
                String brand = BrandMatch.firstCarried(d.getBrands(), s.text());
                if (brand != null && hits.stream().noneMatch(h ->
                        h.getBrand().equals(brand) && h.getVia().equals(s.via()) && h.getLotId().equals(s.lotId()))) {
                    hits.add(new LotSourcingResponse.BrandHit(brand, s.via(), s.lotId()));
                }
            }
            LotSourcingResponse.Entry e = new LotSourcingResponse.Entry();
            e.setDistributor(distributorMapper.toResponse(d));
            e.setPreselect(!hits.isEmpty());
            e.setMatchedBrands(hits);
            entries.add(e);
        }

        LotSourcingResponse resp = new LotSourcingResponse();
        resp.setDistributors(entries);
        return resp;
    }
}
