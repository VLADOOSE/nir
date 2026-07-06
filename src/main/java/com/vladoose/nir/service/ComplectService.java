package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ApparatusRow;
import com.vladoose.nir.dto.response.ComplectSearchResponse;
import com.vladoose.nir.dto.response.ComplectSearchResponse.ApparatusMatch;
import com.vladoose.nir.dto.response.ComplectSearchResponse.ComponentMatch;
import com.vladoose.nir.entity.RegistryComponent;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.RegistryComponentRepository;
import com.vladoose.nir.util.ComplectComponentMatcher;
import com.vladoose.nir.util.ComplectTermExtractor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Поиск по комплектности аппаратов: бренд из ТЗ → аппараты «(МТ)» → live-комплектность → ранжирование
 * компонентов. Сеть ВНЕ транзакции; кеш — ComplectWriter (отдельный @Transactional-бин, §6).
 */
@Service
public class ComplectService {

    private static final int MAX_APPARATUS = 3;

    private final TenderLotService lotService;
    private final MedRegistryRepository registryRepository;
    private final RegistryComponentRepository componentRepository;
    private final NddaClient nddaClient;
    private final ComplectWriter writer;

    public ComplectService(TenderLotService lotService,
                           MedRegistryRepository registryRepository,
                           RegistryComponentRepository componentRepository,
                           NddaClient nddaClient,
                           ComplectWriter writer) {
        this.lotService = lotService;
        this.registryRepository = registryRepository;
        this.componentRepository = componentRepository;
        this.nddaClient = nddaClient;
        this.writer = writer;
    }

    public ComplectSearchResponse search(Long lotId, String termOverride) {
        TenderLot lot = lotService.findById(lotId);
        // em.find обходит фильтр рынка → явный гард (паттекн adopt/proposed-equipment)
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }

        String term = (termOverride != null && !termOverride.isBlank())
                ? termOverride.trim()
                : ComplectTermExtractor.extract(lot.getEquipName(),
                    lot.getRequiredSpec() != null ? lot.getRequiredSpec() : lot.getManufact());

        ComplectSearchResponse resp = new ComplectSearchResponse();
        resp.setTerm(term);
        resp.setApparatuses(new ArrayList<>());
        if (term == null || term.isBlank()) return resp;

        Set<String> lotTokens = ComplectComponentMatcher.tokenize(
                lot.getEquipName() + " " + (lot.getRequiredSpec() != null ? lot.getRequiredSpec() : ""));

        for (ApparatusRow row : registryRepository.findApparatusByTerm(term, MAX_APPARATUS)) {
            List<RegistryComponent> cached = componentRepository.findByRegNumberOrderByPartNumber(row.getRegNumber());
            if (cached.isEmpty()) {
                Long nddaId = row.getNddaId() != null ? row.getNddaId() : nddaClient.resolveId(row.getRegNumber());
                if (nddaId == null) continue;                              // на портале не найден — пропускаем
                List<NddaComplectItemDto> items = nddaClient.fetchComplectList(nddaId); // сеть вне tx
                if (items.isEmpty()) continue;
                writer.cache(row.getRegNumber(), nddaId, items);           // кеш в отдельной tx
                cached = componentRepository.findByRegNumberOrderByPartNumber(row.getRegNumber());
            }
            resp.getApparatuses().add(toMatch(row, cached, lotTokens));
        }
        return resp;
    }

    private ApparatusMatch toMatch(ApparatusRow row, List<RegistryComponent> cached, Set<String> lotTokens) {
        ApparatusMatch am = new ApparatusMatch();
        am.setRegNumber(row.getRegNumber());
        am.setName(row.getName());
        am.setProducer(row.getProducer());
        am.setCountry(row.getCountry());
        List<ComponentMatch> comps = new ArrayList<>();
        for (RegistryComponent c : cached) {
            ComponentMatch cm = new ComponentMatch();
            cm.setPartNumber(c.getPartNumber());
            cm.setProductName(c.getProductName());
            cm.setComponent(c.getComponent());
            cm.setProducer(c.getProducer());
            cm.setCountry(c.getCountry());
            cm.setScore(ComplectComponentMatcher.score(lotTokens, c.getProductName()));
            comps.add(cm);
        }
        comps.sort(Comparator.comparingDouble(ComponentMatch::getScore).reversed()); // лучший компонент сверху
        am.setComponents(comps);
        return am;
    }
}
