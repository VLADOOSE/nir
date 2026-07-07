package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ApparatusRow;
import com.vladoose.nir.dto.response.ComplectSearchResponse;
import com.vladoose.nir.dto.response.ComplectSearchResponse.ApparatusMatch;
import com.vladoose.nir.dto.response.ComplectSearchResponse.ComponentMatch;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import com.vladoose.nir.entity.RegistryComponent;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.RegistryComponentRepository;
import com.vladoose.nir.util.ComplectComponentMatcher;
import com.vladoose.nir.util.ComplectTermExtractor;
import com.vladoose.nir.util.LotDescriptiveText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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
    private final MedEquipmentRepository equipmentRepository;
    private final NddaClient nddaClient;
    private final ComplectWriter writer;

    public ComplectService(TenderLotService lotService,
                           MedRegistryRepository registryRepository,
                           RegistryComponentRepository componentRepository,
                           MedEquipmentRepository equipmentRepository,
                           NddaClient nddaClient,
                           ComplectWriter writer) {
        this.lotService = lotService;
        this.registryRepository = registryRepository;
        this.componentRepository = componentRepository;
        this.equipmentRepository = equipmentRepository;
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

        // Матчим по описательной части лота (имя + описание/доп.описание/характеристики из ТЗ),
        // а не по всему ТЗ — иначе закупочный канцелярит раздувает знаменатель и топит % (см. LotDescriptiveText).
        Set<String> lotTokens = ComplectComponentMatcher.tokenize(
                LotDescriptiveText.forMatching(lot.getEquipName(), lot.getManufact(), lot.getRequiredSpec()));

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

    /**
     * component-adopt: компонент комплектности → позиция каталога (имя/производитель — из компонента,
     * РУ — аппарата, его допуском компонент и покрыт) → предложенная модель лота. Отдельно от adoptForLot.
     */
    @Transactional
    public TenderLot adoptComponent(Long lotId, String regNumber, Integer partNumber) {
        TenderLot lot = lotService.findById(lotId);
        // findById = em.find обходит фильтр рынка → явный гард (паттерн adoptForLot)
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }
        MedRegistry reg = registryRepository.findByRegNumber(regNumber)
                .orElseThrow(() -> new NotFoundException("РУ аппарата не найдено: " + regNumber));
        RegistryComponent comp = componentRepository.findByRegNumberAndPartNumber(regNumber, partNumber)
                .orElseThrow(() -> new NotFoundException("Компонент не найден (сначала поиск по комплектности): "
                        + regNumber + " #" + partNumber));

        String name = trim255(comp.getProductName());
        MedEquipment eq = equipmentRepository
                .findFirstByRegistrationRegNumberAndNameIgnoreCase(regNumber, name)
                .orElseGet(() -> {
                    MedEquipment e = new MedEquipment();
                    e.setName(name);
                    e.setManufact(comp.getProducer() != null && !comp.getProducer().isBlank()
                            ? trim255(comp.getProducer()) : "не указан");
                    e.setSpec(comp.getProductName());      // текст компонента с размерами
                    e.setRegistrationStatus(RegistrationStatus.REGISTERED);
                    e.setRegistration(reg);                // РУ аппарата покрывает компонент
                    e.setRegistrationCheckedAt(OffsetDateTime.now());
                    e.setMarket(MarketContext.get());
                    return equipmentRepository.save(e);
                });
        lot.setProposedEquipment(eq);
        return lotService.save(lot);
    }

    private static String trim255(String s) {
        return s != null && s.length() > 255 ? s.substring(0, 255) : s;
    }
}
