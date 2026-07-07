package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.util.BrandMatch;
import com.vladoose.nir.util.ComplectTermExtractor;
import com.vladoose.nir.util.LotQueryTokenizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Подбор поставщиков по лоту(ам): мягкий ранг typeHit⊕brandHit. Вид МИ лота — сохранённый или
 * от LotTypeClassifier. Brand-сигнал = бренд предложенной модели + производители реестр-кандидатов +
 * эффективный термин Tier 2 (бренд/аппарат для аксессуарных лотов). Релевантные сверху.
 */
@Service
public class LotSourcingService {

    static final int REGISTRY_TOP = 5;
    static final double REGISTRY_SCORE_MIN = 0.35;

    private final TenderLotService tenderLotService;
    private final DistributorService distributorService;
    private final RegistryMatchService registryMatchService;
    private final DistributorMapper distributorMapper;
    private final LotTypeClassifier classifier;

    public LotSourcingService(TenderLotService tenderLotService,
                              DistributorService distributorService,
                              RegistryMatchService registryMatchService,
                              DistributorMapper distributorMapper,
                              LotTypeClassifier classifier) {
        this.tenderLotService = tenderLotService;
        this.distributorService = distributorService;
        this.registryMatchService = registryMatchService;
        this.distributorMapper = distributorMapper;
        this.classifier = classifier;
    }

    private record BrandSource(Long lotId, String text, String via) {}

    public LotSourcingResponse build(Long tenderId, List<Long> lotIds, String termOverride) {
        if (lotIds == null || lotIds.isEmpty()) throw new BadRequestException("Не выбраны лоты");

        List<TenderLot> lots = new ArrayList<>();
        List<BrandSource> sources = new ArrayList<>();
        for (Long lotId : lotIds) {
            TenderLot lot = tenderLotService.findById(lotId); // em.find обходит фильтр рынка
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + lotId + " не принадлежит тендеру " + tenderId);
            }
            if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
                throw new BadRequestException("Лот " + lotId + " не найден");
            }
            lots.add(lot);
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

        boolean singleLot = lots.size() == 1;

        // вид(ы) МИ: сохранённый на лоте или классификатор
        Set<Long> lotTypeIds = new LinkedHashSet<>();
        LotSourcingResponse.DetectedType detectedType = null;
        List<LotSourcingResponse.TypeRef> typeAlternatives = new ArrayList<>();
        for (TenderLot lot : lots) {
            if (lot.getEquipmentType() != null) {
                lotTypeIds.add(lot.getEquipmentType().getId());
                if (singleLot) detectedType = new LotSourcingResponse.DetectedType(
                        lot.getEquipmentType().getId(), lot.getEquipmentType().getName(), 1.0);
            } else {
                LotTypeClassifier.TypeGuess g = classifier.classify(lot);
                if (g.typeId() != null) {
                    lotTypeIds.add(g.typeId());
                    if (singleLot) {
                        detectedType = new LotSourcingResponse.DetectedType(g.typeId(), g.typeName(), g.confidence());
                        for (LotTypeClassifier.TypeGuess alt : g.alternatives()) {
                            typeAlternatives.add(new LotSourcingResponse.TypeRef(alt.typeId(), alt.typeName()));
                        }
                    }
                }
            }
        }

        // Tier 2: эффективный термин точечного поиска (одно-лотовый режим)
        String sourcingTerm = null;
        if (singleLot) {
            TenderLot lot = lots.get(0);
            if (termOverride != null && !termOverride.isBlank()) {
                sourcingTerm = termOverride.trim();
            } else if (lot.getProposedEquipment() != null
                    && lot.getProposedEquipment().getManufact() != null
                    && !lot.getProposedEquipment().getManufact().isBlank()) {
                sourcingTerm = lot.getProposedEquipment().getManufact().trim();
            } else {
                String complect = ComplectTermExtractor.extract(lot.getEquipName(), lot.getRequiredSpec());
                if (complect != null && !complect.isBlank()) {
                    sourcingTerm = complect.trim();
                } else {
                    String regProducer = sources.stream().filter(s -> "REGISTRY".equals(s.via()))
                            .map(BrandSource::text).filter(x -> x != null && !x.isBlank()).findFirst().orElse(null);
                    if (regProducer != null) {
                        sourcingTerm = regProducer.trim();
                    } else {
                        List<LotQueryTokenizer.WeightedToken> toks = LotQueryTokenizer.tokenize(lot.getEquipName(), null);
                        sourcingTerm = toks.isEmpty() ? null : toks.get(0).token();
                    }
                }
            }
            if (sourcingTerm != null && !sourcingTerm.isBlank()) {
                sources.add(new BrandSource(lot.getId(), sourcingTerm, "SEARCH_TERM"));
            }
        }

        // скоринг
        List<LotSourcingResponse.Entry> entries = new ArrayList<>();
        for (Distributor d : distributorService.findAll()) {
            List<LotSourcingResponse.Reason> reasons = new ArrayList<>();
            boolean typeHit = false;
            if (!lotTypeIds.isEmpty() && d.getEquipmentTypes() != null) {
                for (EquipmentType et : d.getEquipmentTypes()) {
                    if (lotTypeIds.contains(et.getId())) {
                        typeHit = true;
                        if (reasons.stream().noneMatch(r -> "TYPE".equals(r.getKind()) && r.getLabel().equals(et.getName()))) {
                            reasons.add(new LotSourcingResponse.Reason("TYPE", et.getName()));
                        }
                    }
                }
            }
            List<LotSourcingResponse.BrandHit> brandHits = new ArrayList<>();
            for (BrandSource s : sources) {
                String brand = BrandMatch.firstCarried(d.getBrands(), s.text());
                if (brand == null) continue;
                if (brandHits.stream().noneMatch(h -> h.getBrand().equals(brand)
                        && h.getVia().equals(s.via()) && h.getLotId().equals(s.lotId()))) {
                    brandHits.add(new LotSourcingResponse.BrandHit(brand, s.via(), s.lotId()));
                }
                if (reasons.stream().noneMatch(r -> "BRAND".equals(r.getKind()) && r.getLabel().equals(brand))) {
                    reasons.add(new LotSourcingResponse.Reason("BRAND", brand));
                }
            }
            boolean brandHit = !brandHits.isEmpty();
            double score = (brandHit ? 1.0 : 0.0) + (typeHit ? 0.7 : 0.0) + (brandHit && typeHit ? 0.3 : 0.0);

            LotSourcingResponse.Entry e = new LotSourcingResponse.Entry();
            e.setDistributor(distributorMapper.toResponse(d));
            e.setMatchedBrands(brandHits);
            e.setReasons(reasons);
            e.setRelevant(brandHit || typeHit);
            e.setScore(score);
            e.setPreselect(brandHit);
            entries.add(e);
        }
        entries.sort((a, b) -> {
            if (a.isRelevant() != b.isRelevant()) return a.isRelevant() ? -1 : 1;
            return Double.compare(b.getScore(), a.getScore());
        });

        LotSourcingResponse resp = new LotSourcingResponse();
        resp.setDistributors(entries);
        resp.setSingleLot(singleLot);
        resp.setDetectedType(detectedType);
        resp.setTypeAlternatives(typeAlternatives);
        resp.setSourcingTerm(sourcingTerm);
        return resp;
    }
}
