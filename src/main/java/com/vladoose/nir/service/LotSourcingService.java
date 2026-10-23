package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.dto.response.PriceHint;
import com.vladoose.nir.dto.response.PriceHintRow;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.service.execution.ExecutionTexts;
import com.vladoose.nir.service.pricelist.LotPriceHints;
import com.vladoose.nir.util.BrandMatch;
import com.vladoose.nir.util.ComplectTermExtractor;
import com.vladoose.nir.util.LotQueryTokenizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Подбор поставщиков по лоту(ам): мягкий ранг typeHit⊕brandHit. Вид МИ лота — сохранённый или
 * от LotTypeClassifier. Brand-сигнал = бренд предложенной модели + производители реестр-кандидатов +
 * эффективный термин Tier 2 (бренд/аппарат для аксессуарных лотов). Релевантные сверху.
 */
@Service
public class LotSourcingService {

    static final int REGISTRY_TOP = 5;
    /**
     * Порог, с которого производитель реестр-кандидата идёт в brand-подсказки поставщиков.
     *
     * <p><b>Перекалибровано 2026-08-02</b> (golden-набор 71 лот, снимок реестра 14072): скоринг
     * V2 сменил шкалу — F<sub>β</sub> со сглаженным знаменателем вместо F1, — и прежние 0.35
     * на ней означают уже не то же самое. Критерий выбора: доля лотов, дающих хотя бы одну
     * brand-подсказку, не должна упасть относительно старого алгоритма. Замер:
     * старый алгоритм при 0.35 — <b>61/71</b>; новый при 0.35 — 58/71 (падение), при 0.32 —
     * 60/71, при <b>0.30 — 65/71</b>.
     *
     * <p>Взято 0.30, а не 0.31 (тоже 65/71), потому что скоры однотокенных запросов лежат на
     * решётке {@code 3.25/(nsig+4.25)} и узел nsig=6 приходится на 0.31707 — порог вплотную
     * к узлу отдаёт целую корзину записей округлению float8 (видно по скачку 0.31 → 0.32:
     * 65 → 60 лотов). 0.30 лежит между узлами 0.28889 (nsig=7) и 0.31707.
     */
    static final double REGISTRY_SCORE_MIN = 0.30;

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

    private LotPriceHints lotPriceHints;

    /** Метка «прайс: …» у поставщика с товаром лота в прайсе; нет бина (тесты, собранные руками) — без метки. */
    @Autowired(required = false)
    public void setLotPriceHints(LotPriceHints lotPriceHints) {
        this.lotPriceHints = lotPriceHints;
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

        // поставщики с товаром лота в прайсе (спека 2026-10-23 §6.2 п. 3) — только одно-лотовый режим
        Map<Long, PriceHintRow> priceRows = new HashMap<>();
        if (singleLot && lotPriceHints != null) {
            PriceHint h = lotPriceHints.forLots(MarketContext.get(), lots).get(lots.get(0).getId());
            if (h != null) for (PriceHintRow r : h.rows()) priceRows.putIfAbsent(r.distributorId(), r);
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
            // завод бренда — первым, с меткой «завод» (спека 2026-10-21-tender-telegram-bot §10 п. 2; как частные заявки)
            boolean maker = brandHit && d.isManufacturer();
            if (maker) reasons.add(0, new LotSourcingResponse.Reason("MAKER", "завод"));
            PriceHintRow pr = priceRows.get(d.getId());
            if (pr != null) reasons.add(new LotSourcingResponse.Reason("PRICE_LIST", "прайс: " + ExecutionTexts.money(pr.price(), pr.currency())));
            double score = (brandHit ? 1.0 : 0.0) + (typeHit ? 0.7 : 0.0) + (brandHit && typeHit ? 0.3 : 0.0) + (maker ? 0.5 : 0.0)
                    + (pr != null ? 0.4 : 0.0);

            LotSourcingResponse.Entry e = new LotSourcingResponse.Entry();
            e.setDistributor(distributorMapper.toResponse(d));
            e.setMatchedBrands(brandHits);
            e.setReasons(reasons);
            e.setRelevant(brandHit || typeHit || pr != null);   // товар в прайсе — среди релевантных; отметка — по бренду
            e.setScore(score);
            e.setPreselect(brandHit);
            entries.add(e);
        }
        // есть завод бренда с почтой — отмечен он, дилеры бренда — без отметки (письмо дилеру — решение оператора)
        if (entries.stream().anyMatch(LotSourcingService::makerWithMail)) {
            entries.forEach(x -> x.setPreselect(makerWithMail(x)));
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

    /** Отмечен по бренду (preselect = попал бренд), завод и с почтой. */
    private static boolean makerWithMail(LotSourcingResponse.Entry e) {
        String mail = e.getDistributor().getEmail();
        return e.isPreselect() && e.getDistributor().isManufacturer() && mail != null && !mail.isBlank();
    }
}
